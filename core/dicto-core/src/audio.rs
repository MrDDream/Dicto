//! Utilitaires audio : conversion, mixage mono, rééchantillonnage, WAV.
//!
//! Tous les moteurs travaillent en PCM `f32` mono à [`SAMPLE_RATE`] Hz, valeurs dans [-1, 1].

use std::f32::consts::PI;
use std::io::Cursor;

use crate::{DictoError, Result};

/// Fréquence d'échantillonnage attendue par Whisper et la plupart des modèles STT.
pub const SAMPLE_RATE: u32 = 16_000;

/// Nombre d'échantillons pour une durée donnée à [`SAMPLE_RATE`].
pub const fn samples_for_ms(ms: u32) -> usize {
    (SAMPLE_RATE as usize * ms as usize) / 1000
}

/// Durée en millisecondes de `n` échantillons à [`SAMPLE_RATE`].
pub const fn ms_for_samples(n: usize) -> u64 {
    (n as u64 * 1000) / SAMPLE_RATE as u64
}

pub fn i16_to_f32(input: &[i16]) -> Vec<f32> {
    input.iter().map(|&s| s as f32 / 32768.0).collect()
}

pub fn f32_to_i16(input: &[f32]) -> Vec<i16> {
    input
        .iter()
        .map(|&s| (s.clamp(-1.0, 1.0) * 32767.0).round() as i16)
        .collect()
}

/// Mixe un signal entrelacé multicanal en mono.
pub fn downmix(interleaved: &[f32], channels: u16) -> Vec<f32> {
    let ch = channels.max(1) as usize;
    if ch == 1 {
        return interleaved.to_vec();
    }
    interleaved
        .chunks_exact(ch)
        .map(|frame| frame.iter().sum::<f32>() / ch as f32)
        .collect()
}

/// Rééchantillonne `input` de `from` Hz vers `to` Hz.
///
/// Filtre passe-bas (sinc fenêtré de Hann) puis interpolation linéaire : largement
/// suffisant pour de la voix, sans dépendance externe.
pub fn resample(input: &[f32], from: u32, to: u32) -> Vec<f32> {
    if from == to || input.is_empty() {
        return input.to_vec();
    }
    let filtered;
    let src = if to < from {
        // Coupure légèrement sous la nouvelle fréquence de Nyquist.
        let cutoff = 0.45 * to as f32 / from as f32;
        filtered = lowpass(input, cutoff, 63);
        &filtered[..]
    } else {
        input
    };

    let ratio = from as f64 / to as f64;
    let out_len = ((src.len() as f64) / ratio).floor() as usize;
    let mut out = Vec::with_capacity(out_len);
    for i in 0..out_len {
        let pos = i as f64 * ratio;
        let idx = pos.floor() as usize;
        let frac = (pos - idx as f64) as f32;
        let a = src[idx];
        let b = *src.get(idx + 1).unwrap_or(&a);
        out.push(a + (b - a) * frac);
    }
    out
}

/// FIR passe-bas. `cutoff` est normalisé (fraction de la fréquence d'échantillonnage, < 0.5).
fn lowpass(input: &[f32], cutoff: f32, taps: usize) -> Vec<f32> {
    let m = taps - 1;
    let kernel: Vec<f32> = (0..taps)
        .map(|n| {
            let x = n as f32 - m as f32 / 2.0;
            let sinc = if x == 0.0 {
                2.0 * cutoff
            } else {
                (2.0 * PI * cutoff * x).sin() / (PI * x)
            };
            let window = 0.5 - 0.5 * (2.0 * PI * n as f32 / m as f32).cos();
            sinc * window
        })
        .collect();
    let sum: f32 = kernel.iter().sum();
    let kernel: Vec<f32> = kernel.iter().map(|k| k / sum).collect();

    let half = m / 2;
    (0..input.len())
        .map(|i| {
            let mut acc = 0.0;
            for (k, &coef) in kernel.iter().enumerate() {
                let j = i as isize + k as isize - half as isize;
                if j >= 0 && (j as usize) < input.len() {
                    acc += coef * input[j as usize];
                }
            }
            acc
        })
        .collect()
}

/// Niveau RMS d'une trame (0 = silence, ~0.7 = sinus pleine échelle).
pub fn rms(frame: &[f32]) -> f32 {
    if frame.is_empty() {
        return 0.0;
    }
    (frame.iter().map(|s| s * s).sum::<f32>() / frame.len() as f32).sqrt()
}

/// Encode du PCM mono en WAV 16 bits (pour l'envoi aux services en ligne).
pub fn encode_wav(pcm: &[f32], sample_rate: u32) -> Result<Vec<u8>> {
    let spec = hound::WavSpec {
        channels: 1,
        sample_rate,
        bits_per_sample: 16,
        sample_format: hound::SampleFormat::Int,
    };
    let mut cursor = Cursor::new(Vec::with_capacity(44 + pcm.len() * 2));
    {
        let mut writer =
            hound::WavWriter::new(&mut cursor, spec).map_err(|e| DictoError::Audio(e.to_string()))?;
        for s in f32_to_i16(pcm) {
            writer
                .write_sample(s)
                .map_err(|e| DictoError::Audio(e.to_string()))?;
        }
        writer
            .finalize()
            .map_err(|e| DictoError::Audio(e.to_string()))?;
    }
    Ok(cursor.into_inner())
}

/// Lit un WAV (n'importe quel nombre de canaux / fréquence) et le ramène en mono 16 kHz.
pub fn decode_wav(bytes: &[u8]) -> Result<Vec<f32>> {
    let mut reader =
        hound::WavReader::new(Cursor::new(bytes)).map_err(|e| DictoError::Audio(e.to_string()))?;
    let spec = reader.spec();
    let samples: Vec<f32> = match spec.sample_format {
        hound::SampleFormat::Float => reader
            .samples::<f32>()
            .collect::<std::result::Result<_, _>>()
            .map_err(|e| DictoError::Audio(e.to_string()))?,
        hound::SampleFormat::Int => {
            let scale = (1i64 << (spec.bits_per_sample - 1)) as f32;
            reader
                .samples::<i32>()
                .map(|s| s.map(|v| v as f32 / scale))
                .collect::<std::result::Result<_, _>>()
                .map_err(|e| DictoError::Audio(e.to_string()))?
        }
    };
    let mono = downmix(&samples, spec.channels);
    Ok(resample(&mono, spec.sample_rate, SAMPLE_RATE))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sine(freq: f32, sr: u32, secs: f32) -> Vec<f32> {
        let n = (sr as f32 * secs) as usize;
        (0..n)
            .map(|i| (2.0 * PI * freq * i as f32 / sr as f32).sin() * 0.5)
            .collect()
    }

    #[test]
    fn resample_48k_to_16k_keeps_duration_and_level() {
        let input = sine(440.0, 48_000, 1.0);
        let out = resample(&input, 48_000, 16_000);
        assert_eq!(out.len(), 16_000);
        let level = rms(&out[1000..15000]);
        assert!((level - 0.5 / 2f32.sqrt()).abs() < 0.02, "rms = {level}");
    }

    #[test]
    fn resample_removes_frequencies_above_nyquist() {
        // 7 kHz passe à 16 kHz (Nyquist 8 kHz) ; 12 kHz doit être fortement atténué.
        let kept = resample(&sine(3_000.0, 48_000, 0.5), 48_000, 16_000);
        let cut = resample(&sine(12_000.0, 48_000, 0.5), 48_000, 16_000);
        assert!(rms(&kept[500..7500]) > 0.3);
        assert!(rms(&cut[500..7500]) < 0.05, "rms = {}", rms(&cut[500..7500]));
    }

    #[test]
    fn downmix_stereo() {
        assert_eq!(downmix(&[1.0, 0.0, 0.5, 0.5], 2), vec![0.5, 0.5]);
    }

    #[test]
    fn wav_roundtrip() {
        let pcm = sine(300.0, SAMPLE_RATE, 0.25);
        let wav = encode_wav(&pcm, SAMPLE_RATE).unwrap();
        let back = decode_wav(&wav).unwrap();
        assert_eq!(back.len(), pcm.len());
        assert!(pcm.iter().zip(&back).all(|(a, b)| (a - b).abs() < 1e-3));
    }
}
