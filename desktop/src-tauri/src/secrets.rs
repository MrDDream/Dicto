//! Clés d'API, gardées dans le coffre du système : Gestionnaire d'identification (Windows),
//! Trousseau (macOS), Secret Service (GNOME Keyring, KWallet). Jamais dans la base ni les réglages.

const SERVICE: &str = "Dicto";

fn entry(name: &str) -> Result<keyring::Entry, String> {
    keyring::Entry::new(SERVICE, name).map_err(|e| format!("Coffre des mots de passe indisponible : {e}"))
}

pub fn get(name: &str) -> Option<String> {
    entry(name).ok()?.get_password().ok().filter(|k| !k.is_empty())
}

pub fn has(name: &str) -> bool {
    get(name).is_some()
}

pub fn set(name: &str, value: &str) -> Result<(), String> {
    entry(name)?.set_password(value).map_err(|e| format!("Clé non enregistrée : {e}"))
}

pub fn forget(name: &str) {
    if let Ok(e) = entry(name) {
        let _ = e.delete_credential();
    }
}
