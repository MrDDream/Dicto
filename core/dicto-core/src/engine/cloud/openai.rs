//! Transcription via l'API `audio/transcriptions` d'OpenAI et des services compatibles
//! (Mistral Voxtral, serveurs auto-hébergés type speaches / faster-whisper-server).

use async_trait::async_trait;
use reqwest::multipart::{Form, Part};
use serde::Deserialize;

use super::{check, http_client};
use crate::audio::{SAMPLE_RATE, encode_wav};
use crate::engine::{SttEngine, TranscribeOptions};
use crate::{Result, Segment, Transcript};

/// Noms typiques des modèles de reconnaissance vocale des serveurs auto-hébergés.
fn looks_like_stt(id: &str) -> bool {
    ["whisper", "transcri", "parakeet", "canary", "voxtral", "speech", "stt", "asr", "sensevoice", "moonshine"]
        .iter()
        .any(|x| id.contains(x))
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Flavor {
    OpenAi,
    Mistral,
    /// Serveur personnel : on ne présume rien des modèles proposés.
    Custom,
}

impl Flavor {
    fn name(self) -> &'static str {
        match self {
            Flavor::OpenAi => "openai",
            Flavor::Mistral => "mistral",
            Flavor::Custom => "custom",
        }
    }

    pub fn default_base_url(self) -> Option<&'static str> {
        match self {
            Flavor::OpenAi => Some("https://api.openai.com/v1"),
            Flavor::Mistral => Some("https://api.mistral.ai/v1"),
            Flavor::Custom => None,
        }
    }

    /// Modèles proposés si la liste du fournisseur est inaccessible.
    pub fn fallback_models(self) -> &'static [&'static str] {
        match self {
            Flavor::OpenAi => &["gpt-4o-transcribe", "gpt-4o-mini-transcribe", "whisper-1"],
            Flavor::Mistral => &["voxtral-mini-latest"],
            Flavor::Custom => &[],
        }
    }

    /// Seuls les modèles faits pour la transcription et utilisables par `audio/transcriptions`
    /// (pas la synthèse vocale, ni le temps réel par WebSocket, ni les modèles de conversation).
    fn is_transcription_model(self, id: &str) -> bool {
        let id = id.to_lowercase();
        let excluded = ["realtime", "tts", "search", "audio-preview"].iter().any(|x| id.contains(x));
        match self {
            Flavor::OpenAi => !excluded && (id.contains("transcribe") || id.starts_with("whisper")),
            // Voxtral Small est un modèle de conversation : seule la version Mini transcrit.
            Flavor::Mistral => !excluded && (id.starts_with("voxtral-mini") || id.contains("transcribe")),
            Flavor::Custom => !excluded && looks_like_stt(&id),
        }
    }

    /// `verbose_json` (segments horodatés) n'est pas accepté par les modèles gpt-4o-*.
    fn supports_segments(self, model: &str) -> bool {
        !(self == Flavor::OpenAi && model.contains("transcribe"))
    }
}

pub struct OpenAiCompatible {
    flavor: Flavor,
    base_url: String,
    api_key: Option<String>,
    model: String,
    client: reqwest::Client,
}

impl OpenAiCompatible {
    pub fn new(flavor: Flavor, api_key: Option<String>, model: impl Into<String>) -> Self {
        let base = flavor.default_base_url().unwrap_or("http://localhost:8000/v1");
        Self::with_base_url(flavor, base, api_key, model)
    }

    pub fn with_base_url(
        flavor: Flavor,
        base_url: impl Into<String>,
        api_key: Option<String>,
        model: impl Into<String>,
    ) -> Self {
        Self {
            flavor,
            base_url: base_url.into().trim_end_matches('/').to_string(),
            api_key: api_key.filter(|k| !k.trim().is_empty()),
            model: model.into(),
            client: http_client(),
        }
    }

    fn auth(&self, req: reqwest::RequestBuilder) -> reqwest::RequestBuilder {
        match &self.api_key {
            Some(key) => req.bearer_auth(key),
            None => req,
        }
    }

    /// Liste les modèles de transcription disponibles pour cette clé.
    pub async fn list_models(&self) -> Result<Vec<String>> {
        #[derive(Deserialize)]
        struct List {
            data: Vec<Item>,
        }
        #[derive(Deserialize)]
        struct Item {
            id: String,
        }
        let resp = self
            .auth(self.client.get(format!("{}/models", self.base_url)))
            .send()
            .await?;
        let list: List = check(self.flavor.name(), resp).await?.json().await?;
        let all: Vec<String> = list.data.into_iter().map(|m| m.id).collect();
        let mut ids: Vec<String> = all.iter().filter(|id| self.flavor.is_transcription_model(id)).cloned().collect();
        // Serveur personnel aux noms inhabituels : on montre tout plutôt que rien.
        if ids.is_empty() && self.flavor == Flavor::Custom {
            ids = all;
        }
        ids.sort();
        Ok(ids)
    }
}

#[derive(Deserialize)]
struct TranscriptionResponse {
    text: String,
    language: Option<String>,
    #[serde(default)]
    segments: Vec<ResponseSegment>,
}

#[derive(Deserialize)]
struct ResponseSegment {
    start: f64,
    end: f64,
    text: String,
}

#[async_trait]
impl SttEngine for OpenAiCompatible {
    fn id(&self) -> String {
        format!("{}:{}", self.flavor.name(), self.model)
    }

    fn is_local(&self) -> bool {
        false
    }

    async fn transcribe(&self, pcm: &[f32], opts: &TranscribeOptions) -> Result<Transcript> {
        let wav = encode_wav(pcm, SAMPLE_RATE)?;
        let file = Part::bytes(wav).file_name("audio.wav").mime_str("audio/wav")?;
        let mut form = Form::new().part("file", file).text("model", self.model.clone());
        if let Some(lang) = &opts.language {
            form = form.text("language", lang.clone());
        }
        if let Some(prompt) = &opts.prompt {
            form = form.text("prompt", prompt.clone());
        }
        let segments = opts.timestamps && self.flavor.supports_segments(&self.model);
        form = if segments {
            form.text("response_format", "verbose_json")
                .text("timestamp_granularities[]", "segment")
        } else {
            form.text("response_format", "json")
        };

        let resp = self
            .auth(self.client.post(format!("{}/audio/transcriptions", self.base_url)))
            .multipart(form)
            .send()
            .await?;
        let body: TranscriptionResponse = check(self.flavor.name(), resp).await?.json().await?;
        Ok(Transcript {
            text: body.text.trim().to_string(),
            language: body.language,
            segments: body
                .segments
                .into_iter()
                .map(|s| Segment {
                    start_ms: (s.start * 1000.0).round() as u64,
                    end_ms: (s.end * 1000.0).round() as u64,
                    text: s.text.trim().to_string(),
                    speaker: None,
                })
                .collect(),
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::DictoError;
    use crate::test_support::MockServer;

    #[tokio::test]
    async fn sends_multipart_and_parses_text() {
        let server = MockServer::start(200, r#"{"text":" Bonjour à tous. "}"#).await;
        let engine = OpenAiCompatible::with_base_url(
            Flavor::OpenAi,
            server.url(),
            Some("sk-test".into()),
            "gpt-4o-transcribe",
        );
        let opts = TranscribeOptions {
            language: Some("fr".into()),
            prompt: Some("Dicto.".into()),
            timestamps: true,
        };
        let t = engine.transcribe(&vec![0.0; 16_000], &opts).await.unwrap();
        assert_eq!(t.text, "Bonjour à tous.");

        let req = server.request().await;
        assert!(req.starts_with("POST /audio/transcriptions"), "{req}");
        assert!(req.to_lowercase().contains("authorization: bearer sk-test"));
        assert!(req.contains("name=\"model\"\r\n\r\ngpt-4o-transcribe"));
        assert!(req.contains("name=\"language\"\r\n\r\nfr"));
        // gpt-4o-transcribe ne prend pas verbose_json.
        assert!(req.contains("name=\"response_format\"\r\n\r\njson"));
        assert_eq!(engine.id(), "openai:gpt-4o-transcribe");
    }

    #[tokio::test]
    async fn parses_segments() {
        let server = MockServer::start(
            200,
            r#"{"text":"Coucou. J'arrive.","language":"french","segments":[
                {"start":0.0,"end":1.5,"text":" Coucou."},{"start":1.5,"end":3.25,"text":" J'arrive."}]}"#,
        )
        .await;
        let engine = OpenAiCompatible::with_base_url(Flavor::Custom, server.url(), None, "whisper-large-v3");
        let opts = TranscribeOptions {
            timestamps: true,
            ..Default::default()
        };
        let t = engine.transcribe(&vec![0.0; 1600], &opts).await.unwrap();
        assert_eq!(t.segments.len(), 2);
        assert_eq!(t.segments[1].start_ms, 1500);
        assert_eq!(t.segments[1].end_ms, 3250);
        assert!(server.request().await.contains("verbose_json"));
    }

    #[tokio::test]
    async fn maps_api_errors() {
        let server = MockServer::start(401, r#"{"error":{"message":"Incorrect API key provided"}}"#).await;
        let engine = OpenAiCompatible::with_base_url(Flavor::OpenAi, server.url(), Some("bad".into()), "whisper-1");
        let err = engine.transcribe(&[0.0; 160], &TranscribeOptions::default()).await.unwrap_err();
        match err {
            DictoError::Api { status, message, .. } => {
                assert_eq!(status, 401);
                assert_eq!(message, "Incorrect API key provided");
            }
            other => panic!("erreur inattendue : {other}"),
        }
    }

    #[test]
    fn keeps_only_speech_to_text_models() {
        let keep = |f: Flavor, id: &str| f.is_transcription_model(id);
        assert!(keep(Flavor::OpenAi, "gpt-4o-transcribe") && keep(Flavor::OpenAi, "whisper-1"));
        assert!(!keep(Flavor::OpenAi, "gpt-4o-mini-tts") && !keep(Flavor::OpenAi, "gpt-realtime-transcribe"));
        assert!(!keep(Flavor::OpenAi, "gpt-5") && !keep(Flavor::OpenAi, "gpt-4o-audio-preview"));
        assert!(keep(Flavor::Mistral, "voxtral-mini-latest") && !keep(Flavor::Mistral, "voxtral-small-latest"));
        assert!(!keep(Flavor::Mistral, "mistral-large-latest"));
        assert!(keep(Flavor::Custom, "Systran/faster-whisper-large-v3") && !keep(Flavor::Custom, "qwen3:8b"));
    }

    #[tokio::test]
    async fn lists_only_transcription_models() {
        let server = MockServer::start(
            200,
            r#"{"data":[{"id":"gpt-5"},{"id":"whisper-1"},{"id":"gpt-4o-transcribe"},{"id":"tts-1"}]}"#,
        )
        .await;
        let engine = OpenAiCompatible::with_base_url(Flavor::OpenAi, server.url(), Some("k".into()), "whisper-1");
        assert_eq!(engine.list_models().await.unwrap(), vec!["gpt-4o-transcribe", "whisper-1"]);
    }
}
