//! Moteur local whisper.cpp (feature `whisper`).

use std::path::Path;
use std::sync::Arc;

use async_trait::async_trait;
use whisper_rs::{FullParams, SamplingStrategy, WhisperContext, WhisperContextParameters};

use super::{SttEngine, TranscribeOptions};
use crate::{DictoError, Result, Segment, Transcript};

#[derive(Debug, Clone)]
pub struct WhisperLoadOptions {
    pub use_gpu: bool,
    /// 0 = nombre de cœurs disponibles (plafonné à 8).
    pub threads: i32,
}

impl Default for WhisperLoadOptions {
    fn default() -> Self {
        Self {
            use_gpu: true,
            threads: 0,
        }
    }
}

pub struct WhisperEngine {
    ctx: Arc<WhisperContext>,
    model_id: String,
    threads: i32,
}

impl WhisperEngine {
    pub fn load(path: &Path, model_id: &str, opts: WhisperLoadOptions) -> Result<Self> {
        let mut params = WhisperContextParameters::default();
        params.use_gpu(opts.use_gpu);
        let ctx = WhisperContext::new_with_params(path, params)
            .map_err(|e| DictoError::Model(format!("chargement de {model_id} : {e}")))?;
        let threads = if opts.threads > 0 {
            opts.threads
        } else {
            std::thread::available_parallelism()
                .map(|n| n.get().min(8) as i32)
                .unwrap_or(4)
        };
        Ok(Self {
            ctx: Arc::new(ctx),
            model_id: model_id.to_string(),
            threads,
        })
    }
}

#[async_trait]
impl SttEngine for WhisperEngine {
    fn id(&self) -> String {
        format!("local:{}", self.model_id)
    }

    fn is_local(&self) -> bool {
        true
    }

    async fn transcribe(&self, pcm: &[f32], opts: &TranscribeOptions) -> Result<Transcript> {
        let ctx = self.ctx.clone();
        let pcm = pcm.to_vec();
        let opts = opts.clone();
        let threads = self.threads;
        tokio::task::spawn_blocking(move || run(&ctx, &pcm, &opts, threads))
            .await
            .map_err(|e| DictoError::Model(format!("tâche whisper interrompue : {e}")))?
    }
}

fn run(ctx: &WhisperContext, pcm: &[f32], opts: &TranscribeOptions, threads: i32) -> Result<Transcript> {
    let err = |e: whisper_rs::WhisperError| DictoError::Model(e.to_string());
    let mut state = ctx.create_state().map_err(err)?;

    let mut params = FullParams::new(SamplingStrategy::Greedy { best_of: 1 });
    params.set_n_threads(threads);
    params.set_language(Some(opts.language.as_deref().unwrap_or("auto")));
    if let Some(prompt) = &opts.prompt {
        params.set_initial_prompt(prompt);
    }
    params.set_no_context(true);
    params.set_suppress_blank(true);
    params.set_print_special(false);
    params.set_print_progress(false);
    params.set_print_realtime(false);
    params.set_print_timestamps(false);

    state.full(params, pcm).map_err(err)?;

    let mut segments = Vec::new();
    for segment in state.as_iter() {
        let text = segment.to_str_lossy().map_err(err)?.trim().to_string();
        if text.is_empty() {
            continue;
        }
        // whisper.cpp compte en centièmes de seconde.
        segments.push(Segment {
            start_ms: (segment.start_timestamp().max(0) as u64) * 10,
            end_ms: (segment.end_timestamp().max(0) as u64) * 10,
            text,
            speaker: None,
        });
    }
    let language = whisper_rs::get_lang_str(state.full_lang_id_from_state()).map(str::to_string);

    let mut transcript = Transcript {
        text: String::new(),
        language,
        segments,
    };
    transcript.rebuild_text();
    if !opts.timestamps {
        transcript.segments.clear();
    }
    Ok(transcript)
}
