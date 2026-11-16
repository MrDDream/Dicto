//! Machine à états des trois modes de dictée.
//!
//! Les plateformes traduisent leurs déclencheurs (raccourci clavier, bouton de la
//! bulle, tuile, Volume−, mot d'activation…) en [`Trigger::Pressed`] /
//! [`Trigger::Released`], poussent l'audio capturé, et exécutent les [`Action`]s
//! renvoyées. La session est purement synchrone et déterministe : les durées sont
//! mesurées en échantillons reçus, pas en temps d'horloge.

use serde::{Deserialize, Serialize};

use crate::audio::{ms_for_samples, samples_for_ms};
use crate::vad::{EnergyVad, Segmenter, SegmenterConfig, VadEvent, VoiceDetector};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Mode {
    /// Un appui pour commencer, un second pour terminer.
    PushToTalk,
    /// Écoute permanente, chaque phrase est transcrite après une pause.
    Continuous,
    /// On parle tant que le déclencheur est maintenu.
    Hold,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Trigger {
    Pressed,
    Released,
    Cancel,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum State {
    Idle,
    /// Push-to-talk ou maintenu : enregistrement en cours.
    Recording,
    /// Mode continu : micro ouvert, en attente de voix.
    Listening,
    /// Mode continu : une phrase est en cours.
    Speaking,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DiscardReason {
    Cancelled,
    TooShort,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Utterance {
    pub id: u64,
    pub samples: Vec<f32>,
    /// Mode continu : silence depuis la phrase précédente (`None` pour la première).
    pub pause_before_ms: Option<u64>,
    /// Push-to-talk ou maintenu : tout l'enregistrement d'un coup (peut contenir des pauses).
    pub whole_recording: bool,
}

impl Utterance {
    pub fn duration_ms(&self) -> u64 {
        ms_for_samples(self.samples.len())
    }
}

#[derive(Debug, Clone, PartialEq)]
pub enum Action {
    StartCapture,
    StopCapture,
    StateChanged(State),
    Transcribe(Utterance),
    Discarded(DiscardReason),
}

#[derive(Debug, Clone)]
pub struct SessionConfig {
    pub mode: Mode,
    /// En dessous, un enregistrement push-to-talk ou maintenu est ignoré (appui accidentel).
    pub min_recording_ms: u32,
    /// Au-delà, l'enregistrement push-to-talk ou maintenu est arrêté et transcrit.
    pub max_recording_ms: u32,
    pub segmenter: SegmenterConfig,
}

impl Default for SessionConfig {
    fn default() -> Self {
        Self {
            mode: Mode::PushToTalk,
            min_recording_ms: 300,
            max_recording_ms: 10 * 60 * 1000,
            segmenter: SegmenterConfig::default(),
        }
    }
}

pub struct Session {
    cfg: SessionConfig,
    state: State,
    buffer: Vec<f32>,
    segmenter: Segmenter<Box<dyn VoiceDetector>>,
    next_id: u64,
}

impl Session {
    pub fn new(cfg: SessionConfig) -> Self {
        let segmenter = Segmenter::new(Box::new(EnergyVad::default()) as Box<dyn VoiceDetector>, cfg.segmenter.clone());
        Self {
            cfg,
            state: State::Idle,
            buffer: Vec::new(),
            segmenter,
            next_id: 1,
        }
    }

    pub fn state(&self) -> State {
        self.state
    }

    /// Remplace le détecteur de voix du mode continu (ex. Silero). Sans effet pendant une dictée.
    pub fn set_detector(&mut self, detector: Box<dyn VoiceDetector>) -> bool {
        if self.is_active() {
            return false;
        }
        self.segmenter = Segmenter::new(detector, self.cfg.segmenter.clone());
        true
    }

    pub fn mode(&self) -> Mode {
        self.cfg.mode
    }

    pub fn is_active(&self) -> bool {
        self.state != State::Idle
    }

    /// Change de mode. Une dictée en cours est annulée.
    pub fn set_mode(&mut self, mode: Mode) -> Vec<Action> {
        let mut actions = Vec::new();
        if mode != self.cfg.mode && self.is_active() {
            actions = self.cancel();
        }
        self.cfg.mode = mode;
        actions
    }

    pub fn trigger(&mut self, trigger: Trigger) -> Vec<Action> {
        if trigger == Trigger::Cancel {
            return if self.is_active() { self.cancel() } else { Vec::new() };
        }
        match (self.cfg.mode, self.state, trigger) {
            (Mode::PushToTalk | Mode::Hold, State::Idle, Trigger::Pressed) => {
                self.start(State::Recording)
            }
            (Mode::PushToTalk, State::Recording, Trigger::Pressed)
            | (Mode::Hold, State::Recording, Trigger::Released) => self.stop_recording(),

            (Mode::Continuous, State::Idle, Trigger::Pressed) => self.start(State::Listening),
            (Mode::Continuous, State::Listening | State::Speaking, Trigger::Pressed) => {
                self.stop_listening()
            }
            _ => Vec::new(),
        }
    }

    pub fn push_audio(&mut self, samples: &[f32]) -> Vec<Action> {
        match self.state {
            State::Idle => Vec::new(),
            State::Recording => {
                self.buffer.extend_from_slice(samples);
                if self.buffer.len() >= samples_for_ms(self.cfg.max_recording_ms) {
                    self.stop_recording()
                } else {
                    Vec::new()
                }
            }
            State::Listening | State::Speaking => {
                let mut actions = Vec::new();
                for event in self.segmenter.push(samples) {
                    match event {
                        VadEvent::SpeechStarted => self.set_state(State::Speaking, &mut actions),
                        VadEvent::Utterance { samples, pause_before_ms } => {
                            let u = self.utterance(samples, pause_before_ms, false);
                            actions.push(Action::Transcribe(u));
                            self.set_state(State::Listening, &mut actions);
                        }
                    }
                }
                actions
            }
        }
    }

    fn start(&mut self, state: State) -> Vec<Action> {
        self.buffer.clear();
        let mut actions = vec![Action::StartCapture];
        self.set_state(state, &mut actions);
        actions
    }

    fn stop_recording(&mut self) -> Vec<Action> {
        let mut actions = vec![Action::StopCapture];
        let samples = std::mem::take(&mut self.buffer);
        if ms_for_samples(samples.len()) < self.cfg.min_recording_ms as u64 {
            actions.push(Action::Discarded(DiscardReason::TooShort));
        } else {
            let u = self.utterance(samples, None, true);
            actions.push(Action::Transcribe(u));
        }
        self.set_state(State::Idle, &mut actions);
        actions
    }

    fn stop_listening(&mut self) -> Vec<Action> {
        let mut actions = vec![Action::StopCapture];
        if let Some((samples, pause_before_ms)) = self.segmenter.flush() {
            let u = self.utterance(samples, pause_before_ms, false);
            actions.push(Action::Transcribe(u));
        }
        self.set_state(State::Idle, &mut actions);
        actions
    }

    fn cancel(&mut self) -> Vec<Action> {
        self.buffer.clear();
        let _ = self.segmenter.flush();
        let mut actions = vec![
            Action::StopCapture,
            Action::Discarded(DiscardReason::Cancelled),
        ];
        self.set_state(State::Idle, &mut actions);
        actions
    }

    fn utterance(&mut self, samples: Vec<f32>, pause_before_ms: Option<u64>, whole_recording: bool) -> Utterance {
        let id = self.next_id;
        self.next_id += 1;
        Utterance { id, samples, pause_before_ms, whole_recording }
    }

    fn set_state(&mut self, state: State, actions: &mut Vec<Action>) {
        if self.state != state {
            self.state = state;
            actions.push(Action::StateChanged(state));
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn session(mode: Mode) -> Session {
        Session::new(SessionConfig {
            mode,
            ..SessionConfig::default()
        })
    }

    fn voice(ms: u32) -> Vec<f32> {
        (0..samples_for_ms(ms))
            .map(|i| (i as f32 * 0.3).sin() * 0.3)
            .collect()
    }

    fn silence(ms: u32) -> Vec<f32> {
        vec![0.0; samples_for_ms(ms)]
    }

    fn transcribed(actions: &[Action]) -> Vec<u64> {
        actions
            .iter()
            .filter_map(|a| match a {
                Action::Transcribe(u) => Some(u.duration_ms()),
                _ => None,
            })
            .collect()
    }

    #[test]
    fn push_to_talk_toggles() {
        let mut s = session(Mode::PushToTalk);
        assert_eq!(
            s.trigger(Trigger::Pressed),
            vec![Action::StartCapture, Action::StateChanged(State::Recording)]
        );
        // Le relâchement n'arrête pas le push-to-talk.
        assert!(s.trigger(Trigger::Released).is_empty());
        s.push_audio(&voice(1500));
        let actions = s.trigger(Trigger::Pressed);
        assert_eq!(actions[0], Action::StopCapture);
        assert_eq!(transcribed(&actions), vec![1500]);
        assert_eq!(s.state(), State::Idle);
    }

    #[test]
    fn hold_stops_on_release_and_ignores_accidental_taps() {
        let mut s = session(Mode::Hold);
        s.trigger(Trigger::Pressed);
        s.push_audio(&voice(100));
        let actions = s.trigger(Trigger::Released);
        assert!(actions.contains(&Action::Discarded(DiscardReason::TooShort)));

        s.trigger(Trigger::Pressed);
        assert!(s.trigger(Trigger::Pressed).is_empty(), "appui répété ignoré");
        s.push_audio(&voice(2000));
        assert_eq!(transcribed(&s.trigger(Trigger::Released)), vec![2000]);
    }

    #[test]
    fn continuous_emits_one_utterance_per_phrase() {
        let mut s = session(Mode::Continuous);
        assert_eq!(
            s.trigger(Trigger::Pressed),
            vec![Action::StartCapture, Action::StateChanged(State::Listening)]
        );
        let mut actions = s.push_audio(&silence(400));
        actions.extend(s.push_audio(&voice(1000)));
        assert_eq!(s.state(), State::Speaking);
        actions.extend(s.push_audio(&silence(1000)));
        actions.extend(s.push_audio(&voice(800)));
        assert_eq!(transcribed(&actions).len(), 1);

        // Arrêt pendant la seconde phrase : elle est transcrite aussi.
        let stop = s.trigger(Trigger::Pressed);
        assert_eq!(stop[0], Action::StopCapture);
        assert_eq!(transcribed(&stop).len(), 1);
        assert_eq!(s.state(), State::Idle);
    }

    #[test]
    fn cancel_discards_everything() {
        let mut s = session(Mode::PushToTalk);
        s.trigger(Trigger::Pressed);
        s.push_audio(&voice(2000));
        let actions = s.trigger(Trigger::Cancel);
        assert!(actions.contains(&Action::Discarded(DiscardReason::Cancelled)));
        assert!(transcribed(&actions).is_empty());
        assert!(s.trigger(Trigger::Cancel).is_empty());
    }

    #[test]
    fn max_duration_auto_stops() {
        let mut s = Session::new(SessionConfig {
            mode: Mode::PushToTalk,
            max_recording_ms: 3_000,
            ..SessionConfig::default()
        });
        s.trigger(Trigger::Pressed);
        assert!(s.push_audio(&voice(2_000)).is_empty());
        let actions = s.push_audio(&voice(1_500));
        assert_eq!(transcribed(&actions), vec![3_500]);
        assert_eq!(s.state(), State::Idle);
    }

    #[test]
    fn switching_mode_cancels_active_dictation() {
        let mut s = session(Mode::PushToTalk);
        s.trigger(Trigger::Pressed);
        let actions = s.set_mode(Mode::Hold);
        assert!(actions.contains(&Action::Discarded(DiscardReason::Cancelled)));
        assert_eq!(s.mode(), Mode::Hold);
    }

    #[test]
    fn audio_ignored_when_idle() {
        let mut s = session(Mode::Continuous);
        assert!(s.push_audio(&voice(2000)).is_empty());
    }
}
