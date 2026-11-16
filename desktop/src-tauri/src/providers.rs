//! Services en ligne, comme sur Android : transcription (OpenAI, Mistral, Gemini, Grok, serveur
//! perso) et retouche par IA (Claude, OpenAI, Gemini, Ollama, serveur compatible OpenAI).

use dicto_ffi::{CloudProvider, RefineProvider};
use serde::{Deserialize, Serialize};

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum Kind {
    Transcription,
    Refine,
}

/// Description d'un fournisseur pour la liste et la fenêtre de réglage.
#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Spec {
    pub slug: &'static str,
    pub label: &'static str,
    /// Logo (voir `src/lib/logos.ts`).
    pub brand: &'static str,
    pub needs_key: bool,
    pub key_hint: &'static str,
    /// Champ d'adresse (serveur perso, Ollama) ; sinon l'adresse officielle.
    pub url_placeholder: Option<&'static str>,
    pub url_hint: Option<&'static str>,
    /// Longues listes de modèles de langage : filtres rapides dans le choix du modèle.
    pub chat_filters: bool,
}

pub const TRANSCRIPTION: &[Spec] = &[
    Spec { slug: "openai", label: "OpenAI", brand: "openai", needs_key: true, key_hint: "sk-…", url_placeholder: None, url_hint: None, chat_filters: false },
    Spec { slug: "mistral", label: "Mistral", brand: "mistral", needs_key: true, key_hint: "clé", url_placeholder: None, url_hint: None, chat_filters: false },
    Spec { slug: "gemini", label: "Google Gemini", brand: "gemini", needs_key: true, key_hint: "AIza…", url_placeholder: None, url_hint: None, chat_filters: false },
    Spec { slug: "xai", label: "Grok (xAI)", brand: "xai", needs_key: true, key_hint: "xai-…", url_placeholder: None, url_hint: None, chat_filters: false },
    Spec {
        slug: "custom",
        label: "Mon serveur",
        brand: "server",
        needs_key: false,
        key_hint: "clé (facultative)",
        url_placeholder: Some("http://IP-du-serveur:8000/v1"),
        url_hint: Some("Serveur compatible OpenAI : speaches, faster-whisper-server, LocalAI…"),
        chat_filters: false,
    },
];

pub const REFINE: &[Spec] = &[
    Spec { slug: "anthropic", label: "Claude (Anthropic)", brand: "claude", needs_key: true, key_hint: "sk-ant-…", url_placeholder: None, url_hint: None, chat_filters: true },
    Spec { slug: "openai", label: "OpenAI", brand: "openai", needs_key: true, key_hint: "sk-…", url_placeholder: None, url_hint: None, chat_filters: true },
    Spec { slug: "gemini", label: "Google Gemini", brand: "gemini", needs_key: true, key_hint: "AIza…", url_placeholder: None, url_hint: None, chat_filters: true },
    Spec {
        slug: "ollama",
        label: "Local (Ollama, LM Studio…)",
        brand: "ollama",
        needs_key: false,
        key_hint: "clé",
        url_placeholder: Some("http://localhost:11434"),
        url_hint: Some("Ollama tourne sur cet ordinateur ou un autre de ton réseau."),
        chat_filters: true,
    },
    Spec {
        slug: "compat",
        label: "Autre (compatible OpenAI)",
        brand: "server",
        needs_key: false,
        key_hint: "clé (facultative)",
        url_placeholder: Some("http://localhost:1234/v1"),
        url_hint: Some("LM Studio, llama.cpp server, vLLM… (adresse se terminant par /v1)."),
        chat_filters: true,
    },
];

pub fn specs(kind: Kind) -> &'static [Spec] {
    match kind {
        Kind::Transcription => TRANSCRIPTION,
        Kind::Refine => REFINE,
    }
}

pub fn spec(kind: Kind, slug: &str) -> Option<&'static Spec> {
    specs(kind).iter().find(|s| s.slug == slug)
}

pub fn cloud_provider(slug: &str) -> Option<CloudProvider> {
    Some(match slug {
        "openai" => CloudProvider::OpenAi,
        "mistral" => CloudProvider::Mistral,
        "gemini" => CloudProvider::Gemini,
        "xai" => CloudProvider::Xai,
        "custom" => CloudProvider::Custom,
        _ => return None,
    })
}

pub fn refine_provider(slug: &str) -> Option<RefineProvider> {
    Some(match slug {
        "anthropic" => RefineProvider::Anthropic,
        "openai" => RefineProvider::OpenAi,
        "gemini" => RefineProvider::Gemini,
        "ollama" => RefineProvider::Ollama,
        "compat" => RefineProvider::OpenAiCompatible,
        _ => return None,
    })
}

/// Nom de la clé dans le coffre. La même clé OpenAI ou Gemini sert aux deux usages.
pub fn secret_name(kind: Kind, slug: &str) -> Option<String> {
    match (kind, slug) {
        (Kind::Refine, "ollama") => None,
        (Kind::Refine, "compat") => Some("key.refine.compat".into()),
        (_, slug) => Some(format!("key.{slug}")),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_provider_maps_to_the_core() {
        assert!(TRANSCRIPTION.iter().all(|s| cloud_provider(s.slug).is_some()));
        assert!(REFINE.iter().all(|s| refine_provider(s.slug).is_some()));
    }

    #[test]
    fn openai_key_is_shared() {
        assert_eq!(secret_name(Kind::Transcription, "openai"), secret_name(Kind::Refine, "openai"));
        assert_eq!(secret_name(Kind::Refine, "ollama"), None);
    }
}
