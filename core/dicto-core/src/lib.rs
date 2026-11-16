//! Cœur partagé de Dicto.
//!
//! Ce crate ne dépend d'aucune plateforme : Android (via `dicto-ffi`/UniFFI) et le
//! bureau (Tauri) lui fournissent l'audio PCM et les déclencheurs, et récupèrent
//! en retour des actions (démarrer/arrêter la capture, transcrire un énoncé…).

pub mod audio;
pub mod commands;
pub mod engine;
pub mod error;
pub mod export;
pub mod files;
pub mod models;
pub mod refine;
pub mod session;
pub mod store;
pub mod transcript;
pub mod vad;
pub mod wake;

#[cfg(test)]
pub(crate) mod test_support;

pub use error::{DictoError, Result};
pub use transcript::{Segment, Transcript};
