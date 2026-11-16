//! Exports d'une transcription : texte, Markdown, sous-titres SRT et WebVTT.

use crate::Transcript;

fn timestamp(ms: u64, decimal: char) -> String {
    let h = ms / 3_600_000;
    let m = (ms / 60_000) % 60;
    let s = (ms / 1000) % 60;
    let milli = ms % 1000;
    format!("{h:02}:{m:02}:{s:02}{decimal}{milli:03}")
}

fn short_timestamp(ms: u64) -> String {
    let total = ms / 1000;
    let (h, m, s) = (total / 3600, (total / 60) % 60, total % 60);
    if h > 0 { format!("{h}:{m:02}:{s:02}") } else { format!("{m:02}:{s:02}") }
}

pub fn to_txt(t: &Transcript) -> String {
    format!("{}\n", t.text.trim())
}

pub fn to_srt(t: &Transcript) -> String {
    let mut out = String::new();
    for (i, seg) in t.segments.iter().enumerate() {
        out.push_str(&format!(
            "{}\n{} --> {}\n{}\n\n",
            i + 1,
            timestamp(seg.start_ms, ','),
            timestamp(seg.end_ms, ','),
            seg.text.trim()
        ));
    }
    out
}

pub fn to_vtt(t: &Transcript) -> String {
    let mut out = String::from("WEBVTT\n\n");
    for seg in &t.segments {
        let voice = seg.speaker.map(|s| format!("<v Voix {}>", s + 1)).unwrap_or_default();
        out.push_str(&format!(
            "{} --> {}\n{voice}{}\n\n",
            timestamp(seg.start_ms, '.'),
            timestamp(seg.end_ms, '.'),
            seg.text.trim()
        ));
    }
    out
}

/// Markdown lisible : un paragraphe par segment, horodaté, avec le locuteur s'il est connu.
pub fn to_md(t: &Transcript, title: &str) -> String {
    let mut out = format!("# {title}\n\n");
    if t.segments.is_empty() {
        out.push_str(t.text.trim());
        out.push('\n');
        return out;
    }
    for seg in &t.segments {
        let who = seg.speaker.map(|s| format!(" · Voix {}", s + 1)).unwrap_or_default();
        out.push_str(&format!(
            "**{}{who}** — {}\n\n",
            short_timestamp(seg.start_ms),
            seg.text.trim()
        ));
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::Segment;

    fn sample() -> Transcript {
        Transcript {
            text: "Coucou. J'arrive vers 19 h 30.".into(),
            language: Some("fr".into()),
            segments: vec![
                Segment { start_ms: 0, end_ms: 6_000, text: " Coucou.".into(), speaker: Some(0) },
                Segment { start_ms: 6_000, end_ms: 3_725_250, text: "J'arrive vers 19 h 30.".into(), speaker: None },
            ],
        }
    }

    #[test]
    fn srt_format() {
        assert_eq!(
            to_srt(&sample()),
            "1\n00:00:00,000 --> 00:00:06,000\nCoucou.\n\n2\n00:00:06,000 --> 01:02:05,250\nJ'arrive vers 19 h 30.\n\n"
        );
    }

    #[test]
    fn vtt_format() {
        let vtt = to_vtt(&sample());
        assert!(vtt.starts_with("WEBVTT\n\n00:00:00.000 --> 00:00:06.000\n<v Voix 1>Coucou.\n"));
        assert!(vtt.contains("01:02:05.250"));
    }

    #[test]
    fn md_and_txt() {
        let md = to_md(&sample(), "vocal-lea.opus");
        assert!(md.starts_with("# vocal-lea.opus\n\n**00:00 · Voix 1** — Coucou."));
        assert!(md.contains("**00:06** — J'arrive"));
        assert_eq!(to_txt(&sample()), "Coucou. J'arrive vers 19 h 30.\n");
    }
}
