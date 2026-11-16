//! Transcription par Gemini : l'audio est envoyé en ligne (WAV) avec une consigne de transcription.

use async_trait::async_trait;
use base64::Engine as _;
use serde_json::{Value, json};

use super::{check, http_client};
use crate::audio::{SAMPLE_RATE, encode_wav};
use crate::engine::{SttEngine, TranscribeOptions};
use crate::refine::gemini_text;
use crate::{Result, Transcript};

pub const FALLBACK_MODELS: &[&str] = &["gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-2.5-pro"];

pub struct GeminiStt {
    base_url: String,
    api_key: Option<String>,
    model: String,
    client: reqwest::Client,
}

impl GeminiStt {
    pub fn new(base_url: Option<String>, api_key: Option<String>, model: impl Into<String>) -> Self {
        Self {
            base_url: base_url
                .filter(|u| !u.trim().is_empty())
                .unwrap_or_else(|| "https://generativelanguage.googleapis.com/v1beta".into())
                .trim_end_matches('/')
                .to_string(),
            api_key,
            model: model.into(),
            client: http_client(),
        }
    }
}

#[async_trait]
impl SttEngine for GeminiStt {
    fn id(&self) -> String {
        format!("gemini:{}", self.model)
    }

    fn is_local(&self) -> bool {
        false
    }

    async fn transcribe(&self, pcm: &[f32], opts: &TranscribeOptions) -> Result<Transcript> {
        let audio = base64::engine::general_purpose::STANDARD.encode(encode_wav(pcm, SAMPLE_RATE)?);
        let mut instruction = String::from(
            "Transcris exactement cet enregistrement vocal, mot pour mot, avec la ponctuation. \
             Ne traduis pas, ne résume pas, n'ajoute rien. Réponds uniquement par la transcription ; \
             si rien n'est dit, réponds par une chaîne vide.",
        );
        if let Some(lang) = &opts.language {
            instruction.push_str(&format!(" Langue parlée : {lang}."));
        }
        if let Some(prompt) = &opts.prompt {
            instruction.push_str(&format!(" Vocabulaire possible : {prompt}"));
        }
        let body = json!({
            "contents": [{"role": "user", "parts": [
                {"text": instruction},
                {"inline_data": {"mime_type": "audio/wav", "data": audio}},
            ]}],
        });
        let mut req = self
            .client
            .post(format!("{}/models/{}:generateContent", self.base_url, self.model))
            .json(&body);
        if let Some(key) = &self.api_key {
            req = req.header("x-goog-api-key", key);
        }
        let v: Value = check("Gemini", req.send().await?).await?.json().await?;
        Ok(Transcript {
            text: gemini_text(&v).trim().to_string(),
            language: opts.language.clone(),
            segments: Vec::new(),
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::test_support::MockServer;

    #[tokio::test]
    async fn sends_inline_audio() {
        let s = MockServer::start(200, r#"{"candidates":[{"content":{"parts":[{"text":" Bonjour. "}]}}]}"#).await;
        let e = GeminiStt::new(Some(s.url()), Some("g".into()), "gemini-2.5-flash");
        let t = e.transcribe(&vec![0.0; 1600], &TranscribeOptions { language: Some("fr".into()), ..Default::default() }).await.unwrap();
        assert_eq!(t.text, "Bonjour.");
        let req = s.request().await;
        assert!(req.contains("\"mime_type\":\"audio/wav\""));
        assert!(req.contains("Langue parlée : fr."));
        assert_eq!(e.id(), "gemini:gemini-2.5-flash");
    }
}
