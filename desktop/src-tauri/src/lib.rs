//! Dicto pour Windows, Linux et macOS.

mod audio;
mod commands;
mod core;
mod decode;
mod foreground;
mod insert;
mod journal;
mod locale;
mod mouse;
#[cfg(target_os = "linux")]
mod portal;
mod providers;
mod secrets;
mod settings;
mod sound;
mod tray;
mod update;

use std::sync::Arc;

use tauri::{Manager, WindowEvent};
use tauri_plugin_global_shortcut::ShortcutState;

use crate::core::Core;

pub fn run() {
    // Les raccourcis sont traités sur un fil à part, dans l'ordre : le greffon appelle son
    // gestionnaire en gardant sa liste verrouillée, et la dictée (ré)enregistre Échap au passage.
    // Traité sur place, ce réenregistrement bloquait Dicto.
    let (hotkey_tx, hotkey_rx) = std::sync::mpsc::channel::<(u32, bool)>();
    tauri::Builder::default()
        // Une seule instance : relancer Dicto ramène la fenêtre existante.
        .plugin(tauri_plugin_single_instance::init(|app, _args, _cwd| tray::show_main(app)))
        .plugin(tauri_plugin_dialog::init())
        .plugin(
            tauri_plugin_global_shortcut::Builder::new()
                .with_handler(move |_app, shortcut, event| {
                    let _ = hotkey_tx.send((shortcut.id(), event.state() == ShortcutState::Pressed));
                })
                .build(),
        )
        .setup(move |app| {
            if let Ok(dir) = app.path().app_data_dir() {
                let _ = std::fs::create_dir_all(&dir);
                journal::init(&dir);
            }
            let core = Core::new(app.handle().clone())?;
            app.manage(core.clone());
            let worker = core.clone();
            std::thread::Builder::new().name("dicto-raccourcis".into()).spawn(move || {
                while let Ok((id, pressed)) = hotkey_rx.recv() {
                    let run = std::panic::AssertUnwindSafe(|| worker.on_hotkey(id, pressed));
                    if std::panic::catch_unwind(run).is_err() {
                        log::error!("raccourci : erreur interne (voir le plantage ci-dessus)");
                    }
                }
            })?;
            app.manage(update::Updates::new(core.storage.clone()));
            tray::create(app.handle())?;
            core.start();
            update::start(app.handle().clone());
            Ok(())
        })
        // Fermer la fenêtre la range dans la zone de notification : les raccourcis restent actifs.
        .on_window_event(|window, event| match event {
            WindowEvent::CloseRequested { api, .. } if window.label() == "main" => {
                api.prevent_close();
                let _ = window.hide();
            }
            // La pastille déplacée à la main garde sa place pour les prochaines dictées.
            WindowEvent::Moved(pos) if window.label() == "pill" => {
                if let Some(core) = window.try_state::<Arc<Core>>() {
                    core.on_pill_moved(pos.x, pos.y);
                }
            }
            _ => {}
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
            commands::read_journal,
            commands::reset_pill_position,
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
            commands::set_history_pinned,
            commands::update_history_text,
            commands::replacements,
            commands::set_replacement,
            commands::remove_replacement,
            commands::profiles,
            commands::save_profile,
            commands::delete_profile,
            commands::last_app,
            commands::translation_targets,
            commands::export_bundle,
            commands::import_bundle,
            commands::platform_info,
            commands::save_text,
            commands::meeting_minutes,
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
