//! Commandes appelées par l'interface (Svelte).

use std::sync::Arc;

use serde::Serialize;
use tauri::{AppHandle, Emitter, State};

use crate::core::{Core, Snapshot};
use crate::providers::{self, Kind};
use crate::secrets;
use crate::settings::{ProviderConfig, Settings};

type Core_<'a> = State<'a, Arc<Core>>;

#[tauri::command]
pub fn snapshot(core: Core_) -> Snapshot {
    core.snapshot()
}

#[tauri::command]
pub fn get_settings(core: Core_) -> Settings {
    core.settings.lock().unwrap().clone()
}

/// Enregistre les réglages et applique ce qui a changé. Renvoie les erreurs de raccourcis.
#[tauri::command]
pub fn set_settings(core: Core_, settings: Settings) -> Vec<String> {
    let previous = std::mem::replace(&mut *core.settings.lock().unwrap(), settings.clone());
    settings.save(&core.storage);
    core.apply_options();
    if previous.model != settings.model
        || previous.use_gpu != settings.use_gpu
        || previous.cloud != settings.cloud
        || previous.cloud_configs != settings.cloud_configs
    {
        core.load_engine();
    }
    let shortcuts_changed = previous.shortcut_toggle != settings.shortcut_toggle
        || previous.shortcut_continuous != settings.shortcut_continuous
        || previous.shortcut_hold != settings.shortcut_hold
        || previous.shortcut_translate != settings.shortcut_translate;
    let errors = if shortcuts_changed { core.register_shortcuts() } else { Vec::new() };
    core.emit_snapshot();
    // L'autre fenêtre (la pastille) suit la langue et le thème.
    let _ = core.app.emit("dicto://settings", &settings);
    errors
}

/// La langue effective de l'interface (« auto » résolu par la fenêtre) : menu de l'icône et fenêtres du système.
#[tauri::command]
pub fn set_ui_locale(app: AppHandle, lang: String) {
    if crate::locale::set(&lang) {
        crate::tray::relabel(&app);
    }
}

#[tauri::command]
pub fn press(core: Core_) {
    core.press_from_app();
}

#[tauri::command]
pub fn release(core: Core_) {
    core.release();
}

#[tauri::command]
pub fn cancel(core: Core_) {
    core.cancel();
}

#[tauri::command]
pub fn copy_text(text: String) -> Result<(), String> {
    crate::insert::copy(&text)
}

/// Suspend les raccourcis pendant qu'on en saisit un nouveau (sinon il se déclencherait).
#[tauri::command]
pub fn pause_shortcuts(core: Core_, paused: bool) -> Vec<String> {
    core.inner().pause_shortcuts(paused)
}

/// Ce que l'interface doit savoir du système (Wayland : pas de boutons de souris ni de profils par app).
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PlatformView {
    os: &'static str,
    wayland: bool,
}

#[tauri::command]
pub fn platform_info() -> PlatformView {
    PlatformView { os: std::env::consts::OS, wayland: crate::foreground::is_wayland() }
}

/// Enregistre un texte préparé par l'interface (export de l'historique) : fenêtre « Enregistrer sous ».
#[tauri::command]
pub async fn save_text(app: AppHandle, name: String, extension: String, content: String) -> Result<bool, String> {
    use tauri_plugin_dialog::DialogExt;
    let Some(path) = app
        .dialog()
        .file()
        .set_title(crate::locale::tr("Enregistrer", "Save"))
        .set_file_name(format!("{name}.{extension}"))
        .add_filter(extension.to_uppercase(), &[extension.as_str()])
        .blocking_save_file()
        .and_then(|p| p.into_path().ok())
    else {
        return Ok(false);
    };
    std::fs::write(&path, content).map_err(|e| format!("Enregistrement impossible : {e}"))?;
    Ok(true)
}

// --- Modèles -----------------------------------------------------------------------------

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ModelView {
    id: String,
    name: String,
    description: String,
    engine: String,
    license: String,
    languages: Vec<String>,
    multilingual: bool,
    punctuation: bool,
    streaming: bool,
    live_preview: bool,
    speed: u8,
    accuracy: u8,
    size_bytes: u64,
    recommended: bool,
    installed: bool,
    downloaded_bytes: u64,
}

#[tauri::command]
pub fn list_models(core: Core_) -> Vec<ModelView> {
    core.models
        .models()
        .into_iter()
        .map(|m| ModelView {
            id: m.id,
            name: m.name,
            description: m.description,
            engine: m.engine,
            license: m.license,
            languages: m.languages,
            multilingual: m.multilingual,
            punctuation: m.punctuation,
            streaming: m.streaming,
            live_preview: m.live_preview,
            speed: m.speed,
            accuracy: m.accuracy,
            size_bytes: m.size_bytes,
            recommended: m.recommended,
            installed: m.installed,
            downloaded_bytes: m.downloaded_bytes,
        })
        .collect()
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct DownloadEvent {
    id: String,
    downloaded: u64,
    total: u64,
    /// « progress », « done », « error » ou « cancelled ».
    status: &'static str,
    error: Option<String>,
}

struct Progress {
    app: AppHandle,
    id: String,
    last: std::sync::Mutex<std::time::Instant>,
}

impl dicto_ffi::DownloadListener for Progress {
    fn on_progress(&self, downloaded: u64, total: u64) {
        // Au plus 10 mises à jour par seconde.
        let mut last = self.last.lock().unwrap();
        if last.elapsed().as_millis() < 100 && downloaded < total {
            return;
        }
        *last = std::time::Instant::now();
        let _ = self.app.emit(
            "dicto://download",
            DownloadEvent { id: self.id.clone(), downloaded, total, status: "progress", error: None },
        );
    }
}

/// Télécharge un modèle ; le premier téléchargé devient le modèle utilisé.
#[tauri::command]
pub async fn download_model(app: AppHandle, core: State<'_, Arc<Core>>, id: String) -> Result<(), String> {
    let core = core.inner().clone();
    let listener = Arc::new(Progress { app: app.clone(), id: id.clone(), last: std::sync::Mutex::new(std::time::Instant::now()) });
    let result = core.models.download(id.clone(), listener).await;
    let (status, error) = match &result {
        Ok(_) => ("done", None),
        Err(dicto_ffi::FfiError::Cancelled) => ("cancelled", None),
        Err(e) => ("error", Some(e.to_string())),
    };
    let _ = app.emit("dicto://download", DownloadEvent { id: id.clone(), downloaded: 0, total: 0, status, error: error.clone() });
    if result.is_ok() {
        let first = core.settings.lock().unwrap().model.is_none();
        let current = core.settings.lock().unwrap().model.clone();
        if first || current.as_deref() == Some(id.as_str()) {
            use_model_inner(&core, id);
        }
    }
    error.map_or(Ok(()), Err)
}

#[tauri::command]
pub fn cancel_download(core: Core_, id: String) {
    core.models.cancel(id);
}

#[tauri::command]
pub fn delete_model(core: Core_, id: String) -> Result<(), String> {
    core.models.delete(id.clone()).map_err(|e| e.to_string())?;
    if core.settings.lock().unwrap().model.as_deref() == Some(id.as_str()) {
        core.load_engine();
    }
    Ok(())
}

#[tauri::command]
pub fn use_model(core: Core_, id: String) {
    use_model_inner(core.inner(), id);
}

/// Choisir un modèle local quitte le service en ligne éventuel.
fn use_model_inner(core: &Arc<Core>, id: String) {
    let settings = {
        let mut s = core.settings.lock().unwrap();
        s.model = Some(id);
        s.cloud = None;
        s.clone()
    };
    settings.save(&core.storage);
    core.load_engine();
}

// --- Historique et vocabulaire -----------------------------------------------------------

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct HistoryView {
    id: i64,
    created_at_ms: i64,
    text: String,
    engine: String,
    duration_ms: u64,
    pinned: bool,
}

#[tauri::command]
pub fn history(core: Core_, query: Option<String>, limit: u32, offset: u32) -> Result<Vec<HistoryView>, String> {
    let items = core.storage.history(query.filter(|q| !q.trim().is_empty()), limit, offset).map_err(|e| e.to_string())?;
    Ok(items
        .into_iter()
        .map(|h| HistoryView {
            id: h.id,
            created_at_ms: h.created_at_ms,
            text: h.text,
            engine: h.engine,
            duration_ms: h.duration_ms,
            pinned: h.pinned,
        })
        .collect())
}

#[tauri::command]
pub fn delete_history(core: Core_, id: i64) -> Result<(), String> {
    core.storage.delete_history(id).map_err(|e| e.to_string())
}

/// Efface l'historique, sauf les dictées épinglées.
#[tauri::command]
pub fn clear_history(core: Core_) -> Result<(), String> {
    core.storage.clear_history().map_err(|e| e.to_string())
}

#[tauri::command]
pub fn set_history_pinned(core: Core_, id: i64, pinned: bool) -> Result<(), String> {
    core.storage.set_history_pinned(id, pinned).map_err(|e| e.to_string())
}

/// Retouche après coup : le nouveau texte remplace l'ancien (gardé comme texte d'origine).
#[tauri::command]
pub fn update_history_text(core: Core_, id: i64, text: String) -> Result<(), String> {
    core.storage.update_history_text(id, text).map_err(|e| e.to_string())
}

// --- Remplacements, profils par app, sauvegarde ------------------------------------------

#[derive(Serialize)]
pub struct ReplacementView {
    id: i64,
    phrase: String,
    replacement: String,
}

#[tauri::command]
pub fn replacements(core: Core_) -> Result<Vec<ReplacementView>, String> {
    Ok(core
        .storage
        .replacements()
        .map_err(|e| e.to_string())?
        .into_iter()
        .map(|r| ReplacementView { id: r.id, phrase: r.phrase, replacement: r.replacement })
        .collect())
}

#[tauri::command]
pub fn set_replacement(core: Core_, phrase: String, replacement: String) -> Result<(), String> {
    core.storage.set_replacement(phrase, replacement).map_err(|e| e.to_string())?;
    core.apply_options();
    Ok(())
}

#[tauri::command]
pub fn remove_replacement(core: Core_, id: i64) -> Result<(), String> {
    core.storage.remove_replacement(id).map_err(|e| e.to_string())?;
    core.apply_options();
    Ok(())
}

#[derive(Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ProfileView {
    id: Option<i64>,
    app_id: String,
    label: Option<String>,
    language: Option<String>,
    mode: Option<String>,
    refine_preset: Option<String>,
}

#[tauri::command]
pub fn profiles(core: Core_) -> Result<Vec<ProfileView>, String> {
    Ok(core
        .storage
        .profiles()
        .map_err(|e| e.to_string())?
        .into_iter()
        .map(|p| ProfileView {
            id: p.id,
            app_id: p.app_id,
            label: p.label,
            language: p.language,
            mode: p.mode,
            refine_preset: p.refine_preset,
        })
        .collect())
}

/// Ajoute ou met à jour le profil d'une app (identifiée par `appId`).
#[tauri::command]
pub fn save_profile(core: Core_, profile: ProfileView) -> Result<i64, String> {
    let app_id = profile.app_id.trim().to_lowercase();
    if app_id.is_empty() {
        return Err("Indique l'app (nom de l'exécutable ou identifiant).".into());
    }
    let clean = |v: Option<String>| v.map(|s| s.trim().to_string()).filter(|s| !s.is_empty());
    core.storage
        .save_profile(dicto_ffi::AppProfile {
            id: profile.id,
            app_id,
            label: clean(profile.label),
            language: clean(profile.language),
            engine: None,
            mode: clean(profile.mode),
            refine_preset: clean(profile.refine_preset),
        })
        .map_err(|e| e.to_string())
}

#[tauri::command]
pub fn delete_profile(core: Core_, id: i64) -> Result<(), String> {
    core.storage.delete_profile(id).map_err(|e| e.to_string())
}

/// Dernière app au premier plan autre que Dicto (pour créer un profil d'un clic).
#[tauri::command]
pub fn last_app(core: Core_) -> Option<crate::foreground::AppInfo> {
    core.last_app.lock().unwrap().clone()
}

#[tauri::command]
pub fn translation_targets() -> Vec<String> {
    dicto_ffi::translation_targets()
}

/// Exporte vocabulaire, remplacements et profils dans un fichier JSON.
#[tauri::command]
pub async fn export_bundle(app: AppHandle, core: State<'_, Arc<Core>>) -> Result<bool, String> {
    use tauri_plugin_dialog::DialogExt;
    let json = core.storage.export_bundle().map_err(|e| e.to_string())?;
    let Some(path) = app
        .dialog()
        .file()
        .set_title(crate::locale::tr("Exporter les réglages de Dicto", "Export Dicto settings"))
        .set_file_name("dicto-export.json")
        .add_filter("JSON", &["json"])
        .blocking_save_file()
        .and_then(|p| p.into_path().ok())
    else {
        return Ok(false);
    };
    std::fs::write(&path, json).map_err(|e| format!("Enregistrement impossible : {e}"))?;
    Ok(true)
}

#[derive(Serialize)]
pub struct ImportView {
    vocabulary: u32,
    replacements: u32,
    profiles: u32,
}

/// Importe un fichier exporté par Dicto (ce téléphone ou un autre ordinateur) ; rien n'est supprimé.
#[tauri::command]
pub async fn import_bundle(app: AppHandle, core: State<'_, Arc<Core>>) -> Result<Option<ImportView>, String> {
    use tauri_plugin_dialog::DialogExt;
    let Some(path) = app
        .dialog()
        .file()
        .set_title(crate::locale::tr("Importer des réglages de Dicto", "Import Dicto settings"))
        .add_filter("JSON", &["json"])
        .blocking_pick_file()
        .and_then(|p| p.into_path().ok())
    else {
        return Ok(None);
    };
    let json = std::fs::read_to_string(&path).map_err(|e| format!("Lecture impossible : {e}"))?;
    let c = core.storage.import_bundle(json).map_err(|e| e.to_string())?;
    core.apply_options();
    Ok(Some(ImportView { vocabulary: c.vocabulary, replacements: c.replacements, profiles: c.profiles }))
}

#[derive(Serialize)]
pub struct TermView {
    id: i64,
    term: String,
}

#[tauri::command]
pub fn vocabulary(core: Core_) -> Result<Vec<TermView>, String> {
    Ok(core
        .storage
        .vocabulary()
        .map_err(|e| e.to_string())?
        .into_iter()
        .map(|t| TermView { id: t.id, term: t.term })
        .collect())
}

#[tauri::command]
pub fn add_term(core: Core_, term: String) -> Result<(), String> {
    core.storage.add_term(term).map_err(|e| e.to_string())?;
    core.apply_options();
    Ok(())
}

#[tauri::command]
pub fn remove_term(core: Core_, id: i64) -> Result<(), String> {
    core.storage.remove_term(id).map_err(|e| e.to_string())?;
    core.apply_options();
    Ok(())
}

#[derive(Serialize)]
pub struct CreditView {
    name: String,
    license: String,
    credit: String,
    url: String,
}

#[tauri::command]
pub fn credits(core: Core_) -> Vec<CreditView> {
    core.models
        .credits()
        .into_iter()
        .map(|c| CreditView { name: c.name, license: c.license, credit: c.credit, url: c.url })
        .collect()
}

// --- Services en ligne et retouche par IA ------------------------------------------------

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ProviderView {
    #[serde(flatten)]
    spec: providers::Spec,
    has_key: bool,
    model: Option<String>,
    base_url: Option<String>,
    /// Modèle choisi, clé et adresse présentes si nécessaires.
    configured: bool,
    active: bool,
}

fn configs(s: &Settings, kind: Kind) -> &std::collections::HashMap<String, ProviderConfig> {
    match kind {
        Kind::Transcription => &s.cloud_configs,
        Kind::Refine => &s.refine_configs,
    }
}

#[tauri::command]
pub fn providers(core: Core_, kind: Kind) -> Vec<ProviderView> {
    let s = core.settings.lock().unwrap().clone();
    let active = match kind {
        Kind::Transcription => s.cloud.clone(),
        Kind::Refine => s.refine_provider.clone(),
    };
    providers::specs(kind)
        .iter()
        .map(|spec| {
            let cfg = configs(&s, kind).get(spec.slug).cloned().unwrap_or_default();
            let has_key = providers::secret_name(kind, spec.slug).is_some_and(|n| secrets::has(&n));
            let configured = cfg.model.as_deref().is_some_and(|m| !m.is_empty())
                && (!spec.needs_key || has_key)
                && (spec.url_placeholder.is_none() || cfg.base_url.as_deref().is_some_and(|u| !u.is_empty()));
            ProviderView {
                spec: spec.clone(),
                has_key,
                model: cfg.model,
                base_url: cfg.base_url,
                configured,
                active: active.as_deref() == Some(spec.slug),
            }
        })
        .collect()
}

/// Enregistre la clé (si saisie), le modèle et l'adresse d'un fournisseur.
#[tauri::command]
pub fn save_provider(
    core: Core_,
    kind: Kind,
    slug: String,
    key: Option<String>,
    model: String,
    base_url: Option<String>,
) -> Result<Settings, String> {
    providers::spec(kind, &slug).ok_or("Fournisseur inconnu.")?;
    if let (Some(key), Some(name)) = (key.filter(|k| !k.trim().is_empty()), providers::secret_name(kind, &slug)) {
        secrets::set(&name, key.trim())?;
    }
    let cfg = ProviderConfig {
        model: Some(model.trim().to_string()).filter(|m| !m.is_empty()),
        base_url: base_url.map(|u| u.trim().to_string()).filter(|u| !u.is_empty()),
    };
    let settings = {
        let mut s = core.settings.lock().unwrap();
        match kind {
            Kind::Transcription => s.cloud_configs.insert(slug.clone(), cfg),
            Kind::Refine => s.refine_configs.insert(slug.clone(), cfg),
        };
        s.clone()
    };
    settings.save(&core.storage);
    match kind {
        Kind::Transcription if settings.cloud.as_deref() == Some(slug.as_str()) => core.load_engine(),
        Kind::Refine => core.apply_options(),
        _ => {}
    }
    Ok(settings)
}

#[tauri::command]
pub fn forget_provider_key(core: Core_, kind: Kind, slug: String) -> Settings {
    if let Some(name) = providers::secret_name(kind, &slug) {
        secrets::forget(&name);
    }
    let settings = {
        let mut s = core.settings.lock().unwrap();
        match kind {
            Kind::Transcription if s.cloud.as_deref() == Some(slug.as_str()) => s.cloud = None,
            Kind::Refine if s.refine_provider.as_deref() == Some(slug.as_str()) => s.refine_provider = None,
            _ => {}
        }
        s.clone()
    };
    settings.save(&core.storage);
    core.load_engine();
    core.apply_options();
    settings
}

/// Modèles proposés par le fournisseur (`key`, `base_url` : ceux saisis, sinon les enregistrés).
#[tauri::command]
pub async fn provider_models(
    core: State<'_, Arc<Core>>,
    kind: Kind,
    slug: String,
    key: Option<String>,
    base_url: Option<String>,
) -> Result<Vec<String>, String> {
    let saved = configs(&core.settings.lock().unwrap(), kind).get(&slug).cloned().unwrap_or_default();
    let key = key
        .filter(|k| !k.trim().is_empty())
        .or_else(|| providers::secret_name(kind, &slug).and_then(|n| secrets::get(&n)));
    let base_url = base_url.filter(|u| !u.trim().is_empty()).or(saved.base_url);
    match kind {
        Kind::Transcription => {
            let provider = providers::cloud_provider(&slug).ok_or("Fournisseur inconnu.")?;
            match dicto_ffi::list_cloud_models(provider, base_url, key).await {
                Ok(models) if !models.is_empty() => Ok(models),
                Ok(_) => Ok(dicto_ffi::fallback_cloud_models(provider)),
                Err(e) => Err(e.to_string()),
            }
        }
        Kind::Refine => {
            let provider = providers::refine_provider(&slug).ok_or("Fournisseur inconnu.")?;
            dicto_ffi::RefineClient::new(provider, base_url, key, None).list_models().await.map_err(|e| e.to_string())
        }
    }
}

/// Met un service en ligne en service (ou revient au modèle local avec `None`).
#[tauri::command]
pub fn set_cloud_engine(core: Core_, slug: Option<String>) -> Settings {
    let settings = {
        let mut s = core.settings.lock().unwrap();
        s.cloud = slug;
        s.clone()
    };
    settings.save(&core.storage);
    core.load_engine();
    settings
}

#[tauri::command]
pub fn set_refine_provider(core: Core_, slug: Option<String>) -> Settings {
    let settings = {
        let mut s = core.settings.lock().unwrap();
        // Modèle conseillé par défaut au premier choix.
        if let Some(slug) = &slug {
            let cfg = s.refine_configs.entry(slug.clone()).or_default();
            if cfg.model.is_none() {
                if let Some(p) = providers::refine_provider(slug) {
                    cfg.model = Some(dicto_ffi::default_refine_model(p)).filter(|m| !m.is_empty());
                }
            }
        }
        s.refine_provider = slug;
        s.clone()
    };
    settings.save(&core.storage);
    core.apply_options();
    settings
}

#[derive(Serialize)]
pub struct PresetView {
    id: String,
    name: String,
}

#[tauri::command]
pub fn refine_presets() -> Vec<PresetView> {
    dicto_ffi::refine_presets().into_iter().map(|p| PresetView { id: p.id, name: p.name }).collect()
}

/// « Reformuler » : retouche un texte avec un préréglage.
#[tauri::command]
pub async fn refine_text(core: State<'_, Arc<Core>>, preset: String, text: String) -> Result<String, String> {
    let client = core.refine_client().ok_or("Active d'abord un fournisseur de retouche dans Réglages.")?;
    let instruction = core.preset_instruction(&preset).ok_or("Consigne vide.")?;
    client.refine(instruction, text).await.map_err(|e| e.to_string())
}

// --- Fichiers audio ----------------------------------------------------------------------

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FileResult {
    name: String,
    text: String,
    duration_ms: u64,
    engine: String,
    segments: usize,
    /// Nombre de voix séparées (0 : pas de séparation).
    speakers: u32,
    lines: Vec<LineView>,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LineView {
    start_ms: u64,
    /// Voix (0, 1…), si la séparation des voix a été faite.
    speaker: Option<u32>,
    text: String,
}

const DIARIZATION: &str = "diarization";

/// Téléchargement du modèle de séparation des voix, sur la barre de progression du fichier.
struct DiarizationDownload(AppHandle);

impl dicto_ffi::DownloadListener for DiarizationDownload {
    fn on_progress(&self, downloaded: u64, total: u64) {
        if total > 0 {
            let _ = self.0.emit("dicto://file-progress", downloaded as f32 / total as f32);
        }
    }
}

struct FileProgress(AppHandle);

impl dicto_ffi::ProgressListener for FileProgress {
    fn on_progress(&self, fraction: f32) {
        let _ = self.0.emit("dicto://file-progress", fraction);
    }
}

/// Choisit un fichier audio (fenêtre du système).
#[tauri::command]
pub async fn pick_audio_file(app: AppHandle) -> Option<String> {
    use tauri_plugin_dialog::DialogExt;
    app.dialog()
        .file()
        .set_title(crate::locale::tr("Fichier audio à transcrire", "Audio file to transcribe"))
        .add_filter("Audio", &["mp3", "m4a", "aac", "mp4", "wav", "flac", "ogg", "oga", "mkv", "webm"])
        .blocking_pick_file()
        .and_then(|p| p.into_path().ok())
        .map(|p| p.to_string_lossy().into_owned())
}

#[tauri::command]
pub async fn transcribe_file(app: AppHandle, core: State<'_, Arc<Core>>, path: String) -> Result<FileResult, String> {
    let engine = core.current_engine().ok_or("Choisis d'abord un modèle ou un service dans Modèles.")?;
    let path = std::path::PathBuf::from(path);
    let name = path.file_stem().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
    let decoded = tauri::async_runtime::spawn_blocking(move || crate::decode::decode(&path))
        .await
        .map_err(|e| e.to_string())??;
    let (language, vocabulary) = {
        let s = core.settings.lock().unwrap();
        let vocab = core.storage.vocabulary().map(|v| v.into_iter().map(|t| t.term).collect()).unwrap_or_default();
        (s.language_option(), vocab)
    };
    // Séparation des voix : le modèle (~46 Mo) est téléchargé la première fois.
    let diarization_dir = if core.settings.lock().unwrap().diarize {
        match core.models.support_path(DIARIZATION.into()) {
            Some(dir) => Some(dir),
            None => {
                let _ = app.emit("dicto://file-stage", "model");
                let dir = core
                    .models
                    .download_support(DIARIZATION.into(), Arc::new(DiarizationDownload(app.clone())))
                    .await
                    .map_err(|e| format!("Modèle de séparation des voix : {e}"))?;
                Some(dir)
            }
        }
    } else {
        None
    };
    let _ = app.emit("dicto://file-stage", "transcribe");
    let job = dicto_ffi::FileJob::new();
    *core.file_job.lock().unwrap() = Some(job.clone());
    let result = job
        .transcribe(
            engine,
            decoded.pcm,
            decoded.sample_rate,
            decoded.channels,
            language,
            vocabulary,
            diarization_dir,
            Arc::new(FileProgress(app)),
        )
        .await;
    *core.file_job.lock().unwrap() = None;
    let transcript = result.map_err(|e| match e {
        dicto_ffi::FfiError::Cancelled => "Transcription annulée.".to_string(),
        e => e.to_string(),
    })?;
    let mut voices: Vec<u32> = transcript.segments.iter().filter_map(|s| s.speaker).collect();
    voices.sort_unstable();
    voices.dedup();
    let view = FileResult {
        name: name.clone(),
        text: transcript.text.clone(),
        duration_ms: transcript.duration_ms,
        engine: transcript.engine.clone(),
        segments: transcript.segments.len(),
        speakers: voices.len() as u32,
        lines: transcript
            .segments
            .iter()
            .map(|s| LineView { start_ms: s.start_ms, speaker: s.speaker, text: s.text.clone() })
            .collect(),
    };
    *core.last_file.lock().unwrap() = Some((name, transcript));
    Ok(view)
}

/// Compte rendu de réunion de la dernière transcription, par le fournisseur de retouche.
#[tauri::command]
pub async fn meeting_minutes(core: State<'_, Arc<Core>>) -> Result<String, String> {
    let client = core.refine_client().ok_or("Active d'abord un fournisseur de retouche dans Réglages.")?;
    let (_, transcript) = core.last_file.lock().unwrap().clone().ok_or("Aucune transcription.")?;
    let text = dicto_ffi::transcript_for_llm(transcript);
    client.refine(dicto_ffi::meeting_minutes_instruction(), text).await.map_err(|e| e.to_string())
}

#[tauri::command]
pub fn cancel_file(core: Core_) {
    if let Some(job) = core.file_job.lock().unwrap().as_ref() {
        job.cancel();
    }
}

/// Enregistre la dernière transcription de fichier : txt, srt, vtt ou md.
#[tauri::command]
pub async fn export_file(app: AppHandle, core: State<'_, Arc<Core>>, format: String) -> Result<bool, String> {
    use tauri_plugin_dialog::DialogExt;
    let (name, transcript) = core.last_file.lock().unwrap().clone().ok_or("Aucune transcription à exporter.")?;
    let fmt = match format.as_str() {
        "srt" => dicto_ffi::ExportFormat::Srt,
        "vtt" => dicto_ffi::ExportFormat::Vtt,
        "md" => dicto_ffi::ExportFormat::Md,
        _ => dicto_ffi::ExportFormat::Txt,
    };
    let content = dicto_ffi::export_transcript(transcript, fmt, name.clone());
    let Some(path) = app
        .dialog()
        .file()
        .set_title(crate::locale::tr("Enregistrer la transcription", "Save the transcript"))
        .set_file_name(format!("{name}.{format}"))
        .add_filter(format.to_uppercase(), &[format.as_str()])
        .blocking_save_file()
        .and_then(|p| p.into_path().ok())
    else {
        return Ok(false);
    };
    std::fs::write(&path, content).map_err(|e| format!("Enregistrement impossible : {e}"))?;
    Ok(true)
}
