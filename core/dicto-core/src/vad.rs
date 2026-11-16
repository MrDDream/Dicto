//! Détection d'activité vocale et découpage en énoncés (mode continu).
//!
//! [`EnergyVad`] est un détecteur simple à seuil adaptatif, sans modèle. Le détecteur
//! Silero (via sherpa-onnx) viendra implémenter le même trait [`VoiceDetector`].

use std::collections::VecDeque;

use crate::audio::{rms, samples_for_ms};

pub trait VoiceDetector: Send {
    /// Taille de trame attendue, en échantillons à 16 kHz.
    fn frame_len(&self) -> usize;
    fn is_speech(&mut self, frame: &[f32]) -> bool;
    fn reset(&mut self);
}

impl VoiceDetector for Box<dyn VoiceDetector> {
    fn frame_len(&self) -> usize {
        (**self).frame_len()
    }
    fn is_speech(&mut self, frame: &[f32]) -> bool {
        (**self).is_speech(frame)
    }
    fn reset(&mut self) {
        (**self).reset()
    }
}

/// Détecteur à énergie : une trame est de la voix si son RMS dépasse
/// `ratio ×` le bruit de fond estimé (et un plancher absolu).
#[derive(Debug, Clone)]
pub struct EnergyVad {
    frame_len: usize,
    noise_floor: f32,
    ratio: f32,
    min_level: f32,
}

impl EnergyVad {
    pub fn new(frame_ms: u32) -> Self {
        Self {
            frame_len: samples_for_ms(frame_ms),
            noise_floor: 0.003,
            ratio: 3.0,
            min_level: 0.01,
        }
    }
}

impl Default for EnergyVad {
    fn default() -> Self {
        Self::new(30)
    }
}

impl VoiceDetector for EnergyVad {
    fn frame_len(&self) -> usize {
        self.frame_len
    }

    fn is_speech(&mut self, frame: &[f32]) -> bool {
        let level = rms(frame);
        let speech = level > (self.noise_floor * self.ratio).max(self.min_level);
        // Le bruit de fond suit vite les baisses, lentement les hausses.
        let alpha = if level < self.noise_floor { 0.2 } else if speech { 0.0 } else { 0.05 };
        self.noise_floor += alpha * (level - self.noise_floor);
        speech
    }

    fn reset(&mut self) {
        self.noise_floor = 0.003;
    }
}

#[derive(Debug, Clone)]
pub struct SegmenterConfig {
    /// Durée de voix minimale pour considérer qu'on parle (filtre les clics).
    pub min_speech_ms: u32,
    /// Silence qui termine un énoncé.
    pub silence_ms: u32,
    /// Audio conservé avant le début détecté (pour ne pas couper la première syllabe).
    pub pre_roll_ms: u32,
    /// Silence conservé après la fin.
    pub tail_ms: u32,
    /// Au-delà, l'énoncé est coupé de force.
    pub max_utterance_ms: u32,
}

impl Default for SegmenterConfig {
    fn default() -> Self {
        Self {
            min_speech_ms: 300,
            silence_ms: 700,
            pre_roll_ms: 300,
            tail_ms: 200,
            max_utterance_ms: 30_000,
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub enum VadEvent {
    /// La voix a été confirmée (`min_speech_ms` atteint).
    SpeechStarted,
    /// Un énoncé complet, prêt à transcrire, avec le silence qui le sépare de la fin du
    /// précédent (`None` pour le premier depuis le début de l'écoute).
    Utterance { samples: Vec<f32>, pause_before_ms: Option<u64> },
}

pub struct Segmenter<D: VoiceDetector = EnergyVad> {
    detector: D,
    cfg: SegmenterConfig,
    frame_ms: u32,
    pending: Vec<f32>,
    pre_roll: VecDeque<f32>,
    current: Vec<f32>,
    in_speech: bool,
    confirmed: bool,
    voiced_ms: u32,
    silence_ms: u32,
    /// Temps écoulé depuis le début de l'écoute, en ms d'audio.
    clock_ms: u64,
    /// Début de la voix de l'énoncé en cours.
    onset_ms: u64,
    /// Fin de la voix de l'énoncé précédent.
    last_end_ms: Option<u64>,
}

impl Segmenter<EnergyVad> {
    pub fn energy(cfg: SegmenterConfig) -> Self {
        Self::new(EnergyVad::default(), cfg)
    }
}

impl<D: VoiceDetector> Segmenter<D> {
    pub fn new(detector: D, cfg: SegmenterConfig) -> Self {
        let frame_ms = (detector.frame_len() * 1000 / crate::audio::SAMPLE_RATE as usize) as u32;
        Self {
            detector,
            cfg,
            frame_ms: frame_ms.max(1),
            pending: Vec::new(),
            pre_roll: VecDeque::new(),
            current: Vec::new(),
            in_speech: false,
            confirmed: false,
            voiced_ms: 0,
            silence_ms: 0,
            clock_ms: 0,
            onset_ms: 0,
            last_end_ms: None,
        }
    }

    pub fn is_in_speech(&self) -> bool {
        self.in_speech && self.confirmed
    }

    pub fn push(&mut self, samples: &[f32]) -> Vec<VadEvent> {
        let mut events = Vec::new();
        self.pending.extend_from_slice(samples);
        let n = self.detector.frame_len();
        let full = self.pending.len() / n * n;
        let frames: Vec<f32> = self.pending.drain(..full).collect();
        for frame in frames.chunks_exact(n) {
            self.process_frame(frame, &mut events);
        }
        events
    }

    /// Termine l'énoncé en cours (fin de l'écoute) et le renvoie s'il contient de la voix,
    /// avec la pause qui le précède.
    pub fn flush(&mut self) -> Option<(Vec<f32>, Option<u64>)> {
        let rest = std::mem::take(&mut self.pending);
        if self.in_speech {
            self.current.extend_from_slice(&rest);
        }
        let out = self.finish();
        self.pre_roll.clear();
        self.detector.reset();
        self.clock_ms = 0;
        self.last_end_ms = None;
        out
    }

    fn process_frame(&mut self, frame: &[f32], events: &mut Vec<VadEvent>) {
        let speech = self.detector.is_speech(frame);
        self.clock_ms += self.frame_ms as u64;

        if !self.in_speech {
            if speech {
                self.onset_ms = self.clock_ms - self.frame_ms as u64;
                self.in_speech = true;
                self.confirmed = false;
                self.voiced_ms = self.frame_ms;
                self.silence_ms = 0;
                self.current = self.pre_roll.drain(..).collect();
                self.current.extend_from_slice(frame);
                self.maybe_confirm(events);
            } else {
                self.pre_roll.extend(frame.iter().copied());
                let max = samples_for_ms(self.cfg.pre_roll_ms);
                while self.pre_roll.len() > max {
                    self.pre_roll.pop_front();
                }
            }
            return;
        }

        self.current.extend_from_slice(frame);
        if speech {
            self.voiced_ms += self.frame_ms;
            self.silence_ms = 0;
            self.maybe_confirm(events);
        } else {
            self.silence_ms += self.frame_ms;
        }

        let too_long =
            self.current.len() >= samples_for_ms(self.cfg.max_utterance_ms);
        if self.silence_ms >= self.cfg.silence_ms || too_long {
            if !self.confirmed {
                // Bruit bref : on l'abandonne et on repart en attente.
                self.reset_utterance();
                return;
            }
            if let Some((samples, pause_before_ms)) = self.finish() {
                events.push(VadEvent::Utterance { samples, pause_before_ms });
            }
            if too_long && speech {
                // On continue directement l'énoncé suivant.
                self.onset_ms = self.clock_ms;
                self.in_speech = true;
                self.confirmed = true;
                self.voiced_ms = self.cfg.min_speech_ms;
            }
        }
    }

    fn maybe_confirm(&mut self, events: &mut Vec<VadEvent>) {
        if !self.confirmed && self.voiced_ms >= self.cfg.min_speech_ms {
            self.confirmed = true;
            events.push(VadEvent::SpeechStarted);
        }
    }

    fn finish(&mut self) -> Option<(Vec<f32>, Option<u64>)> {
        if !self.in_speech || !self.confirmed {
            self.reset_utterance();
            return None;
        }
        let mut utt = std::mem::take(&mut self.current);
        // Retire le silence final au-delà de `tail_ms`.
        let trailing = samples_for_ms(self.silence_ms.saturating_sub(self.cfg.tail_ms));
        utt.truncate(utt.len().saturating_sub(trailing));
        let pause = self.last_end_ms.map(|end| self.onset_ms.saturating_sub(end));
        self.last_end_ms = Some(self.clock_ms.saturating_sub(self.silence_ms as u64));
        self.reset_utterance();
        Some((utt, pause))
    }

    fn reset_utterance(&mut self) {
        self.in_speech = false;
        self.confirmed = false;
        self.voiced_ms = 0;
        self.silence_ms = 0;
        self.current.clear();
    }
}

/// Découpe un enregistrement aux silences d'au moins `min_pause_ms` (les paragraphes d'une
/// longue dictée en push-to-talk). Les morceaux se suivent et couvrent tout l'audio ; chaque
/// coupure tombe au milieu d'un silence encadré de voix.
pub fn split_on_pauses(samples: &[f32], min_pause_ms: u32) -> Vec<std::ops::Range<usize>> {
    let mut vad = EnergyVad::default();
    let n = vad.frame_len();
    let frame_ms = (n * 1000 / crate::audio::SAMPLE_RATE as usize) as u32;
    let speech: Vec<bool> = samples.chunks(n).map(|f| vad.is_speech(f)).collect();
    let mut cuts = Vec::new();
    let mut seen_speech = false;
    let mut run_start = None;
    for (i, &s) in speech.iter().enumerate() {
        if s {
            if let Some(start) = run_start.take() {
                if seen_speech && (i - start) as u32 * frame_ms >= min_pause_ms {
                    cuts.push((start + i) / 2 * n);
                }
            }
            seen_speech = true;
        } else if run_start.is_none() {
            run_start = Some(i);
        }
    }
    let mut ranges = Vec::new();
    let mut from = 0;
    for cut in cuts {
        ranges.push(from..cut);
        from = cut;
    }
    ranges.push(from..samples.len());
    ranges
}

/// Détecteur Silero (réseau de neurones, via sherpa-onnx) : bien plus fiable que
/// l'énergie dans le bruit (rue, voiture, ventilation).
#[cfg(feature = "sherpa")]
pub struct SileroVad {
    vad: sherpa_onnx::VoiceActivityDetector,
}

// Utilisé par un seul fil à la fois (la session de dictée est derrière un Mutex).
#[cfg(feature = "sherpa")]
unsafe impl Send for SileroVad {}

#[cfg(feature = "sherpa")]
impl SileroVad {
    pub const WINDOW: usize = 512;

    pub fn load(model: &std::path::Path) -> crate::Result<Self> {
        let mut config = sherpa_onnx::VadModelConfig::default();
        config.silero_vad.model = Some(model.to_string_lossy().into_owned());
        // Un peu au-dessus du réglage par défaut (0,5) : moins de bruits pris pour de la voix.
        config.silero_vad.threshold = 0.6;
        // Le lissage est fait par le Segmenter : ici on veut l'état brut, fenêtre par fenêtre.
        config.silero_vad.min_silence_duration = 0.1;
        config.silero_vad.min_speech_duration = 0.05;
        config.silero_vad.window_size = Self::WINDOW as i32;
        config.silero_vad.max_speech_duration = 60.0;
        config.sample_rate = crate::audio::SAMPLE_RATE as i32;
        config.num_threads = 1;
        let vad = sherpa_onnx::VoiceActivityDetector::create(&config, 30.0)
            .ok_or_else(|| crate::DictoError::Model("détection de voix Silero : chargement impossible".into()))?;
        Ok(Self { vad })
    }
}

#[cfg(feature = "sherpa")]
impl VoiceDetector for SileroVad {
    fn frame_len(&self) -> usize {
        Self::WINDOW
    }

    fn is_speech(&mut self, frame: &[f32]) -> bool {
        self.vad.accept_waveform(frame);
        let speech = self.vad.detected();
        // Les segments complets ne nous servent pas : on vide la file.
        while !self.vad.is_empty() {
            self.vad.pop();
        }
        speech
    }

    fn reset(&mut self) {
        self.vad.clear();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tone(ms: u32, amp: f32) -> Vec<f32> {
        (0..samples_for_ms(ms))
            .map(|i| (i as f32 * 0.3).sin() * amp)
            .collect()
    }

    fn silence(ms: u32) -> Vec<f32> {
        vec![0.0005; samples_for_ms(ms)]
    }

    fn utterances(events: &[VadEvent]) -> Vec<usize> {
        events
            .iter()
            .filter_map(|e| match e {
                VadEvent::Utterance { samples, .. } => Some(samples.len()),
                _ => None,
            })
            .collect()
    }

    #[test]
    fn splits_two_phrases_on_silence() {
        let mut seg = Segmenter::energy(SegmenterConfig::default());
        let mut events = Vec::new();
        events.extend(seg.push(&silence(500)));
        events.extend(seg.push(&tone(1200, 0.3)));
        events.extend(seg.push(&silence(900)));
        events.extend(seg.push(&tone(800, 0.3)));
        events.extend(seg.push(&silence(900)));

        let starts = events.iter().filter(|e| **e == VadEvent::SpeechStarted).count();
        assert_eq!(starts, 2);
        let utts = utterances(&events);
        assert_eq!(utts.len(), 2);
        // ~ pré-roll 300 ms + voix + queue 200 ms (à une trame près).
        let first_ms = crate::audio::ms_for_samples(utts[0]);
        assert!((1600..=1800).contains(&first_ms), "durée = {first_ms} ms");
    }

    #[test]
    fn ignores_short_clicks() {
        let mut seg = Segmenter::energy(SegmenterConfig::default());
        let mut events = seg.push(&silence(300));
        events.extend(seg.push(&tone(60, 0.5)));
        events.extend(seg.push(&silence(1000)));
        assert!(events.is_empty(), "{events:?}");
        assert!(seg.flush().is_none());
    }

    #[test]
    fn flush_returns_ongoing_speech() {
        let mut seg = Segmenter::energy(SegmenterConfig::default());
        seg.push(&tone(600, 0.3));
        assert!(seg.is_in_speech());
        let (u, pause) = seg.flush().expect("énoncé en cours");
        assert!(crate::audio::ms_for_samples(u.len()) >= 570);
        assert_eq!(pause, None);
    }

    #[test]
    fn measures_the_pause_between_utterances() {
        let mut seg = Segmenter::energy(SegmenterConfig::default());
        let mut events = Vec::new();
        events.extend(seg.push(&tone(1000, 0.3)));
        events.extend(seg.push(&silence(3000)));
        events.extend(seg.push(&tone(1000, 0.3)));
        events.extend(seg.push(&silence(1000)));
        let pauses: Vec<Option<u64>> = events
            .iter()
            .filter_map(|e| match e {
                VadEvent::Utterance { pause_before_ms, .. } => Some(*pause_before_ms),
                _ => None,
            })
            .collect();
        assert_eq!(pauses.len(), 2);
        assert_eq!(pauses[0], None);
        let p = pauses[1].unwrap();
        assert!((2900..=3100).contains(&p), "pause = {p} ms");
    }

    #[test]
    fn splits_recordings_on_long_pauses_only() {
        let mut audio = tone(1500, 0.3);
        audio.extend(silence(1000));
        audio.extend(tone(1500, 0.3));
        audio.extend(silence(3000));
        audio.extend(tone(1500, 0.3));
        audio.extend(silence(2500));
        let ranges = split_on_pauses(&audio, 2000);
        assert_eq!(ranges.len(), 2, "{ranges:?}");
        assert_eq!(ranges[0].start, 0);
        assert_eq!(ranges[1].end, audio.len());
        assert_eq!(ranges[0].end, ranges[1].start);
        // La coupure tombe au milieu du silence de 3 s (entre 4 s et 7 s).
        let cut_ms = crate::audio::ms_for_samples(ranges[0].end);
        assert!((5000..=6000).contains(&cut_ms), "coupure à {cut_ms} ms");
        assert_eq!(split_on_pauses(&tone(1000, 0.3), 2000).len(), 1);
    }

    #[test]
    fn cuts_overlong_utterances() {
        let cfg = SegmenterConfig {
            max_utterance_ms: 2_000,
            ..SegmenterConfig::default()
        };
        let mut seg = Segmenter::energy(cfg);
        let events = seg.push(&tone(5_000, 0.3));
        assert_eq!(utterances(&events).len(), 2);
    }
}
