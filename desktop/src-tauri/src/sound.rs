//! Petits sons de début et de fin de dictée, synthétisés (deux notes douces) et joués sur la
//! sortie audio par défaut, sur un fil à part.

use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use cpal::{SampleFormat, StreamConfig};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Cue {
    /// Note montante : Dicto écoute.
    Start,
    /// Note descendante : l'écoute est coupée.
    Stop,
}

/// Les deux notes (Hz) de chaque son.
fn notes(cue: Cue) -> [f32; 2] {
    match cue {
        Cue::Start => [660.0, 880.0],
        Cue::Stop => [880.0, 587.0],
    }
}

const NOTE_MS: u32 = 70;
const VOLUME: f32 = 0.16;

/// Échantillons mono du son, à la fréquence `rate` : deux notes avec fondu d'entrée et de sortie.
pub fn samples(cue: Cue, rate: u32) -> Vec<f32> {
    let per_note = (rate * NOTE_MS / 1000) as usize;
    let fade = (rate / 200) as usize; // 5 ms
    let mut out = Vec::with_capacity(per_note * 2);
    for freq in notes(cue) {
        for i in 0..per_note {
            let t = i as f32 / rate as f32;
            let edge = i.min(per_note - 1 - i);
            let envelope = if edge < fade { edge as f32 / fade as f32 } else { 1.0 };
            out.push((t * freq * std::f32::consts::TAU).sin() * VOLUME * envelope);
        }
    }
    out
}

/// Joue le son sans attendre ; une erreur (pas de sortie audio) est seulement notée.
pub fn play(cue: Cue) {
    let _ = std::thread::Builder::new().name("dicto-son".into()).spawn(move || {
        if let Err(e) = play_blocking(cue) {
            log::debug!("son impossible : {e}");
        }
    });
}

fn play_blocking(cue: Cue) -> Result<(), String> {
    let device = cpal::default_host().default_output_device().ok_or("aucune sortie audio")?;
    let supported = device.default_output_config().map_err(|e| e.to_string())?;
    let format = supported.sample_format();
    let config: StreamConfig = supported.into();
    let channels = config.channels as usize;
    let mono = samples(cue, config.sample_rate);
    let duration_ms = (mono.len() as u64 * 1000) / config.sample_rate.max(1) as u64;
    let mut pos = 0usize;
    let mut next = move || {
        let s = mono.get(pos / channels).copied().unwrap_or(0.0);
        pos += 1;
        s
    };
    let err = |e: cpal::Error| log::debug!("sortie audio : {e}");
    let stream = match format {
        SampleFormat::F32 => device.build_output_stream::<f32, _, _>(
            config,
            move |out, _| out.iter_mut().for_each(|o| *o = next()),
            err,
            None,
        ),
        SampleFormat::I16 => device.build_output_stream::<i16, _, _>(
            config,
            move |out, _| out.iter_mut().for_each(|o| *o = (next() * 32767.0) as i16),
            err,
            None,
        ),
        SampleFormat::U16 => device.build_output_stream::<u16, _, _>(
            config,
            move |out, _| out.iter_mut().for_each(|o| *o = ((next() * 32767.0) + 32768.0) as u16),
            err,
            None,
        ),
        SampleFormat::I32 => device.build_output_stream::<i32, _, _>(
            config,
            move |out, _| out.iter_mut().for_each(|o| *o = (next() * 2_147_483_647.0) as i32),
            err,
            None,
        ),
        other => return Err(format!("format de sortie non pris en charge : {other:?}")),
    }
    .map_err(|e| e.to_string())?;
    stream.play().map_err(|e| e.to_string())?;
    std::thread::sleep(std::time::Duration::from_millis(duration_ms + 80));
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tones_are_short_soft_and_faded() {
        let s = samples(Cue::Start, 48_000);
        assert_eq!(s.len(), 48_000 * 2 * NOTE_MS as usize / 1000);
        assert!(s.iter().all(|x| x.abs() <= VOLUME + 1e-6));
        // Pas de clic : les bords sont silencieux.
        assert!(s[0].abs() < 1e-6 && s.last().unwrap().abs() < 0.01);
        assert_ne!(samples(Cue::Start, 16_000), samples(Cue::Stop, 16_000));
    }
}
