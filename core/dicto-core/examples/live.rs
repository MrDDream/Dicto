//! Rejoue un WAV par blocs de 100 ms dans un modèle streaming et affiche le texte provisoire.
//!
//! ```bash
//! cargo run --release -p dicto-core --features sherpa --example live -- <fichier.wav> <dossier-des-modèles> [modèle]
//! ```

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut args = std::env::args().skip(1);
    let wav = args.next().ok_or("fichier wav")?;
    let dir = args.next().ok_or("dossier des modèles")?;
    let id = args.next().unwrap_or_else(|| "nemotron-3.5-streaming-560ms-int8".into());
    let catalog = dicto_core::models::Catalog::builtin();
    let entry = catalog.get(&id).ok_or("modèle inconnu")?;
    let store = dicto_core::models::ModelStore::new(&dir);
    let loaded = dicto_core::engine::local::load_full(&store, entry, &Default::default())?;
    let live = loaded.live.ok_or("ce modèle n'a pas de mode direct")?;
    let mut stream = live.open().ok_or("session impossible")?;
    let pcm = dicto_core::audio::decode_wav(&std::fs::read(&wav)?)?;
    let start = std::time::Instant::now();
    for (i, chunk) in pcm.chunks(1600).enumerate() {
        if let Some(text) = stream.push(chunk) {
            println!("{:5.1} s  {text}", (i + 1) as f32 * 0.1);
        }
    }
    eprintln!("{:.1} s d'audio traitées en {:.2} s", pcm.len() as f32 / 16_000.0, start.elapsed().as_secs_f32());
    Ok(())
}
