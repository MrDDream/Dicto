//! Icône de la zone de notification : ouvrir Dicto, dicter, quitter. Elle passe à l'orange
//! pendant l'écoute.

use std::sync::Arc;

use tauri::image::Image;
use tauri::menu::{Menu, MenuItem, PredefinedMenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{AppHandle, Manager};

use crate::core::Core;
use crate::locale::tr;

const TRAY_ID: &str = "dicto";
const IDLE: &[u8] = include_bytes!("../icons/tray.png");
const LISTENING: &[u8] = include_bytes!("../icons/tray-listening.png");

/// Le menu, dans la langue de l'interface.
fn menu(app: &AppHandle) -> tauri::Result<Menu<tauri::Wry>> {
    let open = MenuItem::with_id(app, "open", tr("Ouvrir Dicto", "Open Dicto"), true, None::<&str>)?;
    let dictate = MenuItem::with_id(app, "dictate", tr("Dicter / arrêter", "Dictate / stop"), true, None::<&str>)?;
    let quit = MenuItem::with_id(app, "quit", tr("Quitter Dicto", "Quit Dicto"), true, None::<&str>)?;
    Menu::with_items(app, &[&open, &dictate, &PredefinedMenuItem::separator(app)?, &quit])
}

/// Après un changement de langue.
pub fn relabel(app: &AppHandle) {
    if let (Some(tray), Ok(menu)) = (app.tray_by_id(TRAY_ID), menu(app)) {
        let _ = tray.set_menu(Some(menu));
    }
}

pub fn create(app: &AppHandle) -> tauri::Result<()> {
    let menu = menu(app)?;
    TrayIconBuilder::with_id(TRAY_ID)
        .icon(Image::from_bytes(IDLE)?)
        .tooltip("Dicto")
        .menu(&menu)
        .show_menu_on_left_click(false)
        .on_menu_event(|app, event| match event.id.as_ref() {
            "open" => show_main(app),
            "dictate" => {
                if let Some(core) = app.try_state::<Arc<Core>>() {
                    core.press_from_app();
                }
            }
            "quit" => {
                if let Some(core) = app.try_state::<Arc<Core>>() {
                    core.cancel();
                }
                app.exit(0);
            }
            _ => {}
        })
        .on_tray_icon_event(|tray, event| {
            if let TrayIconEvent::Click { button: MouseButton::Left, button_state: MouseButtonState::Up, .. } = event {
                show_main(tray.app_handle());
            }
        })
        .build(app)?;
    Ok(())
}

pub fn show_main(app: &AppHandle) {
    if let Some(w) = app.get_webview_window("main") {
        let _ = w.unminimize();
        let _ = w.show();
        let _ = w.set_focus();
    }
}

pub fn set_listening(app: &AppHandle, listening: bool) {
    if let Some(tray) = app.tray_by_id(TRAY_ID) {
        if let Ok(icon) = Image::from_bytes(if listening { LISTENING } else { IDLE }) {
            let _ = tray.set_icon(Some(icon));
        }
        let _ = tray.set_tooltip(Some(if listening { tr("Dicto · je t'écoute", "Dicto · listening") } else { "Dicto" }));
    }
}
