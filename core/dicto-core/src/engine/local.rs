//! Chargement d'un modèle local du catalogue, quel que soit son moteur.

use std::sync::Arc;

use super::SttEngine;
use crate::models::{EngineKind, ModelEntry, ModelStore};
use crate::{DictoError, Result};

#[derive(Debug, Clone, Default)]
pub struct LocalOptions {
    /// Calcul sur GPU quand le moteur et la compilation le permettent (whisper.cpp Vulkan/CUDA).
    pub use_gpu: bool,
}

/// Moteur chargé, avec sa capacité de transcription en direct le cas échéant.
pub struct Loaded {
    pub stt: Arc<dyn SttEngine>,
    pub live: Option<Arc<dyn super::LiveEngine>>,
}

/// Comme [`load`], en gardant l'accès au direct des modèles streaming.
pub fn load_full(store: &ModelStore, entry: &ModelEntry, opts: &LocalOptions) -> Result<Loaded> {
    #[cfg(feature = "sherpa")]
    if entry.engine == EngineKind::Sherpa && entry.streaming {
        if !store.is_installed(entry) {
            return Err(DictoError::Model(format!("{} n'est pas installé", entry.name)));
        }
        let kind = entry.sherpa.ok_or_else(|| DictoError::Model(format!("{} : famille sherpa non précisée", entry.id)))?;
        let engine = Arc::new(super::sherpa::SherpaEngine::load(&store.install_path(entry), &entry.id, kind)?);
        return Ok(Loaded { stt: engine.clone(), live: Some(engine) });
    }
    Ok(Loaded { stt: load(store, entry, opts)?, live: None })
}

/// Charge un modèle installé. L'opération est lente (lecture de centaines de Mo) :
/// à appeler hors du thread de l'interface.
pub fn load(store: &ModelStore, entry: &ModelEntry, opts: &LocalOptions) -> Result<Arc<dyn SttEngine>> {
    if !store.is_installed(entry) {
        return Err(DictoError::Model(format!("{} n'est pas installé", entry.name)));
    }
    let path = store.install_path(entry);
    match entry.engine {
        EngineKind::Whisper => {
            #[cfg(feature = "whisper")]
            {
                use super::whisper::{WhisperEngine, WhisperLoadOptions};
                let load = WhisperLoadOptions { use_gpu: opts.use_gpu, ..Default::default() };
                Ok(Arc::new(WhisperEngine::load(&path, &entry.id, load)?))
            }
            #[cfg(not(feature = "whisper"))]
            {
                let _ = (path, opts);
                Err(DictoError::Unsupported("cette version a été compilée sans whisper.cpp".into()))
            }
        }
        EngineKind::Sherpa => {
            #[cfg(feature = "sherpa")]
            {
                let _ = opts;
                let kind = entry
                    .sherpa
                    .ok_or_else(|| DictoError::Model(format!("{} : famille sherpa non précisée", entry.id)))?;
                Ok(Arc::new(super::sherpa::SherpaEngine::load(&path, &entry.id, kind)?))
            }
            #[cfg(not(feature = "sherpa"))]
            {
                let _ = (path, opts);
                Err(DictoError::Unsupported("cette version a été compilée sans sherpa-onnx".into()))
            }
        }
    }
}
