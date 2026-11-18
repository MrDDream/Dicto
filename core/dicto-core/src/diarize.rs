//! Séparation des voix (« qui parle quand ») d'un enregistrement, pour les fichiers audio
//! et les comptes rendus de réunion. Modèles sherpa-onnx : segmentation pyannote et
//! empreinte vocale 3D-Speaker.

use crate::Segment;

/// Un tour de parole : `[début, fin)` en ms et le numéro de la voix (0, 1…).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Turn {
    pub start_ms: u64,
    pub end_ms: u64,
    pub speaker: u32,
}

/// Fichiers attendus dans le dossier du modèle de séparation des voix (catalogue « diarization »).
/// L'archive de segmentation est extraite sans son dossier racine (voir `models::extract_tar_bz2`).
pub const SEGMENTATION_MODEL: &str = "model.int8.onnx";
pub const EMBEDDING_MODEL: &str = "3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx";

/// Donne à chaque segment la voix qui parle le plus longtemps pendant celui-ci ; les voix
/// sont renumérotées dans l'ordre où elles prennent la parole.
pub fn assign_speakers(segments: &mut [Segment], turns: &[Turn]) {
    let mut order: Vec<u32> = Vec::new();
    for s in segments.iter_mut() {
        let mut best: (u64, Option<u32>) = (0, None);
        for t in turns {
            let overlap = s.end_ms.min(t.end_ms).saturating_sub(s.start_ms.max(t.start_ms));
            if overlap > best.0 {
                best = (overlap, Some(t.speaker));
            }
        }
        s.speaker = best.1.map(|v| {
            let pos = order.iter().position(|&o| o == v).unwrap_or_else(|| {
                order.push(v);
                order.len() - 1
            });
            pos as u32
        });
    }
}

#[cfg(feature = "sherpa")]
pub struct Diarizer {
    inner: sherpa_onnx::OfflineSpeakerDiarization,
}

#[cfg(feature = "sherpa")]
impl Diarizer {
    /// `dir` : dossier du modèle téléchargé ; `speakers` : nombre de voix s'il est connu.
    pub fn load(dir: &std::path::Path, speakers: Option<u32>) -> crate::Result<Self> {
        let mut config = sherpa_onnx::OfflineSpeakerDiarizationConfig::default();
        config.segmentation.pyannote.model = Some(dir.join(SEGMENTATION_MODEL).to_string_lossy().into_owned());
        config.segmentation.num_threads = 2;
        config.embedding.model = Some(dir.join(EMBEDDING_MODEL).to_string_lossy().into_owned());
        config.embedding.num_threads = 2;
        config.clustering.num_clusters = speakers.filter(|n| *n > 0).map(|n| n as i32).unwrap_or(-1);
        // Plus haut = moins de voix. 0,5 (valeur des exemples) coupe une même voix en plusieurs.
        config.clustering.threshold = 0.8;
        let inner = sherpa_onnx::OfflineSpeakerDiarization::create(&config)
            .ok_or_else(|| crate::DictoError::Model("séparation des voix : chargement impossible".into()))?;
        Ok(Self { inner })
    }

    /// Tours de parole d'un enregistrement mono 16 kHz.
    pub fn turns(&self, pcm: &[f32]) -> crate::Result<Vec<Turn>> {
        let result = self
            .inner
            .process(pcm)
            .ok_or_else(|| crate::DictoError::Model("séparation des voix impossible".into()))?;
        Ok(result
            .sort_by_start_time()
            .into_iter()
            .map(|s| Turn {
                start_ms: (s.start.max(0.0) * 1000.0) as u64,
                end_ms: (s.end.max(0.0) * 1000.0) as u64,
                speaker: s.speaker.max(0) as u32,
            })
            .collect())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn seg(start_ms: u64, end_ms: u64) -> Segment {
        Segment { start_ms, end_ms, text: "…".into(), speaker: None }
    }

    #[test]
    fn segments_take_the_main_speaker_in_order_of_appearance() {
        let turns = [
            Turn { start_ms: 0, end_ms: 4_000, speaker: 3 },
            Turn { start_ms: 4_000, end_ms: 9_000, speaker: 1 },
            Turn { start_ms: 9_000, end_ms: 12_000, speaker: 3 },
        ];
        let mut segs = vec![seg(0, 3_000), seg(3_500, 8_000), seg(9_500, 11_000), seg(20_000, 21_000)];
        assign_speakers(&mut segs, &turns);
        let speakers: Vec<Option<u32>> = segs.iter().map(|s| s.speaker).collect();
        assert_eq!(speakers, vec![Some(0), Some(1), Some(0), None]);
    }
}
