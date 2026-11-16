use thiserror::Error;

pub type Result<T, E = DictoError> = std::result::Result<T, E>;

#[derive(Debug, Error)]
pub enum DictoError {
    #[error("erreur d'entrée/sortie : {0}")]
    Io(#[from] std::io::Error),

    #[error("erreur réseau : {0}")]
    Http(#[from] reqwest::Error),

    #[error("{provider} a répondu {status} : {message}")]
    Api {
        provider: String,
        status: u16,
        message: String,
    },

    #[error("modèle : {0}")]
    Model(String),

    #[error("stockage : {0}")]
    Store(#[from] rusqlite::Error),

    #[error("audio : {0}")]
    Audio(String),

    #[error("données invalides : {0}")]
    Json(#[from] serde_json::Error),

    #[error("non pris en charge : {0}")]
    Unsupported(String),

    #[error("opération annulée")]
    Cancelled,
}
