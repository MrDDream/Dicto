//! Journal de Dicto : les messages `log` et les plantages, dans `dicto.log` (dossier des données
//! de l'app), pour comprendre un problème après coup. Le fichier repart de zéro au-delà de 1 Mo.

use std::fs::{File, OpenOptions};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::{Mutex, OnceLock};

const MAX_BYTES: u64 = 1024 * 1024;

static PATH: OnceLock<PathBuf> = OnceLock::new();

struct Journal {
    file: Mutex<Option<File>>,
}

fn open(path: &Path) -> Option<File> {
    if std::fs::metadata(path).is_ok_and(|m| m.len() > MAX_BYTES) {
        let _ = std::fs::remove_file(path);
    }
    OpenOptions::new().create(true).append(true).open(path).ok()
}

fn stamp() -> String {
    let secs = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    let (h, m, s) = ((secs / 3600) % 24, (secs / 60) % 60, secs % 60);
    let days = secs / 86_400;
    format!("j{days} {h:02}:{m:02}:{s:02} UTC")
}

fn append(line: &str) {
    if let Some(path) = PATH.get() {
        if let Some(mut f) = open(path) {
            let _ = writeln!(f, "{} {line}", stamp());
        }
    }
}

impl log::Log for Journal {
    fn enabled(&self, metadata: &log::Metadata) -> bool {
        metadata.level() <= log::Level::Info || metadata.target().starts_with("dicto")
    }

    fn log(&self, record: &log::Record) {
        if !self.enabled(record.metadata()) {
            return;
        }
        let line = format!("{} {} : {}", record.level(), record.target(), record.args());
        if let Ok(mut file) = self.file.lock() {
            if let Some(f) = file.as_mut() {
                let _ = writeln!(f, "{} {line}", stamp());
            }
        }
        #[cfg(debug_assertions)]
        eprintln!("{line}");
    }

    fn flush(&self) {
        if let Ok(mut file) = self.file.lock() {
            if let Some(f) = file.as_mut() {
                let _ = f.flush();
            }
        }
    }
}

/// Branche le journal sur `dir/dicto.log` et note les plantages (avec l'endroit du code).
pub fn init(dir: &Path) {
    let path = dir.join("dicto.log");
    let file = open(&path);
    let _ = PATH.set(path);
    let journal: &'static Journal = Box::leak(Box::new(Journal { file: Mutex::new(file) }));
    if log::set_logger(journal).is_ok() {
        log::set_max_level(log::LevelFilter::Debug);
    }
    let previous = std::panic::take_hook();
    std::panic::set_hook(Box::new(move |info| {
        let thread = std::thread::current().name().unwrap_or("?").to_string();
        let place = info.location().map(|l| format!("{}:{}", l.file(), l.line())).unwrap_or_default();
        let what = info
            .payload()
            .downcast_ref::<&str>()
            .map(|s| s.to_string())
            .or_else(|| info.payload().downcast_ref::<String>().cloned())
            .unwrap_or_default();
        append(&format!("PLANTAGE (fil « {thread} ») {place} : {what}"));
        previous(info);
    }));
    log::info!("Dicto {} démarre ({})", env!("CARGO_PKG_VERSION"), std::env::consts::OS);
}

/// Contenu du journal (les 200 dernières lignes), pour le copier dans un message.
pub fn tail() -> String {
    let Some(path) = PATH.get() else { return String::new() };
    let text = std::fs::read_to_string(path).unwrap_or_default();
    let lines: Vec<&str> = text.lines().collect();
    lines[lines.len().saturating_sub(200)..].join("\n")
}
