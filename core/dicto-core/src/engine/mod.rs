//! Moteurs de reconnaissance vocale, locaux et en ligne, derrière un trait commun.

pub mod cloud;
pub mod local;
pub mod preview;
#[cfg(feature = "sherpa")]
pub mod sherpa;
#[cfg(feature = "whisper")]
pub mod whisper;

use async_trait::async_trait;

use crate::commands::is_hallucination;
use crate::{Result, Transcript};

#[derive(Debug, Clone, Default, PartialEq)]
pub struct TranscribeOptions {
    /// Code ISO 639-1 (« fr », « en »…). `None` = détection automatique.
    pub language: Option<String>,
    /// Texte d'amorce : vocabulaire, noms propres, style attendu.
    pub prompt: Option<String>,
    /// Demande des segments horodatés (fichiers audio, sous-titres).
    pub timestamps: bool,
}

#[async_trait]
pub trait SttEngine: Send + Sync {
    /// Identifiant stable, ex. `local:whisper-small` ou `openai:gpt-4o-transcribe`.
    fn id(&self) -> String;
    /// Vrai si l'audio ne quitte pas l'appareil.
    fn is_local(&self) -> bool;
    /// Transcrit du PCM mono 16 kHz.
    async fn transcribe(&self, pcm: &[f32], opts: &TranscribeOptions) -> Result<Transcript>;
}

/// Moteur capable d'afficher le texte pendant qu'on parle (modèles streaming).
pub trait LiveEngine: Send + Sync {
    fn open(&self) -> Option<Box<dyn LiveStream>>;
}

pub trait LiveStream: Send {
    /// Ajoute de l'audio 16 kHz ; renvoie le texte provisoire s'il a changé.
    fn push(&mut self, pcm: &[f32]) -> Option<String>;
    /// Repart de zéro (nouvelle phrase).
    fn reset(&mut self);
}

/// Transcrit puis écarte les sorties vides ou hallucinées (sous-titres fantômes).
pub async fn transcribe_filtered(
    engine: &dyn SttEngine,
    pcm: &[f32],
    opts: &TranscribeOptions,
) -> Result<Option<Transcript>> {
    let mut t = engine.transcribe(pcm, opts).await?;
    if !t.segments.is_empty() {
        t.segments.retain(|s| !is_hallucination(&s.text));
        t.rebuild_text();
    }
    if is_hallucination(&t.text) {
        return Ok(None);
    }
    t.text = t.text.trim().to_string();
    Ok(Some(t))
}

/// Construit l'amorce envoyée au modèle à partir du vocabulaire personnel.
///
/// Whisper ne lit que ~224 jetons d'amorce : on se limite à environ 600 caractères.
pub fn vocabulary_prompt(terms: &[String]) -> Option<String> {
    const MAX_CHARS: usize = 600;
    let mut out = String::new();
    for term in terms.iter().map(|t| t.trim()).filter(|t| !t.is_empty()) {
        let extra = if out.is_empty() { term.len() } else { term.len() + 2 };
        if out.len() + extra > MAX_CHARS {
            break;
        }
        if !out.is_empty() {
            out.push_str(", ");
        }
        out.push_str(term);
    }
    (!out.is_empty()).then(|| format!("{out}."))
}

#[cfg(test)]
mod tests {
    use super::*;

    struct Fixed(&'static str);

    #[async_trait]
    impl SttEngine for Fixed {
        fn id(&self) -> String {
            "test:fixed".into()
        }
        fn is_local(&self) -> bool {
            true
        }
        async fn transcribe(&self, _: &[f32], _: &TranscribeOptions) -> Result<Transcript> {
            Ok(Transcript::from_text(self.0))
        }
    }

    #[tokio::test]
    async fn filters_hallucinations() {
        let opts = TranscribeOptions::default();
        let ok = transcribe_filtered(&Fixed("  Bonjour.  "), &[], &opts).await.unwrap();
        assert_eq!(ok.unwrap().text, "Bonjour.");
        let ghost = transcribe_filtered(&Fixed("Sous-titres réalisés par la communauté d'Amara.org"), &[], &opts)
            .await
            .unwrap();
        assert!(ghost.is_none());
    }

    #[test]
    fn vocabulary_prompt_is_bounded() {
        assert_eq!(vocabulary_prompt(&[]), None);
        assert_eq!(
            vocabulary_prompt(&["Dicto".into(), " Loftia ".into(), "".into()]),
            Some("Dicto, Loftia.".into())
        );
        let many: Vec<String> = (0..500).map(|i| format!("terme{i}")).collect();
        assert!(vocabulary_prompt(&many).unwrap().len() <= 601);
    }
}
