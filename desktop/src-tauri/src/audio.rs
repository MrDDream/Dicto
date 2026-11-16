//! Capture du micro par défaut (cpal), convertie au format du cœur : PCM 16 bits mono 16 kHz,
//! par blocs de 50 ms. Le flux cpal vit sur son propre fil (il n'est pas `Send` partout).

use std::sync::Mutex;
use std::sync::mpsc;
use std::thread;

use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use cpal::{SampleFormat, StreamConfig};

pub const RATE: u32 = 16_000;
/// 50 ms à 16 kHz.
const CHUNK: usize = RATE as usize / 20;

/// Reçoit chaque bloc (PCM 16 bits LE) et son niveau sonore 0..1.
pub type OnAudio = Box<dyn FnMut(Vec<u8>, f32) + Send>;

#[derive(Default)]
pub struct Recorder {
    stop: Mutex<Option<mpsc::Sender<()>>>,
}

impl Recorder {
    /// Ouvre le micro par défaut. Erreur lisible si aucun micro ou accès refusé.
    pub fn start(&self, on_audio: OnAudio) -> Result<(), String> {
        let mut stop = self.stop.lock().unwrap();
        if stop.is_some() {
            return Ok(());
        }
        let (stop_tx, stop_rx) = mpsc::channel::<()>();
        let (ready_tx, ready_rx) = mpsc::sync_channel::<Result<(), String>>(1);
        thread::Builder::new()
            .name("dicto-micro".into())
            .spawn(move || {
                let stream = match open_stream(on_audio) {
                    Ok(s) => s,
                    Err(e) => {
                        let _ = ready_tx.send(Err(e));
                        return;
                    }
                };
                let _ = ready_tx.send(Ok(()));
                // Jusqu'à l'arrêt (ou l'abandon de l'émetteur) : le flux est fermé en sortant.
                let _ = stop_rx.recv();
                drop(stream);
            })
            .map_err(|e| e.to_string())?;
        ready_rx.recv().map_err(|e| e.to_string())??;
        *stop = Some(stop_tx);
        Ok(())
    }

    pub fn stop(&self) {
        if let Some(tx) = self.stop.lock().unwrap().take() {
            let _ = tx.send(());
        }
    }
}

fn open_stream(on_audio: OnAudio) -> Result<cpal::Stream, String> {
    let host = cpal::default_host();
    let device = host.default_input_device().ok_or("Aucun micro trouvé.")?;
    let supported = device
        .default_input_config()
        .map_err(|e| format!("Micro inaccessible : {e}"))?;
    let format = supported.sample_format();
    let config: StreamConfig = supported.into();
    let mut conv = Converter::new(config.channels as usize, config.sample_rate, on_audio);
    let err = |e: cpal::Error| log::warn!("micro : {e}");
    let stream = match format {
        SampleFormat::F32 => device.build_input_stream::<f32, _, _>(config, move |d, _| conv.push(d.iter().copied()), err, None),
        SampleFormat::I16 => device.build_input_stream::<i16, _, _>(
            config,
            move |d, _| conv.push(d.iter().map(|&s| s as f32 / 32768.0)),
            err,
            None,
        ),
        SampleFormat::U16 => device.build_input_stream::<u16, _, _>(
            config,
            move |d, _| conv.push(d.iter().map(|&s| (s as f32 - 32768.0) / 32768.0)),
            err,
            None,
        ),
        SampleFormat::I32 => device.build_input_stream::<i32, _, _>(
            config,
            move |d, _| conv.push(d.iter().map(|&s| s as f32 / 2_147_483_648.0)),
            err,
            None,
        ),
        other => return Err(format!("Format du micro non pris en charge : {other:?}")),
    }
    .map_err(|e| format!("Micro inaccessible : {e}"))?;
    stream.play().map_err(|e| format!("Micro inaccessible : {e}"))?;
    Ok(stream)
}

/// Mono, filtre passe-bas puis rééchantillonnage linéaire vers 16 kHz, découpage en blocs.
struct Converter {
    channels: usize,
    frame: Vec<f32>,
    /// Pas de lecture : fréquence d'entrée / 16 kHz.
    step: f64,
    pos: f64,
    prev: f32,
    /// Passe-bas à ~7 kHz avant de réduire la fréquence (évite le repliement).
    lp: f32,
    alpha: f32,
    out: Vec<i16>,
    on_audio: OnAudio,
}

impl Converter {
    fn new(channels: usize, rate: u32, on_audio: OnAudio) -> Self {
        let rate = rate.max(1) as f32;
        let cutoff = 7_000f32.min(rate * 0.45);
        Self {
            channels: channels.max(1),
            frame: Vec::with_capacity(channels.max(1)),
            step: rate as f64 / RATE as f64,
            pos: 0.0,
            prev: 0.0,
            lp: 0.0,
            alpha: 1.0 - (-2.0 * std::f32::consts::PI * cutoff / rate).exp(),
            out: Vec::with_capacity(CHUNK * 2),
            on_audio,
        }
    }

    fn push(&mut self, samples: impl Iterator<Item = f32>) {
        for s in samples {
            self.frame.push(s);
            if self.frame.len() < self.channels {
                continue;
            }
            let mono = self.frame.iter().sum::<f32>() / self.channels as f32;
            self.frame.clear();
            self.lp += self.alpha * (mono - self.lp);
            let x = self.lp;
            // Échantillons de sortie qui tombent entre l'échantillon précédent et celui-ci.
            while self.pos < 1.0 {
                let y = self.prev + (x - self.prev) * self.pos as f32;
                self.out.push((y.clamp(-1.0, 1.0) * 32767.0) as i16);
                self.pos += self.step;
            }
            self.pos -= 1.0;
            self.prev = x;
            if self.out.len() >= CHUNK {
                let chunk: Vec<i16> = self.out.drain(..CHUNK).collect();
                let level = level(&chunk);
                let bytes = chunk.iter().flat_map(|s| s.to_le_bytes()).collect();
                (self.on_audio)(bytes, level);
            }
        }
    }
}

/// Niveau 0..1 (RMS, échelle racine pour que la voix normale occupe le milieu), comme sur Android.
pub fn level(pcm: &[i16]) -> f32 {
    if pcm.is_empty() {
        return 0.0;
    }
    let sum: f64 = pcm.iter().map(|&s| (s as f64 / 32768.0).powi(2)).sum();
    let rms = (sum / pcm.len() as f64).sqrt();
    (rms * 4.0).sqrt().clamp(0.0, 1.0) as f32
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::{Arc, Mutex};

    fn collect(channels: usize, rate: u32, input: Vec<f32>) -> Vec<i16> {
        let got = Arc::new(Mutex::new(Vec::<i16>::new()));
        let sink = got.clone();
        let mut conv = Converter::new(
            channels,
            rate,
            Box::new(move |bytes, _| {
                sink.lock()
                    .unwrap()
                    .extend(bytes.chunks_exact(2).map(|b| i16::from_le_bytes([b[0], b[1]])));
            }),
        );
        conv.push(input.into_iter());
        got.lock().unwrap().clone()
    }

    #[test]
    fn downsamples_48k_stereo_to_16k_mono_in_50ms_blocks() {
        // 1 s de stéréo à 48 kHz : 16 000 échantillons en sortie, soit 20 blocs de 800.
        let out = collect(2, 48_000, vec![0.25; 48_000 * 2]);
        assert_eq!(out.len(), 16_000);
        // Le passe-bas a convergé : niveau constant de 0,25.
        assert!((out[15_999] as f32 / 32767.0 - 0.25).abs() < 0.01);
    }

    #[test]
    fn keeps_16k_as_is() {
        let out = collect(1, 16_000, vec![0.0; 1_600]);
        assert_eq!(out.len(), 1_600);
    }

    #[test]
    fn level_of_silence_is_zero() {
        assert_eq!(level(&[0; 800]), 0.0);
        assert!(level(&[16_000; 800]) > 0.9);
    }
}
