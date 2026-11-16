//! Services de transcription en ligne.

pub mod gemini;
pub mod openai;
pub mod xai;

use crate::{DictoError, Result};

pub use openai::OpenAiCompatible;

/// Transforme une réponse HTTP en erreur lisible si elle n'est pas un succès.
pub(crate) async fn check(provider: &str, resp: reqwest::Response) -> Result<reqwest::Response> {
    let status = resp.status();
    if status.is_success() {
        return Ok(resp);
    }
    let body = resp.text().await.unwrap_or_default();
    let message = serde_json::from_str::<serde_json::Value>(&body)
        .ok()
        .and_then(|v| {
            v.pointer("/error/message")
                .or_else(|| v.pointer("/message"))
                .or_else(|| v.pointer("/detail"))
                .and_then(|m| m.as_str().map(str::to_string))
        })
        .unwrap_or_else(|| body.chars().take(300).collect());
    Err(DictoError::Api {
        provider: provider.to_string(),
        status: status.as_u16(),
        message,
    })
}

pub(crate) fn http_client() -> reqwest::Client {
    reqwest::Client::builder()
        .user_agent(concat!("Dicto/", env!("CARGO_PKG_VERSION")))
        .connect_timeout(std::time::Duration::from_secs(15))
        .timeout(std::time::Duration::from_secs(300))
        .build()
        .expect("client HTTP")
}
