//! Langue effective de l'interface (« auto » résolu par la fenêtre), pour ce que Rust affiche lui-même :
//! le menu de l'icône, l'info-bulle et les fenêtres du système (ouvrir, enregistrer).

use std::sync::atomic::{AtomicBool, Ordering};

static ENGLISH: AtomicBool = AtomicBool::new(false);

/// Rend true si la langue a changé.
pub fn set(lang: &str) -> bool {
    let en = lang == "en";
    ENGLISH.swap(en, Ordering::SeqCst) != en
}

/// Le texte français, ou son équivalent anglais.
pub fn tr(fr: &'static str, en: &'static str) -> &'static str {
    if ENGLISH.load(Ordering::SeqCst) { en } else { fr }
}
