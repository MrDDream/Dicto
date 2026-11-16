//! Réglages du bureau, gardés en JSON dans la base du cœur (clé `desktop.settings`).

use std::collections::HashMap;

use dicto_ffi::{Mode, Storage};
use serde::{Deserialize, Serialize};

const KEY: &str = "desktop.settings";

/// Où va le texte dicté.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum Output {
    /// Tapé dans la fenêtre active, comme au clavier.
    Type,
    /// Copié puis collé (Ctrl+V / Cmd+V), le presse-papiers est ensuite remis.
    Paste,
    /// Gardé dans Dicto, à relire avant de copier.
    Review,
}

/// Mode d'écoute, sérialisable pour l'interface.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum ListenMode {
    PushToTalk,
    Continuous,
    Hold,
}

impl From<ListenMode> for Mode {
    fn from(m: ListenMode) -> Self {
        match m {
            ListenMode::PushToTalk => Mode::PushToTalk,
            ListenMode::Continuous => Mode::Continuous,
            ListenMode::Hold => Mode::Hold,
        }
    }
}

/// Ce que lance un bouton latéral de la souris.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum MouseAction {
    #[default]
    None,
    PushToTalk,
    Continuous,
    Hold,
}

impl MouseAction {
    pub fn mode(self) -> Option<ListenMode> {
        match self {
            MouseAction::None => None,
            MouseAction::PushToTalk => Some(ListenMode::PushToTalk),
            MouseAction::Continuous => Some(ListenMode::Continuous),
            MouseAction::Hold => Some(ListenMode::Hold),
        }
    }
}

/// Pastille d'enregistrement.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum Pill {
    /// En bas, au centre de l'écran.
    Bottom,
    /// Pas de pastille : seule l'icône de la zone de notification change.
    Hidden,
}

/// Langue de l'interface.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum UiLanguage {
    /// Celle du système.
    #[default]
    Auto,
    Fr,
    En,
}

/// Thème de l'interface.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum Theme {
    /// Clair ou sombre comme le système.
    #[default]
    Auto,
    Light,
    Dark,
}

/// Réglage d'un service en ligne (la clé, elle, est dans le coffre du système).
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct ProviderConfig {
    pub model: Option<String>,
    pub base_url: Option<String>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct Settings {
    /// Modèle local choisi (identifiant du catalogue).
    pub model: Option<String>,
    pub use_gpu: bool,
    /// « auto » ou code ISO 639-1.
    pub language: String,
    pub voice_commands: bool,
    pub live_preview: bool,
    /// Paragraphes automatiques : pause (ms) qui ouvre un paragraphe, 0 = jamais.
    pub paragraph_pause_ms: u32,
    /// « virgule », « point d'interrogation »… écrits comme de la ponctuation.
    pub spoken_punctuation: bool,
    /// Retire « euh », « hum » et les mots répétés par erreur.
    pub remove_fillers: bool,
    pub output: Output,
    /// Mode du bouton micro de la fenêtre Dicto.
    pub mode: ListenMode,
    /// Raccourcis globaux, au format « Ctrl+Alt+Space » (vide : aucun).
    pub shortcut_toggle: String,
    pub shortcut_continuous: String,
    pub shortcut_hold: String,
    /// « Dicter et traduire » : une dictée marche/arrêt traduite vers `translate_to`.
    pub shortcut_translate: String,
    /// Langue cible de la traduction à la volée (code ISO 639-1).
    pub translate_to: String,
    /// Boutons latéraux de la souris (précédent, suivant).
    pub mouse_back: MouseAction,
    pub mouse_forward: MouseAction,
    pub pill: Pill,
    pub sounds: bool,
    /// Service de transcription en ligne en service (sinon le modèle local).
    pub cloud: Option<String>,
    pub cloud_configs: HashMap<String, ProviderConfig>,
    /// Fournisseur de retouche par IA actif.
    pub refine_provider: Option<String>,
    pub refine_configs: HashMap<String, ProviderConfig>,
    /// Consigne de retouche : un préréglage du cœur, ou « custom ».
    pub refine_preset: String,
    pub refine_custom: String,
    /// Retoucher chaque dictée (sinon seulement avec « Reformuler »).
    pub auto_refine: bool,
    pub ui_language: UiLanguage,
    pub theme: Theme,
    /// Fichiers audio : séparer les voix (« Voix 1 », « Voix 2 »…).
    pub diarize: bool,
}

impl Default for Settings {
    fn default() -> Self {
        Self {
            model: None,
            use_gpu: true,
            language: "auto".into(),
            voice_commands: true,
            live_preview: true,
            paragraph_pause_ms: 2500,
            spoken_punctuation: false,
            remove_fillers: true,
            output: Output::Type,
            mode: ListenMode::PushToTalk,
            shortcut_toggle: "Ctrl+Alt+Space".into(),
            shortcut_continuous: "Ctrl+Alt+C".into(),
            shortcut_hold: "Ctrl+Shift+Space".into(),
            shortcut_translate: "Ctrl+Alt+T".into(),
            translate_to: "en".into(),
            mouse_back: MouseAction::None,
            mouse_forward: MouseAction::None,
            pill: Pill::Bottom,
            sounds: true,
            cloud: None,
            cloud_configs: HashMap::new(),
            refine_provider: None,
            refine_configs: HashMap::new(),
            refine_preset: "clean".into(),
            refine_custom: String::new(),
            auto_refine: false,
            ui_language: UiLanguage::Auto,
            theme: Theme::Auto,
            diarize: false,
        }
    }
}

impl Settings {
    pub fn load(storage: &Storage) -> Self {
        storage
            .setting(KEY.into())
            .ok()
            .flatten()
            .and_then(|json| serde_json::from_str(&json).ok())
            .unwrap_or_default()
    }

    pub fn save(&self, storage: &Storage) {
        if let Ok(json) = serde_json::to_string(self) {
            if let Err(e) = storage.set_setting(KEY.into(), json) {
                log::warn!("réglages non enregistrés : {e}");
            }
        }
    }

    /// Langue pour le moteur : `None` = détection automatique.
    pub fn language_option(&self) -> Option<String> {
        let l = self.language.trim();
        (!l.is_empty() && l != "auto").then(|| l.to_string())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn missing_fields_take_defaults() {
        let s: Settings = serde_json::from_str(r#"{"model":"whisper-base","output":"paste"}"#).unwrap();
        assert_eq!(s.model.as_deref(), Some("whisper-base"));
        assert_eq!(s.output, Output::Paste);
        assert_eq!(s.shortcut_toggle, "Ctrl+Alt+Space");
        assert!(s.voice_commands);
        assert_eq!(s.paragraph_pause_ms, 2500);
        assert_eq!(s.ui_language, UiLanguage::Auto);
        assert_eq!(s.theme, Theme::Auto);
        assert!(!s.spoken_punctuation);
        assert!(s.remove_fillers);
        assert_eq!(s.shortcut_translate, "Ctrl+Alt+T");
        assert_eq!(s.translate_to, "en");
        assert_eq!((s.mouse_back, s.mouse_forward), (MouseAction::None, MouseAction::None));
        assert!(!s.diarize);
        let s: Settings = serde_json::from_str(r#"{"mouseBack":"hold","mouseForward":"pushToTalk"}"#).unwrap();
        assert_eq!(s.mouse_back.mode(), Some(ListenMode::Hold));
        assert_eq!(s.mouse_forward.mode(), Some(ListenMode::PushToTalk));
        let s: Settings = serde_json::from_str(r#"{"uiLanguage":"en","theme":"dark"}"#).unwrap();
        assert_eq!((s.ui_language, s.theme), (UiLanguage::En, Theme::Dark));
    }

    #[test]
    fn auto_language_is_none() {
        let mut s = Settings::default();
        assert_eq!(s.language_option(), None);
        s.language = "fr".into();
        assert_eq!(s.language_option().as_deref(), Some("fr"));
    }
}
