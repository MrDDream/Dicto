use serde::{Deserialize, Serialize};

/// Un morceau de transcription horodaté (millisecondes depuis le début de l'audio).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Segment {
    pub start_ms: u64,
    pub end_ms: u64,
    pub text: String,
    /// Locuteur détecté (diarisation), si disponible.
    pub speaker: Option<u32>,
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct Transcript {
    pub text: String,
    /// Langue détectée ou imposée (code ISO 639-1).
    pub language: Option<String>,
    pub segments: Vec<Segment>,
}

impl Transcript {
    pub fn from_text(text: impl Into<String>) -> Self {
        Self {
            text: text.into(),
            ..Self::default()
        }
    }

    /// Reconstruit `text` à partir des segments (utile après un filtrage).
    pub fn rebuild_text(&mut self) {
        self.text = self
            .segments
            .iter()
            .map(|s| s.text.trim())
            .filter(|t| !t.is_empty())
            .collect::<Vec<_>>()
            .join(" ");
    }
}
