//! Cherche une phrase d'appel dans un fichier WAV.
//!
//! ```bash
//! cargo run -p dicto-core --features sherpa --example wake -- <dossier-modèle-kws> <fichier.wav> "LIGHT UP"
//! ```

fn main() -> Result<(), Box<dyn std::error::Error>> {
    #[cfg(feature = "sherpa")]
    {
        let mut args = std::env::args().skip(1);
        let dir = args.next().ok_or("dossier du modèle")?;
        let wav = args.next().ok_or("fichier wav")?;
        let phrases: Vec<String> = args.collect();
        let pcm = dicto_core::audio::decode_wav(&std::fs::read(&wav)?)?;
        let wake = dicto_core::wake::WakeWord::load(std::path::Path::new(&dir), &phrases, 0.5)?;
        let mut found = Vec::new();
        for (i, chunk) in pcm.chunks(800).enumerate() {
            if let Some(k) = wake.push(chunk) {
                found.push(format!("{k} à {:.1} s", i as f32 * 0.05));
            }
        }
        if let Some(k) = wake.push(&vec![0.0; 16_000]) {
            found.push(format!("{k} (fin)"));
        }
        println!("{}", if found.is_empty() { "rien".to_string() } else { found.join(", ") });
    }
    Ok(())
}
