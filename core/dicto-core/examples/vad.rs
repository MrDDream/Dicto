//! Découpe un WAV en phrases avec Silero : `vad <silero_vad.int8.onnx> <fichier.wav>`.
fn main() -> Result<(), Box<dyn std::error::Error>> {
    #[cfg(feature = "sherpa")]
    {
        use dicto_core::vad::{Segmenter, SegmenterConfig, SileroVad, VadEvent};
        let mut args = std::env::args().skip(1);
        let model = args.next().ok_or("modèle")?;
        let wav = args.next().ok_or("wav")?;
        let mut pcm = dicto_core::audio::decode_wav(&std::fs::read(&wav)?)?;
        pcm.extend(vec![0.0; 16_000]);
        let mut seg = Segmenter::new(SileroVad::load(std::path::Path::new(&model))?, SegmenterConfig::default());
        for chunk in pcm.chunks(800) {
            for e in seg.push(chunk) {
                if let VadEvent::Utterance { samples, pause_before_ms } = e {
                    println!("phrase de {:.2} s (pause avant : {pause_before_ms:?} ms)", samples.len() as f32 / 16_000.0);
                }
            }
        }
    }
    Ok(())
}
