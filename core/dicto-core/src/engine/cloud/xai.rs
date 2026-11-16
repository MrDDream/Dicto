//! Transcription par Grok (xAI) : `POST /v1/stt`, envoi multipart d'un WAV.
//! Doc : <https://docs.x.ai/developers/rest-api-reference/inference/speech-to-text>

use async_trait::async_trait;
use reqwest::multipart::{Form, Part};
use serde::Deserialize;

use super::{check, http_client};
use crate::audio::{SAMPLE_RATE, encode_wav};
use crate::engine::{SttEngine, TranscribeOptions};
use crate::{Result, Segment, Transcript};

/// xAI n'a pas de liste de modèles pour la transcription : celui-ci est son seul modèle.
pub const MODELS: &[&str] = &["grok-voice-transcribe-2.0"];

/// Langues où xAI sait écrire nombres, dates et montants en chiffres (`format=true`).
const FORMAT_LANGUAGES: &[&str] = &["ar", "de", "en", "es", "fr", "ja", "pt", "ru", "sv", "vi", "zh"];

pub struct XaiStt {
    base_url: String,
    api_key: Option<String>,
    model: String,
    client: reqwest::Client,
}

impl XaiStt {
    pub fn new(base_url: Option<String>, api_key: Option<String>, model: impl Into<String>) -> Self {
        let model = model.into();
        Self {
            base_url: base_url
                .filter(|u| !u.trim().is_empty())
                .unwrap_or_else(|| "https://api.x.ai/v1".into())
                .trim_end_matches('/')
                .to_string(),
            api_key: api_key.filter(|k| !k.trim().is_empty()),
            model: if model.trim().is_empty() { MODELS[0].to_string() } else { model },
            client: http_client(),
        }
    }
}

#[derive(Deserialize)]
struct Response {
    #[serde(default)]
    text: String,
    language: Option<String>,
    #[serde(default)]
    words: Vec<Word>,
}

#[derive(Deserialize)]
struct Word {
    text: String,
    start: f64,
    end: f64,
}

/// Le vocabulaire arrive sous forme « terme, terme, terme. » : xAI veut des termes séparés.
fn keyterms(prompt: &str) -> Vec<String> {
    prompt
        .trim()
        .trim_end_matches('.')
        .split(',')
        .map(str::trim)
        .filter(|t| !t.is_empty() && t.chars().count() <= 50)
        .take(100)
        .map(str::to_string)
        .collect()
}

/// Regroupe les mots horodatés en phrases (fin de phrase, silence ou longueur), pour les sous-titres.
fn segments(words: &[Word]) -> Vec<Segment> {
    let mut out = Vec::new();
    let mut current: Vec<&Word> = Vec::new();
    for (i, w) in words.iter().enumerate() {
        current.push(w);
        let start = current[0].start;
        let ends_sentence = w.text.ends_with(['.', '?', '!', '…']);
        let pause = words.get(i + 1).is_some_and(|n| n.start - w.end > 0.8);
        let long = w.end - start > 12.0;
        if ends_sentence || pause || long || i + 1 == words.len() {
            out.push(Segment {
                start_ms: (start * 1000.0).round() as u64,
                end_ms: (w.end * 1000.0).round() as u64,
                text: current.iter().map(|w| w.text.trim()).collect::<Vec<_>>().join(" "),
                speaker: None,
            });
            current.clear();
        }
    }
    out
}

#[async_trait]
impl SttEngine for XaiStt {
    fn id(&self) -> String {
        format!("xai:{}", self.model)
    }

    fn is_local(&self) -> bool {
        false
    }

    async fn transcribe(&self, pcm: &[f32], opts: &TranscribeOptions) -> Result<Transcript> {
        // Les options doivent précéder le fichier, qui ferme le formulaire.
        let mut form = Form::new().text("model", self.model.clone());
        if let Some(lang) = &opts.language {
            form = form.text("language", lang.clone());
            if FORMAT_LANGUAGES.contains(&lang.as_str()) {
                form = form.text("format", "true");
            }
        }
        if let Some(prompt) = &opts.prompt {
            for term in keyterms(prompt) {
                form = form.text("keyterm", term);
            }
        }
        let wav = encode_wav(pcm, SAMPLE_RATE)?;
        form = form.part("file", Part::bytes(wav).file_name("audio.wav").mime_str("audio/wav")?);

        let mut req = self.client.post(format!("{}/stt", self.base_url)).multipart(form);
        if let Some(key) = &self.api_key {
            req = req.bearer_auth(key);
        }
        let body: Response = check("xAI", req.send().await?).await?.json().await?;
        Ok(Transcript {
            text: body.text.trim().to_string(),
            language: body.language.or_else(|| opts.language.clone()),
            segments: if opts.timestamps { segments(&body.words) } else { Vec::new() },
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::test_support::MockServer;

    #[tokio::test]
    async fn sends_options_before_file() {
        let s = MockServer::start(
            200,
            r#"{"text":" Bonjour à tous. ","language":"fr","duration":1.2,
                "words":[{"text":"Bonjour","start":0.1,"end":0.5},{"text":"à","start":0.5,"end":0.6},{"text":"tous.","start":0.6,"end":1.0}]}"#,
        )
        .await;
        let e = XaiStt::new(Some(s.url()), Some("xai-key".into()), "");
        let opts = TranscribeOptions {
            language: Some("fr".into()),
            prompt: Some("Dicto, Loftia.".into()),
            timestamps: true,
        };
        let t = e.transcribe(&vec![0.0; 1600], &opts).await.unwrap();
        assert_eq!(t.text, "Bonjour à tous.");
        assert_eq!(t.language.as_deref(), Some("fr"));
        assert_eq!(t.segments.len(), 1);
        assert_eq!(t.segments[0].start_ms, 100);
        assert_eq!(t.segments[0].text, "Bonjour à tous.");
        assert_eq!(e.id(), "xai:grok-voice-transcribe-2.0");

        let req = s.request().await;
        assert!(req.starts_with("POST /stt"));
        assert!(req.to_lowercase().contains("authorization: bearer xai-key"));
        assert!(req.contains("name=\"format\"\r\n\r\ntrue"));
        assert!(req.contains("name=\"keyterm\"\r\n\r\nDicto\r\n"));
        assert!(req.contains("name=\"keyterm\"\r\n\r\nLoftia\r\n"));
        let file = req.find("name=\"file\"").unwrap();
        assert!(req.find("name=\"model\"").unwrap() < file);
        assert!(req.find("name=\"keyterm\"").unwrap() < file);
    }

    #[test]
    fn keyterms_are_split_and_bounded() {
        assert_eq!(keyterms("Dicto, Loftia."), vec!["Dicto", "Loftia"]);
        assert!(keyterms(&"x".repeat(60)).is_empty());
        let many = (0..150).map(|i| format!("t{i}")).collect::<Vec<_>>().join(", ");
        assert_eq!(keyterms(&many).len(), 100);
    }
}
