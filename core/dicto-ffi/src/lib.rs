//! Interface exposée à Kotlin (Android) via UniFFI.
//!
//! Côté Android, le service de dictée :
//! 1. crée un [`Dictation`] avec un [`DictationListener`] ;
//! 2. traduit les déclencheurs (bulle, tuile, Volume−, IME…) en [`Trigger`] ;
//! 3. ouvre le micro sur `on_capture(true)` et pousse le PCM 16 kHz avec `push_audio` ;
//! 4. insère le texte reçu dans `on_result`.

uniffi::setup_scaffolding!();

use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, OnceLock, RwLock};

use dicto_core::audio::{i16_to_f32, ms_for_samples, samples_for_ms};
use dicto_core::commands::{self, Edit};
use dicto_core::engine::cloud::openai::{Flavor, OpenAiCompatible};
use dicto_core::engine::{SttEngine, TranscribeOptions, transcribe_filtered, vocabulary_prompt};
use dicto_core::models::{Catalog, ModelStore};
use dicto_core::refine::Refiner;
use dicto_core::session::{self, Session, SessionConfig};
use dicto_core::store;
use dicto_core::DictoError;
use tokio::sync::mpsc;

fn runtime() -> &'static tokio::runtime::Runtime {
    static RT: OnceLock<tokio::runtime::Runtime> = OnceLock::new();
    RT.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .thread_name("dicto-core")
            .enable_all()
            .build()
            .expect("runtime tokio")
    })
}

// --- Erreurs -------------------------------------------------------------------

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum FfiError {
    #[error("{detail}")]
    Network { detail: String },
    #[error("{provider} ({status}) : {detail}")]
    Api { provider: String, status: u16, detail: String },
    #[error("{detail}")]
    Model { detail: String },
    #[error("{detail}")]
    Storage { detail: String },
    #[error("annulé")]
    Cancelled,
    #[error("{detail}")]
    Other { detail: String },
}

impl From<DictoError> for FfiError {
    fn from(e: DictoError) -> Self {
        match e {
            DictoError::Http(e) => FfiError::Network { detail: e.to_string() },
            DictoError::Api { provider, status, message } => FfiError::Api { provider, status, detail: message },
            DictoError::Model(detail) => FfiError::Model { detail },
            DictoError::Store(e) => FfiError::Storage { detail: e.to_string() },
            DictoError::Cancelled => FfiError::Cancelled,
            other => FfiError::Other { detail: other.to_string() },
        }
    }
}

type FfiResult<T> = Result<T, FfiError>;

// --- Types simples -------------------------------------------------------------

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum Mode {
    PushToTalk,
    Continuous,
    Hold,
}

impl From<Mode> for session::Mode {
    fn from(m: Mode) -> Self {
        match m {
            Mode::PushToTalk => session::Mode::PushToTalk,
            Mode::Continuous => session::Mode::Continuous,
            Mode::Hold => session::Mode::Hold,
        }
    }
}

impl From<session::Mode> for Mode {
    fn from(m: session::Mode) -> Self {
        match m {
            session::Mode::PushToTalk => Mode::PushToTalk,
            session::Mode::Continuous => Mode::Continuous,
            session::Mode::Hold => Mode::Hold,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum Trigger {
    Pressed,
    Released,
    Cancel,
}

impl From<Trigger> for session::Trigger {
    fn from(t: Trigger) -> Self {
        match t {
            Trigger::Pressed => session::Trigger::Pressed,
            Trigger::Released => session::Trigger::Released,
            Trigger::Cancel => session::Trigger::Cancel,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum State {
    Idle,
    Recording,
    Listening,
    Speaking,
}

impl From<session::State> for State {
    fn from(s: session::State) -> Self {
        match s {
            session::State::Idle => State::Idle,
            session::State::Recording => State::Recording,
            session::State::Listening => State::Listening,
            session::State::Speaking => State::Speaking,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum DiscardReason {
    Cancelled,
    TooShort,
    /// Rien d'intelligible (silence, bruit, hallucination filtrée).
    NoSpeech,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum TextEdit {
    Text { text: String },
    Newline,
    Paragraph,
    DeletePrevious,
}

impl From<Edit> for TextEdit {
    fn from(e: Edit) -> Self {
        match e {
            Edit::Text(text) => TextEdit::Text { text },
            Edit::Newline => TextEdit::Newline,
            Edit::Paragraph => TextEdit::Paragraph,
            Edit::DeletePrevious => TextEdit::DeletePrevious,
        }
    }
}

impl From<TextEdit> for Edit {
    fn from(e: TextEdit) -> Self {
        match e {
            TextEdit::Text { text } => Edit::Text(text),
            TextEdit::Newline => Edit::Newline,
            TextEdit::Paragraph => Edit::Paragraph,
            TextEdit::DeletePrevious => Edit::DeletePrevious,
        }
    }
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct DictationResult {
    /// Texte final, commandes vocales appliquées.
    pub text: String,
    /// Les mêmes éditions en détail (pour gérer « efface ça » dans le champ cible).
    pub edits: Vec<TextEdit>,
    /// Texte brut renvoyé par le moteur.
    pub raw_text: String,
    pub language: Option<String>,
    pub engine: String,
    pub duration_ms: u64,
    /// Problème non bloquant (ex. retouche impossible : le texte brut est livré).
    pub warning: Option<String>,
    /// Longue pause avant cette phrase : elle ouvre un paragraphe. `edits` commence alors par
    /// `Paragraph` ; `text` ne contient pas ce saut.
    pub new_paragraph: bool,
}

// --- Moteurs -------------------------------------------------------------------

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum CloudProvider {
    OpenAi,
    Mistral,
    Gemini,
    /// Grok (xAI), API `/v1/stt`.
    Xai,
    /// Serveur compatible OpenAI (speaches, faster-whisper-server, LocalAI…).
    Custom,
}

impl From<CloudProvider> for Flavor {
    fn from(p: CloudProvider) -> Self {
        match p {
            CloudProvider::OpenAi => Flavor::OpenAi,
            CloudProvider::Mistral => Flavor::Mistral,
            // Gemini et xAI ont leur propre client ; ce cas ne sert que de valeur par défaut.
            CloudProvider::Gemini | CloudProvider::Xai | CloudProvider::Custom => Flavor::Custom,
        }
    }
}

/// Un moteur prêt à transcrire (local chargé en mémoire, ou client en ligne).
#[derive(uniffi::Object)]
pub struct Engine {
    inner: Arc<dyn SttEngine>,
    /// Transcription en direct (modèles streaming), si le moteur la permet.
    live: Option<Arc<dyn dicto_core::engine::LiveEngine>>,
    /// Modèle local assez rapide pour un aperçu en direct par retranscription.
    preview: bool,
}

#[uniffi::export]
impl Engine {
    /// Service en ligne. `base_url` vide = adresse officielle du fournisseur.
    #[uniffi::constructor]
    pub fn cloud(provider: CloudProvider, base_url: Option<String>, api_key: Option<String>, model: String) -> Arc<Self> {
        if provider == CloudProvider::Gemini {
            let engine = dicto_core::engine::cloud::gemini::GeminiStt::new(base_url, api_key, model);
            return Arc::new(Self { inner: Arc::new(engine), live: None, preview: false });
        }
        if provider == CloudProvider::Xai {
            let engine = dicto_core::engine::cloud::xai::XaiStt::new(base_url, api_key, model);
            return Arc::new(Self { inner: Arc::new(engine), live: None, preview: false });
        }
        let flavor: Flavor = provider.into();
        let engine = match base_url.filter(|u| !u.trim().is_empty()) {
            Some(url) => OpenAiCompatible::with_base_url(flavor, url, api_key, model),
            None => OpenAiCompatible::new(flavor, api_key, model),
        };
        Arc::new(Self { inner: Arc::new(engine), live: None, preview: false })
    }

    pub fn id(&self) -> String {
        self.inner.id()
    }

    pub fn is_local(&self) -> bool {
        self.inner.is_local()
    }

    /// Vrai si le texte peut s'afficher pendant qu'on parle.
    pub fn supports_live(&self) -> bool {
        self.live.is_some() || self.preview
    }
}

/// Modèles de transcription proposés par un service en ligne pour cette clé.
#[uniffi::export(async_runtime = "tokio")]
pub async fn list_cloud_models(
    provider: CloudProvider,
    base_url: Option<String>,
    api_key: Option<String>,
) -> FfiResult<Vec<String>> {
    if provider == CloudProvider::Gemini {
        let r = Refiner::new(dicto_core::refine::RefineProvider::Gemini, base_url, api_key, None);
        let models = r.list_models().await?;
        // Gemini n'a pas de modèle dédié à la transcription : on garde les modèles généraux
        // qui comprennent l'audio envoyé (Flash, Pro), sans synthèse vocale, image, live ni embeddings.
        let excluded = ["tts", "image", "live", "native-audio", "embedding", "robotics", "computer-use", "thinking", "exp", "learnlm"];
        return Ok(models
            .into_iter()
            .filter(|m| m.starts_with("gemini-") && (m.contains("flash") || m.contains("pro")))
            .filter(|m| !excluded.iter().any(|x| m.contains(x)))
            .collect());
    }
    if provider == CloudProvider::Xai {
        // Pas de liste des modèles de transcription chez xAI.
        return Ok(fallback_cloud_models(provider));
    }
    let flavor: Flavor = provider.into();
    let client = match base_url.filter(|u| !u.trim().is_empty()) {
        Some(url) => OpenAiCompatible::with_base_url(flavor, url, api_key, ""),
        None => OpenAiCompatible::new(flavor, api_key, ""),
    };
    Ok(client.list_models().await?)
}

/// Modèles conseillés quand la liste du fournisseur est inaccessible.
#[uniffi::export]
pub fn fallback_cloud_models(provider: CloudProvider) -> Vec<String> {
    if provider == CloudProvider::Gemini {
        return dicto_core::engine::cloud::gemini::FALLBACK_MODELS.iter().map(|s| s.to_string()).collect();
    }
    if provider == CloudProvider::Xai {
        return dicto_core::engine::cloud::xai::MODELS.iter().map(|s| s.to_string()).collect();
    }
    Flavor::from(provider)
        .fallback_models()
        .iter()
        .map(|s| s.to_string())
        .collect()
}

// --- Session de dictée ---------------------------------------------------------

#[uniffi::export(with_foreign)]
pub trait DictationListener: Send + Sync {
    /// Ouvrir (`true`) ou fermer (`false`) le micro.
    fn on_capture(&self, active: bool);
    fn on_state(&self, state: State);
    /// Un énoncé part en transcription.
    fn on_transcribing(&self, utterance_id: u64);
    /// Texte provisoire pendant qu'on parle (modèles streaming) ; vide = effacer.
    fn on_partial(&self, text: String);
    fn on_result(&self, utterance_id: u64, result: DictationResult);
    fn on_discarded(&self, reason: DiscardReason);
    fn on_error(&self, utterance_id: u64, error: FfiError);
}

struct Job {
    id: u64,
    samples: Vec<f32>,
    pause_before_ms: Option<u64>,
    whole_recording: bool,
}

#[derive(Clone)]
struct JobSettings {
    engine: Option<Arc<dyn SttEngine>>,
    options: TranscribeOptions,
    voice_commands: bool,
    refine: Option<(Refiner, String)>,
    /// Paragraphes automatiques : pause qui en ouvre un (`None` = jamais).
    paragraph_pause_ms: Option<u32>,
}

enum LiveMsg {
    Audio(Vec<f32>),
    Reset,
}

#[derive(uniffi::Object)]
pub struct Dictation {
    session: Mutex<Session>,
    settings: Arc<RwLock<JobSettings>>,
    listener: Arc<dyn DictationListener>,
    jobs: mpsc::UnboundedSender<Job>,
    live_engine: RwLock<Option<Arc<dyn dicto_core::engine::LiveEngine>>>,
    live: Mutex<Option<std::sync::mpsc::Sender<LiveMsg>>>,
    /// Le moteur choisi accepte l'aperçu en direct par retranscription.
    preview_engine: AtomicBool,
    /// Réglage « Aperçu en direct ».
    preview_enabled: AtomicBool,
    /// Phrases en cours de transcription : l'aperçu leur cède la place.
    pending: Arc<std::sync::atomic::AtomicUsize>,
    /// Dernière seconde d'audio, rejouée au direct quand la voix est détectée.
    recent: Mutex<std::collections::VecDeque<f32>>,
}

#[uniffi::export]
impl Dictation {
    #[uniffi::constructor]
    pub fn new(mode: Mode, listener: Arc<dyn DictationListener>) -> Arc<Self> {
        let session = Session::new(SessionConfig {
            mode: mode.into(),
            ..SessionConfig::default()
        });
        let settings = Arc::new(RwLock::new(JobSettings {
            engine: None,
            options: TranscribeOptions::default(),
            voice_commands: true,
            refine: None,
            paragraph_pause_ms: None,
        }));
        let (tx, rx) = mpsc::unbounded_channel();
        let pending = Arc::new(std::sync::atomic::AtomicUsize::new(0));
        runtime().spawn(worker(rx, settings.clone(), listener.clone(), pending.clone()));
        Arc::new(Self {
            session: Mutex::new(session),
            settings,
            listener,
            jobs: tx,
            live_engine: RwLock::new(None),
            live: Mutex::new(None),
            preview_engine: AtomicBool::new(false),
            preview_enabled: AtomicBool::new(true),
            pending,
            recent: Mutex::new(std::collections::VecDeque::new()),
        })
    }

    pub fn set_mode(&self, mode: Mode) {
        let actions = self.session.lock().unwrap().set_mode(mode.into());
        self.dispatch(actions);
    }

    pub fn trigger(&self, trigger: Trigger) {
        let actions = self.session.lock().unwrap().trigger(trigger.into());
        self.dispatch(actions);
    }

    /// PCM 16 bits little-endian, mono, 16 kHz (format natif d'`AudioRecord`).
    /// Un `ByteArray` côté Kotlin : pas d'objet alloué par échantillon.
    pub fn push_audio(&self, pcm: Vec<u8>) {
        let pcm: Vec<i16> = pcm
            .chunks_exact(2)
            .map(|b| i16::from_le_bytes([b[0], b[1]]))
            .collect();
        let samples = i16_to_f32(&pcm);
        let (before, actions, after) = {
            let mut session = self.session.lock().unwrap();
            let before = session.state();
            let actions = session.push_audio(&samples);
            (before, actions, session.state())
        };
        self.feed_live(&samples, before, after);
        self.dispatch(actions);
    }

    pub fn state(&self) -> State {
        self.session.lock().unwrap().state().into()
    }

    /// Détection de voix Silero pour le mode continu (`None` = détecteur à énergie).
    /// Renvoie faux si une dictée est en cours (réessayer plus tard).
    pub fn use_silero(&self, model_path: Option<String>) -> FfiResult<bool> {
        let detector: Box<dyn dicto_core::vad::VoiceDetector> = match model_path {
            #[cfg(feature = "sherpa")]
            Some(path) => Box::new(dicto_core::vad::SileroVad::load(std::path::Path::new(&path))?),
            #[cfg(not(feature = "sherpa"))]
            Some(_) => return Err(FfiError::Model { detail: "cette version a été compilée sans sherpa-onnx".into() }),
            None => Box::new(dicto_core::vad::EnergyVad::default()),
        };
        Ok(self.session.lock().unwrap().set_detector(detector))
    }

    pub fn mode(&self) -> Mode {
        self.session.lock().unwrap().mode().into()
    }

    pub fn set_engine(&self, engine: Arc<Engine>) {
        self.settings.write().unwrap().engine = Some(engine.inner.clone());
        *self.live_engine.write().unwrap() = engine.live.clone();
        self.preview_engine.store(engine.preview, Ordering::SeqCst);
    }

    /// Aperçu en direct des modèles qui transcrivent une phrase entière (plus de calcul).
    pub fn set_live_preview(&self, enabled: bool) {
        self.preview_enabled.store(enabled, Ordering::SeqCst);
    }

    /// Retouche automatique de chaque énoncé (`None` = texte brut).
    pub fn set_refiner(&self, refiner: Option<Arc<RefineClient>>, instruction: Option<String>) {
        self.settings.write().unwrap().refine = match (refiner, instruction) {
            (Some(r), Some(i)) if !i.trim().is_empty() => Some((r.inner.clone(), i)),
            _ => None,
        };
    }

    /// Langue imposée (`None` = auto) et vocabulaire personnel injecté en amorce.
    pub fn set_options(&self, language: Option<String>, vocabulary: Vec<String>, voice_commands: bool) {
        let mut s = self.settings.write().unwrap();
        s.options.language = language.filter(|l| !l.is_empty() && l != "auto");
        s.options.prompt = vocabulary_prompt(&vocabulary);
        s.voice_commands = voice_commands;
    }

    /// Paragraphes automatiques : une pause d'au moins `pause_ms` ouvre un nouveau paragraphe
    /// (entre deux phrases en mode continu, dans l'enregistrement sinon). `None` = désactivé.
    pub fn set_paragraphs(&self, pause_ms: Option<u32>) {
        self.settings.write().unwrap().paragraph_pause_ms = pause_ms.filter(|p| *p > 0);
    }
}

impl Dictation {
    fn dispatch(&self, actions: Vec<session::Action>) {
        for action in actions {
            match action {
                session::Action::StartCapture => {
                    self.recent.lock().unwrap().clear();
                    self.start_live();
                    self.listener.on_capture(true)
                }
                session::Action::StopCapture => {
                    // Fermer le canal arrête le fil du direct.
                    self.live.lock().unwrap().take();
                    self.listener.on_capture(false)
                }
                session::Action::StateChanged(s) => self.listener.on_state(s.into()),
                session::Action::Discarded(r) => self.listener.on_discarded(match r {
                    session::DiscardReason::Cancelled => DiscardReason::Cancelled,
                    session::DiscardReason::TooShort => DiscardReason::TooShort,
                }),
                session::Action::Transcribe(u) => {
                    if let Some(tx) = self.live.lock().unwrap().as_ref() {
                        let _ = tx.send(LiveMsg::Reset);
                    }
                    self.listener.on_transcribing(u.id);
                    self.pending.fetch_add(1, Ordering::SeqCst);
                    if self.jobs.send(Job { id: u.id, samples: u.samples, pause_before_ms: u.pause_before_ms, whole_recording: u.whole_recording }).is_err() {
                        self.pending.fetch_sub(1, Ordering::SeqCst);
                    }
                }
            }
        }
    }
}

impl Dictation {
    /// Envoie l'audio au direct seulement quand on parle : en mode continu, le silence
    /// et les bruits d'attente donneraient du texte fantôme.
    fn feed_live(&self, samples: &[f32], before: session::State, after: session::State) {
        use session::State as S;
        let mut recent = self.recent.lock().unwrap();
        let live = self.live.lock().unwrap();
        if let Some(tx) = live.as_ref() {
            match (before, after) {
                (_, S::Recording) => {
                    let _ = tx.send(LiveMsg::Audio(samples.to_vec()));
                }
                // La voix vient d'être confirmée : on rejoue son début (déjà dans `recent`).
                (S::Listening, S::Speaking) => {
                    let mut start: Vec<f32> = recent.drain(..).collect();
                    start.extend_from_slice(samples);
                    let _ = tx.send(LiveMsg::Audio(start));
                }
                (S::Speaking, S::Speaking) => {
                    let _ = tx.send(LiveMsg::Audio(samples.to_vec()));
                }
                _ => {}
            }
        }
        recent.extend(samples.iter().copied());
        let max = samples_for_ms(1_000);
        while recent.len() > max {
            recent.pop_front();
        }
    }

    /// Ouvre le fil de transcription en direct si le moteur le permet.
    fn start_live(&self) {
        let open: Box<dyn FnOnce() -> Option<Box<dyn dicto_core::engine::LiveStream>> + Send> =
            if let Some(engine) = self.live_engine.read().unwrap().clone() {
                Box::new(move || engine.open())
            } else if self.preview_engine.load(Ordering::SeqCst) && self.preview_enabled.load(Ordering::SeqCst) {
                let s = self.settings.read().unwrap();
                let Some(engine) = s.engine.clone() else { return };
                let opts = s.options.clone();
                let pending = self.pending.clone();
                Box::new(move || {
                    let preview = dicto_core::engine::preview::Preview::new(
                        engine,
                        opts,
                        runtime().handle().clone(),
                        Arc::new(move || pending.load(Ordering::SeqCst) == 0),
                    );
                    Some(Box::new(preview) as Box<dyn dicto_core::engine::LiveStream>)
                })
            } else {
                return;
            };
        let (tx, rx) = std::sync::mpsc::channel::<LiveMsg>();
        *self.live.lock().unwrap() = Some(tx);
        let listener = self.listener.clone();
        std::thread::Builder::new()
            .name("dicto-live".into())
            .spawn(move || {
                let Some(mut stream) = open() else { return };
                while let Ok(msg) = rx.recv() {
                    match msg {
                        LiveMsg::Audio(pcm) => {
                            if let Some(text) = stream.push(&pcm) {
                                listener.on_partial(text);
                            }
                        }
                        LiveMsg::Reset => {
                            stream.reset();
                            listener.on_partial(String::new());
                        }
                    }
                }
                listener.on_partial(String::new());
            })
            .ok();
    }
}

/// Décompte une phrase transcrite, quelle que soit l'issue.
struct PendingGuard(Arc<std::sync::atomic::AtomicUsize>);

impl Drop for PendingGuard {
    fn drop(&mut self) {
        self.0.fetch_sub(1, Ordering::SeqCst);
    }
}

/// Traite les énoncés un par un, dans l'ordre, pour que le texte arrive dans l'ordre parlé.
async fn worker(
    mut rx: mpsc::UnboundedReceiver<Job>,
    settings: Arc<RwLock<JobSettings>>,
    listener: Arc<dyn DictationListener>,
    pending: Arc<std::sync::atomic::AtomicUsize>,
) {
    while let Some(job) = rx.recv().await {
        let _done = PendingGuard(pending.clone());
        let s = settings.read().unwrap().clone();
        let Some(engine) = s.engine else {
            listener.on_error(job.id, FfiError::Model { detail: "aucun moteur sélectionné".into() });
            continue;
        };
        // Un long enregistrement est transcrit morceau par morceau, coupé aux longues pauses :
        // un paragraphe par morceau.
        let ranges = match s.paragraph_pause_ms {
            Some(pause) if job.whole_recording => dicto_core::vad::split_on_pauses(&job.samples, pause),
            _ => std::iter::once(0..job.samples.len()).collect(),
        };
        let mut texts = Vec::new();
        let mut language = None;
        let mut failed = None;
        for range in ranges {
            match transcribe_filtered(engine.as_ref(), &job.samples[range], &s.options).await {
                Ok(Some(t)) => {
                    language = language.or(t.language);
                    texts.push(t.text);
                }
                Ok(None) => {}
                Err(e) => {
                    failed = Some(e);
                    break;
                }
            }
        }
        if let Some(e) = failed {
            listener.on_error(job.id, e.into());
            continue;
        }
        let pieces: Vec<Vec<Edit>> = texts
            .iter()
            .map(|text| if s.voice_commands { commands::parse_voice_commands(text) } else { vec![Edit::Text(text.clone())] })
            .collect();
        let mut edits = commands::join_paragraphs(pieces);
        if edits.is_empty() {
            listener.on_discarded(DiscardReason::NoSpeech);
            continue;
        }
        let mut warning = None;
        let mut engine_id = engine.id();
        if let Some((refiner, instruction)) = &s.refine {
            // « efface ça » en tête reste une commande ; le reste est retouché d'un bloc.
            let delete_first = matches!(edits.first(), Some(Edit::DeletePrevious));
            let body: Vec<Edit> = edits.iter().filter(|e| !matches!(e, Edit::DeletePrevious)).cloned().collect();
            let text = commands::render(&body);
            if !text.trim().is_empty() {
                match refiner.refine(instruction, &text).await {
                    Ok(refined) if !refined.trim().is_empty() => {
                        edits = delete_first.then_some(Edit::DeletePrevious).into_iter().chain([Edit::Text(refined)]).collect();
                        engine_id = format!("{engine_id}+{}", refiner.id());
                    }
                    Ok(_) => {}
                    Err(e) => warning = Some(format!("retouche impossible : {e}")),
                }
            }
        }
        let text = commands::render(&edits);
        // Longue pause depuis la phrase précédente (mode continu) : nouveau paragraphe.
        let new_paragraph = !matches!(edits.first(), Some(Edit::DeletePrevious) | None)
            && matches!((s.paragraph_pause_ms, job.pause_before_ms), (Some(p), Some(b)) if b >= p as u64);
        if new_paragraph {
            edits.insert(0, Edit::Paragraph);
        }
        let result = DictationResult {
            text,
            new_paragraph,
            edits: edits.into_iter().map(Into::into).collect(),
            raw_text: texts.join("\n\n"),
            language: language.or(s.options.language),
            engine: engine_id,
            duration_ms: ms_for_samples(job.samples.len()),
            warning,
        };
        listener.on_result(job.id, result);
    }
}

#[uniffi::export]
pub fn parse_voice_commands(text: String) -> Vec<TextEdit> {
    commands::parse_voice_commands(&text).into_iter().map(Into::into).collect()
}

#[uniffi::export]
pub fn render_edits(edits: Vec<TextEdit>) -> String {
    let edits: Vec<Edit> = edits.into_iter().map(Into::into).collect();
    commands::render(&edits)
}

// --- Retouche par IA -------------------------------------------------------------

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum RefineProvider {
    Anthropic,
    OpenAi,
    Gemini,
    Ollama,
    OpenAiCompatible,
}

impl From<RefineProvider> for dicto_core::refine::RefineProvider {
    fn from(p: RefineProvider) -> Self {
        use dicto_core::refine::RefineProvider as R;
        match p {
            RefineProvider::Anthropic => R::Anthropic,
            RefineProvider::OpenAi => R::OpenAi,
            RefineProvider::Gemini => R::Gemini,
            RefineProvider::Ollama => R::Ollama,
            RefineProvider::OpenAiCompatible => R::OpenAiCompatible,
        }
    }
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct RefinePreset {
    pub id: String,
    pub name: String,
    pub instruction: String,
}

#[uniffi::export]
pub fn refine_presets() -> Vec<RefinePreset> {
    dicto_core::refine::builtin_presets()
        .into_iter()
        .map(|p| RefinePreset { id: p.id, name: p.name, instruction: p.instruction })
        .collect()
}

#[uniffi::export]
pub fn default_refine_model(provider: RefineProvider) -> String {
    dicto_core::refine::RefineProvider::from(provider).default_model().to_string()
}

/// Client d'un modèle de langage pour la retouche (Claude, OpenAI, Gemini, Ollama…).
#[derive(uniffi::Object)]
pub struct RefineClient {
    inner: Refiner,
}

#[uniffi::export(async_runtime = "tokio")]
impl RefineClient {
    #[uniffi::constructor]
    pub fn new(provider: RefineProvider, base_url: Option<String>, api_key: Option<String>, model: Option<String>) -> Arc<Self> {
        Arc::new(Self { inner: Refiner::new(provider.into(), base_url, api_key, model) })
    }

    pub fn model(&self) -> String {
        self.inner.model.clone()
    }

    pub async fn refine(&self, instruction: String, text: String) -> FfiResult<String> {
        Ok(self.inner.refine(&instruction, &text).await?)
    }

    pub async fn list_models(&self) -> FfiResult<Vec<String>> {
        Ok(self.inner.list_models().await?)
    }
}

// --- Modèles locaux ------------------------------------------------------------

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct ModelInfo {
    pub id: String,
    pub name: String,
    pub description: String,
    /// « whisper » ou « sherpa ».
    pub engine: String,
    pub license: String,
    /// Codes ISO 639-1, ou `["multi"]`.
    pub languages: Vec<String>,
    pub multilingual: bool,
    pub punctuation: bool,
    /// Modèle streaming : texte affiché pendant qu'on parle, sans calcul en plus.
    pub streaming: bool,
    /// Assez rapide pour l'aperçu en direct par retranscription.
    pub live_preview: bool,
    pub speed: u8,
    pub accuracy: u8,
    pub size_bytes: u64,
    /// Conseillé sur la plateforme du [`ModelManager`].
    pub recommended: bool,
    pub installed: bool,
    pub downloaded_bytes: u64,
}

/// Une ligne de l'écran « Crédits et licences ».
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct Credit {
    pub name: String,
    pub license: String,
    pub credit: String,
    pub url: String,
}

#[uniffi::export(with_foreign)]
pub trait DownloadListener: Send + Sync {
    fn on_progress(&self, downloaded: u64, total: u64);
}

#[derive(uniffi::Object)]
pub struct ModelManager {
    store: ModelStore,
    catalog: Catalog,
    platform: String,
    cancels: Mutex<HashMap<String, Arc<AtomicBool>>>,
}

#[uniffi::export(async_runtime = "tokio")]
impl ModelManager {
    /// `platform` : « android » ou « desktop » ; seuls les modèles adaptés sont proposés.
    #[uniffi::constructor]
    pub fn new(models_dir: String, platform: String) -> Arc<Self> {
        Arc::new(Self {
            store: ModelStore::new(PathBuf::from(models_dir)),
            catalog: Catalog::builtin(),
            platform,
            cancels: Mutex::new(HashMap::new()),
        })
    }

    /// Modèles de la plateforme, les conseillés d'abord.
    pub fn models(&self) -> Vec<ModelInfo> {
        self.catalog
            .for_platform(&self.platform)
            .into_iter()
            .map(|m| ModelInfo {
                id: m.id.clone(),
                name: m.name.clone(),
                description: m.description.clone(),
                engine: match m.engine {
                    dicto_core::models::EngineKind::Whisper => "whisper".into(),
                    dicto_core::models::EngineKind::Sherpa => "sherpa".into(),
                },
                license: m.license.clone(),
                languages: m.languages.clone(),
                multilingual: m.multilingual,
                punctuation: m.punctuation,
                streaming: m.streaming,
                live_preview: !m.streaming && m.speed >= 3,
                speed: m.speed,
                accuracy: m.accuracy,
                size_bytes: m.total_size(),
                recommended: m.recommended_on(&self.platform),
                installed: self.store.is_installed(m),
                downloaded_bytes: self.store.downloaded_bytes(m),
            })
            .collect()
    }

    /// Auteurs et licences des modèles proposés sur la plateforme et des fichiers annexes.
    pub fn credits(&self) -> Vec<Credit> {
        let models = self.catalog.for_platform(&self.platform).into_iter().map(|m| Credit {
            name: m.name.clone(),
            license: m.license.clone(),
            credit: m.credit.clone(),
            url: m.source_url.clone(),
        });
        let support = self.catalog.support.iter().map(|s| Credit {
            name: s.name.clone(),
            license: s.license.clone(),
            credit: s.credit.clone(),
            url: s.source_url.clone(),
        });
        models.chain(support).collect()
    }

    pub fn is_installed(&self, id: String) -> bool {
        self.catalog.get(&id).is_some_and(|m| self.store.is_installed(m))
    }

    /// Charge un modèle installé (plusieurs secondes : à appeler hors du thread principal).
    pub fn create_engine(&self, id: String, use_gpu: bool) -> FfiResult<Arc<Engine>> {
        let entry = self
            .catalog
            .get(&id)
            .ok_or_else(|| FfiError::Model { detail: format!("modèle inconnu : {id}") })?;
        let opts = dicto_core::engine::local::LocalOptions { use_gpu };
        let loaded = dicto_core::engine::local::load_full(&self.store, entry, &opts)?;
        // Retranscrire la phrase toutes les secondes n'a de sens qu'avec un modèle rapide.
        let preview = loaded.live.is_none() && entry.speed >= 3;
        Ok(Arc::new(Engine { inner: loaded.stt, live: loaded.live, preview }))
    }

    pub async fn download(&self, id: String, listener: Arc<dyn DownloadListener>) -> FfiResult<String> {
        let entry = self
            .catalog
            .get(&id)
            .ok_or_else(|| FfiError::Model { detail: format!("modèle inconnu : {id}") })?
            .clone();
        let cancel = Arc::new(AtomicBool::new(false));
        self.cancels.lock().unwrap().insert(id.clone(), cancel.clone());
        let result = self
            .store
            .download(&entry, &cancel, |p| listener.on_progress(p.downloaded, p.total))
            .await;
        self.cancels.lock().unwrap().remove(&id);
        Ok(result?.to_string_lossy().into_owned())
    }

    /// Fichiers annexes (VAD Silero, mot d'activation) : dossier une fois installés.
    pub fn support_path(&self, id: String) -> Option<String> {
        let entry = self.catalog.support(&id)?;
        self.store
            .is_installed(entry)
            .then(|| self.store.model_dir(entry).to_string_lossy().into_owned())
    }

    pub async fn download_support(&self, id: String, listener: Arc<dyn DownloadListener>) -> FfiResult<String> {
        let entry = self
            .catalog
            .support(&id)
            .ok_or_else(|| FfiError::Model { detail: format!("fichier annexe inconnu : {id}") })?
            .clone();
        let cancel = Arc::new(AtomicBool::new(false));
        let dir = self
            .store
            .download(&entry, &cancel, |p| listener.on_progress(p.downloaded, p.total))
            .await?;
        Ok(dir.to_string_lossy().into_owned())
    }

    /// Interrompt le téléchargement (le fichier partiel est gardé pour reprendre).
    pub fn cancel(&self, id: String) {
        if let Some(flag) = self.cancels.lock().unwrap().get(&id) {
            flag.store(true, Ordering::Relaxed);
        }
    }

    pub fn delete(&self, id: String) -> FfiResult<()> {
        if let Some(entry) = self.catalog.get(&id) {
            self.store.delete(entry)?;
        }
        Ok(())
    }
}

// --- Fichiers audio ------------------------------------------------------------

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct TimedSegment {
    pub start_ms: u64,
    pub end_ms: u64,
    pub text: String,
    pub speaker: Option<u32>,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct FileTranscript {
    pub text: String,
    pub language: Option<String>,
    pub segments: Vec<TimedSegment>,
    pub duration_ms: u64,
    pub engine: String,
}

impl FileTranscript {
    fn to_core(&self) -> dicto_core::Transcript {
        dicto_core::Transcript {
            text: self.text.clone(),
            language: self.language.clone(),
            segments: self
                .segments
                .iter()
                .map(|s| dicto_core::Segment { start_ms: s.start_ms, end_ms: s.end_ms, text: s.text.clone(), speaker: s.speaker })
                .collect(),
        }
    }
}

#[uniffi::export(with_foreign)]
pub trait ProgressListener: Send + Sync {
    /// Avancement de 0 à 1.
    fn on_progress(&self, fraction: f32);
}

/// Transcription d'un fichier en cours (annulable).
#[derive(uniffi::Object)]
pub struct FileJob {
    cancel: Arc<AtomicBool>,
}

#[uniffi::export(async_runtime = "tokio")]
impl FileJob {
    #[uniffi::constructor]
    pub fn new() -> Arc<Self> {
        Arc::new(Self { cancel: Arc::new(AtomicBool::new(false)) })
    }

    pub fn cancel(&self) {
        self.cancel.store(true, Ordering::Relaxed);
    }

    /// `pcm` : PCM 16 bits little-endian entrelacé, tel que décodé par la plateforme.
    #[allow(clippy::too_many_arguments)] // signature exposée telle quelle à Kotlin
    pub async fn transcribe(
        &self,
        engine: Arc<Engine>,
        pcm: Vec<u8>,
        sample_rate: u32,
        channels: u16,
        language: Option<String>,
        vocabulary: Vec<String>,
        listener: Arc<dyn ProgressListener>,
    ) -> FfiResult<FileTranscript> {
        let samples: Vec<f32> = pcm
            .chunks_exact(2)
            .map(|b| i16::from_le_bytes([b[0], b[1]]) as f32 / 32768.0)
            .collect();
        let mono = dicto_core::audio::downmix(&samples, channels);
        let pcm16 = dicto_core::audio::resample(&mono, sample_rate, dicto_core::audio::SAMPLE_RATE);
        let opts = TranscribeOptions {
            language: language.filter(|l| !l.is_empty() && l != "auto"),
            prompt: vocabulary_prompt(&vocabulary),
            timestamps: true,
        };
        let t = dicto_core::files::transcribe_long(engine.inner.as_ref(), &pcm16, &opts, &self.cancel, |p| listener.on_progress(p)).await?;
        Ok(FileTranscript {
            text: t.text,
            language: t.language,
            segments: t
                .segments
                .into_iter()
                .map(|s| TimedSegment { start_ms: s.start_ms, end_ms: s.end_ms, text: s.text, speaker: s.speaker })
                .collect(),
            duration_ms: ms_for_samples(pcm16.len()),
            engine: engine.inner.id(),
        })
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum ExportFormat {
    Txt,
    Srt,
    Vtt,
    Md,
}

#[uniffi::export]
pub fn export_transcript(transcript: FileTranscript, format: ExportFormat, title: String) -> String {
    let t = transcript.to_core();
    match format {
        ExportFormat::Txt => dicto_core::export::to_txt(&t),
        ExportFormat::Srt => dicto_core::export::to_srt(&t),
        ExportFormat::Vtt => dicto_core::export::to_vtt(&t),
        ExportFormat::Md => dicto_core::export::to_md(&t, &title),
    }
}

// --- Mot d'activation ----------------------------------------------------------

/// Écoute en continu une phrase d'appel (« OK Dicto ») dans le flux du micro.
#[derive(uniffi::Object)]
pub struct WakeWordDetector {
    #[cfg(feature = "sherpa")]
    inner: dicto_core::wake::WakeWord,
}

#[uniffi::export]
impl WakeWordDetector {
    /// `model_dir` : dossier du modèle « kws-gigaspeech » ; `sensitivity` de 0 (strict) à 1.
    #[uniffi::constructor]
    pub fn new(model_dir: String, phrases: Vec<String>, sensitivity: f32) -> FfiResult<Arc<Self>> {
        #[cfg(feature = "sherpa")]
        {
            let inner = dicto_core::wake::WakeWord::load(std::path::Path::new(&model_dir), &phrases, sensitivity)?;
            Ok(Arc::new(Self { inner }))
        }
        #[cfg(not(feature = "sherpa"))]
        {
            let _ = (model_dir, phrases, sensitivity);
            Err(FfiError::Model { detail: "cette version a été compilée sans sherpa-onnx".into() })
        }
    }

    /// PCM 16 bits little-endian 16 kHz ; renvoie la phrase reconnue, s'il y en a une.
    pub fn push(&self, pcm: Vec<u8>) -> Option<String> {
        #[cfg(feature = "sherpa")]
        {
            let samples: Vec<f32> = pcm
                .chunks_exact(2)
                .map(|b| i16::from_le_bytes([b[0], b[1]]) as f32 / 32768.0)
                .collect();
            self.inner.push(&samples)
        }
        #[cfg(not(feature = "sherpa"))]
        {
            let _ = pcm;
            None
        }
    }
}

// --- Stockage ------------------------------------------------------------------

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct HistoryItem {
    pub id: i64,
    pub created_at_ms: i64,
    pub text: String,
    pub raw_text: Option<String>,
    pub engine: String,
    pub language: Option<String>,
    pub duration_ms: u64,
    pub app: Option<String>,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct VocabularyTerm {
    pub id: i64,
    pub term: String,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct AppProfile {
    pub id: Option<i64>,
    pub app_id: String,
    pub label: Option<String>,
    pub language: Option<String>,
    pub engine: Option<String>,
    pub mode: Option<String>,
    pub refine_preset: Option<String>,
}

impl From<store::Profile> for AppProfile {
    fn from(p: store::Profile) -> Self {
        Self {
            id: p.id,
            app_id: p.app_id,
            label: p.label,
            language: p.language,
            engine: p.engine,
            mode: p.mode,
            refine_preset: p.refine_preset,
        }
    }
}

#[derive(uniffi::Object)]
pub struct Storage {
    inner: Mutex<store::Store>,
}

#[uniffi::export]
impl Storage {
    #[uniffi::constructor]
    pub fn open(path: String) -> FfiResult<Arc<Self>> {
        Ok(Arc::new(Self { inner: Mutex::new(store::Store::open(path)?) }))
    }

    pub fn add_history(&self, result: DictationResult, app: Option<String>) -> FfiResult<i64> {
        let raw = (result.raw_text != result.text).then_some(result.raw_text);
        Ok(self.inner.lock().unwrap().add_history(&store::NewHistoryEntry {
            text: result.text,
            raw_text: raw,
            engine: result.engine,
            language: result.language,
            duration_ms: result.duration_ms,
            app,
        })?)
    }

    pub fn history(&self, query: Option<String>, limit: u32, offset: u32) -> FfiResult<Vec<HistoryItem>> {
        let items = self.inner.lock().unwrap().list_history(query.as_deref(), limit, offset)?;
        Ok(items
            .into_iter()
            .map(|e| HistoryItem {
                id: e.id,
                created_at_ms: e.created_at,
                text: e.text,
                raw_text: e.raw_text,
                engine: e.engine,
                language: e.language,
                duration_ms: e.duration_ms,
                app: e.app,
            })
            .collect())
    }

    pub fn delete_history(&self, id: i64) -> FfiResult<()> {
        Ok(self.inner.lock().unwrap().delete_history(id)?)
    }

    pub fn clear_history(&self) -> FfiResult<()> {
        Ok(self.inner.lock().unwrap().clear_history()?)
    }

    pub fn vocabulary(&self) -> FfiResult<Vec<VocabularyTerm>> {
        let terms = self.inner.lock().unwrap().terms()?;
        Ok(terms.into_iter().map(|t| VocabularyTerm { id: t.id, term: t.term }).collect())
    }

    pub fn add_term(&self, term: String) -> FfiResult<()> {
        Ok(self.inner.lock().unwrap().add_term(&term)?)
    }

    pub fn remove_term(&self, id: i64) -> FfiResult<()> {
        Ok(self.inner.lock().unwrap().remove_term(id)?)
    }

    pub fn profiles(&self) -> FfiResult<Vec<AppProfile>> {
        let profiles = self.inner.lock().unwrap().profiles()?;
        Ok(profiles.into_iter().map(Into::into).collect())
    }

    pub fn profile_for_app(&self, app_id: String) -> FfiResult<Option<AppProfile>> {
        Ok(self.inner.lock().unwrap().profile_for_app(&app_id)?.map(Into::into))
    }

    pub fn save_profile(&self, profile: AppProfile) -> FfiResult<i64> {
        Ok(self.inner.lock().unwrap().upsert_profile(&store::Profile {
            id: profile.id,
            app_id: profile.app_id,
            label: profile.label,
            language: profile.language,
            engine: profile.engine,
            mode: profile.mode,
            refine_preset: profile.refine_preset,
        })?)
    }

    pub fn delete_profile(&self, id: i64) -> FfiResult<()> {
        Ok(self.inner.lock().unwrap().delete_profile(id)?)
    }

    pub fn setting(&self, key: String) -> FfiResult<Option<String>> {
        Ok(self.inner.lock().unwrap().setting(&key)?)
    }

    pub fn set_setting(&self, key: String, value: String) -> FfiResult<()> {
        Ok(self.inner.lock().unwrap().set_setting(&key, &value)?)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::mpsc as std_mpsc;
    use std::time::Duration;

    #[derive(Debug, PartialEq)]
    enum Event {
        Capture(bool),
        State(State),
        Transcribing(u64),
        Result(u64, String),
        Discarded(DiscardReason),
        Error(u64, String),
    }

    struct Recorder(Mutex<std_mpsc::Sender<Event>>);

    impl DictationListener for Recorder {
        fn on_capture(&self, active: bool) {
            self.0.lock().unwrap().send(Event::Capture(active)).unwrap();
        }
        fn on_state(&self, state: State) {
            self.0.lock().unwrap().send(Event::State(state)).unwrap();
        }
        fn on_transcribing(&self, id: u64) {
            self.0.lock().unwrap().send(Event::Transcribing(id)).unwrap();
        }
        fn on_partial(&self, _text: String) {}
        fn on_result(&self, id: u64, result: DictationResult) {
            self.0.lock().unwrap().send(Event::Result(id, result.text)).unwrap();
        }
        fn on_discarded(&self, reason: DiscardReason) {
            self.0.lock().unwrap().send(Event::Discarded(reason)).unwrap();
        }
        fn on_error(&self, id: u64, error: FfiError) {
            self.0.lock().unwrap().send(Event::Error(id, error.to_string())).unwrap();
        }
    }

    struct Echo;

    #[async_trait::async_trait]
    impl SttEngine for Echo {
        fn id(&self) -> String {
            "test:echo".into()
        }
        fn is_local(&self) -> bool {
            true
        }
        async fn transcribe(&self, pcm: &[f32], _: &TranscribeOptions) -> dicto_core::Result<dicto_core::Transcript> {
            Ok(dicto_core::Transcript::from_text(format!(
                "{} ms, nouvelle ligne. fin",
                ms_for_samples(pcm.len())
            )))
        }
    }

    #[test]
    fn push_to_talk_round_trip() {
        let (tx, rx) = std_mpsc::channel();
        let d = Dictation::new(Mode::PushToTalk, Arc::new(Recorder(Mutex::new(tx))));
        d.settings.write().unwrap().engine = Some(Arc::new(Echo));

        d.trigger(Trigger::Pressed);
        d.push_audio(1000i16.to_le_bytes().repeat(16_000));
        d.trigger(Trigger::Pressed);

        let mut events = Vec::new();
        while let Ok(e) = rx.recv_timeout(Duration::from_secs(5)) {
            let done = matches!(e, Event::Result(..) | Event::Error(..));
            events.push(e);
            if done {
                break;
            }
        }
        assert_eq!(
            events,
            vec![
                Event::Capture(true),
                Event::State(State::Recording),
                Event::Capture(false),
                Event::Transcribing(1),
                Event::State(State::Idle),
                Event::Result(1, "1000 ms\nfin".into()),
            ]
        );
    }

    #[test]
    fn reports_missing_engine() {
        let (tx, rx) = std_mpsc::channel();
        let d = Dictation::new(Mode::Hold, Arc::new(Recorder(Mutex::new(tx))));
        d.trigger(Trigger::Pressed);
        d.push_audio(500i16.to_le_bytes().repeat(8_000));
        d.trigger(Trigger::Released);
        let error = std::iter::from_fn(|| rx.recv_timeout(Duration::from_secs(5)).ok())
            .find(|e| matches!(e, Event::Error(..)));
        assert_eq!(error, Some(Event::Error(1, "aucun moteur sélectionné".into())));
    }
}
