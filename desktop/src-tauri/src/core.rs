//! Cœur de l'app de bureau : relie la dictée partagée (dicto-ffi), le micro, les raccourcis,
//! l'écriture dans la fenêtre active, la pastille et l'interface.

use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use dicto_ffi::{
    Dictation, DictationListener, DictationResult, DiscardReason, Engine, FfiError, ModelManager, RefineClient, State,
    Storage, TextEdit, Trigger, refine_presets, render_edits,
};
use serde::Serialize;
use tauri::{AppHandle, Emitter, Manager, PhysicalPosition};
use tauri_plugin_global_shortcut::{GlobalShortcutExt, Shortcut};

use crate::audio::Recorder;
use crate::insert::{self, Written};
use crate::providers::{self, Kind};
use crate::secrets;
use crate::settings::{ListenMode, Output, Pill, ProviderConfig, Settings};

const SILERO: &str = "silero-vad";

/// État du moteur, pour l'interface.
#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct EngineStatus {
    /// « none », « loading », « ready » ou « error ».
    pub kind: &'static str,
    pub label: String,
    pub detail: Option<String>,
}

impl EngineStatus {
    fn none(detail: &str) -> Self {
        Self { kind: "none", label: "Aucun modèle".into(), detail: Some(detail.into()) }
    }
}

/// Tout ce que l'interface affiche, envoyé à chaque changement.
#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Snapshot {
    /// « idle », « recording », « listening » ou « speaking ».
    pub state: &'static str,
    pub active: bool,
    pub mode: ListenMode,
    pub pending: u32,
    pub partial: String,
    pub engine: EngineStatus,
    /// Début de l'écoute (ms depuis l'epoch), pour le chronomètre.
    pub started_at: Option<u64>,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ResultView {
    pub text: String,
    pub engine: String,
    pub duration_ms: u64,
    /// Texte écrit dans une autre fenêtre (sinon gardé dans Dicto).
    pub written: bool,
}

/// Raccourcis globaux et le mode qu'ils lancent.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Hotkey {
    Toggle,
    Continuous,
    Hold,
    Escape,
}

/// D'où vient la dictée en cours : de la fenêtre Dicto (le texte y reste) ou d'un raccourci.
#[derive(Clone, Copy, PartialEq, Eq)]
enum Source {
    App,
    Shortcut,
}

fn state_name(s: State) -> &'static str {
    match s {
        State::Idle => "idle",
        State::Recording => "recording",
        State::Listening => "listening",
        State::Speaking => "speaking",
    }
}

/// Retire 1 sans passer sous zéro.
fn decrement(n: &AtomicU32) {
    let mut current = n.load(Ordering::SeqCst);
    while current > 0 {
        match n.compare_exchange(current, current - 1, Ordering::SeqCst, Ordering::SeqCst) {
            Ok(_) => return,
            Err(actual) => current = actual,
        }
    }
}

fn now_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

pub struct Core {
    pub app: AppHandle,
    pub dictation: Arc<Dictation>,
    pub models: Arc<ModelManager>,
    pub storage: Arc<Storage>,
    pub settings: Mutex<Settings>,
    recorder: Recorder,
    engine: Mutex<EngineStatus>,
    /// Moteur chargé (local ou en ligne), gardé pour les fichiers audio.
    engine_obj: Mutex<Option<Arc<Engine>>>,
    state: Mutex<State>,
    started_at: Mutex<Option<u64>>,
    pending: AtomicU32,
    partial: Mutex<String>,
    source: Mutex<Source>,
    /// Longueurs des derniers textes écrits (« efface ça ») et heure du dernier.
    written: Mutex<(Vec<usize>, Option<Instant>)>,
    hotkeys: Mutex<HashMap<u32, Hotkey>>,
    escape: AtomicBool,
    silero: Mutex<Option<String>>,
    silero_applied: AtomicBool,
    /// Transcription de fichier en cours (annulable) et dernier résultat (pour l'export).
    pub file_job: Mutex<Option<Arc<dicto_ffi::FileJob>>>,
    pub last_file: Mutex<Option<(String, dicto_ffi::FileTranscript)>>,
}

struct Listener(AppHandle);

impl Listener {
    fn core(&self) -> Option<Arc<Core>> {
        self.0.try_state::<Arc<Core>>().map(|s| s.inner().clone())
    }
}

impl DictationListener for Listener {
    fn on_capture(&self, active: bool) {
        if let Some(c) = self.core() {
            c.on_capture(active);
        }
    }
    fn on_state(&self, state: State) {
        if let Some(c) = self.core() {
            c.on_state(state);
        }
    }
    fn on_transcribing(&self, _utterance_id: u64) {
        if let Some(c) = self.core() {
            c.pending.fetch_add(1, Ordering::SeqCst);
            c.emit_snapshot();
        }
    }
    fn on_partial(&self, text: String) {
        if let Some(c) = self.core() {
            *c.partial.lock().unwrap() = text;
            c.emit_snapshot();
        }
    }
    fn on_result(&self, _utterance_id: u64, result: DictationResult) {
        if let Some(c) = self.core() {
            c.on_result(result);
        }
    }
    fn on_discarded(&self, reason: DiscardReason) {
        if let Some(c) = self.core() {
            if reason == DiscardReason::NoSpeech {
                c.done_one();
            }
        }
    }
    fn on_error(&self, _utterance_id: u64, error: FfiError) {
        if let Some(c) = self.core() {
            c.done_one();
            c.message(&format!("Transcription impossible : {error}"));
        }
    }
}

impl Core {
    pub fn new(app: AppHandle) -> Result<Arc<Self>, String> {
        let data = app.path().app_data_dir().map_err(|e| e.to_string())?;
        std::fs::create_dir_all(&data).map_err(|e| e.to_string())?;
        let storage = Storage::open(data.join("dicto.db").to_string_lossy().into()).map_err(|e| e.to_string())?;
        let models_dir = data.join("models");
        std::fs::create_dir_all(&models_dir).map_err(|e| e.to_string())?;
        let models = ModelManager::new(models_dir.to_string_lossy().into(), "desktop".into());
        let settings = Settings::load(&storage);
        let dictation = Dictation::new(settings.mode.into(), Arc::new(Listener(app.clone())));
        Ok(Arc::new(Self {
            app,
            dictation,
            models,
            storage,
            settings: Mutex::new(settings),
            recorder: Recorder::default(),
            engine: Mutex::new(EngineStatus::none("Choisis un modèle dans Modèles.")),
            engine_obj: Mutex::new(None),
            state: Mutex::new(State::Idle),
            started_at: Mutex::new(None),
            pending: AtomicU32::new(0),
            partial: Mutex::new(String::new()),
            source: Mutex::new(Source::App),
            written: Mutex::new((Vec::new(), None)),
            hotkeys: Mutex::new(HashMap::new()),
            escape: AtomicBool::new(false),
            silero: Mutex::new(None),
            silero_applied: AtomicBool::new(false),
            file_job: Mutex::new(None),
            last_file: Mutex::new(None),
        }))
    }

    /// Au lancement : options, moteur, détection de voix, raccourcis.
    pub fn start(self: &Arc<Self>) {
        self.apply_options();
        self.load_engine();
        self.setup_silero();
        let errors = self.register_shortcuts();
        if !errors.is_empty() {
            self.message(&errors.join("\n"));
        }
    }

    // --- Instantané et messages ---------------------------------------------------------

    pub fn snapshot(&self) -> Snapshot {
        let state = *self.state.lock().unwrap();
        Snapshot {
            state: state_name(state),
            active: state != State::Idle,
            mode: self.settings.lock().unwrap().mode,
            pending: self.pending.load(Ordering::SeqCst),
            partial: self.partial.lock().unwrap().clone(),
            engine: self.engine.lock().unwrap().clone(),
            started_at: *self.started_at.lock().unwrap(),
        }
    }

    pub fn emit_snapshot(&self) {
        let _ = self.app.emit("dicto://snapshot", self.snapshot());
    }

    pub fn message(&self, text: &str) {
        log::info!("{text}");
        let _ = self.app.emit("dicto://message", text.to_string());
    }

    fn done_one(&self) {
        decrement(&self.pending);
        self.emit_snapshot();
        self.update_pill();
    }

    // --- Options et moteur --------------------------------------------------------------

    pub fn apply_options(&self) {
        let s = self.settings.lock().unwrap().clone();
        let vocabulary = self.storage.vocabulary().map(|v| v.into_iter().map(|t| t.term).collect()).unwrap_or_default();
        self.dictation.set_options(s.language_option(), vocabulary, s.voice_commands);
        self.dictation.set_live_preview(s.live_preview);
        if self.dictation.state() == State::Idle {
            self.dictation.set_mode(s.mode.into());
        }
        // Retouche automatique de chaque dictée, si demandée.
        let instruction = if s.auto_refine { self.preset_instruction(&s.refine_preset) } else { None };
        match (instruction, self.refine_client()) {
            (Some(i), Some(client)) => self.dictation.set_refiner(Some(client), Some(i)),
            _ => self.dictation.set_refiner(None, None),
        }
    }

    /// Client du fournisseur de retouche actif, s'il est configuré.
    pub fn refine_client(&self) -> Option<Arc<RefineClient>> {
        let s = self.settings.lock().unwrap();
        let slug = s.refine_provider.clone()?;
        let provider = providers::refine_provider(&slug)?;
        let cfg = s.refine_configs.get(&slug).cloned().unwrap_or_default();
        let key = providers::secret_name(Kind::Refine, &slug).and_then(|n| secrets::get(&n));
        Some(RefineClient::new(provider, cfg.base_url, key, cfg.model))
    }

    /// Consigne d'un préréglage (« custom » : celle de l'utilisateur).
    pub fn preset_instruction(&self, id: &str) -> Option<String> {
        match id {
            "" | "none" => None,
            "custom" => Some(self.settings.lock().unwrap().refine_custom.clone()).filter(|c| !c.trim().is_empty()),
            id => refine_presets().into_iter().find(|p| p.id == id).map(|p| p.instruction),
        }
    }

    /// Moteur en service (modèle local ou service en ligne), pour les fichiers audio.
    pub fn current_engine(&self) -> Option<Arc<Engine>> {
        self.engine_obj.lock().unwrap().clone()
    }

    fn set_engine_status(&self, status: EngineStatus) {
        *self.engine.lock().unwrap() = status;
        self.emit_snapshot();
    }

    /// Charge le modèle choisi (quelques secondes pour les gros) sans bloquer l'interface.
    pub fn load_engine(self: &Arc<Self>) {
        *self.engine_obj.lock().unwrap() = None;
        let (model, gpu, cloud) = {
            let s = self.settings.lock().unwrap();
            let cloud = s.cloud.clone().map(|slug| (s.cloud_configs.get(&slug).cloned().unwrap_or_default(), slug));
            (s.model.clone(), s.use_gpu, cloud)
        };
        if let Some((cfg, slug)) = cloud {
            self.load_cloud_engine(&slug, cfg);
            return;
        }
        let Some(id) = model else {
            self.set_engine_status(EngineStatus::none("Choisis un modèle dans Modèles."));
            return;
        };
        if !self.models.is_installed(id.clone()) {
            self.set_engine_status(EngineStatus::none("Le modèle choisi n'est pas téléchargé."));
            return;
        }
        let label = self.models.models().into_iter().find(|m| m.id == id).map(|m| m.name).unwrap_or(id.clone());
        self.set_engine_status(EngineStatus { kind: "loading", label: label.clone(), detail: Some("chargement…".into()) });
        let core = self.clone();
        std::thread::spawn(move || match core.models.create_engine(id, gpu) {
            Ok(engine) => {
                *core.engine_obj.lock().unwrap() = Some(engine.clone());
                core.dictation.set_engine(engine);
                core.set_engine_status(EngineStatus {
                    kind: "ready",
                    label,
                    detail: Some(if gpu { "local · GPU si disponible".into() } else { "local · processeur".into() }),
                });
            }
            Err(e) => core.set_engine_status(EngineStatus { kind: "error", label, detail: Some(e.to_string()) }),
        });
    }

    /// Service en ligne : rien à charger, le client est prêt aussitôt.
    fn load_cloud_engine(&self, slug: &str, cfg: ProviderConfig) {
        let Some(spec) = providers::spec(Kind::Transcription, slug) else {
            self.set_engine_status(EngineStatus::none("Service inconnu."));
            return;
        };
        let key = providers::secret_name(Kind::Transcription, slug).and_then(|n| secrets::get(&n));
        let problem = match (&cfg.model, &key, &cfg.base_url) {
            (None, _, _) => Some("aucun modèle choisi"),
            (_, None, _) if spec.needs_key => Some("clé d'API manquante"),
            (_, _, None) if spec.url_placeholder.is_some() => Some("adresse du serveur manquante"),
            _ => None,
        };
        if let Some(p) = problem {
            self.set_engine_status(EngineStatus { kind: "error", label: spec.label.into(), detail: Some(format!("{p} : règle-le dans Modèles.")) });
            return;
        }
        let model = cfg.model.unwrap_or_default();
        let provider = providers::cloud_provider(slug).expect("service connu");
        let engine = Engine::cloud(provider, cfg.base_url, key, model.clone());
        *self.engine_obj.lock().unwrap() = Some(engine.clone());
        self.dictation.set_engine(engine);
        self.set_engine_status(EngineStatus { kind: "ready", label: format!("{} · {model}", spec.label), detail: Some("en ligne".into()) });
    }

    /// Détection de voix Silero (212 Ko) pour le mode continu, téléchargée une fois.
    fn setup_silero(self: &Arc<Self>) {
        let core = self.clone();
        tauri::async_runtime::spawn(async move {
            let dir = match core.models.support_path(SILERO.into()) {
                Some(d) => d,
                None => match core.models.download_support(SILERO.into(), Arc::new(NoProgress)).await {
                    Ok(d) => d,
                    Err(e) => {
                        log::warn!("Silero indisponible, détecteur à énergie conservé : {e}");
                        return;
                    }
                },
            };
            let path = std::path::Path::new(&dir).join("silero_vad.int8.onnx");
            *core.silero.lock().unwrap() = Some(path.to_string_lossy().into());
            core.apply_silero();
        });
    }

    /// Seulement au repos : la détection ne change pas en pleine phrase.
    fn apply_silero(&self) {
        if self.silero_applied.load(Ordering::SeqCst) || self.dictation.state() != State::Idle {
            return;
        }
        if let Some(path) = self.silero.lock().unwrap().clone() {
            let ok = self.dictation.use_silero(Some(path)).unwrap_or(false);
            self.silero_applied.store(ok, Ordering::SeqCst);
        }
    }

    // --- Commandes de dictée ------------------------------------------------------------

    /// Bouton micro de la fenêtre Dicto : le texte reste dans Dicto.
    pub fn press_from_app(&self) {
        if self.dictation.state() == State::Idle {
            *self.source.lock().unwrap() = Source::App;
            let mode = self.settings.lock().unwrap().mode;
            self.dictation.set_mode(mode.into());
        }
        self.press();
    }

    fn press(&self) {
        if self.dictation.state() == State::Idle && self.engine.lock().unwrap().kind != "ready" {
            let e = self.engine.lock().unwrap().clone();
            self.message(match e.kind {
                "loading" => "Le modèle se charge, encore un instant…",
                _ => "Choisis et télécharge un modèle dans Modèles.",
            });
            return;
        }
        self.dictation.trigger(Trigger::Pressed);
    }

    pub fn release(&self) {
        self.dictation.trigger(Trigger::Released);
    }

    pub fn cancel(&self) {
        self.dictation.trigger(Trigger::Cancel);
    }

    pub fn on_hotkey(&self, id: u32, pressed: bool) {
        let Some(hotkey) = self.hotkeys.lock().unwrap().get(&id).copied() else { return };
        let mode = match hotkey {
            Hotkey::Escape => {
                if pressed {
                    self.cancel();
                }
                return;
            }
            Hotkey::Toggle => ListenMode::PushToTalk,
            Hotkey::Continuous => ListenMode::Continuous,
            Hotkey::Hold => ListenMode::Hold,
        };
        if pressed {
            if self.dictation.state() == State::Idle {
                *self.source.lock().unwrap() = Source::Shortcut;
                self.dictation.set_mode(mode.into());
            }
            self.press();
        } else if mode == ListenMode::Hold {
            self.release();
        }
    }

    // --- Raccourcis globaux -------------------------------------------------------------

    /// (Ré)enregistre les raccourcis des réglages. Renvoie les erreurs lisibles.
    pub fn register_shortcuts(&self) -> Vec<String> {
        let gs = self.app.global_shortcut();
        let _ = gs.unregister_all();
        self.escape.store(false, Ordering::SeqCst);
        let mut map = self.hotkeys.lock().unwrap();
        map.clear();
        let s = self.settings.lock().unwrap().clone();
        let mut errors = Vec::new();
        for (text, hotkey, name) in [
            (&s.shortcut_toggle, Hotkey::Toggle, "Marche/arrêt"),
            (&s.shortcut_continuous, Hotkey::Continuous, "Continu"),
            (&s.shortcut_hold, Hotkey::Hold, "Maintenu"),
        ] {
            if text.trim().is_empty() {
                continue;
            }
            match text.parse::<Shortcut>() {
                Ok(sc) => match gs.register(sc) {
                    Ok(()) => {
                        map.insert(sc.id(), hotkey);
                    }
                    Err(e) => errors.push(format!("Raccourci {name} ({text}) indisponible : {e}")),
                },
                Err(e) => errors.push(format!("Raccourci {name} ({text}) invalide : {e}")),
            }
        }
        errors
    }

    /// Échap annule, mais seulement pendant l'écoute : sinon il resterait pris aux autres apps.
    fn set_escape(&self, on: bool) {
        if self.escape.swap(on, Ordering::SeqCst) == on {
            return;
        }
        let gs = self.app.global_shortcut();
        let Ok(sc) = "Escape".parse::<Shortcut>() else { return };
        if on {
            if gs.register(sc).is_ok() {
                self.hotkeys.lock().unwrap().insert(sc.id(), Hotkey::Escape);
            }
        } else {
            let _ = gs.unregister(sc);
            self.hotkeys.lock().unwrap().remove(&sc.id());
        }
    }

    // --- Rappels de la dictée -----------------------------------------------------------

    fn on_capture(self: &Arc<Self>, active: bool) {
        if active {
            let core = self.clone();
            let result = self.recorder.start(Box::new(move |pcm, level| {
                core.dictation.push_audio(pcm);
                let _ = core.app.emit("dicto://level", level);
            }));
            if let Err(e) = result {
                self.message(&e);
                self.cancel();
                return;
            }
        } else {
            self.recorder.stop();
            let _ = self.app.emit("dicto://level", 0.0f32);
        }
        self.set_escape(active);
        crate::tray::set_listening(&self.app, active);
        self.update_pill();
    }

    fn on_state(&self, state: State) {
        let previous = std::mem::replace(&mut *self.state.lock().unwrap(), state);
        if state == State::Idle {
            self.partial.lock().unwrap().clear();
            *self.started_at.lock().unwrap() = None;
            // Le mode imposé par un raccourci ne dure que le temps de la dictée.
            let mode = self.settings.lock().unwrap().mode;
            self.dictation.set_mode(mode.into());
            self.apply_silero();
        } else if previous == State::Idle {
            *self.started_at.lock().unwrap() = Some(now_ms());
        }
        self.emit_snapshot();
        self.update_pill();
    }

    fn on_result(&self, result: DictationResult) {
        decrement(&self.pending);
        if let Some(w) = &result.warning {
            self.message(w);
        }
        if let Err(e) = self.storage.add_history(result.clone(), None) {
            log::warn!("historique : {e}");
        }
        let output = self.settings.lock().unwrap().output;
        let to_window = *self.source.lock().unwrap() == Source::Shortcut && output != Output::Review;
        if to_window {
            self.write_to_window(&result.edits, output);
        }
        let _ = self.app.emit(
            "dicto://result",
            ResultView { text: result.text.clone(), engine: result.engine.clone(), duration_ms: result.duration_ms, written: to_window },
        );
        self.emit_snapshot();
        self.update_pill();
    }

    /// Écrit dans la fenêtre active, avec « efface ça » et une espace entre deux phrases.
    fn write_to_window(&self, edits: &[TextEdit], output: Output) {
        let mut erase = 0;
        let mut rest = Vec::new();
        {
            let mut written = self.written.lock().unwrap();
            for e in edits {
                if *e == TextEdit::DeletePrevious && rest.is_empty() {
                    erase += written.0.pop().unwrap_or(0);
                } else {
                    rest.push(e.clone());
                }
            }
        }
        let mut text = render_edits(rest);
        {
            let mut written = self.written.lock().unwrap();
            // Phrase qui suit de près la précédente : on les sépare d'une espace.
            let recent = written.1.is_some_and(|t| t.elapsed() < Duration::from_secs(120));
            if recent && !text.is_empty() && !text.starts_with(char::is_whitespace) {
                text.insert(0, ' ');
            }
            if !text.is_empty() {
                written.0.push(text.chars().count());
                if written.0.len() > 20 {
                    written.0.remove(0);
                }
                written.1 = Some(Instant::now());
            }
        }
        let app = self.app.clone();
        // La frappe peut prendre un moment : hors du fil de la transcription.
        std::thread::spawn(move || {
            insert::erase(erase);
            if let Written::Copied(msg) = insert::write(&text, output) {
                let _ = app.emit("dicto://message", msg);
            }
        });
    }

    // --- Pastille -----------------------------------------------------------------------

    /// Visible pendant l'écoute et tant qu'une phrase est en cours de transcription.
    fn update_pill(&self) {
        let Some(pill) = self.app.get_webview_window("pill") else { return };
        let busy = *self.state.lock().unwrap() != State::Idle || self.pending.load(Ordering::SeqCst) > 0;
        let wanted = busy && self.settings.lock().unwrap().pill == Pill::Bottom;
        if !wanted {
            let _ = pill.hide();
            return;
        }
        if pill.is_visible().unwrap_or(false) {
            return;
        }
        // En bas, au centre de l'écran où se trouve la souris, au-dessus de la barre des tâches.
        let monitor = self
            .app
            .cursor_position()
            .ok()
            .and_then(|p| pill.monitor_from_point(p.x, p.y).ok().flatten())
            .or_else(|| pill.primary_monitor().ok().flatten());
        if let (Some(m), Ok(size)) = (monitor, pill.outer_size()) {
            let area = m.work_area();
            let margin = (24.0 * m.scale_factor()) as i32;
            let x = area.position.x + (area.size.width as i32 - size.width as i32) / 2;
            let y = area.position.y + area.size.height as i32 - size.height as i32 - margin;
            let _ = pill.set_position(PhysicalPosition::new(x, y));
        }
        let _ = pill.show();
    }
}

struct NoProgress;

impl dicto_ffi::DownloadListener for NoProgress {
    fn on_progress(&self, _downloaded: u64, _total: u64) {}
}
