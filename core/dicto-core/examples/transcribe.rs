//! Transcrit un fichier WAV avec un modèle local du catalogue (téléchargé au besoin).
//!
//! ```bash
//! cargo run --release -p dicto-core --features whisper,sherpa --example transcribe -- \
//!     <fichier.wav> [id-du-modèle] [dossier-des-modèles] [langue]
//! ```

use std::sync::atomic::AtomicBool;
use std::time::Instant;

use dicto_core::audio::decode_wav;
use dicto_core::engine::TranscribeOptions;
use dicto_core::engine::local::{LocalOptions, load};
use dicto_core::models::{Catalog, ModelStore};

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut args = std::env::args().skip(1);
    let wav = args.next().ok_or("usage : transcribe <fichier.wav> [modèle] [dossier] [langue]")?;
    let model_id = args.next().unwrap_or_else(|| "whisper-tiny-q5_1".into());
    let dir = args.next().unwrap_or_else(|| "models".into());
    let language = args.next();

    let catalog = Catalog::builtin();
    let entry = catalog.get(&model_id).ok_or("modèle inconnu")?;
    let store = ModelStore::new(&dir);
    if !store.is_installed(entry) {
        eprintln!("Téléchargement de {} ({} Mo)…", entry.name, entry.total_size() / 1_000_000);
        let mut last = 0;
        store
            .download(entry, &AtomicBool::new(false), |p| {
                let pct = p.downloaded * 100 / p.total.max(1);
                if pct >= last + 10 {
                    last = pct;
                    eprintln!("  {pct} %");
                }
            })
            .await?;
    }

    let pcm = decode_wav(&std::fs::read(&wav)?)?;
    let start = Instant::now();
    let engine = load(&store, entry, &LocalOptions::default())?;
    let loaded = start.elapsed();
    let opts = TranscribeOptions { language, ..Default::default() };
    let start = Instant::now();
    let t = engine.transcribe(&pcm, &opts).await?;
    eprintln!(
        "{} · chargé en {:.1} s · {:.1} s d'audio transcrites en {:.2} s",
        engine.id(),
        loaded.as_secs_f32(),
        pcm.len() as f32 / 16_000.0,
        start.elapsed().as_secs_f32(),
    );
    println!("{}", t.text);
    Ok(())
}
