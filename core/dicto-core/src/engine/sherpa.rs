//! Moteurs locaux sherpa-onnx (feature `sherpa`) : Parakeet, Canary,
//! Omnilingual (hors ligne) et Nemotron/Zipformer (streaming).
//!
//! Les fichiers d'un modèle sont retrouvés dans son dossier par motif
//! (`*encoder*.onnx`, `tokens.txt`…), en préférant les versions int8.

use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};

use async_trait::async_trait;
use sherpa_onnx::{
    OfflineModelConfig, OfflineRecognizer, OfflineRecognizerConfig, OnlineModelConfig, OnlineRecognizer,
    OnlineRecognizerConfig,
};

use super::{LiveEngine, LiveStream, SttEngine, TranscribeOptions};
use crate::audio::SAMPLE_RATE;
use crate::models::SherpaKind;
use crate::{DictoError, Result, Transcript};

/// Les reconnaisseurs sherpa-onnx sont des pointeurs C++ ; leur accès est sérialisé
/// par le `Mutex` de [`SherpaEngine`], ce qui permet de les partager entre threads.
struct Shared<T>(T);
unsafe impl<T> Send for Shared<T> {}

enum Recognizer {
    Offline(Shared<OfflineRecognizer>),
    Online(Shared<OnlineRecognizer>),
}

pub struct SherpaEngine {
    inner: Arc<Inner>,
}

struct Inner {
    recognizer: Mutex<Recognizer>,
    model_id: String,
    kind: SherpaKind,
}

fn threads() -> i32 {
    std::thread::available_parallelism().map(|n| n.get().min(4) as i32).unwrap_or(2)
}

/// Cherche un `.onnx` dont le nom contient `pattern`, version int8 de préférence.
fn find_onnx(dir: &Path, pattern: &str) -> Option<String> {
    let mut found: Vec<PathBuf> = std::fs::read_dir(dir)
        .ok()?
        .filter_map(|e| e.ok().map(|e| e.path()))
        .filter(|p| {
            let name = p.file_name().and_then(|n| n.to_str()).unwrap_or_default();
            name.ends_with(".onnx") && name.contains(pattern)
        })
        .collect();
    found.sort_by_key(|p| !p.to_string_lossy().contains("int8"));
    found.first().map(|p| p.to_string_lossy().into_owned())
}

fn require(dir: &Path, pattern: &str) -> Result<String> {
    find_onnx(dir, pattern)
        .ok_or_else(|| DictoError::Model(format!("fichier « {pattern} » introuvable dans {}", dir.display())))
}

fn tokens(dir: &Path) -> Result<String> {
    let path = dir.join("tokens.txt");
    if path.exists() {
        Ok(path.to_string_lossy().into_owned())
    } else {
        Err(DictoError::Model(format!("tokens.txt introuvable dans {}", dir.display())))
    }
}

impl SherpaEngine {
    pub fn load(dir: &Path, model_id: &str, kind: SherpaKind) -> Result<Self> {
        let recognizer = match kind {
            SherpaKind::OnlineTransducer => {
                let mut model = OnlineModelConfig::default();
                model.transducer.encoder = Some(require(dir, "encoder")?);
                model.transducer.decoder = Some(require(dir, "decoder")?);
                model.transducer.joiner = Some(require(dir, "joiner")?);
                model.tokens = Some(tokens(dir)?);
                model.num_threads = threads();
                let config = OnlineRecognizerConfig {
                    model_config: model,
                    decoding_method: Some("greedy_search".into()),
                    ..Default::default()
                };
                let r = OnlineRecognizer::create(&config)
                    .ok_or_else(|| DictoError::Model(format!("chargement de {model_id} impossible")))?;
                Recognizer::Online(Shared(r))
            }
            _ => {
                let mut model = OfflineModelConfig::default();
                match kind {
                    SherpaKind::NemoTransducer => {
                        model.transducer.encoder = Some(require(dir, "encoder")?);
                        model.transducer.decoder = Some(require(dir, "decoder")?);
                        model.transducer.joiner = Some(require(dir, "joiner")?);
                        model.model_type = Some("nemo_transducer".into());
                    }
                    SherpaKind::Canary => {
                        model.canary.encoder = Some(require(dir, "encoder")?);
                        model.canary.decoder = Some(require(dir, "decoder")?);
                        model.canary.src_lang = Some("fr".into());
                        model.canary.tgt_lang = Some("fr".into());
                        model.canary.use_pnc = true;
                    }
                    SherpaKind::OmnilingualCtc => {
                        model.omnilingual.model = Some(require(dir, "model")?);
                    }
                    SherpaKind::OnlineTransducer => unreachable!(),
                }
                model.tokens = Some(tokens(dir)?);
                model.num_threads = threads();
                let config = OfflineRecognizerConfig {
                    model_config: model,
                    decoding_method: Some("greedy_search".into()),
                    ..Default::default()
                };
                let r = OfflineRecognizer::create(&config)
                    .ok_or_else(|| DictoError::Model(format!("chargement de {model_id} impossible")))?;
                Recognizer::Offline(Shared(r))
            }
        };
        Ok(Self {
            inner: Arc::new(Inner {
                recognizer: Mutex::new(recognizer),
                model_id: model_id.to_string(),
                kind,
            }),
        })
    }
}

impl SherpaEngine {
    /// Vrai si le modèle produit du texte pendant qu'on parle.
    pub fn supports_live(&self) -> bool {
        self.inner.kind == SherpaKind::OnlineTransducer
    }

    /// Ouvre une session de transcription en direct (modèles streaming uniquement).
    pub fn live(&self) -> Option<LiveSession> {
        let guard = self.inner.recognizer.lock().ok()?;
        match &*guard {
            Recognizer::Online(Shared(r)) => Some(LiveSession {
                inner: self.inner.clone(),
                stream: Shared(r.create_stream()),
                last: String::new(),
            }),
            Recognizer::Offline(_) => None,
        }
    }
}

/// Texte provisoire au fil de la parole. Le texte final reste celui de la transcription
/// de l'énoncé complet ; cette session ne sert qu'à l'affichage.
pub struct LiveSession {
    inner: Arc<Inner>,
    stream: Shared<sherpa_onnx::OnlineStream>,
    last: String,
}

impl LiveEngine for SherpaEngine {
    fn open(&self) -> Option<Box<dyn LiveStream>> {
        self.live().map(|s| Box::new(s) as Box<dyn LiveStream>)
    }
}

impl LiveStream for LiveSession {
    fn push(&mut self, pcm: &[f32]) -> Option<String> {
        LiveSession::push(self, pcm)
    }

    fn reset(&mut self) {
        LiveSession::reset(self)
    }
}

impl LiveSession {
    /// Ajoute de l'audio ; renvoie le texte provisoire s'il a changé.
    pub fn push(&mut self, pcm: &[f32]) -> Option<String> {
        let guard = self.inner.recognizer.lock().ok()?;
        let Recognizer::Online(Shared(r)) = &*guard else { return None };
        let stream = &self.stream.0;
        stream.accept_waveform(SAMPLE_RATE as i32, pcm);
        while r.is_ready(stream) {
            r.decode(stream);
        }
        let text = tidy(&r.get_result(stream).map(|res| res.text).unwrap_or_default());
        (text != self.last).then(|| {
            self.last = text.clone();
            text
        })
    }

    /// Repart de zéro (nouvelle phrase en mode continu).
    pub fn reset(&mut self) {
        if let Ok(guard) = self.inner.recognizer.lock() {
            if let Recognizer::Online(Shared(r)) = &*guard {
                r.reset(&self.stream.0);
            }
        }
        self.last.clear();
    }
}

impl Inner {
    fn run(&self, pcm: &[f32], opts: &TranscribeOptions) -> Result<Transcript> {
        let guard = self.recognizer.lock().map_err(|_| DictoError::Model("moteur indisponible".into()))?;
        let text = match &*guard {
            Recognizer::Offline(Shared(r)) => {
                let stream = r.create_stream();
                if self.kind == SherpaKind::Canary {
                    // Canary transcrit dans la langue demandée (sinon le français par défaut).
                    let lang = opts.language.as_deref().filter(|l| ["fr", "en", "de", "es"].contains(l));
                    if let Some(lang) = lang {
                        stream.set_option("src_lang", lang);
                        stream.set_option("tgt_lang", lang);
                    }
                }
                stream.accept_waveform(SAMPLE_RATE as i32, pcm);
                r.decode(&stream);
                stream.get_result().map(|res| res.text).unwrap_or_default()
            }
            Recognizer::Online(Shared(r)) => {
                let stream = r.create_stream();
                stream.accept_waveform(SAMPLE_RATE as i32, pcm);
                // Un peu de silence pour vider le contexte droit du modèle.
                stream.accept_waveform(SAMPLE_RATE as i32, &vec![0.0; SAMPLE_RATE as usize * 2 / 3]);
                stream.input_finished();
                while r.is_ready(&stream) {
                    r.decode(&stream);
                }
                r.get_result(&stream).map(|res| res.text).unwrap_or_default()
            }
        };
        Ok(Transcript {
            text: tidy(&text),
            language: opts.language.clone(),
            segments: Vec::new(),
        })
    }
}

/// Espaces superflus, et majuscule initiale pour les modèles qui n'en mettent pas.
fn tidy(text: &str) -> String {
    let t = text.split_whitespace().collect::<Vec<_>>().join(" ");
    let mut chars = t.chars();
    match chars.next() {
        Some(c) if c.is_lowercase() => c.to_uppercase().collect::<String>() + chars.as_str(),
        _ => t,
    }
}

#[async_trait]
impl SttEngine for SherpaEngine {
    fn id(&self) -> String {
        format!("local:{}", self.inner.model_id)
    }

    fn is_local(&self) -> bool {
        true
    }

    async fn transcribe(&self, pcm: &[f32], opts: &TranscribeOptions) -> Result<Transcript> {
        let inner = self.inner.clone();
        let (pcm, opts) = (pcm.to_vec(), opts.clone());
        tokio::task::spawn_blocking(move || inner.run(&pcm, &opts))
            .await
            .map_err(|e| DictoError::Model(format!("tâche sherpa interrompue : {e}")))?
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tidy_capitalizes() {
        assert_eq!(tidy("  bonjour   à tous "), "Bonjour à tous");
        assert_eq!(tidy("Déjà."), "Déjà.");
        assert_eq!(tidy(""), "");
    }
}
