//! Dicto pour Windows, Linux et macOS.

mod audio;
mod commands;
mod core;
mod decode;
mod insert;
mod locale;
mod providers;
mod secrets;
mod settings;
mod tray;
mod update;

use std::sync::Arc;

use tauri::{Manager, WindowEvent};
use tauri_plugin_global_shortcut::ShortcutState;

use crate::core::Core;

pub fn run() {
    tauri::Builder::default()
        // Une seule instance : relancer Dicto ramène la fenêtre existante.
        .plugin(tauri_plugin_single_instance::init(|app, _args, _cwd| tray::show_main(app)))
        .plugin(tauri_plugin_dialog::init())
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
            app.manage(update::Updates::new(core.storage.clone()));
            tray::create(app.handle())?;
            core.start();
            update::start(app.handle().clone());
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
            update::update_state,
            update::update_set_consent,
            update::update_check,
            update::update_skip,
            update::update_later,
            update::update_install,
            update::update_open_page,
            commands::snapshot,
            commands::get_settings,
            commands::set_settings,
            commands::press,
            commands::release,
            commands::cancel,
            commands::copy_text,
            commands::pause_shortcuts,
            commands::set_ui_locale,
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
            commands::providers,
            commands::save_provider,
            commands::forget_provider_key,
            commands::provider_models,
            commands::set_cloud_engine,
            commands::set_refine_provider,
            commands::refine_presets,
            commands::refine_text,
            commands::pick_audio_file,
            commands::transcribe_file,
            commands::cancel_file,
            commands::export_file,
        ])
        .run(tauri::generate_context!())
        .expect("Dicto n'a pas pu démarrer");
}
