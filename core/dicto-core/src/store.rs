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
    // v2 : dictées épinglées, remplacements personnels
    "ALTER TABLE history ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0;
     CREATE TABLE replacements (
        id          INTEGER PRIMARY KEY,
        phrase      TEXT NOT NULL UNIQUE COLLATE NOCASE,
        replacement TEXT NOT NULL,
        created_at  INTEGER NOT NULL
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
    /// Épinglée : reste en tête de l'historique.
    pub pinned: bool,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Term {
    pub id: i64,
    pub term: String,
}

/// « mon adresse » → « 12 rue des Lilas » : une expression dite, le texte écrit à sa place.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Replacement {
    pub id: i64,
    pub phrase: String,
    pub replacement: String,
}

/// Ce qu'on garde d'un appareil à l'autre (fichier d'export) : vocabulaire, remplacements, profils.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct Bundle {
    pub app: String,
    pub version: u32,
    pub vocabulary: Vec<String>,
    pub replacements: Vec<BundleReplacement>,
    pub profiles: Vec<Profile>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct BundleReplacement {
    pub phrase: String,
    pub replacement: String,
}

/// Ce qu'un import a ajouté ou mis à jour.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct ImportCounts {
    pub vocabulary: u32,
    pub replacements: u32,
    pub profiles: u32,
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
            "SELECT id, created_at, text, raw_text, engine, language, duration_ms, app, pinned FROM history
             WHERE ?1 IS NULL OR text LIKE ?1 ESCAPE '\\'
             ORDER BY pinned DESC, created_at DESC, id DESC LIMIT ?2 OFFSET ?3",
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
                pinned: r.get::<_, i64>(8)? != 0,
            })
        })?;
        Ok(rows.collect::<rusqlite::Result<_>>()?)
    }

    pub fn delete_history(&self, id: i64) -> Result<()> {
        self.conn.execute("DELETE FROM history WHERE id = ?1", [id])?;
        Ok(())
    }

    /// Efface l'historique, sauf les dictées épinglées.
    pub fn clear_history(&self) -> Result<()> {
        self.conn.execute("DELETE FROM history WHERE pinned = 0", [])?;
        Ok(())
    }

    pub fn set_history_pinned(&self, id: i64, pinned: bool) -> Result<()> {
        self.conn.execute("UPDATE history SET pinned = ?2 WHERE id = ?1", params![id, pinned as i64])?;
        Ok(())
    }

    /// Remplace le texte d'une dictée (retouche après coup) ; le texte d'origine est gardé.
    pub fn update_history_text(&self, id: i64, text: &str) -> Result<()> {
        self.conn.execute(
            "UPDATE history SET raw_text = COALESCE(raw_text, text), text = ?2 WHERE id = ?1",
            params![id, text],
        )?;
        Ok(())
    }

    // --- Remplacements ------------------------------------------------------------

    /// Ajoute un remplacement, ou change le texte d'une expression déjà connue.
    pub fn set_replacement(&self, phrase: &str, replacement: &str) -> Result<()> {
        let phrase = phrase.trim();
        if !phrase.is_empty() && !replacement.trim().is_empty() {
            self.conn.execute(
                "INSERT INTO replacements (phrase, replacement, created_at) VALUES (?1, ?2, ?3)
                 ON CONFLICT(phrase) DO UPDATE SET replacement = excluded.replacement",
                params![phrase, replacement, now_ms()],
            )?;
        }
        Ok(())
    }

    pub fn remove_replacement(&self, id: i64) -> Result<()> {
        self.conn.execute("DELETE FROM replacements WHERE id = ?1", [id])?;
        Ok(())
    }

    pub fn replacements(&self) -> Result<Vec<Replacement>> {
        let mut stmt = self
            .conn
            .prepare("SELECT id, phrase, replacement FROM replacements ORDER BY phrase COLLATE NOCASE")?;
        let rows = stmt.query_map([], |r| Ok(Replacement { id: r.get(0)?, phrase: r.get(1)?, replacement: r.get(2)? }))?;
        Ok(rows.collect::<rusqlite::Result<_>>()?)
    }

    // --- Export et import ---------------------------------------------------------

    /// Vocabulaire, remplacements et profils, à importer sur un autre appareil.
    pub fn export_bundle(&self) -> Result<String> {
        let bundle = Bundle {
            app: "dicto".into(),
            version: 1,
            vocabulary: self.terms()?.into_iter().map(|t| t.term).collect(),
            replacements: self
                .replacements()?
                .into_iter()
                .map(|r| BundleReplacement { phrase: r.phrase, replacement: r.replacement })
                .collect(),
            profiles: self.profiles()?.into_iter().map(|p| Profile { id: None, ..p }).collect(),
        };
        Ok(serde_json::to_string_pretty(&bundle)?)
    }

    /// Ajoute le contenu d'un export à celui de cet appareil (rien n'est supprimé).
    pub fn import_bundle(&self, json: &str) -> Result<ImportCounts> {
        let bundle: Bundle = serde_json::from_str(json)
            .map_err(|e| crate::DictoError::Unsupported(format!("fichier Dicto illisible : {e}")))?;
        if bundle.app != "dicto" {
            return Err(crate::DictoError::Unsupported("ce fichier ne vient pas de Dicto".into()));
        }
        let mut counts = ImportCounts::default();
        let terms_before = self.terms()?.len();
        for term in &bundle.vocabulary {
            self.add_term(term)?;
        }
        counts.vocabulary = (self.terms()?.len() - terms_before) as u32;
        for r in &bundle.replacements {
            if !r.phrase.trim().is_empty() && !r.replacement.trim().is_empty() {
                self.set_replacement(&r.phrase, &r.replacement)?;
                counts.replacements += 1;
            }
        }
        for p in &bundle.profiles {
            if !p.app_id.trim().is_empty() {
                self.upsert_profile(&Profile { id: None, ..p.clone() })?;
                counts.profiles += 1;
            }
        }
        Ok(counts)
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
    fn pinned_entries_stay_on_top_and_survive_clearing() {
        let s = Store::open_in_memory().unwrap();
        let old = s.add_history(&entry("Ancienne")).unwrap();
        s.add_history(&entry("Récente")).unwrap();
        s.set_history_pinned(old, true).unwrap();
        let all = s.list_history(None, 10, 0).unwrap();
        assert_eq!(all[0].text, "Ancienne");
        assert!(all[0].pinned);

        s.update_history_text(old, "Ancienne, retouchée").unwrap();
        s.update_history_text(old, "Ancienne, retouchée deux fois").unwrap();
        let e = &s.list_history(None, 10, 0).unwrap()[0];
        assert_eq!(e.text, "Ancienne, retouchée deux fois");
        assert_eq!(e.raw_text.as_deref(), Some("Ancienne"), "le texte d'origine est gardé");

        s.clear_history().unwrap();
        assert_eq!(s.list_history(None, 10, 0).unwrap().len(), 1);
    }

    #[test]
    fn replacements_upsert_case_insensitive() {
        let s = Store::open_in_memory().unwrap();
        s.set_replacement("mon adresse", "12 rue des Lilas").unwrap();
        s.set_replacement("Mon Adresse", "3 place du Marché").unwrap();
        s.set_replacement("vide", "  ").unwrap();
        let r = s.replacements().unwrap();
        assert_eq!(r.len(), 1);
        assert_eq!(r[0].replacement, "3 place du Marché");
        s.remove_replacement(r[0].id).unwrap();
        assert!(s.replacements().unwrap().is_empty());
    }

    #[test]
    fn bundle_round_trip() {
        let a = Store::open_in_memory().unwrap();
        a.add_term("Dicto").unwrap();
        a.set_replacement("sig", "Dorian").unwrap();
        a.upsert_profile(&Profile { app_id: "slack".into(), language: Some("en".into()), ..Profile::default() }).unwrap();
        let json = a.export_bundle().unwrap();

        let b = Store::open_in_memory().unwrap();
        b.add_term("dicto").unwrap();
        let counts = b.import_bundle(&json).unwrap();
        assert_eq!(counts, ImportCounts { vocabulary: 0, replacements: 1, profiles: 1 });
        assert_eq!(b.replacements().unwrap()[0].replacement, "Dorian");
        assert_eq!(b.profile_for_app("slack").unwrap().unwrap().language.as_deref(), Some("en"));
        assert!(b.import_bundle("{\"app\":\"autre\"}").is_err());
        assert!(b.import_bundle("pas du json").is_err());
    }

    #[test]
    fn migrates_a_version_1_database() {
        let dir = crate::test_support::temp_dir("store-v1");
        let path = dir.join("dicto.db");
        {
            let conn = Connection::open(&path).unwrap();
            conn.execute_batch(&format!("{}; PRAGMA user_version = 1;", MIGRATIONS[0])).unwrap();
            conn.execute("INSERT INTO history (created_at, text, engine) VALUES (1, 'Avant', 'x')", []).unwrap();
        }
        let s = Store::open(&path).unwrap();
        let e = &s.list_history(None, 10, 0).unwrap()[0];
        assert_eq!(e.text, "Avant");
        assert!(!e.pinned);
        s.set_replacement("a", "b").unwrap();
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
