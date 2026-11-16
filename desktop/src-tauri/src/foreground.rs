//! L'app au premier plan, pour les profils par app : nom de l'exécutable sous Windows,
//! identifiant du paquet sous macOS, classe de fenêtre (WM_CLASS) sous Linux X11.
//! Wayland ne donne pas cette information aux apps : pas de profil par app.

use serde::Serialize;

#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AppInfo {
    /// Ce qui identifie l'app dans un profil (« slack », « com.apple.mail », « firefox »).
    pub id: String,
    /// Nom lisible.
    pub label: String,
}

/// Vrai si la session graphique est Wayland (Linux).
pub fn is_wayland() -> bool {
    cfg!(target_os = "linux")
        && (std::env::var("XDG_SESSION_TYPE").is_ok_and(|v| v.eq_ignore_ascii_case("wayland"))
            || std::env::var_os("WAYLAND_DISPLAY").is_some())
}

/// L'app au premier plan, si ce n'est pas Dicto et si le système le dit.
pub fn current() -> Option<AppInfo> {
    if is_wayland() {
        return None;
    }
    imp::current().filter(|a| !a.id.is_empty())
}

/// Surveille l'app au premier plan (toutes les 1,5 s) et signale chaque changement, Dicto exclu.
pub fn watch(on_change: impl Fn(AppInfo) + Send + 'static) {
    if is_wayland() {
        return;
    }
    let _ = std::thread::Builder::new().name("dicto-app-active".into()).spawn(move || {
        let mut last: Option<AppInfo> = None;
        loop {
            if let Some(app) = current() {
                if last.as_ref() != Some(&app) {
                    on_change(app.clone());
                    last = Some(app);
                }
            }
            std::thread::sleep(std::time::Duration::from_millis(1500));
        }
    });
}

#[cfg(any(windows, target_os = "linux"))]
fn capitalize(s: &str) -> String {
    let mut c = s.chars();
    match c.next() {
        Some(f) => f.to_uppercase().chain(c).collect(),
        None => String::new(),
    }
}

#[cfg(windows)]
mod imp {
    use super::{AppInfo, capitalize};
    use windows_sys::Win32::Foundation::CloseHandle;
    use windows_sys::Win32::System::Threading::{
        OpenProcess, PROCESS_NAME_WIN32, PROCESS_QUERY_LIMITED_INFORMATION, QueryFullProcessImageNameW,
    };
    use windows_sys::Win32::UI::WindowsAndMessaging::{GetForegroundWindow, GetWindowThreadProcessId};

    pub fn current() -> Option<AppInfo> {
        // SAFETY : appels Win32 sans pointeur conservé ; les tampons sont locaux et dimensionnés.
        unsafe {
            let hwnd = GetForegroundWindow();
            if hwnd.is_null() {
                return None;
            }
            let mut pid = 0u32;
            GetWindowThreadProcessId(hwnd, &mut pid);
            if pid == 0 || pid == std::process::id() {
                return None;
            }
            let process = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, pid);
            if process.is_null() {
                return None;
            }
            let mut buf = [0u16; 1024];
            let mut len = buf.len() as u32;
            let ok = QueryFullProcessImageNameW(process, PROCESS_NAME_WIN32, buf.as_mut_ptr(), &mut len);
            CloseHandle(process);
            if ok == 0 {
                return None;
            }
            let path = String::from_utf16_lossy(&buf[..len as usize]);
            let stem = std::path::Path::new(&path).file_stem()?.to_string_lossy().to_lowercase();
            Some(AppInfo { label: capitalize(&stem), id: stem })
        }
    }
}

#[cfg(target_os = "macos")]
mod imp {
    use super::AppInfo;
    use objc2_app_kit::NSWorkspace;

    pub fn current() -> Option<AppInfo> {
        let app = NSWorkspace::sharedWorkspace().frontmostApplication()?;
        let id = app.bundleIdentifier()?.to_string();
        // Dicto lui-même (fenêtre de réglages ouverte) : rien à retenir.
        if id.to_lowercase().contains("dicto") {
            return None;
        }
        let label = app.localizedName().map(|n| n.to_string()).unwrap_or_else(|| id.clone());
        Some(AppInfo { id, label })
    }
}

#[cfg(target_os = "linux")]
mod imp {
    use super::{AppInfo, capitalize};
    use x11rb::connection::Connection;
    use x11rb::protocol::xproto::{AtomEnum, ConnectionExt, Window};
    use x11rb::rust_connection::RustConnection;

    thread_local! {
        static CONN: std::cell::RefCell<Option<(RustConnection, Window)>> = const { std::cell::RefCell::new(None) };
    }

    pub fn current() -> Option<AppInfo> {
        CONN.with(|cell| {
            let mut cell = cell.borrow_mut();
            if cell.is_none() {
                let (conn, screen) = x11rb::connect(None).ok()?;
                let root = conn.setup().roots[screen].root;
                *cell = Some((conn, root));
            }
            let (conn, root) = cell.as_ref()?;
            let result = active(conn, *root);
            if result.is_none() {
                // Connexion perdue (serveur relancé) : on la refera au prochain appel.
                if conn.flush().is_err() {
                    *cell = None;
                }
            }
            result
        })
    }

    fn atom(conn: &RustConnection, name: &str) -> Option<u32> {
        Some(conn.intern_atom(false, name.as_bytes()).ok()?.reply().ok()?.atom)
    }

    fn active(conn: &RustConnection, root: Window) -> Option<AppInfo> {
        let net_active = atom(conn, "_NET_ACTIVE_WINDOW")?;
        let reply = conn.get_property(false, root, net_active, AtomEnum::WINDOW, 0, 1).ok()?.reply().ok()?;
        let window = reply.value32()?.next().filter(|w| *w != 0)?;
        // Dicto lui-même : rien à retenir.
        if let Some(pid_atom) = atom(conn, "_NET_WM_PID") {
            let pid = conn
                .get_property(false, window, pid_atom, AtomEnum::CARDINAL, 0, 1)
                .ok()
                .and_then(|c| c.reply().ok())
                .and_then(|r| r.value32().and_then(|mut v| v.next()));
            if pid == Some(std::process::id()) {
                return None;
            }
        }
        let class = conn.get_property(false, window, AtomEnum::WM_CLASS, AtomEnum::STRING, 0, 256).ok()?.reply().ok()?;
        // WM_CLASS : « instance\0Classe\0 » ; la classe est la plus stable.
        let parts: Vec<String> = class
            .value
            .split(|b| *b == 0)
            .filter(|p| !p.is_empty())
            .map(|p| String::from_utf8_lossy(p).into_owned())
            .collect();
        let class = parts.last()?.clone();
        let id = class.to_lowercase();
        Some(AppInfo { label: capitalize(&class), id })
    }
}

#[cfg(not(any(windows, target_os = "macos", target_os = "linux")))]
mod imp {
    use super::AppInfo;

    pub fn current() -> Option<AppInfo> {
        None
    }
}
