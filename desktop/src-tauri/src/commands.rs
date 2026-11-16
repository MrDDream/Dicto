//! Commandes appelées par l'interface (Svelte).

use std::sync::Arc;

use serde::Serialize;
use tauri::{AppHandle, Emitter, State};

use crate::core::{Core, Snapshot};
use crate::settings::Settings;

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
    if previous.model != settings.model || previous.use_gpu != settings.use_gpu {
        core.load_engine();
    }
    let shortcuts_changed = previous.shortcut_toggle != settings.shortcut_toggle
        || previous.shortcut_continuous != settings.shortcut_continuous
        || previous.shortcut_hold != settings.shortcut_hold;
    let errors = if shortcuts_changed { core.register_shortcuts() } else { Vec::new() };
    core.emit_snapshot();
    errors
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
    use tauri_plugin_global_shortcut::GlobalShortcutExt;
    if paused {
        let _ = core.app.global_shortcut().unregister_all();
        Vec::new()
    } else {
        core.register_shortcuts()
    }
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

fn use_model_inner(core: &Arc<Core>, id: String) {
    let settings = {
        let mut s = core.settings.lock().unwrap();
        s.model = Some(id);
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
}

#[tauri::command]
pub fn history(core: Core_, query: Option<String>, limit: u32, offset: u32) -> Result<Vec<HistoryView>, String> {
    let items = core.storage.history(query.filter(|q| !q.trim().is_empty()), limit, offset).map_err(|e| e.to_string())?;
    Ok(items
        .into_iter()
        .map(|h| HistoryView { id: h.id, created_at_ms: h.created_at_ms, text: h.text, engine: h.engine, duration_ms: h.duration_ms })
        .collect())
}

#[tauri::command]
pub fn delete_history(core: Core_, id: i64) -> Result<(), String> {
    core.storage.delete_history(id).map_err(|e| e.to_string())
}

#[tauri::command]
pub fn clear_history(core: Core_) -> Result<(), String> {
    core.storage.clear_history().map_err(|e| e.to_string())
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
