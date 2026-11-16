//! Transcription de fichiers longs (réunions, messages vocaux, vidéos).
//!
//! L'audio est découpé aux silences en morceaux de 30 s au plus, chacun transcrit
//! puis horodaté par rapport au début du fichier.

use std::sync::atomic::{AtomicBool, Ordering};

use crate::audio::{SAMPLE_RATE, ms_for_samples, rms, samples_for_ms};
use crate::commands::is_hallucination;
use crate::engine::{SttEngine, TranscribeOptions};
use crate::{DictoError, Result, Segment, Transcript};

/// Bornes `[début, fin)` des morceaux : coupe au point le plus calme entre `min_s` et `max_s`.
pub fn split_points(pcm: &[f32], min_s: u32, max_s: u32) -> Vec<(usize, usize)> {
    let frame = samples_for_ms(30);
    let (min, max) = (samples_for_ms(min_s * 1000), samples_for_ms(max_s * 1000));
    let mut out = Vec::new();
    let mut start = 0;
    while start < pcm.len() {
        if pcm.len() - start <= max {
            out.push((start, pcm.len()));
            break;
        }
        let mut best = (f32::MAX, start + max);
        let mut pos = start + min;
        while pos + frame <= start + max {
            let level = rms(&pcm[pos..pos + frame]);
            if level < best.0 {
                best = (level, pos + frame / 2);
            }
            pos += frame;
        }
        out.push((start, best.1));
        start = best.1;
    }
    out
}

/// Transcrit un enregistrement mono 16 kHz de n'importe quelle durée.
/// `progress` reçoit l'avancement de 0 à 1.
pub async fn transcribe_long(
    engine: &dyn SttEngine,
    pcm: &[f32],
    opts: &TranscribeOptions,
    cancel: &AtomicBool,
    mut progress: impl FnMut(f32) + Send,
) -> Result<Transcript> {
    let chunks = split_points(pcm, 20, 30);
    let opts = TranscribeOptions { timestamps: true, ..opts.clone() };
    let mut segments = Vec::new();
    let mut language = opts.language.clone();
    progress(0.0);
    for (i, &(start, end)) in chunks.iter().enumerate() {
        if cancel.load(Ordering::Relaxed) {
            return Err(DictoError::Cancelled);
        }
        let chunk = &pcm[start..end];
        if rms(chunk) > 0.003 {
            let offset = ms_for_samples(start);
            let t = engine.transcribe(chunk, &opts).await?;
            if language.is_none() {
                language = t.language.clone();
            }
            if t.segments.is_empty() {
                if !t.text.trim().is_empty() && !is_hallucination(&t.text) {
                    segments.push(Segment {
                        start_ms: offset,
                        end_ms: offset + ms_for_samples(chunk.len()),
                        text: t.text.trim().to_string(),
                        speaker: None,
                    });
                }
            } else {
                for s in t.segments {
                    if is_hallucination(&s.text) {
                        continue;
                    }
                    segments.push(Segment {
                        start_ms: offset + s.start_ms,
                        end_ms: offset + s.end_ms.max(s.start_ms),
                        text: s.text,
                        speaker: s.speaker,
                    });
                }
            }
        }
        progress((i + 1) as f32 / chunks.len() as f32);
    }
    let mut t = Transcript { text: String::new(), language, segments };
    t.rebuild_text();
    Ok(t)
}

/// Durée d'un enregistrement mono 16 kHz, en secondes.
pub fn duration_s(pcm: &[f32]) -> f32 {
    pcm.len() as f32 / SAMPLE_RATE as f32
}

#[cfg(test)]
mod tests {
    use super::*;
    use async_trait::async_trait;

    fn tone(ms: u32) -> Vec<f32> {
        (0..samples_for_ms(ms)).map(|i| (i as f32 * 0.3).sin() * 0.3).collect()
    }

    #[test]
    fn splits_at_quiet_points_within_bounds() {
        let mut pcm = tone(22_000);
        pcm.extend(vec![0.0; samples_for_ms(1_000)]);
        pcm.extend(tone(40_000));
        let chunks = split_points(&pcm, 20, 30);
        let first_end_s = chunks[0].1 as f32 / 16_000.0;
        assert!((22.0..=23.0).contains(&first_end_s), "coupé à {first_end_s} s");
        assert!(chunks.iter().all(|(a, b)| b - a <= samples_for_ms(30_000)));
        assert_eq!(chunks.last().unwrap().1, pcm.len());
        assert!(chunks.windows(2).all(|w| w[0].1 == w[1].0), "morceaux contigus");
    }

    struct Fake;

    #[async_trait]
    impl SttEngine for Fake {
        fn id(&self) -> String {
            "test:fake".into()
        }
        fn is_local(&self) -> bool {
            true
        }
        async fn transcribe(&self, pcm: &[f32], _: &TranscribeOptions) -> Result<Transcript> {
            Ok(Transcript::from_text(format!("{} s", pcm.len() / 16_000)))
        }
    }

    #[tokio::test]
    async fn offsets_segments_and_reports_progress() {
        let mut pcm = tone(25_000);
        pcm.extend(vec![0.0; samples_for_ms(500)]);
        pcm.extend(tone(10_000));
        let mut last = 0.0;
        let t = transcribe_long(&Fake, &pcm, &TranscribeOptions::default(), &AtomicBool::new(false), |p| last = p)
            .await
            .unwrap();
        assert_eq!(last, 1.0);
        assert_eq!(t.segments.len(), 2);
        assert_eq!(t.segments[0].start_ms, 0);
        assert!(t.segments[1].start_ms >= 25_000);
        assert!(t.text.contains(" s"));
    }

    #[tokio::test]
    async fn cancellable() {
        let pcm = tone(5_000);
        let r = transcribe_long(&Fake, &pcm, &TranscribeOptions::default(), &AtomicBool::new(true), |_| {}).await;
        assert!(matches!(r, Err(DictoError::Cancelled)));
    }
}
