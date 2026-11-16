//! Séparation des voix d'un ou plusieurs WAV mis bout à bout.
//!
//!   cargo run --example diarize --features sherpa-shared -- <dossier du modèle> a.wav [b.wav…]

fn main() -> Result<(), Box<dyn std::error::Error>> {
    #[cfg(feature = "sherpa")]
    {
        let mut args = std::env::args().skip(1);
        let dir = args.next().ok_or("dossier du modèle")?;
        let mut pcm = Vec::new();
        for wav in args {
            pcm.extend(dicto_core::audio::decode_wav(&std::fs::read(&wav)?)?);
            pcm.extend(vec![0.0; 8_000]);
        }
        let d = dicto_core::diarize::Diarizer::load(std::path::Path::new(&dir), None)?;
        for t in d.turns(&pcm)? {
            println!("{:>6.2} s → {:>6.2} s : voix {}", t.start_ms as f32 / 1000.0, t.end_ms as f32 / 1000.0, t.speaker + 1);
        }
    }
    Ok(())
}
