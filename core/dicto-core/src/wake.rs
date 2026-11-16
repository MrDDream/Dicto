//! Mot d'activation (« OK Dicto ») avec le keyword spotting de sherpa-onnx.
//!
//! La phrase est donnée en texte : sherpa-onnx la découpe en jetons BPE à l'exécution
//! grâce à `bpe.vocab`, que l'on génère à partir du `bpe.model` (SentencePiece) du modèle.

use crate::{DictoError, Result};

/// Extrait la liste « jeton<TAB>score » d'un modèle SentencePiece (protobuf `ModelProto`).
pub fn bpe_vocab_from_model(model: &[u8]) -> Result<String> {
    fn varint(buf: &[u8], pos: &mut usize) -> Option<u64> {
        let mut out = 0u64;
        for shift in (0..64).step_by(7) {
            let b = *buf.get(*pos)?;
            *pos += 1;
            out |= ((b & 0x7f) as u64) << shift;
            if b & 0x80 == 0 {
                return Some(out);
            }
        }
        None
    }
    fn skip(buf: &[u8], pos: &mut usize, wire: u64) -> Option<()> {
        match wire {
            0 => varint(buf, pos).map(|_| ()),
            1 => (*pos + 8 <= buf.len()).then(|| *pos += 8),
            2 => {
                let len = varint(buf, pos)? as usize;
                (*pos + len <= buf.len()).then(|| *pos += len)
            }
            5 => (*pos + 4 <= buf.len()).then(|| *pos += 4),
            _ => None,
        }
    }
    let bad = || DictoError::Model("bpe.model illisible".into());
    let mut out = String::new();
    let mut pos = 0;
    while pos < model.len() {
        let key = varint(model, &mut pos).ok_or_else(bad)?;
        let (field, wire) = (key >> 3, key & 7);
        if field == 1 && wire == 2 {
            // SentencePiece { 1: piece, 2: score, 3: type }
            let len = varint(model, &mut pos).ok_or_else(bad)? as usize;
            let end = pos.checked_add(len).filter(|&e| e <= model.len()).ok_or_else(bad)?;
            let (mut piece, mut score) = (String::new(), 0f32);
            while pos < end {
                let k = varint(model, &mut pos).ok_or_else(bad)?;
                match (k >> 3, k & 7) {
                    (1, 2) => {
                        let l = varint(model, &mut pos).ok_or_else(bad)? as usize;
                        let bytes = model.get(pos..pos + l).ok_or_else(bad)?;
                        piece = String::from_utf8_lossy(bytes).into_owned();
                        pos += l;
                    }
                    (2, 5) => {
                        let b = model.get(pos..pos + 4).ok_or_else(bad)?;
                        score = f32::from_le_bytes([b[0], b[1], b[2], b[3]]);
                        pos += 4;
                    }
                    (_, w) => skip(model, &mut pos, w).ok_or_else(bad)?,
                }
            }
            out.push_str(&format!("{piece}\t{score}\n"));
        } else {
            skip(model, &mut pos, wire).ok_or_else(bad)?;
        }
    }
    if out.is_empty() {
        return Err(bad());
    }
    Ok(out)
}

/// Découpe une phrase en jetons comme SentencePiece (modèle unigram, celui d'icefall) :
/// chaque mot reçoit le préfixe « ▁ », puis on choisit la segmentation dont la somme des
/// scores (log-probabilités) est maximale (Viterbi). Un caractère inconnu devient `<unk>`.
pub fn bpe_encode(vocab: &std::collections::HashMap<String, f32>, text: &str) -> Vec<String> {
    const UNK_PENALTY: f32 = -100.0;
    let mut out = Vec::new();
    for word in text.split_whitespace() {
        let chars: Vec<char> = std::iter::once('▁').chain(word.chars()).collect();
        let n = chars.len();
        // best[i] = (score, début du dernier morceau) pour le préfixe de longueur i.
        let mut best: Vec<(f32, usize)> = vec![(f32::NEG_INFINITY, 0); n + 1];
        best[0] = (0.0, 0);
        for end in 1..=n {
            for start in end.saturating_sub(24)..end {
                if best[start].0 == f32::NEG_INFINITY {
                    continue;
                }
                let piece: String = chars[start..end].iter().collect();
                let score = match vocab.get(&piece) {
                    Some(s) => *s,
                    None if end - start == 1 => UNK_PENALTY,
                    None => continue,
                };
                let total = best[start].0 + score;
                if total > best[end].0 {
                    best[end] = (total, start);
                }
            }
        }
        let mut pieces = Vec::new();
        let mut end = n;
        while end > 0 {
            let start = best[end].1;
            let piece: String = chars[start..end].iter().collect();
            pieces.push(if vocab.contains_key(&piece) { piece } else { "<unk>".to_string() });
            end = start;
        }
        pieces.reverse();
        out.extend(pieces);
    }
    out
}

/// Lit un fichier « jeton<TAB>score ».
pub fn parse_vocab(text: &str) -> std::collections::HashMap<String, f32> {
    text.lines()
        .filter_map(|l| {
            let (piece, score) = l.split_once('\t')?;
            Some((piece.to_string(), score.trim().parse().ok()?))
        })
        .collect()
}

/// Phrase(s) d'appel normalisées pour le modèle anglais GigaSpeech : majuscules, sans ponctuation.
pub fn normalize_phrase(phrase: &str) -> String {
    phrase
        .chars()
        .map(|c| if c.is_alphanumeric() || c == '\'' { c.to_ascii_uppercase() } else { ' ' })
        .collect::<String>()
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
}

#[cfg(feature = "sherpa")]
mod spotter {
    use std::sync::Mutex;

    use sherpa_onnx::{KeywordSpotter, KeywordSpotterConfig, OnlineStream};

    use std::path::Path;

    use super::*;
    use crate::audio::SAMPLE_RATE;

    struct Shared(KeywordSpotter, OnlineStream);
    // Accès sérialisé par le Mutex de WakeWord.
    unsafe impl Send for Shared {}

    pub struct WakeWord {
        inner: Mutex<Shared>,
    }

    fn find(dir: &Path, part: &str) -> Result<String> {
        let mut files: Vec<_> = std::fs::read_dir(dir)?
            .filter_map(|e| e.ok().map(|e| e.path()))
            .filter(|p| p.file_name().and_then(|n| n.to_str()).is_some_and(|n| n.starts_with(part) && n.ends_with(".onnx")))
            .collect();
        files.sort_by_key(|p| !p.to_string_lossy().contains("int8"));
        files
            .first()
            .map(|p| p.to_string_lossy().into_owned())
            .ok_or_else(|| DictoError::Model(format!("{part}*.onnx introuvable dans {}", dir.display())))
    }

    impl WakeWord {
        /// `sensitivity` : 0 (strict) à 1 (sensible).
        pub fn load(dir: &Path, phrases: &[String], sensitivity: f32) -> Result<Self> {
            let vocab = dir.join("bpe.vocab");
            if !vocab.exists() {
                let model = std::fs::read(dir.join("bpe.model"))?;
                std::fs::write(&vocab, bpe_vocab_from_model(&model)?)?;
            }
            let vocab = parse_vocab(&std::fs::read_to_string(&vocab)?);
            // Format sherpa-onnx : « ▁O K ▁D IC TO @OK_DICTO » (le libellé ne contient pas d'espace).
            let keywords: Vec<String> = phrases
                .iter()
                .map(|p| normalize_phrase(p))
                .filter(|p| !p.is_empty())
                .map(|p| format!("{} @{}", bpe_encode(&vocab, &p).join(" "), p.replace(' ', "_")))
                .collect();
            if keywords.is_empty() {
                return Err(DictoError::Model("phrase d'appel vide".into()));
            }
            let mut config = KeywordSpotterConfig::default();
            config.model_config.transducer.encoder = Some(find(dir, "encoder")?);
            config.model_config.transducer.decoder = Some(find(dir, "decoder")?);
            config.model_config.transducer.joiner = Some(find(dir, "joiner")?);
            config.model_config.tokens = Some(dir.join("tokens.txt").to_string_lossy().into_owned());
            config.model_config.num_threads = 1;
            config.keywords_buf = Some(keywords.join("\n"));
            config.keywords_threshold = 0.4 - 0.3 * sensitivity.clamp(0.0, 1.0);
            config.keywords_score = 1.5;
            let spotter = KeywordSpotter::create(&config)
                .ok_or_else(|| DictoError::Model("mot d'activation : chargement impossible".into()))?;
            let stream = spotter.create_stream();
            Ok(Self { inner: Mutex::new(Shared(spotter, stream)) })
        }

        /// Analyse un bloc d'audio 16 kHz ; renvoie la phrase détectée, le cas échéant.
        pub fn push(&self, pcm: &[f32]) -> Option<String> {
            let guard = self.inner.lock().ok()?;
            let Shared(spotter, stream) = &*guard;
            stream.accept_waveform(SAMPLE_RATE as i32, pcm);
            while spotter.is_ready(stream) {
                spotter.decode(stream);
                if let Some(r) = spotter.get_result(stream) {
                    if !r.keyword.is_empty() {
                        spotter.reset(stream);
                        return Some(r.keyword.replace('_', " "));
                    }
                }
            }
            None
        }
    }
}

#[cfg(feature = "sherpa")]
pub use spotter::WakeWord;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn segmentation_maximizes_score() {
        let vocab: std::collections::HashMap<String, f32> = [
            ("▁", -3.0), ("L", -5.0), ("I", -5.0), ("G", -6.0), ("H", -6.0), ("T", -5.0),
            ("IGHT", -6.6), ("▁LI", -6.6), ("▁UP", -6.0), ("U", -6.0), ("P", -6.0),
        ]
        .into_iter()
        .map(|(k, v)| (k.to_string(), v))
        .collect();
        assert_eq!(bpe_encode(&vocab, "LIGHT UP"), ["▁", "L", "IGHT", "▁UP"]);
        assert_eq!(bpe_encode(&vocab, "Z"), ["▁", "<unk>"]);
    }

    #[test]
    fn normalizes_phrases() {
        assert_eq!(normalize_phrase("Ok, Dicto !"), "OK DICTO");
        assert_eq!(normalize_phrase("  hey   dicto"), "HEY DICTO");
    }

    #[test]
    fn parses_sentencepiece_pieces() {
        // ModelProto { pieces: [{piece:"<unk>", score:0}, {piece:"▁OK", score:-1.5}] }
        fn piece(p: &str, score: f32) -> Vec<u8> {
            let mut inner = vec![0x0a, p.len() as u8];
            inner.extend_from_slice(p.as_bytes());
            inner.push(0x15);
            inner.extend_from_slice(&score.to_le_bytes());
            let mut out = vec![0x0a, inner.len() as u8];
            out.extend(inner);
            out
        }
        let mut model = piece("<unk>", 0.0);
        model.extend(piece("▁OK", -1.5));
        model.extend([0x12, 0x02, 0x08, 0x01]); // champ 2 (trainer_spec) ignoré
        assert_eq!(bpe_vocab_from_model(&model).unwrap(), "<unk>\t0\n▁OK\t-1.5\n");
        assert!(bpe_vocab_from_model(&[0xff]).is_err());
    }
}
