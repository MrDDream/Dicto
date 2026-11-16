//! Aperçu en direct pour les modèles qui ne transcrivent qu'une phrase entière
//! (Whisper, Canary, Parakeet…) : la phrase en cours est retranscrite régulièrement
//! pendant qu'on parle. Le texte final reste celui de la transcription complète.

use std::sync::Arc;
use std::time::Instant;

use super::{LiveStream, SttEngine, TranscribeOptions};
use crate::audio::{rms, samples_for_ms};
use crate::commands::is_hallucination;

/// Rien n'est affiché avant ce temps de parole.
const FIRST_MS: u32 = 800;
/// Intervalle minimal entre deux retranscriptions.
const MIN_INTERVAL_MS: u32 = 1_000;
/// Au-delà, l'aperçu s'arrête : retranscrire une longue phrase prendrait trop de temps.
const MAX_MS: u32 = 30_000;
/// Niveau sonore d'une voix proche du micro ; en dessous, on ne transcrit pas (silence, souffle).
const VOICE_LEVEL: f32 = 0.015;

pub struct Preview {
    engine: Arc<dyn SttEngine>,
    opts: TranscribeOptions,
    rt: tokio::runtime::Handle,
    /// Faux pendant qu'une phrase est transcrite pour de bon : elle passe en priorité.
    may_run: Arc<dyn Fn() -> bool + Send + Sync>,
    audio: Vec<f32>,
    decoded: usize,
    interval: usize,
    last: String,
    /// Une voix a été entendue depuis le début de la phrase.
    heard: bool,
}

impl Preview {
    pub fn new(
        engine: Arc<dyn SttEngine>,
        opts: TranscribeOptions,
        rt: tokio::runtime::Handle,
        may_run: Arc<dyn Fn() -> bool + Send + Sync>,
    ) -> Self {
        Self {
            engine,
            opts: TranscribeOptions { timestamps: false, ..opts },
            rt,
            may_run,
            audio: Vec::new(),
            decoded: 0,
            interval: samples_for_ms(MIN_INTERVAL_MS),
            last: String::new(),
            heard: false,
        }
    }

    fn due(&self) -> bool {
        let len = self.audio.len();
        len >= samples_for_ms(FIRST_MS) && len <= samples_for_ms(MAX_MS) && len - self.decoded >= self.interval
    }
}

impl LiveStream for Preview {
    fn push(&mut self, pcm: &[f32]) -> Option<String> {
        self.audio.extend_from_slice(pcm);
        // Les modèles inventent volontiers une phrase sur du silence : on attend une voix.
        self.heard = self.heard || pcm.chunks(samples_for_ms(30)).any(|f| rms(f) > VOICE_LEVEL);
        if !self.heard || !self.due() || !(self.may_run)() {
            return None;
        }
        let started = Instant::now();
        let result = self.rt.block_on(self.engine.transcribe(&self.audio, &self.opts));
        self.decoded = self.audio.len();
        // Sur un téléphone lent, on espace les passages : au plus un tiers du temps de calcul.
        let cost = started.elapsed().as_millis().min(u32::MAX as u128) as u32;
        self.interval = samples_for_ms(MIN_INTERVAL_MS.max(cost.saturating_mul(2)));
        let text = result.ok()?.text.trim().to_string();
        if text.is_empty() || is_hallucination(&text) || text == self.last {
            return None;
        }
        self.last = text.clone();
        Some(text)
    }

    fn reset(&mut self) {
        self.audio.clear();
        self.decoded = 0;
        self.last.clear();
        self.heard = false;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{Result, Transcript};
    use async_trait::async_trait;
    use std::sync::atomic::{AtomicUsize, Ordering};

    /// Renvoie le nombre d'échantillons reçus, et compte les appels.
    struct Counter(Arc<AtomicUsize>);

    #[async_trait]
    impl SttEngine for Counter {
        fn id(&self) -> String {
            "test:counter".into()
        }
        fn is_local(&self) -> bool {
            true
        }
        async fn transcribe(&self, pcm: &[f32], _: &TranscribeOptions) -> Result<Transcript> {
            self.0.fetch_add(1, Ordering::SeqCst);
            Ok(Transcript::from_text(&format!("{} échantillons", pcm.len())))
        }
    }

    fn preview(calls: Arc<AtomicUsize>, allowed: Arc<std::sync::atomic::AtomicBool>) -> (Preview, tokio::runtime::Runtime) {
        let rt = tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap();
        let p = Preview::new(
            Arc::new(Counter(calls)),
            TranscribeOptions::default(),
            rt.handle().clone(),
            Arc::new(move || allowed.load(Ordering::SeqCst)),
        );
        (p, rt)
    }

    #[test]
    fn retranscribes_the_whole_phrase_at_intervals() {
        let calls = Arc::new(AtomicUsize::new(0));
        let allowed = Arc::new(std::sync::atomic::AtomicBool::new(true));
        let (mut p, _rt) = preview(calls.clone(), allowed);
        let chunk = vec![0.1; samples_for_ms(100)];
        let mut shown = Vec::new();
        for _ in 0..25 {
            shown.extend(p.push(&chunk));
        }
        // 2,5 s de parole : un passage à 1 s puis à 2 s (le premier attend 0,8 s de parole).
        assert_eq!(calls.load(Ordering::SeqCst), 2);
        assert_eq!(shown, vec!["16000 échantillons".to_string(), "32000 échantillons".to_string()]);

        p.reset();
        assert!(p.push(&chunk).is_none(), "nouvelle phrase : rien avant 0,8 s");
    }

    #[test]
    fn ignores_silence() {
        let calls = Arc::new(AtomicUsize::new(0));
        let allowed = Arc::new(std::sync::atomic::AtomicBool::new(true));
        let (mut p, _rt) = preview(calls.clone(), allowed);
        assert!(p.push(&vec![0.001; samples_for_ms(3_000)]).is_none());
        assert_eq!(calls.load(Ordering::SeqCst), 0);
    }

    #[test]
    fn waits_while_a_final_transcription_runs() {
        let calls = Arc::new(AtomicUsize::new(0));
        let allowed = Arc::new(std::sync::atomic::AtomicBool::new(false));
        let (mut p, _rt) = preview(calls.clone(), allowed.clone());
        let second = vec![0.1; samples_for_ms(1_000)];
        assert!(p.push(&second).is_none());
        assert_eq!(calls.load(Ordering::SeqCst), 0);
        allowed.store(true, Ordering::SeqCst);
        assert!(p.push(&[0.1]).is_some());
    }
}
