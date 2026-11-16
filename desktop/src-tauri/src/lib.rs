//! Dicto pour Windows, Linux et macOS.

mod audio;
mod commands;
mod core;
mod insert;
mod settings;
mod tray;

use std::sync::Arc;

use tauri::{Manager, WindowEvent};
use tauri_plugin_global_shortcut::ShortcutState;

use crate::core::Core;

pub fn run() {
    tauri::Builder::default()
        // Une seule instance : relancer Dicto ramène la fenêtre existante.
        .plugin(tauri_plugin_single_instance::init(|app, _args, _cwd| tray::show_main(app)))
        .plugin(
            tauri_plugin_global_shortcut::Builder::new()
                .with_handler(|app, shortcut, event| {
                    if let Some(core) = app.try_state::<Arc<Core>>() {
                        core.on_hotkey(shortcut.id(), event.state() == ShortcutState::Pressed);
                    }
                })
                .build(),
        )
        .setup(|app| {
            let core = Core::new(app.handle().clone())?;
            app.manage(core.clone());
            tray::create(app.handle())?;
            core.start();
            Ok(())
        })
        // Fermer la fenêtre la range dans la zone de notification : les raccourcis restent actifs.
        .on_window_event(|window, event| {
            if let WindowEvent::CloseRequested { api, .. } = event {
                if window.label() == "main" {
                    api.prevent_close();
                    let _ = window.hide();
                }
            }
        })
        .invoke_handler(tauri::generate_handler![
            commands::snapshot,
            commands::get_settings,
            commands::set_settings,
            commands::press,
            commands::release,
            commands::cancel,
            commands::copy_text,
            commands::pause_shortcuts,
            commands::list_models,
            commands::download_model,
            commands::cancel_download,
            commands::delete_model,
            commands::use_model,
            commands::history,
            commands::delete_history,
            commands::clear_history,
            commands::vocabulary,
            commands::add_term,
            commands::remove_term,
            commands::credits,
        ])
        .run(tauri::generate_context!())
        .expect("Dicto n'a pas pu démarrer");
}
