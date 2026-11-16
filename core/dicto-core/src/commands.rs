//! Commandes vocales locales (sans LLM) et filtrage des hallucinations de Whisper.
//!
//! « Bonjour, nouvelle ligne. Comment ça va ? » devient
//! `[Text("Bonjour"), Newline, Text("Comment ça va ?")]`.

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", content = "text", rename_all = "snake_case")]
pub enum Edit {
    Text(String),
    Newline,
    Paragraph,
    /// Supprimer le texte inséré précédemment (l'énoncé précédent).
    DeletePrevious,
}

#[derive(Clone, Copy)]
enum Command {
    Newline,
    Paragraph,
    Delete,
}

const COMMANDS: &[(&str, Command)] = &[
    // Français
    ("nouveau paragraphe", Command::Paragraph),
    ("nouvelle ligne", Command::Newline),
    ("à la ligne", Command::Newline),
    ("retour à la ligne", Command::Newline),
    ("efface ça", Command::Delete),
    ("efface ca", Command::Delete),
    ("supprime ça", Command::Delete),
    ("supprime ca", Command::Delete),
    // English
    ("new paragraph", Command::Paragraph),
    ("new line", Command::Newline),
    ("newline", Command::Newline),
    ("scratch that", Command::Delete),
    ("delete that", Command::Delete),
];

const EDGE_PUNCT: &[char] = &[
    '.', ',', ';', ':', '!', '?', '…', '«', '»', '"', '“', '”', '(', ')', '¿', '¡',
];

fn normalize(word: &str) -> String {
    word.trim_matches(|c: char| EDGE_PUNCT.contains(&c) || c == '\'' || c == '’')
        .to_lowercase()
        .replace('’', "'")
}

fn phrase_words(phrase: &str) -> Vec<String> {
    phrase.split_whitespace().map(normalize).collect()
}

/// Découpe un texte transcrit en morceaux de texte et commandes.
pub fn parse_voice_commands(text: &str) -> Vec<Edit> {
    let tokens: Vec<&str> = text.split_whitespace().collect();
    let norms: Vec<String> = tokens.iter().map(|t| normalize(t)).collect();
    let mut phrases: Vec<(Vec<String>, Command)> = COMMANDS
        .iter()
        .map(|(p, c)| (phrase_words(p), *c))
        .collect();
    phrases.sort_by_key(|(w, _)| std::cmp::Reverse(w.len()));

    let mut edits = Vec::new();
    let mut current: Vec<&str> = Vec::new();
    let mut i = 0;
    'outer: while i < tokens.len() {
        for (words, cmd) in &phrases {
            let end = i + words.len();
            if end <= tokens.len() && norms[i..end] == words[..] {
                push_text(&mut edits, &mut current, true);
                match cmd {
                    Command::Newline => edits.push(Edit::Newline),
                    Command::Paragraph => edits.push(Edit::Paragraph),
                    Command::Delete => match edits.last() {
                        Some(Edit::Text(_)) => {
                            edits.pop();
                        }
                        _ => edits.push(Edit::DeletePrevious),
                    },
                }
                i = end;
                continue 'outer;
            }
        }
        current.push(tokens[i]);
        i += 1;
    }
    push_text(&mut edits, &mut current, false);
    edits
}

fn push_text(edits: &mut Vec<Edit>, words: &mut Vec<&str>, before_command: bool) {
    if words.is_empty() {
        return;
    }
    let mut text = words.join(" ");
    words.clear();
    if before_command {
        // « Bonjour, nouvelle ligne » : la virgule avant la commande n'a plus de sens.
        while text.ends_with([',', ';', ':']) {
            text.pop();
        }
    }
    if !text.trim().is_empty() {
        edits.push(Edit::Text(text));
    }
}

/// Assemble des éditions en texte brut. Les `DeletePrevious` qui ne trouvent rien à
/// supprimer dans cette liste sont ignorés.
pub fn render(edits: &[Edit]) -> String {
    let mut parts: Vec<String> = Vec::new();
    for e in edits {
        match e {
            Edit::Text(t) => parts.push(t.clone()),
            Edit::Newline => parts.push("\n".into()),
            Edit::Paragraph => parts.push("\n\n".into()),
            Edit::DeletePrevious => {
                parts.pop();
            }
        }
    }
    let mut out = String::new();
    for p in parts {
        let joins_line = p.starts_with('\n') || out.ends_with('\n') || out.is_empty();
        if !joins_line {
            out.push(' ');
        }
        out.push_str(&p);
    }
    out
}

/// Met bout à bout les éditions de plusieurs morceaux d'un même enregistrement, un
/// paragraphe par morceau. « Efface ça » en tête d'un morceau efface le morceau précédent.
pub fn join_paragraphs(pieces: Vec<Vec<Edit>>) -> Vec<Edit> {
    let mut out: Vec<Edit> = Vec::new();
    for piece in pieces {
        let mut first = true;
        for e in piece {
            if e == Edit::DeletePrevious {
                if out.iter().any(|e| matches!(e, Edit::Text(_))) {
                    while let Some(last) = out.pop() {
                        if matches!(last, Edit::Text(_)) {
                            break;
                        }
                    }
                } else {
                    out.push(Edit::DeletePrevious);
                }
                continue;
            }
            if first && matches!(out.last(), Some(Edit::Text(_))) {
                out.push(Edit::Paragraph);
            }
            first = false;
            out.push(e);
        }
    }
    out
}

/// Textes que Whisper invente sur du silence ou du bruit (sous-titres de son corpus).
const HALLUCINATIONS: &[&str] = &[
    "amara.org",
    "sous-titres réalisés par",
    "sous-titrage st'",
    "sous-titrage société radio-canada",
    "merci d'avoir regardé",
    "thanks for watching",
    "thank you for watching",
    "subtitles by",
    "abonnez-vous",
];

/// Vrai si le texte ne contient que du bruit ou une hallucination connue.
pub fn is_hallucination(text: &str) -> bool {
    let t = text.trim().to_lowercase().replace('’', "'");
    if t.chars().all(|c| !c.is_alphanumeric()) {
        return true;
    }
    HALLUCINATIONS.iter().any(|h| t.contains(h))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn newline_in_the_middle() {
        assert_eq!(
            parse_voice_commands("Bonjour, nouvelle ligne. Comment ça va ?"),
            vec![
                Edit::Text("Bonjour".into()),
                Edit::Newline,
                Edit::Text("Comment ça va ?".into())
            ]
        );
    }

    #[test]
    fn delete_removes_preceding_text_or_previous_utterance() {
        assert_eq!(
            parse_voice_commands("Rendez-vous lundi. Efface ça. Rendez-vous mardi."),
            vec![Edit::Text("Rendez-vous mardi.".into())]
        );
        assert_eq!(parse_voice_commands("Scratch that."), vec![Edit::DeletePrevious]);
    }

    #[test]
    fn plain_text_is_untouched() {
        let text = "Petit point sur la mise à jour : la migration est terminée.";
        assert_eq!(parse_voice_commands(text), vec![Edit::Text(text.into())]);
    }

    #[test]
    fn paragraph_and_render() {
        let edits = parse_voice_commands("Salut. Nouveau paragraphe. À demain. New line. Bye");
        assert_eq!(render(&edits), "Salut.\n\nÀ demain.\nBye");
    }

    #[test]
    fn joins_pieces_as_paragraphs() {
        let pieces = vec![
            parse_voice_commands("Premier point."),
            parse_voice_commands("Deuxième point."),
            parse_voice_commands("Efface ça. Troisième point."),
        ];
        assert_eq!(render(&join_paragraphs(pieces)), "Premier point.\n\nTroisième point.");
        let lone = join_paragraphs(vec![parse_voice_commands("Efface ça.")]);
        assert_eq!(lone, vec![Edit::DeletePrevious]);
    }

    #[test]
    fn detects_hallucinations() {
        assert!(is_hallucination("Sous-titres réalisés par la communauté d'Amara.org"));
        assert!(is_hallucination(" ... "));
        assert!(is_hallucination("Thanks for watching!"));
        assert!(!is_hallucination("Je passe à la boulangerie."));
    }
}
