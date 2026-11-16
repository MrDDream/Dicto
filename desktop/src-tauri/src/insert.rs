//! Écriture du texte dicté dans la fenêtre active : frappe simulée, ou collage par le
//! presse-papiers (remis ensuite). « Efface ça » envoie autant de retours arrière que le
//! dernier texte écrit.

#[cfg(target_os = "linux")]
use std::sync::{Mutex, OnceLock};
use std::thread;
use std::time::Duration;

use enigo::{Direction, Enigo, Key, Keyboard, Settings as EnigoSettings};

use crate::settings::Output;

/// Résultat de l'écriture, pour l'interface.
pub enum Written {
    Typed,
    Pasted,
    /// Rien n'a pu être écrit : le texte est dans le presse-papiers.
    Copied(String),
    /// Mode « relire d'abord » : rien n'est envoyé.
    Kept,
}

fn enigo() -> Result<Enigo, String> {
    Enigo::new(&EnigoSettings::default()).map_err(|e| e.to_string())
}

/// Touche de collage : Cmd sur macOS, Ctrl ailleurs.
fn paste_modifier() -> Key {
    if cfg!(target_os = "macos") { Key::Meta } else { Key::Control }
}

pub fn write(text: &str, output: Output) -> Written {
    if text.is_empty() {
        return Written::Kept;
    }
    match output {
        Output::Review => Written::Kept,
        Output::Type => match enigo().and_then(|mut e| e.text(text).map_err(|e| e.to_string())) {
            Ok(()) => Written::Typed,
            // Saisie simulée impossible (Wayland sans portail, droits macOS…) : on colle.
            Err(e) => {
                log::warn!("frappe impossible, collage à la place : {e}");
                paste(text)
            }
        },
        Output::Paste => paste(text),
    }
}

/// Accès au presse-papiers. Sous Linux (X11), il reste ouvert : son contenu disparaît
/// quand il est fermé. Ailleurs, un accès neuf à chaque fois.
fn with_clipboard<R>(f: impl FnOnce(&mut arboard::Clipboard) -> R) -> Result<R, String> {
    #[cfg(target_os = "linux")]
    {
        static CLIPBOARD: OnceLock<Option<Mutex<arboard::Clipboard>>> = OnceLock::new();
        let c = CLIPBOARD
            .get_or_init(|| arboard::Clipboard::new().map(Mutex::new).map_err(|e| log::warn!("presse-papiers : {e}")).ok())
            .as_ref()
            .ok_or("Presse-papiers inaccessible.")?;
        Ok(f(&mut c.lock().unwrap()))
    }
    #[cfg(not(target_os = "linux"))]
    {
        let mut c = arboard::Clipboard::new().map_err(|e| format!("Presse-papiers inaccessible : {e}"))?;
        Ok(f(&mut c))
    }
}

/// Copie seule (bouton « Copier », ou secours quand rien ne peut être écrit).
pub fn copy(text: &str) -> Result<(), String> {
    with_clipboard(|c| c.set_text(text.to_string()).map_err(|e| e.to_string()))?
}

fn paste(text: &str) -> Written {
    let previous = match with_clipboard(|c| {
        let previous = c.get_text().ok();
        c.set_text(text.to_string()).map(|_| previous).map_err(|e| e.to_string())
    }) {
        Ok(Ok(previous)) => previous,
        Ok(Err(e)) | Err(e) => return Written::Copied(format!("Presse-papiers inaccessible : {e}")),
    };
    let sent = enigo().and_then(|mut e| {
        let m = paste_modifier();
        e.key(m, Direction::Press).map_err(|e| e.to_string())?;
        let r = e.key(Key::Unicode('v'), Direction::Click).map_err(|e| e.to_string());
        e.key(m, Direction::Release).map_err(|e| e.to_string())?;
        r
    });
    match sent {
        Ok(()) => {
            // L'app cible lit le presse-papiers un peu après le raccourci : on attend avant de le remettre.
            if let Some(previous) = previous {
                thread::spawn(move || {
                    thread::sleep(Duration::from_millis(400));
                    let _ = with_clipboard(|c| c.set_text(previous));
                });
            }
            Written::Pasted
        }
        Err(e) => Written::Copied(format!("Texte copié, à coller avec Ctrl+V ({e}).")),
    }
}

/// Valide le champ (« envoie le message ») : touche Entrée, un instant après le texte.
pub fn press_enter() {
    thread::sleep(Duration::from_millis(120));
    if let Ok(mut e) = enigo() {
        let _ = e.key(Key::Return, Direction::Click);
    }
}

/// Efface les `count` derniers caractères écrits (« efface ça »).
pub fn erase(count: usize) {
    if count == 0 {
        return;
    }
    if let Ok(mut e) = enigo() {
        for _ in 0..count {
            if e.key(Key::Backspace, Direction::Click).is_err() {
                break;
            }
        }
    }
}
