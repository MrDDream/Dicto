//! Boutons latéraux de la souris (« précédent » et « suivant ») comme déclencheurs de dictée.
//!
//! - Windows : crochet bas niveau `WH_MOUSE_LL` ; un bouton attribué n'est pas transmis à l'app.
//! - macOS : `CGEventTap`, avec l'autorisation « Surveillance de l'entrée » ; idem.
//! - Linux X11 : événements bruts XInput 2 ; l'app reçoit aussi le clic (X11 ne permet pas de le retenir).
//! - Wayland : impossible pour une app, l'interface le signale.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Button {
    Back,
    Forward,
}

static BACK: AtomicBool = AtomicBool::new(false);
static FORWARD: AtomicBool = AtomicBool::new(false);
static STARTED: AtomicBool = AtomicBool::new(false);

/// Boutons attribués à une dictée (les autres passent sans être vus).
pub fn set_bound(back: bool, forward: bool) {
    BACK.store(back, Ordering::SeqCst);
    FORWARD.store(forward, Ordering::SeqCst);
}

fn is_bound(b: Button) -> bool {
    match b {
        Button::Back => BACK.load(Ordering::SeqCst),
        Button::Forward => FORWARD.load(Ordering::SeqCst),
    }
}

/// Lance l'écoute (une seule fois). `on_event(bouton, appuyé)` est appelé sur un fil à part :
/// le crochet du système doit rendre la main tout de suite.
pub fn start(on_event: impl Fn(Button, bool) + Send + 'static) {
    if STARTED.swap(true, Ordering::SeqCst) || crate::foreground::is_wayland() {
        return;
    }
    let (tx, rx) = mpsc::channel::<(Button, bool)>();
    let _ = std::thread::Builder::new().name("dicto-souris".into()).spawn(move || {
        while let Ok((b, pressed)) = rx.recv() {
            on_event(b, pressed);
        }
    });
    let _ = std::thread::Builder::new().name("dicto-souris-ecoute".into()).spawn(move || {
        if let Err(e) = imp::listen(tx) {
            log::warn!("boutons de la souris indisponibles : {e}");
        }
    });
}

#[cfg(windows)]
mod imp {
    use super::{Button, is_bound};
    use std::sync::OnceLock;
    use std::sync::mpsc::Sender;
    use windows_sys::Win32::Foundation::{LPARAM, LRESULT, WPARAM};
    use windows_sys::Win32::System::LibraryLoader::GetModuleHandleW;
    use windows_sys::Win32::UI::WindowsAndMessaging::{
        CallNextHookEx, GetMessageW, MSG, MSLLHOOKSTRUCT, SetWindowsHookExW, WH_MOUSE_LL, WM_XBUTTONDOWN, WM_XBUTTONUP,
    };

    static TX: OnceLock<std::sync::Mutex<Sender<(Button, bool)>>> = OnceLock::new();

    unsafe extern "system" fn hook(code: i32, wparam: WPARAM, lparam: LPARAM) -> LRESULT {
        let message = wparam as u32;
        if code >= 0 && (message == WM_XBUTTONDOWN || message == WM_XBUTTONUP) {
            // SAFETY : pour WH_MOUSE_LL, lparam pointe sur un MSLLHOOKSTRUCT valide pendant l'appel.
            let info = unsafe { &*(lparam as *const MSLLHOOKSTRUCT) };
            let button = match (info.mouseData >> 16) & 0xffff {
                1 => Some(Button::Back),
                2 => Some(Button::Forward),
                _ => None,
            };
            if let Some(b) = button.filter(|b| is_bound(*b)) {
                if let Some(tx) = TX.get() {
                    let _ = tx.lock().unwrap().send((b, message == WM_XBUTTONDOWN));
                }
                // Gardé pour Dicto : le navigateur ne revient pas en arrière.
                return 1;
            }
        }
        // SAFETY : on passe la main au crochet suivant avec les mêmes arguments.
        unsafe { CallNextHookEx(std::ptr::null_mut(), code, wparam, lparam) }
    }

    pub fn listen(tx: Sender<(Button, bool)>) -> Result<(), String> {
        let _ = TX.set(std::sync::Mutex::new(tx));
        // SAFETY : crochet global posé sur ce fil, qui garde ensuite sa boucle de messages.
        unsafe {
            let module = GetModuleHandleW(std::ptr::null());
            let handle = SetWindowsHookExW(WH_MOUSE_LL, Some(hook), module, 0);
            if handle.is_null() {
                return Err("crochet de la souris refusé".into());
            }
            let mut msg: MSG = std::mem::zeroed();
            while GetMessageW(&mut msg, std::ptr::null_mut(), 0, 0) > 0 {}
        }
        Ok(())
    }
}

#[cfg(target_os = "macos")]
mod imp {
    use super::{Button, is_bound};
    use core_foundation::runloop::CFRunLoop;
    use core_graphics::event::{
        CGEventTap, CGEventTapLocation, CGEventTapOptions, CGEventTapPlacement, CGEventType, CallbackResult, EventField,
    };
    use std::sync::mpsc::Sender;

    pub fn listen(tx: Sender<(Button, bool)>) -> Result<(), String> {
        CGEventTap::with_enabled(
            CGEventTapLocation::Session,
            CGEventTapPlacement::HeadInsertEventTap,
            CGEventTapOptions::Default,
            vec![CGEventType::OtherMouseDown, CGEventType::OtherMouseUp],
            move |_proxy, kind, event| {
                let button = match event.get_integer_value_field(EventField::MOUSE_EVENT_BUTTON_NUMBER) {
                    3 => Some(Button::Back),
                    4 => Some(Button::Forward),
                    _ => None,
                };
                match button.filter(|b| is_bound(*b)) {
                    Some(b) => {
                        let _ = tx.send((b, matches!(kind, CGEventType::OtherMouseDown)));
                        CallbackResult::Drop
                    }
                    None => CallbackResult::Keep,
                }
            },
            CFRunLoop::run_current,
        )
        .map_err(|_| "autorise Dicto dans Réglages Système › Confidentialité et sécurité › Surveillance de l'entrée".to_string())
    }
}

#[cfg(target_os = "linux")]
mod imp {
    use super::{Button, is_bound};
    use std::sync::mpsc::Sender;
    use x11rb::connection::Connection;
    use x11rb::protocol::Event;
    use x11rb::protocol::xinput::{self, ConnectionExt as _};

    pub fn listen(tx: Sender<(Button, bool)>) -> Result<(), String> {
        let (conn, screen) = x11rb::connect(None).map_err(|e| e.to_string())?;
        conn.xinput_xi_query_version(2, 0)
            .map_err(|e| e.to_string())?
            .reply()
            .map_err(|e| format!("XInput 2 absent : {e}"))?;
        let root = conn.setup().roots[screen].root;
        let mask = xinput::EventMask {
            deviceid: xinput::Device::ALL_MASTER.into(),
            mask: vec![xinput::XIEventMask::RAW_BUTTON_PRESS | xinput::XIEventMask::RAW_BUTTON_RELEASE],
        };
        conn.xinput_xi_select_events(root, &[mask]).map_err(|e| e.to_string())?;
        conn.flush().map_err(|e| e.to_string())?;
        loop {
            let (detail, pressed) = match conn.wait_for_event().map_err(|e| e.to_string())? {
                Event::XinputRawButtonPress(e) => (e.detail, true),
                Event::XinputRawButtonRelease(e) => (e.detail, false),
                _ => continue,
            };
            let button = match detail {
                8 => Button::Back,
                9 => Button::Forward,
                _ => continue,
            };
            if is_bound(button) && tx.send((button, pressed)).is_err() {
                return Ok(());
            }
        }
    }
}

#[cfg(not(any(windows, target_os = "macos", target_os = "linux")))]
mod imp {
    use super::Button;
    use std::sync::mpsc::Sender;

    pub fn listen(_tx: Sender<(Button, bool)>) -> Result<(), String> {
        Err("système non pris en charge".into())
    }
}
