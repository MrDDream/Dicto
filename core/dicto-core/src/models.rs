//! Catalogue des modèles locaux et gestion des téléchargements.
//!
//! Les téléchargements sont reprenables (fichier `.part` + en-tête `Range`) et chaque
//! fichier est vérifié par SHA-256 avant d'être utilisable.

use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use tokio::io::{AsyncReadExt, AsyncWriteExt};

use crate::engine::cloud::http_client;
use crate::{DictoError, Result};

const BUILTIN_CATALOG: &str = include_str!("../../catalog/models.json");
const PROGRESS_STEP: u64 = 512 * 1024;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum EngineKind {
    Whisper,
    Sherpa,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ModelFile {
    pub name: String,
    pub url: String,
    pub sha256: String,
    pub size: u64,
    /// Archive `.tar.bz2` à extraire dans le dossier du modèle après vérification.
    #[serde(default)]
    pub archive: bool,
}

/// Famille de modèle sherpa-onnx (détermine la configuration du reconnaisseur).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SherpaKind {
    /// Transducteur NeMo hors ligne (Parakeet).
    NemoTransducer,
    /// NeMo Canary (encodeur + décodeur, langue source à préciser).
    Canary,
    /// CTC Omnilingual (Meta).
    OmnilingualCtc,
    /// Transducteur en streaming (Nemotron, Zipformer) : texte au fil de la parole.
    OnlineTransducer,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ModelEntry {
    pub id: String,
    pub name: String,
    pub engine: EngineKind,
    #[serde(default)]
    pub sherpa: Option<SherpaKind>,
    pub description: String,
    #[serde(default)]
    pub license: String,
    /// Auteurs et provenance, affichés dans les crédits (exigé par CC-BY).
    #[serde(default)]
    pub credit: String,
    #[serde(default)]
    pub source_url: String,
    /// Codes ISO 639-1, ou `["multi"]` pour un modèle très multilingue.
    #[serde(default)]
    pub languages: Vec<String>,
    pub multilingual: bool,
    #[serde(default)]
    pub punctuation: bool,
    /// Le modèle produit du texte pendant qu'on parle.
    #[serde(default)]
    pub streaming: bool,
    /// 1 (lent) à 4 (très rapide).
    pub speed: u8,
    /// 1 (simple) à 4 (excellente).
    pub accuracy: u8,
    /// Plateformes où le modèle est proposé : « android », « desktop ».
    #[serde(default)]
    pub platforms: Vec<String>,
    /// Plateformes où il est conseillé.
    #[serde(default)]
    pub recommended: Vec<String>,
    pub files: Vec<ModelFile>,
}

impl ModelEntry {
    pub fn total_size(&self) -> u64 {
        self.files.iter().map(|f| f.size).sum()
    }

    pub fn available_on(&self, platform: &str) -> bool {
        self.platforms.is_empty() || self.platforms.iter().any(|p| p == platform)
    }

    pub fn recommended_on(&self, platform: &str) -> bool {
        self.recommended.iter().any(|p| p == platform)
    }
}

/// Fichiers annexes téléchargés à la demande (VAD Silero, mot d'activation…).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct SupportEntry {
    pub id: String,
    pub name: String,
    #[serde(default)]
    pub license: String,
    #[serde(default)]
    pub credit: String,
    #[serde(default)]
    pub source_url: String,
    pub files: Vec<ModelFile>,
}

/// Ce qui se télécharge dans `<dossier>/<id>/`.
pub trait Bundle {
    fn id(&self) -> &str;
    fn files(&self) -> &[ModelFile];
    fn total_size(&self) -> u64 {
        self.files().iter().map(|f| f.size).sum()
    }
}

impl Bundle for ModelEntry {
    fn id(&self) -> &str {
        &self.id
    }
    fn files(&self) -> &[ModelFile] {
        &self.files
    }
}

impl Bundle for SupportEntry {
    fn id(&self) -> &str {
        &self.id
    }
    fn files(&self) -> &[ModelFile] {
        &self.files
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Catalog {
    pub version: u32,
    pub models: Vec<ModelEntry>,
    #[serde(default)]
    pub support: Vec<SupportEntry>,
}

impl Catalog {
    pub fn builtin() -> Self {
        serde_json::from_str(BUILTIN_CATALOG).expect("catalogue intégré valide")
    }

    pub fn get(&self, id: &str) -> Option<&ModelEntry> {
        self.models.iter().find(|m| m.id == id)
    }

    pub fn support(&self, id: &str) -> Option<&SupportEntry> {
        self.support.iter().find(|m| m.id == id)
    }

    /// Modèles proposés sur une plateforme, les conseillés d'abord.
    pub fn for_platform(&self, platform: &str) -> Vec<&ModelEntry> {
        let mut models: Vec<&ModelEntry> = self.models.iter().filter(|m| m.available_on(platform)).collect();
        models.sort_by_key(|m| !m.recommended_on(platform));
        models
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Progress {
    pub downloaded: u64,
    pub total: u64,
}

/// Dossier où sont rangés les modèles : `<dir>/<id>/…`.
#[derive(Debug, Clone)]
pub struct ModelStore {
    dir: PathBuf,
}

impl ModelStore {
    pub fn new(dir: impl Into<PathBuf>) -> Self {
        Self { dir: dir.into() }
    }

    pub fn model_dir(&self, entry: &impl Bundle) -> PathBuf {
        self.dir.join(entry.id())
    }

    /// Fichier à charger pour un modèle Whisper, dossier pour un modèle sherpa-onnx.
    pub fn install_path(&self, entry: &ModelEntry) -> PathBuf {
        match entry.engine {
            EngineKind::Whisper => self.model_dir(entry).join(&entry.files[0].name),
            EngineKind::Sherpa => self.model_dir(entry),
        }
    }

    fn is_file_done(&self, dir: &Path, f: &ModelFile) -> bool {
        if f.archive {
            dir.join(extracted_marker(&f.name)).exists()
        } else {
            std::fs::metadata(dir.join(&f.name)).map(|m| m.len() == f.size).unwrap_or(false)
        }
    }

    pub fn is_installed(&self, entry: &impl Bundle) -> bool {
        let dir = self.model_dir(entry);
        entry.files().iter().all(|f| self.is_file_done(&dir, f))
    }

    /// Octets déjà présents (fichiers terminés + `.part`), pour afficher une reprise.
    pub fn downloaded_bytes(&self, entry: &impl Bundle) -> u64 {
        let dir = self.model_dir(entry);
        entry
            .files()
            .iter()
            .map(|f| {
                if self.is_file_done(&dir, f) {
                    return f.size;
                }
                let done = dir.join(&f.name);
                std::fs::metadata(&done)
                    .or_else(|_| std::fs::metadata(part_path(&done)))
                    .map(|m| m.len().min(f.size))
                    .unwrap_or(0)
            })
            .sum()
    }

    pub fn delete(&self, entry: &impl Bundle) -> Result<()> {
        let dir = self.model_dir(entry);
        if dir.exists() {
            std::fs::remove_dir_all(dir)?;
        }
        Ok(())
    }

    /// Télécharge (ou reprend) tous les fichiers, les vérifie et extrait les archives.
    /// Renvoie le dossier du modèle.
    pub async fn download(
        &self,
        entry: &impl Bundle,
        cancel: &AtomicBool,
        mut on_progress: impl FnMut(Progress) + Send,
    ) -> Result<PathBuf> {
        let dir = self.model_dir(entry);
        tokio::fs::create_dir_all(&dir).await?;
        let client = http_client();
        let total = entry.total_size();
        let mut done_before = 0u64;

        for file in entry.files() {
            if self.is_file_done(&dir, file) {
                done_before += file.size;
                on_progress(Progress { downloaded: done_before, total });
                continue;
            }
            let dest = dir.join(&file.name);
            let already = std::fs::metadata(&dest).map(|m| m.len() == file.size).unwrap_or(false);
            if !already {
                let base = done_before;
                // Au plus un signal tous les 512 Kio (plus le dernier) : la progression
                // traverse le pont FFI jusqu'à l'interface.
                let mut last_sent: Option<u64> = None;
                let size = file.size;
                download_file(&client, file, &dest, cancel, |n| {
                    let due = last_sent.is_none_or(|l| n >= l + PROGRESS_STEP || n < l) || n == size;
                    if due && last_sent != Some(n) {
                        last_sent = Some(n);
                        on_progress(Progress { downloaded: base + n, total });
                    }
                })
                .await?;
            }
            if file.archive {
                let (archive, target, marker) = (dest.clone(), dir.clone(), dir.join(extracted_marker(&file.name)));
                tokio::task::spawn_blocking(move || extract_tar_bz2(&archive, &target))
                    .await
                    .map_err(|e| DictoError::Model(format!("extraction interrompue : {e}")))??;
                tokio::fs::write(&marker, &file.sha256).await?;
                tokio::fs::remove_file(&dest).await?;
            }
            done_before += file.size;
        }
        Ok(dir)
    }
}

fn extracted_marker(archive_name: &str) -> String {
    format!(".{archive_name}.extracted")
}

/// Extrait une archive `.tar.bz2` dans `target` en retirant le dossier racine
/// (`modele/encoder.onnx` → `target/encoder.onnx`). Les exemples audio sont ignorés.
fn extract_tar_bz2(archive: &Path, target: &Path) -> Result<()> {
    use std::path::Component;
    let file = std::fs::File::open(archive)?;
    let mut tar = tar::Archive::new(bzip2::read::BzDecoder::new(std::io::BufReader::new(file)));
    for entry in tar.entries()? {
        let mut entry = entry?;
        let path = entry.path()?.into_owned();
        let parts: Vec<_> = path
            .components()
            .filter(|c| !matches!(c, Component::CurDir))
            .collect();
        if parts.iter().any(|c| !matches!(c, Component::Normal(_))) {
            return Err(DictoError::Model(format!("chemin refusé dans l'archive : {}", path.display())));
        }
        let rel: PathBuf = parts.iter().skip(1).collect();
        if rel.as_os_str().is_empty() || rel.starts_with("test_wavs") {
            continue;
        }
        let out = target.join(&rel);
        if entry.header().entry_type().is_dir() {
            std::fs::create_dir_all(&out)?;
        } else if entry.header().entry_type().is_file() {
            if let Some(parent) = out.parent() {
                std::fs::create_dir_all(parent)?;
            }
            entry.unpack(&out)?;
        }
    }
    Ok(())
}

fn part_path(dest: &Path) -> PathBuf {
    let mut name = dest.file_name().unwrap_or_default().to_os_string();
    name.push(".part");
    dest.with_file_name(name)
}

async fn download_file(
    client: &reqwest::Client,
    file: &ModelFile,
    dest: &Path,
    cancel: &AtomicBool,
    mut on_bytes: impl FnMut(u64),
) -> Result<()> {
    let part = part_path(dest);
    let mut have = tokio::fs::metadata(&part).await.map(|m| m.len()).unwrap_or(0);
    if have > file.size {
        tokio::fs::remove_file(&part).await?;
        have = 0;
    }

    if have < file.size {
        let mut req = client.get(&file.url);
        if have > 0 {
            req = req.header(reqwest::header::RANGE, format!("bytes={have}-"));
        }
        let mut resp = crate::engine::cloud::check("téléchargement", req.send().await?).await?;
        let resumed = resp.status() == reqwest::StatusCode::PARTIAL_CONTENT;
        let mut out = tokio::fs::OpenOptions::new()
            .create(true)
            .append(resumed)
            .write(true)
            .truncate(!resumed)
            .open(&part)
            .await?;
        if !resumed {
            have = 0;
        }
        on_bytes(have);
        while let Some(chunk) = resp.chunk().await? {
            if cancel.load(Ordering::Relaxed) {
                out.flush().await?;
                return Err(DictoError::Cancelled);
            }
            out.write_all(&chunk).await?;
            have += chunk.len() as u64;
            on_bytes(have);
        }
        out.flush().await?;
        drop(out);
    }

    let digest = sha256_file(&part).await?;
    if !digest.eq_ignore_ascii_case(&file.sha256) {
        tokio::fs::remove_file(&part).await?;
        return Err(DictoError::Model(format!(
            "{} est corrompu (empreinte {digest} au lieu de {}), il sera retéléchargé",
            file.name, file.sha256
        )));
    }
    tokio::fs::rename(&part, dest).await?;
    Ok(())
}

async fn sha256_file(path: &Path) -> Result<String> {
    let mut f = tokio::fs::File::open(path).await?;
    let mut hasher = Sha256::new();
    let mut buf = vec![0u8; 1 << 20];
    loop {
        let n = f.read(&mut buf).await?;
        if n == 0 {
            break;
        }
        hasher.update(&buf[..n]);
    }
    Ok(hex::encode(hasher.finalize()))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::test_support::{MockServer, Response, temp_dir};

    #[test]
    fn builtin_catalog_is_consistent() {
        let cat = Catalog::builtin();
        assert!(cat.models.len() >= 5);
        for m in &cat.models {
            assert!(!m.files.is_empty(), "{}", m.id);
            assert!(!m.license.is_empty() && !m.platforms.is_empty(), "{}", m.id);
            assert!(!m.credit.is_empty() && m.source_url.starts_with("https://"), "crédits de {}", m.id);
            assert_eq!(m.engine == EngineKind::Sherpa, m.sherpa.is_some(), "{}", m.id);
            for f in &m.files {
                assert_eq!(f.sha256.len(), 64, "{}", f.name);
                assert!(f.url.starts_with("https://"), "{}", f.url);
                assert!(f.size > 1_000_000, "{}", f.name);
            }
            assert!((1..=4).contains(&m.speed) && (1..=4).contains(&m.accuracy));
        }
        let ids: std::collections::HashSet<_> = cat.models.iter().map(|m| &m.id).collect();
        assert_eq!(ids.len(), cat.models.len(), "identifiants en double");
    }

    fn fake_entry(url: String, data: &[u8]) -> ModelEntry {
        ModelEntry {
            id: "fake".into(),
            name: "fake".into(),
            engine: EngineKind::Whisper,
            sherpa: None,
            description: String::new(),
            license: String::new(),
            credit: String::new(),
            source_url: String::new(),
            languages: vec![],
            multilingual: true,
            punctuation: false,
            streaming: false,
            speed: 1,
            accuracy: 1,
            platforms: vec![],
            recommended: vec![],
            files: vec![ModelFile {
                name: "model.bin".into(),
                url,
                sha256: hex::encode(Sha256::digest(data)),
                size: data.len() as u64,
                archive: false,
            }],
        }
    }

    fn tar_bz2(files: &[(&str, &[u8])]) -> Vec<u8> {
        let mut builder = tar::Builder::new(bzip2::write::BzEncoder::new(Vec::new(), bzip2::Compression::fast()));
        for (path, data) in files {
            let mut header = tar::Header::new_gnu();
            header.set_size(data.len() as u64);
            header.set_mode(0o644);
            header.set_cksum();
            builder.append_data(&mut header, path, *data).unwrap();
        }
        builder.into_inner().unwrap().finish().unwrap()
    }

    #[tokio::test]
    async fn extracts_archives_without_root_folder() {
        let archive = tar_bz2(&[
            ("./sherpa-model/encoder.int8.onnx", b"enc"),
            ("./sherpa-model/tokens.txt", b"a 0"),
            ("./sherpa-model/test_wavs/fr.wav", b"wav"),
        ]);
        let server = MockServer::start_with(serve_bytes(archive.clone())).await;
        let store = ModelStore::new(temp_dir("archive"));
        let mut entry = fake_entry(format!("{}/m.tar.bz2", server.url()), &archive);
        entry.engine = EngineKind::Sherpa;
        entry.files[0].name = "m.tar.bz2".into();
        entry.files[0].archive = true;

        let dir = store.download(&entry, &AtomicBool::new(false), |_| {}).await.unwrap();
        assert_eq!(std::fs::read(dir.join("encoder.int8.onnx")).unwrap(), b"enc");
        assert!(dir.join("tokens.txt").exists());
        assert!(!dir.join("test_wavs").exists(), "exemples audio ignorés");
        assert!(!dir.join("m.tar.bz2").exists(), "archive supprimée après extraction");
        assert!(store.is_installed(&entry));
        assert_eq!(store.downloaded_bytes(&entry), archive.len() as u64);
        assert_eq!(store.install_path(&entry), dir);
    }

    #[test]
    fn rejects_path_traversal() {
        let dir = temp_dir("traversal");
        let archive_path = dir.join("evil.tar.bz2");
        // tar::Builder refuse « .. » : on écrit l'en-tête à la main.
        let mut header = tar::Header::new_gnu();
        header.as_gnu_mut().unwrap().name[..12].copy_from_slice(b"root/../../x");
        header.set_size(1);
        header.set_mode(0o644);
        header.set_cksum();
        let mut raw = header.as_bytes().to_vec();
        raw.extend_from_slice(b"x");
        raw.resize(1024, 0);
        raw.extend_from_slice(&[0u8; 1024]);
        let mut enc = bzip2::write::BzEncoder::new(Vec::new(), bzip2::Compression::fast());
        std::io::Write::write_all(&mut enc, &raw).unwrap();
        std::fs::write(&archive_path, enc.finish().unwrap()).unwrap();
        assert!(extract_tar_bz2(&archive_path, &dir.join("out")).is_err());
    }

    #[test]
    fn filters_by_platform() {
        let cat = Catalog::builtin();
        let android = cat.for_platform("android");
        assert!(android.iter().all(|m| m.available_on("android")));
        assert!(!android.iter().any(|m| m.id == "whisper-large-v3-turbo"), "trop lourd pour un téléphone");
        assert!(android[0].recommended_on("android"), "conseillés en premier");
        let desktop = cat.for_platform("desktop");
        assert!(!desktop.iter().any(|m| m.id == "whisper-tiny-q5_1"));
        assert!(cat.support("silero-vad").is_some());
        assert!(cat.support.iter().all(|s| !s.credit.is_empty() && !s.license.is_empty()));
        // Seuls des modèles sous licence libre sont proposés.
        assert!(cat.models.iter().all(|m| !m.license.contains("non libre")));
    }

    fn serve_bytes(data: Vec<u8>) -> impl Fn(&crate::test_support::Request) -> Response + Send + Sync {
        move |req| {
            let range = req
                .header("range")
                .and_then(|r| r.strip_prefix("bytes="))
                .and_then(|r| r.trim_end_matches('-').parse::<usize>().ok());
            match range {
                Some(start) => Response::new(206, data[start..].to_vec()),
                None => Response::new(200, data.clone()),
            }
        }
    }

    #[tokio::test]
    async fn downloads_verifies_and_resumes() {
        let data: Vec<u8> = (0..200_000u32).map(|i| (i % 251) as u8).collect();
        let server = MockServer::start_with(serve_bytes(data.clone())).await;
        let dir = temp_dir("models");
        let store = ModelStore::new(&dir);
        let entry = fake_entry(format!("{}/model.bin", server.url()), &data);

        // Simule un téléchargement interrompu à mi-chemin.
        std::fs::create_dir_all(store.model_dir(&entry)).unwrap();
        std::fs::write(store.model_dir(&entry).join("model.bin.part"), &data[..80_000]).unwrap();
        assert_eq!(store.downloaded_bytes(&entry), 80_000);
        assert!(!store.is_installed(&entry));

        let mut last = Progress { downloaded: 0, total: 0 };
        let mut calls = 0;
        let path = store
            .download(&entry, &AtomicBool::new(false), |p| {
                last = p;
                calls += 1;
            })
            .await
            .unwrap();
        assert_eq!(std::fs::read(path.join("model.bin")).unwrap(), data);
        assert_eq!(last, Progress { downloaded: 200_000, total: 200_000 });
        assert!(calls <= 3, "progression limitée : {calls} appels");
        assert!(store.is_installed(&entry));
        assert!(server.requests().await[0].contains("range: bytes=80000-"));

        store.delete(&entry).unwrap();
        assert!(!store.is_installed(&entry));
    }

    #[tokio::test]
    async fn rejects_corrupted_files() {
        let data = vec![7u8; 50_000];
        let server = MockServer::start_with(serve_bytes(vec![8u8; 50_000])).await;
        let store = ModelStore::new(temp_dir("corrupt"));
        let entry = fake_entry(format!("{}/model.bin", server.url()), &data);
        let err = store
            .download(&entry, &AtomicBool::new(false), |_| {})
            .await
            .unwrap_err();
        assert!(matches!(err, DictoError::Model(_)), "{err}");
        assert_eq!(store.downloaded_bytes(&entry), 0, "le .part corrompu est supprimé");
    }

    #[tokio::test]
    async fn can_be_cancelled() {
        let data = vec![1u8; 100_000];
        let server = MockServer::start_with(serve_bytes(data.clone())).await;
        let store = ModelStore::new(temp_dir("cancel"));
        let entry = fake_entry(format!("{}/model.bin", server.url()), &data);
        let err = store
            .download(&entry, &AtomicBool::new(true), |_| {})
            .await
            .unwrap_err();
        assert!(matches!(err, DictoError::Cancelled));
    }
}
