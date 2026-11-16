//! Raccourcis globaux sous Wayland, par le portail XDG « GlobalShortcuts » (GNOME 48+, KDE Plasma,
//! Hyprland…). Le bureau demande une fois à l'utilisateur de confirmer les raccourcis ; il envoie
//! ensuite l'appui et le relâchement, ce qui permet aussi le mode maintenu.

use crate::core::Hotkey;

/// « Ctrl+Alt+Space », « Ctrl+Shift+KeyD » → « CTRL+ALT+space », « CTRL+SHIFT+d » (format du portail).
pub fn xdg_trigger(shortcut: &str) -> Option<String> {
    let mut mods = Vec::new();
    let mut key = None;
    for part in shortcut.split('+').map(str::trim).filter(|p| !p.is_empty()) {
        match part.to_lowercase().as_str() {
            "ctrl" | "control" | "commandorcontrol" | "cmdorctrl" => mods.push("CTRL"),
            "alt" | "option" => mods.push("ALT"),
            "shift" => mods.push("SHIFT"),
            "super" | "meta" | "cmd" | "command" => mods.push("LOGO"),
            low => {
                let k = if let Some(c) = low.strip_prefix("key").filter(|c| c.len() == 1) {
                    c.to_string()
                } else if let Some(d) = low.strip_prefix("digit").filter(|d| d.len() == 1) {
                    d.to_string()
                } else {
                    match low {
                        "space" => "space".into(),
                        "enter" | "return" => "Return".into(),
                        "escape" | "esc" => "Escape".into(),
                        "tab" => "Tab".into(),
                        "backspace" => "BackSpace".into(),
                        l if l.len() == 1 => l.into(),
                        l if l.starts_with('f') && l[1..].parse::<u8>().is_ok() => l.to_uppercase(),
                        _ => return None,
                    }
                };
                key = Some(k);
            }
        }
    }
    let key = key?;
    mods.push("");
    Some(format!("{}{key}", mods.join("+")).replace("++", "+"))
}

pub fn hotkey_id(h: Hotkey) -> &'static str {
    match h {
        Hotkey::Toggle => "toggle",
        Hotkey::Continuous => "continuous",
        Hotkey::Hold => "hold",
        Hotkey::Translate => "translate",
        Hotkey::Escape => "escape",
    }
}

fn hotkey_from_id(id: &str) -> Option<Hotkey> {
    Some(match id {
        "toggle" => Hotkey::Toggle,
        "continuous" => Hotkey::Continuous,
        "hold" => Hotkey::Hold,
        "translate" => Hotkey::Translate,
        _ => return None,
    })
}

#[cfg(target_os = "linux")]
pub use linux::Portal;

#[cfg(target_os = "linux")]
mod linux {
    use std::sync::Mutex;

    use ashpd::desktop::global_shortcuts::{GlobalShortcuts, NewShortcut};
    use futures_util::StreamExt;

    use super::{hotkey_from_id, hotkey_id, xdg_trigger};
    use crate::core::Hotkey;

    /// Session du portail en cours ; la remplacer ferme la précédente.
    #[derive(Default)]
    pub struct Portal {
        stop: Mutex<Option<tokio::sync::oneshot::Sender<()>>>,
    }

    impl Portal {
        /// Lie les raccourcis au portail. `on_hotkey(raccourci, appuyé)` ; `on_error` si le bureau refuse
        /// (portail absent ou trop ancien) : l'appelant revient alors aux raccourcis X11.
        pub fn bind(
            &self,
            shortcuts: Vec<(Hotkey, String, String)>,
            on_hotkey: impl Fn(Hotkey, bool) + Send + Sync + 'static,
            on_error: impl FnOnce(String) + Send + 'static,
        ) {
            let (stop_tx, stop_rx) = tokio::sync::oneshot::channel();
            if let Some(previous) = self.stop.lock().unwrap().replace(stop_tx) {
                let _ = previous.send(());
            }
            if shortcuts.is_empty() {
                return;
            }
            tauri::async_runtime::spawn(async move {
                if let Err(e) = run(shortcuts, on_hotkey, stop_rx).await {
                    on_error(e.to_string());
                }
            });
        }
    }

    async fn run(
        shortcuts: Vec<(Hotkey, String, String)>,
        on_hotkey: impl Fn(Hotkey, bool),
        mut stop: tokio::sync::oneshot::Receiver<()>,
    ) -> Result<(), ashpd::Error> {
        let portal = GlobalShortcuts::new().await?;
        let session = portal.create_session().await?;
        let wanted: Vec<NewShortcut> = shortcuts
            .iter()
            .map(|(h, trigger, description)| {
                NewShortcut::new(hotkey_id(*h), description.as_str()).preferred_trigger(xdg_trigger(trigger).as_deref())
            })
            .collect();
        portal.bind_shortcuts(&session, &wanted, None).await?.response()?;
        let activated = portal.receive_activated().await?.map(|a| (a.shortcut_id().to_string(), true));
        let deactivated = portal.receive_deactivated().await?.map(|d| (d.shortcut_id().to_string(), false));
        let mut events = futures_util::stream::select(activated, deactivated);
        loop {
            tokio::select! {
                _ = &mut stop => break,
                event = events.next() => match event {
                    Some((id, pressed)) => {
                        if let Some(h) = hotkey_from_id(&id) {
                            on_hotkey(h, pressed);
                        }
                    }
                    None => break,
                },
            }
        }
        let _ = session.close().await;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn converts_shortcuts_to_the_portal_format() {
        assert_eq!(xdg_trigger("Ctrl+Alt+Space").as_deref(), Some("CTRL+ALT+space"));
        assert_eq!(xdg_trigger("Ctrl+Shift+KeyD").as_deref(), Some("CTRL+SHIFT+d"));
        assert_eq!(xdg_trigger("Ctrl+Alt+C").as_deref(), Some("CTRL+ALT+c"));
        assert_eq!(xdg_trigger("Super+Digit1").as_deref(), Some("LOGO+1"));
        assert_eq!(xdg_trigger("F9").as_deref(), Some("F9"));
        assert_eq!(xdg_trigger("Ctrl+Alt").as_deref(), None);
        assert_eq!(hotkey_from_id(hotkey_id(Hotkey::Translate)), Some(Hotkey::Translate));
    }
}
