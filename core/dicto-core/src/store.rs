//! Stockage local SQLite : historique, vocabulaire, profils par app, réglages.
//!
//! Les clés d'API n'y sont jamais écrites : elles restent dans le Keystore Android
//! ou le trousseau du système, et sont passées au cœur à l'exécution.

use std::path::Path;

use rusqlite::{Connection, OptionalExtension, params};
use serde::{Deserialize, Serialize};

use crate::Result;

const MIGRATIONS: &[&str] = &[
    // v1
    "CREATE TABLE history (
        id          INTEGER PRIMARY KEY,
        created_at  INTEGER NOT NULL,
        text        TEXT NOT NULL,
        raw_text    TEXT,
        engine      TEXT NOT NULL,
        language    TEXT,
        duration_ms INTEGER NOT NULL DEFAULT 0,
        app         TEXT
     );
     CREATE INDEX history_created ON history(created_at DESC);
     CREATE TABLE vocabulary (
        id         INTEGER PRIMARY KEY,
        term       TEXT NOT NULL UNIQUE COLLATE NOCASE,
        created_at INTEGER NOT NULL
     );
     CREATE TABLE profiles (
        id            INTEGER PRIMARY KEY,
        app_id        TEXT NOT NULL UNIQUE,
        label         TEXT,
        language      TEXT,
        engine        TEXT,
        mode          TEXT,
        refine_preset TEXT
     );
     CREATE TABLE settings (
        key   TEXT PRIMARY KEY,
        value TEXT NOT NULL
     );",
];

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct NewHistoryEntry {
    pub text: String,
    /// Texte avant post-traitement LLM, s'il y en a eu un.
    pub raw_text: Option<String>,
    pub engine: String,
    pub language: Option<String>,
    pub duration_ms: u64,
    /// App dans laquelle le texte a été inséré (package Android, exécutable…).
    pub app: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct HistoryEntry {
    pub id: i64,
    pub created_at: i64,
    pub text: String,
    pub raw_text: Option<String>,
    pub engine: String,
    pub language: Option<String>,
    pub duration_ms: u64,
    pub app: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Term {
    pub id: i64,
    pub term: String,
}

/// Réglages appliqués automatiquement quand on dicte dans une app donnée.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct Profile {
    pub id: Option<i64>,
    pub app_id: String,
    pub label: Option<String>,
    pub language: Option<String>,
    pub engine: Option<String>,
    pub mode: Option<String>,
    pub refine_preset: Option<String>,
}

pub struct Store {
    conn: Connection,
}

pub fn now_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

impl Store {
    pub fn open(path: impl AsRef<Path>) -> Result<Self> {
        let conn = Connection::open(path)?;
        conn.pragma_update(None, "journal_mode", "WAL")?;
        Self::init(conn)
    }

    pub fn open_in_memory() -> Result<Self> {
        Self::init(Connection::open_in_memory()?)
    }

    fn init(conn: Connection) -> Result<Self> {
        conn.pragma_update(None, "foreign_keys", "ON")?;
        let version: i64 = conn.pragma_query_value(None, "user_version", |r| r.get(0))?;
        for (i, sql) in MIGRATIONS.iter().enumerate().skip(version.max(0) as usize) {
            conn.execute_batch(&format!("BEGIN; {sql}; PRAGMA user_version = {}; COMMIT;", i + 1))?;
        }
        Ok(Self { conn })
    }

    // --- Historique -------------------------------------------------------------

    pub fn add_history(&self, e: &NewHistoryEntry) -> Result<i64> {
        self.conn.execute(
            "INSERT INTO history (created_at, text, raw_text, engine, language, duration_ms, app)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
            params![now_ms(), e.text, e.raw_text, e.engine, e.language, e.duration_ms as i64, e.app],
        )?;
        Ok(self.conn.last_insert_rowid())
    }

    /// Entrées les plus récentes d'abord, filtrées par texte si `query` est donné.
    pub fn list_history(&self, query: Option<&str>, limit: u32, offset: u32) -> Result<Vec<HistoryEntry>> {
        let pattern = query
            .map(str::trim)
            .filter(|q| !q.is_empty())
            .map(|q| format!("%{}%", q.replace('\\', "\\\\").replace('%', "\\%").replace('_', "\\_")));
        let mut stmt = self.conn.prepare(
            "SELECT id, created_at, text, raw_text, engine, language, duration_ms, app FROM history
             WHERE ?1 IS NULL OR text LIKE ?1 ESCAPE '\\'
             ORDER BY created_at DESC, id DESC LIMIT ?2 OFFSET ?3",
        )?;
        let rows = stmt.query_map(params![pattern, limit, offset], |r| {
            Ok(HistoryEntry {
                id: r.get(0)?,
                created_at: r.get(1)?,
                text: r.get(2)?,
                raw_text: r.get(3)?,
                engine: r.get(4)?,
                language: r.get(5)?,
                duration_ms: r.get::<_, i64>(6)? as u64,
                app: r.get(7)?,
            })
        })?;
        Ok(rows.collect::<rusqlite::Result<_>>()?)
    }

    pub fn delete_history(&self, id: i64) -> Result<()> {
        self.conn.execute("DELETE FROM history WHERE id = ?1", [id])?;
        Ok(())
    }

    pub fn clear_history(&self) -> Result<()> {
        self.conn.execute("DELETE FROM history", [])?;
        Ok(())
    }

    // --- Vocabulaire ------------------------------------------------------------

    /// Ajoute un terme (ignoré s'il existe déjà, sans tenir compte de la casse).
    pub fn add_term(&self, term: &str) -> Result<()> {
        let term = term.trim();
        if !term.is_empty() {
            self.conn.execute(
                "INSERT OR IGNORE INTO vocabulary (term, created_at) VALUES (?1, ?2)",
                params![term, now_ms()],
            )?;
        }
        Ok(())
    }

    pub fn remove_term(&self, id: i64) -> Result<()> {
        self.conn.execute("DELETE FROM vocabulary WHERE id = ?1", [id])?;
        Ok(())
    }

    pub fn terms(&self) -> Result<Vec<Term>> {
        let mut stmt = self
            .conn
            .prepare("SELECT id, term FROM vocabulary ORDER BY term COLLATE NOCASE")?;
        let rows = stmt.query_map([], |r| Ok(Term { id: r.get(0)?, term: r.get(1)? }))?;
        Ok(rows.collect::<rusqlite::Result<_>>()?)
    }

    // --- Profils par app --------------------------------------------------------

    pub fn upsert_profile(&self, p: &Profile) -> Result<i64> {
        self.conn.execute(
            "INSERT INTO profiles (app_id, label, language, engine, mode, refine_preset)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6)
             ON CONFLICT(app_id) DO UPDATE SET label = excluded.label, language = excluded.language,
                engine = excluded.engine, mode = excluded.mode, refine_preset = excluded.refine_preset",
            params![p.app_id, p.label, p.language, p.engine, p.mode, p.refine_preset],
        )?;
        Ok(self
            .conn
            .query_row("SELECT id FROM profiles WHERE app_id = ?1", [&p.app_id], |r| r.get(0))?)
    }

    pub fn profile_for_app(&self, app_id: &str) -> Result<Option<Profile>> {
        Ok(self
            .conn
            .query_row(
                "SELECT id, app_id, label, language, engine, mode, refine_preset FROM profiles WHERE app_id = ?1",
                [app_id],
                row_to_profile,
            )
            .optional()?)
    }

    pub fn profiles(&self) -> Result<Vec<Profile>> {
        let mut stmt = self.conn.prepare(
            "SELECT id, app_id, label, language, engine, mode, refine_preset FROM profiles
             ORDER BY COALESCE(label, app_id) COLLATE NOCASE",
        )?;
        let rows = stmt.query_map([], row_to_profile)?;
        Ok(rows.collect::<rusqlite::Result<_>>()?)
    }

    pub fn delete_profile(&self, id: i64) -> Result<()> {
        self.conn.execute("DELETE FROM profiles WHERE id = ?1", [id])?;
        Ok(())
    }

    // --- Réglages ---------------------------------------------------------------

    pub fn setting(&self, key: &str) -> Result<Option<String>> {
        Ok(self
            .conn
            .query_row("SELECT value FROM settings WHERE key = ?1", [key], |r| r.get(0))
            .optional()?)
    }

    pub fn set_setting(&self, key: &str, value: &str) -> Result<()> {
        self.conn.execute(
            "INSERT INTO settings (key, value) VALUES (?1, ?2)
             ON CONFLICT(key) DO UPDATE SET value = excluded.value",
            params![key, value],
        )?;
        Ok(())
    }
}

fn row_to_profile(r: &rusqlite::Row<'_>) -> rusqlite::Result<Profile> {
    Ok(Profile {
        id: r.get(0)?,
        app_id: r.get(1)?,
        label: r.get(2)?,
        language: r.get(3)?,
        engine: r.get(4)?,
        mode: r.get(5)?,
        refine_preset: r.get(6)?,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn entry(text: &str) -> NewHistoryEntry {
        NewHistoryEntry {
            text: text.into(),
            raw_text: None,
            engine: "local:whisper-small-q5_1".into(),
            language: Some("fr".into()),
            duration_ms: 1200,
            app: Some("org.thoughtcrime.securesms".into()),
        }
    }

    #[test]
    fn history_is_ordered_and_searchable() {
        let s = Store::open_in_memory().unwrap();
        s.add_history(&entry("Appeler le plombier demain")).unwrap();
        let id = s.add_history(&entry("Ajouter du lait à la liste, 100% bio")).unwrap();
        let all = s.list_history(None, 10, 0).unwrap();
        assert_eq!(all.len(), 2);
        assert_eq!(all[0].id, id, "le plus récent d'abord");

        assert_eq!(s.list_history(Some("plombier"), 10, 0).unwrap().len(), 1);
        assert_eq!(s.list_history(Some("100%"), 10, 0).unwrap().len(), 1);
        assert_eq!(s.list_history(Some("%"), 10, 0).unwrap().len(), 1, "% est littéral");

        s.delete_history(id).unwrap();
        assert_eq!(s.list_history(None, 10, 0).unwrap().len(), 1);
        s.clear_history().unwrap();
        assert!(s.list_history(None, 10, 0).unwrap().is_empty());
    }

    #[test]
    fn vocabulary_is_unique_case_insensitive() {
        let s = Store::open_in_memory().unwrap();
        s.add_term("Loftia").unwrap();
        s.add_term("loftia").unwrap();
        s.add_term("  ").unwrap();
        s.add_term("Dicto").unwrap();
        let terms: Vec<_> = s.terms().unwrap().into_iter().map(|t| t.term).collect();
        assert_eq!(terms, vec!["Dicto", "Loftia"]);
    }

    #[test]
    fn profiles_upsert_by_app() {
        let s = Store::open_in_memory().unwrap();
        let p = Profile {
            app_id: "com.slack".into(),
            language: Some("en".into()),
            mode: Some("hold".into()),
            ..Profile::default()
        };
        let id = s.upsert_profile(&p).unwrap();
        let id2 = s
            .upsert_profile(&Profile { language: Some("fr".into()), ..p.clone() })
            .unwrap();
        assert_eq!(id, id2);
        let got = s.profile_for_app("com.slack").unwrap().unwrap();
        assert_eq!(got.language.as_deref(), Some("fr"));
        assert!(s.profile_for_app("other").unwrap().is_none());
        assert_eq!(s.profiles().unwrap().len(), 1);
    }

    #[test]
    fn settings_and_reopen() {
        let dir = crate::test_support::temp_dir("store");
        let path = dir.join("dicto.db");
        {
            let s = Store::open(&path).unwrap();
            s.set_setting("mode", "continuous").unwrap();
            s.set_setting("mode", "hold").unwrap();
        }
        let s = Store::open(&path).unwrap();
        assert_eq!(s.setting("mode").unwrap().as_deref(), Some("hold"));
        assert_eq!(s.setting("missing").unwrap(), None);
    }
}
