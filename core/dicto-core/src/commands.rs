//! Ce que Dicto fait du texte transcrit, sans LLM : commandes vocales, ponctuation
//! dictée, nettoyage des hésitations et des répétitions, remplacements personnels, et
//! filtrage des hallucinations de Whisper.
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
    /// Valider le champ (touche Entrée ou « Envoyer ») une fois le texte écrit.
    Send,
}

/// Les traitements à appliquer au texte transcrit.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct TextOptions {
    /// « nouvelle ligne », « efface ça », « majuscule », « envoie le message »…
    pub voice_commands: bool,
    /// « virgule », « point d'interrogation », « ouvrez les guillemets »…
    pub spoken_punctuation: bool,
    /// Retire « euh », « hum »… et les mots répétés par erreur (« le le »).
    pub remove_fillers: bool,
    /// Remplacements personnels : (expression dite, texte écrit).
    pub replacements: Vec<(String, String)>,
}

impl TextOptions {
    pub fn commands_only() -> Self {
        Self { voice_commands: true, ..Self::default() }
    }
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
enum Mark {
    Comma,
    Period,
    Question,
    Exclamation,
    Colon,
    Semicolon,
    Ellipsis,
    OpenQuote,
    CloseQuote,
    OpenParen,
    CloseParen,
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
enum Lang {
    Fr,
    En,
}

#[derive(Clone, Copy, Debug)]
enum Command {
    Newline,
    Paragraph,
    Delete,
    Send,
    /// Majuscule au mot suivant.
    Capitalize,
    /// Mot suivant tout en majuscules.
    Upper,
    Punct(Mark),
}

use Command as C;
use Lang::{En, Fr};
use Mark as M;

/// (expression, commande, langue, ambiguë). Une expression ambiguë (« point », « period »)
/// est aussi un mot courant : elle n'est pas une commande quand le contexte dit le contraire
/// (« le point de vue », « a period of time »).
const COMMANDS: &[(&str, Command, Lang, bool)] = &[
    // Français
    ("nouveau paragraphe", C::Paragraph, Fr, false),
    ("nouvelle ligne", C::Newline, Fr, false),
    ("à la ligne", C::Newline, Fr, false),
    ("retour à la ligne", C::Newline, Fr, false),
    ("efface ça", C::Delete, Fr, false),
    ("efface ca", C::Delete, Fr, false),
    ("supprime ça", C::Delete, Fr, false),
    ("supprime ca", C::Delete, Fr, false),
    ("annule ça", C::Delete, Fr, false),
    ("annule ca", C::Delete, Fr, false),
    ("envoie le message", C::Send, Fr, false),
    ("envoyer le message", C::Send, Fr, false),
    ("majuscule", C::Capitalize, Fr, true),
    ("tout en majuscules", C::Upper, Fr, false),
    ("tout en majuscule", C::Upper, Fr, false),
    // English
    ("new paragraph", C::Paragraph, En, false),
    ("new line", C::Newline, En, false),
    ("newline", C::Newline, En, false),
    ("scratch that", C::Delete, En, false),
    ("delete that", C::Delete, En, false),
    ("undo that", C::Delete, En, false),
    ("send message", C::Send, En, false),
    ("send the message", C::Send, En, false),
    ("capitalize", C::Capitalize, En, false),
    ("all caps", C::Upper, En, false),
];

const PUNCTUATION: &[(&str, Command, Lang, bool)] = &[
    // Français
    ("virgule", C::Punct(M::Comma), Fr, false),
    ("point", C::Punct(M::Period), Fr, true),
    ("point final", C::Punct(M::Period), Fr, false),
    ("point d'interrogation", C::Punct(M::Question), Fr, false),
    ("point d'exclamation", C::Punct(M::Exclamation), Fr, false),
    ("deux points", C::Punct(M::Colon), Fr, true),
    ("deux-points", C::Punct(M::Colon), Fr, false),
    ("point virgule", C::Punct(M::Semicolon), Fr, false),
    ("point-virgule", C::Punct(M::Semicolon), Fr, false),
    ("points de suspension", C::Punct(M::Ellipsis), Fr, false),
    ("ouvrez les guillemets", C::Punct(M::OpenQuote), Fr, false),
    ("ouvre les guillemets", C::Punct(M::OpenQuote), Fr, false),
    ("ouvrir les guillemets", C::Punct(M::OpenQuote), Fr, false),
    ("fermez les guillemets", C::Punct(M::CloseQuote), Fr, false),
    ("ferme les guillemets", C::Punct(M::CloseQuote), Fr, false),
    ("fermer les guillemets", C::Punct(M::CloseQuote), Fr, false),
    ("ouvrez la parenthèse", C::Punct(M::OpenParen), Fr, false),
    ("ouvre la parenthèse", C::Punct(M::OpenParen), Fr, false),
    ("ouvrir la parenthèse", C::Punct(M::OpenParen), Fr, false),
    ("fermez la parenthèse", C::Punct(M::CloseParen), Fr, false),
    ("ferme la parenthèse", C::Punct(M::CloseParen), Fr, false),
    ("fermer la parenthèse", C::Punct(M::CloseParen), Fr, false),
    // English
    ("comma", C::Punct(M::Comma), En, false),
    ("period", C::Punct(M::Period), En, true),
    ("full stop", C::Punct(M::Period), En, false),
    ("question mark", C::Punct(M::Question), En, false),
    ("exclamation mark", C::Punct(M::Exclamation), En, false),
    ("exclamation point", C::Punct(M::Exclamation), En, false),
    ("colon", C::Punct(M::Colon), En, false),
    ("semicolon", C::Punct(M::Semicolon), En, false),
    ("semi colon", C::Punct(M::Semicolon), En, false),
    ("ellipsis", C::Punct(M::Ellipsis), En, false),
    ("open quote", C::Punct(M::OpenQuote), En, false),
    ("close quote", C::Punct(M::CloseQuote), En, false),
    ("end quote", C::Punct(M::CloseQuote), En, false),
    ("unquote", C::Punct(M::CloseQuote), En, false),
    ("open paren", C::Punct(M::OpenParen), En, false),
    ("open parenthesis", C::Punct(M::OpenParen), En, false),
    ("close paren", C::Punct(M::CloseParen), En, false),
    ("close parenthesis", C::Punct(M::CloseParen), En, false),
];

/// Mots qui, juste avant une expression ambiguë, en font un nom (« le point », « a period »).
const DETERMINERS: &[&str] = &[
    "un", "une", "le", "la", "les", "l", "ce", "cet", "cette", "ces", "au", "aux", "du", "des", "mon", "ton", "son",
    "ma", "ta", "sa", "mes", "tes", "ses", "notre", "votre", "leur", "nos", "vos", "leurs", "chaque", "quel",
    "quelle", "premier", "dernier", "bon", "en", "a", "an", "the", "this", "that", "each", "every", "any", "some",
    "trial", "grace", "first", "last", "good",
];

/// Mots qui, juste après une expression ambiguë, en font un nom (« point de vue », « deux points à voir »).
const FOLLOWERS: &[&str] = &["de", "du", "des", "à", "sur", "pour", "of", "to", "in", "on", "for", "about"];

/// Hésitations retirées par le nettoyage.
const FILLERS: &[&str] = &["euh", "heu", "euhm", "hum", "hmm", "hmmm", "bah", "uh", "um", "uhm", "erm", "mmh"];

/// Mots qui se répètent légitimement (« nous nous sommes vus », « I had had »).
const VALID_DOUBLES: &[&str] = &[
    "nous", "vous", "très", "non", "oui", "si", "ha", "ah", "oh", "bye", "hey", "that", "had", "is", "so", "very", "no",
    "yes",
];

const EDGE_PUNCT: &[char] = &[
    '.', ',', ';', ':', '!', '?', '…', '«', '»', '"', '“', '”', '(', ')', '¿', '¡',
];

/// Ponctuation que le moteur ajoute d'office et que la ponctuation dictée remplace.
const TRAILING: &[char] = &[',', '.', ';', ':', '!', '?', '…'];

fn normalize(word: &str) -> String {
    word.trim_matches(|c: char| EDGE_PUNCT.contains(&c) || c == '\'' || c == '’')
        .to_lowercase()
        .replace('’', "'")
}

fn phrase_words(phrase: &str) -> Vec<String> {
    phrase.split_whitespace().map(normalize).collect()
}

fn capitalize(word: &str) -> String {
    let mut chars = word.chars();
    let mut out = String::new();
    // Les guillemets ou parenthèses en tête restent devant la majuscule.
    for c in chars.by_ref() {
        if c.is_alphanumeric() {
            out.extend(c.to_uppercase());
            break;
        }
        out.push(c);
    }
    out.extend(chars);
    out
}

fn lowercase_first(word: &str) -> String {
    let mut chars = word.chars();
    match chars.next() {
        Some(c) => c.to_lowercase().chain(chars).collect(),
        None => String::new(),
    }
}

#[derive(Clone, Copy)]
enum Case {
    Capital,
    Upper,
    Lower,
}

/// Construit les éditions mot à mot.
#[derive(Default)]
struct Builder {
    edits: Vec<Edit>,
    words: Vec<String>,
    /// Le mot suivant se colle au précédent (après « ( »).
    glue: bool,
    case: Option<Case>,
}

impl Builder {
    fn word(&mut self, w: &str) {
        let w = match self.case.take() {
            Some(Case::Capital) => capitalize(w),
            Some(Case::Upper) => w.to_uppercase(),
            Some(Case::Lower) => lowercase_first(w),
            None => w.to_string(),
        };
        if std::mem::take(&mut self.glue) {
            if let Some(last) = self.words.last_mut() {
                last.push_str(&w);
                return;
            }
        }
        self.words.push(w);
    }

    fn flush(&mut self, before_command: bool) {
        if self.words.is_empty() {
            return;
        }
        let mut text = self.words.join(" ");
        self.words.clear();
        self.glue = false;
        if before_command {
            // « Bonjour, nouvelle ligne » : la virgule avant la commande n'a plus de sens.
            while text.ends_with([',', ';', ':']) {
                text.pop();
            }
        }
        if !text.trim().is_empty() {
            self.edits.push(Edit::Text(text));
        }
    }

    fn punct(&mut self, mark: Mark, lang: Lang, sentence_ended: bool) {
        let fr = lang == Fr;
        match mark {
            M::OpenQuote if fr => self.words.push("«".into()),
            M::OpenQuote => {
                self.words.push("\"".into());
                self.glue = true;
            }
            M::OpenParen => {
                self.words.push("(".into());
                self.glue = true;
            }
            _ => {
                let s = match mark {
                    M::Comma => ",",
                    M::Period => ".",
                    M::Question if fr => " ?",
                    M::Question => "?",
                    M::Exclamation if fr => " !",
                    M::Exclamation => "!",
                    M::Colon if fr => " :",
                    M::Colon => ":",
                    M::Semicolon if fr => " ;",
                    M::Semicolon => ";",
                    M::Ellipsis => "…",
                    M::CloseQuote if fr => " »",
                    M::CloseQuote => "\"",
                    _ => ")",
                };
                if !matches!(mark, M::CloseQuote | M::CloseParen) {
                    // La ponctuation mise d'office par le moteur laisse la place à celle dictée.
                    while let Some(last) = self.words.last_mut() {
                        while last.ends_with(TRAILING) {
                            last.pop();
                        }
                        let len = last.trim_end().len();
                        last.truncate(len);
                        if last.is_empty() {
                            self.words.pop();
                        } else {
                            break;
                        }
                    }
                }
                match self.words.last_mut() {
                    Some(last) => last.push_str(s),
                    None => self.words.push(s.trim_start().to_string()),
                }
                self.glue = false;
                self.case = match mark {
                    M::Period | M::Question | M::Exclamation => Some(Case::Capital),
                    // Le moteur avait fini la phrase sur la commande : la suite n'en commence pas une.
                    M::Comma | M::Colon | M::Semicolon if sentence_ended => Some(Case::Lower),
                    _ => self.case,
                };
            }
        }
    }
}

/// Traite un texte transcrit : nettoyage, commandes, ponctuation dictée, remplacements.
pub fn process_text(text: &str, opts: &TextOptions) -> Vec<Edit> {
    let mut tokens: Vec<String> = text.split_whitespace().map(String::from).collect();
    if opts.remove_fillers {
        tokens = clean_disfluencies(tokens);
    }
    let norms: Vec<String> = tokens.iter().map(|t| normalize(t)).collect();

    let mut phrases: Vec<(Vec<String>, Command, Lang, bool)> = Vec::new();
    if opts.voice_commands {
        phrases.extend(COMMANDS.iter().map(|(p, c, l, a)| (phrase_words(p), *c, *l, *a)));
    }
    if opts.spoken_punctuation {
        phrases.extend(PUNCTUATION.iter().map(|(p, c, l, a)| (phrase_words(p), *c, *l, *a)));
    }
    phrases.sort_by_key(|(w, ..)| std::cmp::Reverse(w.len()));

    let mut b = Builder::default();
    let mut i = 0;
    'outer: while i < tokens.len() {
        for (words, cmd, lang, ambiguous) in &phrases {
            let end = i + words.len();
            if end > tokens.len() || norms[i..end] != words[..] {
                continue;
            }
            if *ambiguous && reads_as_prose(&norms, i, end) {
                continue;
            }
            let sentence_ended = tokens[end - 1].ends_with(['.', '?', '!']);
            match cmd {
                C::Newline | C::Paragraph | C::Send => {
                    b.flush(true);
                    b.edits.push(match cmd {
                        C::Newline => Edit::Newline,
                        C::Paragraph => Edit::Paragraph,
                        _ => Edit::Send,
                    });
                    b.case = Some(Case::Capital);
                }
                C::Delete => {
                    b.flush(true);
                    match b.edits.last() {
                        Some(Edit::Text(_)) => {
                            b.edits.pop();
                        }
                        _ => b.edits.push(Edit::DeletePrevious),
                    }
                    b.case = Some(Case::Capital);
                }
                C::Capitalize => b.case = Some(Case::Capital),
                C::Upper => b.case = Some(Case::Upper),
                C::Punct(mark) => b.punct(*mark, *lang, sentence_ended),
            }
            i = end;
            // La ponctuation isolée laissée par le moteur juste après la commande.
            while i < tokens.len() && norms[i].is_empty() {
                i += 1;
            }
            continue 'outer;
        }
        b.word(&tokens[i]);
        i += 1;
    }
    b.flush(false);

    let mut edits = b.edits;
    if !opts.replacements.is_empty() {
        let mut reps: Vec<(Vec<String>, &str)> = opts
            .replacements
            .iter()
            .map(|(from, to)| (phrase_words(from), to.as_str()))
            .filter(|(w, _)| !w.is_empty() && w.iter().all(|w| !w.is_empty()))
            .collect();
        reps.sort_by_key(|(w, _)| std::cmp::Reverse(w.len()));
        for e in &mut edits {
            if let Edit::Text(t) = e {
                *t = apply_replacements(t, &reps);
            }
        }
    }
    edits
}

/// Découpe un texte transcrit en morceaux de texte et commandes vocales.
pub fn parse_voice_commands(text: &str) -> Vec<Edit> {
    process_text(text, &TextOptions::commands_only())
}

/// Vrai si l'expression ambiguë en `norms[i..end]` est ici un mot ordinaire.
fn reads_as_prose(norms: &[String], i: usize, end: usize) -> bool {
    let before = i.checked_sub(1).map(|p| norms[p].as_str());
    let after = norms.get(end).map(String::as_str);
    before.is_some_and(|w| DETERMINERS.contains(&w))
        || after.is_some_and(|w| FOLLOWERS.contains(&w) || w.starts_with("d'"))
}

/// Ponctuation en tête et en fin d'un mot.
fn edges(token: &str) -> (&str, &str, &str) {
    let start = token.len() - token.trim_start_matches(EDGE_PUNCT).len();
    let core_end = token.trim_end_matches(EDGE_PUNCT).len().max(start);
    (&token[..start], &token[start..core_end], &token[core_end..])
}

/// Retire les hésitations et les mots répétés par erreur, en gardant une ponctuation propre.
fn clean_disfluencies(tokens: Vec<String>) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    let mut capitalize_next = false;
    for tok in tokens {
        let norm = normalize(&tok);
        let (_, _, trailing) = edges(&tok);
        if FILLERS.contains(&norm.as_str()) {
            let at_start = out.last().is_none_or(|w| w.ends_with(['.', '?', '!']));
            if at_start {
                // « Euh, je pense » : « Je pense ».
                capitalize_next |= tok.chars().next().is_some_and(char::is_uppercase) || out.is_empty();
            } else if let Some(last) = out.last_mut() {
                if trailing.contains(['.', '?', '!']) {
                    // « c'est prêt, euh. » : « c'est prêt. »
                    while last.ends_with([',', ';', ':']) {
                        last.pop();
                    }
                    last.push_str(trailing.trim_start_matches([',', ';', ':']));
                } else if trailing.contains(',') && last.ends_with(',') {
                    // « Je pense, euh, que » : « Je pense que ».
                    last.pop();
                }
            }
            continue;
        }
        if let Some(last) = out.last_mut() {
            let (_, _, last_trailing) = edges(last);
            let repeated = last_trailing.is_empty()
                && normalize(last) == norm
                && norm.chars().any(char::is_alphabetic)
                && !VALID_DOUBLES.contains(&norm.as_str());
            if repeated {
                // « le le projet » : « le projet » (la ponctuation du second mot est gardée).
                last.push_str(trailing);
                continue;
            }
        }
        let tok = if std::mem::take(&mut capitalize_next) { capitalize(&tok) } else { tok };
        out.push(tok);
    }
    out
}

/// Remplace les expressions personnelles (mots entiers, sans tenir compte de la casse).
fn apply_replacements(text: &str, reps: &[(Vec<String>, &str)]) -> String {
    // Le texte peut contenir des sauts de ligne : on les garde tels quels.
    text.split('\n')
        .map(|line| {
            let tokens: Vec<&str> = line.split(' ').collect();
            let norms: Vec<String> = tokens.iter().map(|t| normalize(t)).collect();
            let mut out: Vec<String> = Vec::new();
            let mut i = 0;
            'outer: while i < tokens.len() {
                for (words, to) in reps {
                    let end = i + words.len();
                    if end <= tokens.len() && norms[i..end] == words[..] {
                        let (lead, ..) = edges(tokens[i]);
                        let (.., trail) = edges(tokens[end - 1]);
                        out.push(format!("{lead}{to}{trail}"));
                        i = end;
                        continue 'outer;
                    }
                }
                out.push(tokens[i].to_string());
                i += 1;
            }
            out.join(" ")
        })
        .collect::<Vec<_>>()
        .join("\n")
}

/// Assemble des éditions en texte brut. Les `DeletePrevious` qui ne trouvent rien à
/// supprimer dans cette liste sont ignorés, `Send` n'écrit rien.
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
            Edit::Send => {}
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

    fn text(input: &str, opts: &TextOptions) -> String {
        render(&process_text(input, opts))
    }

    fn punctuation() -> TextOptions {
        TextOptions { voice_commands: true, spoken_punctuation: true, ..TextOptions::default() }
    }

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
        assert_eq!(parse_voice_commands("Annule ça."), vec![Edit::DeletePrevious]);
    }

    #[test]
    fn plain_text_is_untouched() {
        let text = "Petit point sur la mise à jour : la migration est terminée.";
        assert_eq!(parse_voice_commands(text), vec![Edit::Text(text.into())]);
        assert_eq!(process_text(text, &punctuation()), vec![Edit::Text(text.into())]);
    }

    #[test]
    fn paragraph_and_render() {
        let edits = parse_voice_commands("Salut. Nouveau paragraphe. À demain. New line. Bye");
        assert_eq!(render(&edits), "Salut.\n\nÀ demain.\nBye");
    }

    #[test]
    fn spoken_punctuation_in_french() {
        let p = punctuation();
        assert_eq!(text("Bonjour virgule comment ça va point d'interrogation", &p), "Bonjour, comment ça va ?");
        // Le moteur a déjà ponctué autour des commandes.
        assert_eq!(text("Bonjour, virgule, comment ça va, point d'interrogation.", &p), "Bonjour, comment ça va ?");
        assert_eq!(text("Bonjour virgule. Comment ça va", &p), "Bonjour, comment ça va");
        assert_eq!(
            text("Il a dit ouvrez les guillemets bonjour fermez les guillemets point", &p),
            "Il a dit « bonjour »."
        );
        assert_eq!(text("C'est fini point la suite demain", &p), "C'est fini. La suite demain");
        assert_eq!(text("Trois choses deux points le pain", &p), "Trois choses : le pain");
        assert_eq!(text("voir ouvrez la parenthèse page deux fermez la parenthèse", &p), "voir (page deux)");
    }

    #[test]
    fn ambiguous_words_stay_words() {
        let p = punctuation();
        assert_eq!(text("Mon point de vue est clair point", &p), "Mon point de vue est clair.");
        assert_eq!(text("On fait le point demain", &p), "On fait le point demain");
        assert_eq!(text("J'ai deux points à voir", &p), "J'ai deux points à voir");
        assert_eq!(text("for a period of time period", &p), "for a period of time.");
        assert_eq!(text("Il faut une majuscule ici", &TextOptions::commands_only()), "Il faut une majuscule ici");
    }

    #[test]
    fn spoken_punctuation_in_english() {
        let p = punctuation();
        assert_eq!(text("hello comma how are you question mark", &p), "hello, how are you?");
        assert_eq!(text("she said open quote yes close quote", &p), "she said \"yes\"");
        // Sans l'option, les mots restent.
        assert_eq!(text("hello comma", &TextOptions::commands_only()), "hello comma");
    }

    #[test]
    fn capitalization_and_send() {
        let c = TextOptions::commands_only();
        assert_eq!(text("j'habite à majuscule paris", &c), "j'habite à Paris");
        assert_eq!(text("c'est tout en majuscules urgent", &c), "c'est URGENT");
        assert_eq!(text("call all caps nasa", &c), "call NASA");
        assert_eq!(
            process_text("On se voit demain. Envoie le message.", &c),
            vec![Edit::Text("On se voit demain.".into()), Edit::Send]
        );
        assert_eq!(text("Je vous envoie le fichier", &c), "Je vous envoie le fichier");
    }

    #[test]
    fn removes_fillers_and_repeats() {
        let f = TextOptions { remove_fillers: true, ..TextOptions::default() };
        assert_eq!(
            text("Euh, je pense, euh, que le le projet est, hum, prêt.", &f),
            "Je pense que le projet est prêt."
        );
        assert_eq!(text("C'est prêt, euh.", &f), "C'est prêt.");
        assert_eq!(text("Nous nous sommes vus, très très vite.", &f), "Nous nous sommes vus, très très vite.");
        assert_eq!(text("I um think that that is fine", &f), "I think that that is fine");
        // Sans l'option, rien ne bouge.
        assert_eq!(text("Euh, le le", &TextOptions::default()), "Euh, le le");
    }

    #[test]
    fn personal_replacements() {
        let r = TextOptions {
            replacements: vec![("mon adresse".into(), "12 rue des Lilas, Lyon".into()), ("sig".into(), "Dorian\n06 00".into())],
            ..TextOptions::commands_only()
        };
        assert_eq!(text("Envoie-le à mon adresse.", &r), "Envoie-le à 12 rue des Lilas, Lyon.");
        assert_eq!(text("Mon adresse est simple", &r), "12 rue des Lilas, Lyon est simple");
        assert_eq!(text("Merci, sig", &r), "Merci, Dorian\n06 00");
        assert_eq!(text("signature", &r), "signature");
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
