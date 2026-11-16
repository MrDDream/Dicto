//! « Mises à jour » du bureau, comme Nautile : la dernière version publiée sur GitHub, vérifiée au démarrage
//! puis toutes les heures (une fois par 20 h au plus, et seulement si l'utilisateur l'a accepté). Le fichier de
//! ce système est téléchargé dans un dossier privé, vérifié contre le `SHA256SUMS-desktop.txt` de la version
//! (obligatoire), vérifié de nouveau juste avant d'être installé. Android fait de même dans `UpdateChecker.kt`.

use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::sync::Mutex;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

use dicto_ffi::Storage;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use tauri::{AppHandle, Emitter, Manager, State};

/// Où les versions sont publiées.
pub const REPO: &str = "MrDDream/Dicto";
pub const CURRENT: &str = env!("CARGO_PKG_VERSION");
/// Les sommes des fichiers du bureau, publiées avec chaque version par release.yml.
pub const SUMS_FILE: &str = "SHA256SUMS-desktop.txt";
const PREFS_KEY: &str = "desktop.updates";
const DAY_MS: u64 = 20 * 3_600_000;

#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
pub struct Asset {
    pub name: String,
    pub browser_download_url: String,
    #[serde(default)]
    pub size: u64,
}

#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
pub struct Release {
    pub tag_name: String,
    #[serde(default)]
    pub body: Option<String>,
    #[serde(default)]
    pub prerelease: bool,
    #[serde(default)]
    pub html_url: String,
    #[serde(default)]
    pub assets: Vec<Asset>,
}

impl Release {
    /// `0.2.0` pour `v0.2.0`.
    pub fn version(&self) -> &str {
        self.tag_name.trim_start_matches('v')
    }
}

// --- Versions ------------------------------------------------------------------------------

/// `1.2.3-rc1` : ses nombres et son suffixe de pré-version (ce qui suit `+` est ignoré).
fn parts(v: &str) -> (Vec<u64>, Option<&str>) {
    let v = v.trim_start_matches('v');
    let v = v.split('+').next().unwrap_or_default();
    let (numbers, pre) = match v.split_once('-') {
        Some((n, p)) => (n, Some(p)),
        None => (v, None),
    };
    (numbers.split('.').map(|p| p.parse().unwrap_or(0)).collect(), pre)
}

/// Si [candidate] est plus récente que [current] : par les nombres, puis une version passe après ses
/// pré-versions (`0.5.0` après `0.5.0-rc1`).
pub fn is_newer(candidate: &str, current: &str) -> bool {
    let ((a, pre_a), (b, pre_b)) = (parts(candidate), parts(current));
    for i in 0..a.len().max(b.len()) {
        let (x, y) = (a.get(i).copied().unwrap_or(0), b.get(i).copied().unwrap_or(0));
        if x != y {
            return x > y;
        }
    }
    match (pre_a, pre_b) {
        (None, Some(_)) => true,
        (Some(x), Some(y)) => x > y,
        _ => false,
    }
}

// --- Le fichier de ce système --------------------------------------------------------------

/// Ce qu'installe cette copie de Dicto, pour choisir le bon fichier.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
// Chaque système ne construit que les siennes.
#[allow(dead_code)]
pub enum Target {
    WindowsSetup,
    Deb,
    Rpm,
    AppImage,
    /// Le processeur dans le nom du fichier (`arm64`, `x86_64`).
    Mac(&'static str),
}

/// La façon dont Dicto a été installé ; `None` quand on ne sait pas (copie lancée à la main).
pub fn target() -> Option<Target> {
    #[cfg(windows)]
    {
        Some(Target::WindowsSetup)
    }
    #[cfg(target_os = "macos")]
    {
        Some(Target::Mac(if cfg!(target_arch = "aarch64") { "arm64" } else { "x86_64" }))
    }
    #[cfg(not(any(windows, target_os = "macos")))]
    {
        if std::env::var_os("APPIMAGE").is_some() {
            return Some(Target::AppImage);
        }
        let installed = std::env::current_exe().is_ok_and(|p| p.starts_with("/usr"));
        if !installed {
            None
        } else if Path::new("/etc/debian_version").exists() {
            Some(Target::Deb)
        } else {
            Some(Target::Rpm)
        }
    }
}

/// Le nom du fichier de [target] dans une version, tel que release.yml le publie.
pub fn asset_name(version: &str, target: Target) -> String {
    match target {
        Target::WindowsSetup => format!("Dicto-{version}-setup.exe"),
        Target::Deb => format!("dicto_{version}_amd64.deb"),
        Target::Rpm => format!("dicto-{version}.x86_64.rpm"),
        Target::AppImage => format!("Dicto-{version}-x86_64.AppImage"),
        Target::Mac(arch) => format!("Dicto-{version}-macos-{arch}.dmg"),
    }
}

pub fn asset_for(release: &Release, target: Target) -> Option<&Asset> {
    let name = asset_name(release.version(), target);
    release.assets.iter().find(|a| a.name == name)
}

// --- Sommes de contrôle -------------------------------------------------------------------

/// Le SHA-256 de [file] dans une liste au format de `sha256sum`.
pub fn expected_sha256(sums: &str, file: &str) -> Option<String> {
    sums.lines().find_map(|l| {
        let (hash, name) = l.trim().split_once(char::is_whitespace)?;
        (name.trim().trim_start_matches('*') == file).then(|| hash.trim().to_lowercase())
    })
}

/// Si un fichier de SHA-256 [got] peut être installé comme [file] : seulement quand [sums] le liste avec ce
/// même hash. Pas de liste, ou pas de ligne : c'est un refus.
pub fn check_sha256(sums: Option<&str>, file: &str, got: &str) -> Result<(), String> {
    let want = sums
        .and_then(|s| expected_sha256(s, file))
        .ok_or("Cette version ne publie pas la somme de contrôle du fichier : mise à jour refusée.")?;
    if want != got.to_lowercase() {
        return Err("Le fichier téléchargé ne correspond pas à la somme de contrôle de la version : il a été supprimé.".into());
    }
    Ok(())
}

pub fn sha256_file(path: &Path) -> std::io::Result<String> {
    use std::io::Read;
    let mut f = std::fs::File::open(path)?;
    let mut hash = Sha256::new();
    let mut buf = vec![0u8; 64 * 1024];
    loop {
        let n = f.read(&mut buf)?;
        if n == 0 {
            break;
        }
        hash.update(&buf[..n]);
    }
    Ok(hex::encode(hash.finalize()))
}

// --- Réseau --------------------------------------------------------------------------------

fn client(timeout: u64) -> Result<reqwest::Client, String> {
    reqwest::Client::builder()
        .user_agent(concat!("Dicto/", env!("CARGO_PKG_VERSION")))
        .connect_timeout(Duration::from_secs(20))
        .timeout(Duration::from_secs(timeout))
        .build()
        .map_err(|e| e.to_string())
}

/// La dernière version publiée (les brouillons et pré-versions ne sont jamais « latest » sur GitHub).
pub async fn latest() -> Result<Release, String> {
    let url = format!("https://api.github.com/repos/{REPO}/releases/latest");
    let r = client(30)?
        .get(url)
        .header("Accept", "application/vnd.github+json")
        .send()
        .await
        .map_err(|e| e.to_string())?;
    if r.status() == reqwest::StatusCode::NOT_FOUND {
        return Err("Aucune version publiée pour l'instant.".into());
    }
    if !r.status().is_success() {
        return Err(format!("GitHub : HTTP {}", r.status().as_u16()));
    }
    r.json().await.map_err(|e| e.to_string())
}

async fn fetch_text(url: &str) -> Result<String, String> {
    let r = client(60)?.get(url).send().await.map_err(|e| e.to_string())?;
    if !r.status().is_success() {
        return Err(format!("HTTP {}", r.status().as_u16()));
    }
    if r.content_length().is_some_and(|n| n > 1 << 20) {
        return Err("Liste des sommes trop grande.".into());
    }
    r.text().await.map_err(|e| e.to_string())
}

/// Télécharge [asset] dans [dest], vérifié contre les sommes de la version : refusé quand elles manquent, ne
/// listent pas le fichier ou ne lui correspondent pas (rien n'est alors laissé à [dest]). Rend le SHA-256.
async fn download_checked(
    release: &Release,
    asset: &Asset,
    dest: &Path,
    mut progress: impl FnMut(u64, u64),
) -> Result<String, String> {
    let sums_asset = release
        .assets
        .iter()
        .find(|a| a.name == SUMS_FILE)
        .ok_or("Cette version ne publie pas la somme de contrôle du fichier : mise à jour refusée.")?;
    let sums = fetch_text(&sums_asset.browser_download_url).await?;
    // Vérifié avant le long téléchargement, puis contre les octets reçus.
    check_sha256(Some(&sums), &asset.name, expected_sha256(&sums, &asset.name).as_deref().unwrap_or(""))?;

    let part = dest.with_extension("part");
    let result = async {
        use std::io::Write;
        let mut r = client(30 * 60)?
            .get(&asset.browser_download_url)
            .send()
            .await
            .map_err(|e| e.to_string())?;
        if !r.status().is_success() {
            return Err(format!("HTTP {}", r.status().as_u16()));
        }
        let total = r.content_length().unwrap_or(asset.size);
        let mut out = std::fs::File::create(&part).map_err(|e| e.to_string())?;
        let mut hash = Sha256::new();
        let mut done = 0u64;
        while let Some(chunk) = r.chunk().await.map_err(|e| e.to_string())? {
            out.write_all(&chunk).map_err(|e| e.to_string())?;
            hash.update(&chunk);
            done += chunk.len() as u64;
            progress(done, total);
        }
        out.flush().map_err(|e| e.to_string())?;
        Ok(hex::encode(hash.finalize()))
    }
    .await;
    let got = match result {
        Ok(h) => h,
        Err(e) => {
            let _ = std::fs::remove_file(&part);
            return Err(e);
        }
    };
    if let Err(e) = check_sha256(Some(&sums), &asset.name, &got) {
        let _ = std::fs::remove_file(&part);
        return Err(e);
    }
    std::fs::rename(&part, dest).map_err(|e| e.to_string())?;
    Ok(got)
}

// --- Installation --------------------------------------------------------------------------

/// Ouvre un fichier avec le programme du système (l'installeur de paquets, le Finder…).
fn open_with_system(path: &Path) -> Result<(), String> {
    #[cfg(target_os = "macos")]
    let mut cmd = std::process::Command::new("open");
    #[cfg(windows)]
    let mut cmd = std::process::Command::new("explorer");
    #[cfg(not(any(windows, target_os = "macos")))]
    let mut cmd = std::process::Command::new("xdg-open");
    cmd.arg(path).spawn().map(|_| ()).map_err(|e| e.to_string())
}

/// Ce qui se passe une fois le fichier vérifié.
enum Installed {
    /// L'installeur a pris le relais (ou la nouvelle version est lancée) : Dicto se ferme.
    Quit,
    /// Le paquet est ouvert dans l'installeur du système : message à afficher.
    Opened(String),
}

/// Confie le fichier au système. Il est haché de nouveau avant : ce qui est confié est ce qui a été vérifié.
fn install(target: Target, path: &Path, sha256: &str) -> Result<Installed, String> {
    if !sha256_file(path).is_ok_and(|h| h.eq_ignore_ascii_case(sha256)) {
        let _ = std::fs::remove_file(path);
        return Err("Le fichier téléchargé a changé depuis sa vérification : il a été supprimé.".into());
    }
    match target {
        Target::WindowsSetup => {
            std::process::Command::new(path).spawn().map_err(|e| e.to_string())?;
            Ok(Installed::Quit)
        }
        Target::AppImage => {
            // La nouvelle AppImage prend la place de l'ancienne, puis démarre.
            let current = std::env::var_os("APPIMAGE").map(PathBuf::from).ok_or("AppImage introuvable")?;
            #[cfg(unix)]
            {
                use std::os::unix::fs::PermissionsExt;
                std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o755)).map_err(|e| e.to_string())?;
            }
            std::fs::rename(path, &current).map_err(|e| e.to_string())?;
            std::process::Command::new(&current).spawn().map_err(|e| e.to_string())?;
            Ok(Installed::Quit)
        }
        Target::Deb | Target::Rpm | Target::Mac(_) => {
            open_with_system(path)?;
            Ok(Installed::Opened(if matches!(target, Target::Mac(_)) {
                "L'image disque est ouverte : glisse Dicto dans Applications, puis relance-le.".into()
            } else {
                "Le paquet est ouvert dans l'installeur du système. Relance Dicto ensuite.".into()
            }))
        }
    }
}

/// Le dossier du téléchargement : un dossier privé du cache de l'app (jamais Téléchargements, où un autre
/// programme pourrait remplacer le fichier après la vérification). L'AppImage, elle, va à côté de l'actuelle
/// pour pouvoir la remplacer d'un simple renommage.
fn download_dir(app: &AppHandle, target: Target) -> Result<PathBuf, String> {
    let beside_appimage = std::env::var_os("APPIMAGE")
        .map(PathBuf::from)
        .and_then(|p| p.parent().map(Path::to_path_buf))
        .filter(|_| target == Target::AppImage);
    if let Some(dir) = beside_appimage {
        return Ok(dir);
    }
    let dir = app.path().app_cache_dir().map_err(|e| e.to_string())?.join("updates");
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let _ = std::fs::set_permissions(&dir, std::fs::Permissions::from_mode(0o700));
    }
    // Ce qu'une mise à jour précédente a laissé.
    if let Ok(entries) = std::fs::read_dir(&dir) {
        for e in entries.flatten() {
            let _ = std::fs::remove_file(e.path());
        }
    }
    Ok(dir)
}

// --- État et commandes ---------------------------------------------------------------------

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
#[serde(default, rename_all = "camelCase")]
struct Prefs {
    /// La réponse à « Vérifier automatiquement les mises à jour ? » ; `None` : pas encore posée.
    consent: Option<bool>,
    last_check: u64,
    skipped: Option<String>,
}

pub struct Updates {
    storage: Arc<Storage>,
    prefs: Mutex<Prefs>,
    available: Mutex<Option<Release>>,
    checking: AtomicBool,
    downloading: AtomicBool,
}

/// Ce que voit l'interface.
#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UpdateView {
    pub current: &'static str,
    pub consent: Option<bool>,
    pub available: Option<ReleaseView>,
    pub downloading: bool,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ReleaseView {
    pub tag: String,
    pub notes: String,
    pub page: String,
    /// Un fichier pour ce système (sinon : ouvrir la page).
    pub installable: bool,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct ProgressEvent {
    done: u64,
    total: u64,
}

fn now_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

impl Updates {
    pub fn new(storage: Arc<Storage>) -> Self {
        let prefs = storage
            .setting(PREFS_KEY.into())
            .ok()
            .flatten()
            .and_then(|json| serde_json::from_str(&json).ok())
            .unwrap_or_default();
        Self {
            storage,
            prefs: Mutex::new(prefs),
            available: Mutex::new(None),
            checking: AtomicBool::new(false),
            downloading: AtomicBool::new(false),
        }
    }

    fn save(&self) {
        let json = serde_json::to_string(&*self.prefs.lock().unwrap());
        if let Ok(json) = json {
            let _ = self.storage.set_setting(PREFS_KEY.into(), json);
        }
    }

    fn view(&self) -> UpdateView {
        UpdateView {
            current: CURRENT,
            consent: self.prefs.lock().unwrap().consent,
            available: self.available.lock().unwrap().as_ref().map(release_view),
            downloading: self.downloading.load(Ordering::SeqCst),
        }
    }

    /// Interroge GitHub ; rend la version plus récente, s'il y en a une. [manual] : même une version ignorée.
    async fn check(&self, manual: bool) -> Result<Option<Release>, String> {
        if self.checking.swap(true, Ordering::SeqCst) {
            return Ok(self.available.lock().unwrap().clone());
        }
        let result = latest().await;
        self.checking.store(false, Ordering::SeqCst);
        let release = result?;
        self.prefs.lock().unwrap().last_check = now_ms();
        self.save();
        let skipped = self.prefs.lock().unwrap().skipped.as_deref() == Some(release.version());
        let newer = !release.prerelease && is_newer(release.version(), CURRENT) && (manual || !skipped);
        let found = newer.then_some(release);
        *self.available.lock().unwrap() = found.clone();
        Ok(found)
    }

    fn due(&self) -> bool {
        let p = self.prefs.lock().unwrap();
        p.consent == Some(true) && now_ms().saturating_sub(p.last_check) > DAY_MS
    }
}

fn release_view(r: &Release) -> ReleaseView {
    ReleaseView {
        tag: r.tag_name.clone(),
        notes: r.body.clone().unwrap_or_default(),
        page: if r.html_url.starts_with("https://") {
            r.html_url.clone()
        } else {
            format!("https://github.com/{REPO}/releases/latest")
        },
        installable: target().is_some_and(|t| asset_for(r, t).is_some()),
    }
}

/// Au lancement puis toutes les heures (l'app reste souvent des jours dans la zone de notification) : la
/// vérification du jour, si elle est acceptée. Une nouvelle version est annoncée à l'interface.
pub fn start(app: AppHandle) {
    tauri::async_runtime::spawn(async move {
        loop {
            if let Some(u) = app.try_state::<Updates>().filter(|u| u.due()) {
                let found = u.check(false).await;
                if matches!(found, Ok(Some(_))) {
                    let _ = app.emit("dicto://update", u.view());
                }
            }
            tokio::time::sleep(Duration::from_secs(3600)).await;
        }
    });
}

#[tauri::command]
pub fn update_state(updates: State<'_, Updates>) -> UpdateView {
    updates.view()
}

#[tauri::command]
pub async fn update_set_consent(app: AppHandle, updates: State<'_, Updates>, on: bool) -> Result<UpdateView, String> {
    updates.prefs.lock().unwrap().consent = Some(on);
    updates.save();
    if on && updates.due() && updates.check(false).await.is_ok_and(|r| r.is_some()) {
        let _ = app.emit("dicto://update", updates.view());
    }
    Ok(updates.view())
}

/// « Vérifier maintenant ».
#[tauri::command]
pub async fn update_check(updates: State<'_, Updates>) -> Result<UpdateView, String> {
    updates.check(true).await?;
    Ok(updates.view())
}

#[tauri::command]
pub fn update_skip(updates: State<'_, Updates>) -> UpdateView {
    if let Some(r) = updates.available.lock().unwrap().take() {
        updates.prefs.lock().unwrap().skipped = Some(r.version().to_owned());
    }
    updates.save();
    updates.view()
}

#[tauri::command]
pub fn update_later(updates: State<'_, Updates>) -> UpdateView {
    updates.available.lock().unwrap().take();
    updates.view()
}

/// Télécharge, vérifie et installe la version proposée. Rend un message quand Dicto reste ouvert.
#[tauri::command]
pub async fn update_install(app: AppHandle, updates: State<'_, Updates>) -> Result<String, String> {
    let release = updates.available.lock().unwrap().clone().ok_or("Aucune mise à jour à installer.")?;
    let target = target().ok_or("Cette copie de Dicto n'a pas été installée par un paquet : ouvre la page de la version.")?;
    let asset = asset_for(&release, target)
        .cloned()
        .ok_or("Pas de fichier pour ce système dans cette version : ouvre sa page.")?;
    if updates.downloading.swap(true, Ordering::SeqCst) {
        return Err("Téléchargement déjà en cours.".into());
    }
    let result = async {
        let dest = download_dir(&app, target)?.join(format!("{}.download", asset.name));
        let mut shown = 0u64;
        let sha = download_checked(&release, &asset, &dest, |done, total| {
            let step = (total / 100).max(256 * 1024);
            if done >= shown + step || done == total {
                shown = done;
                let _ = app.emit("dicto://update-progress", ProgressEvent { done, total });
            }
        })
        .await?;
        // Le nom d'origine (l'installeur de paquets se fie à l'extension).
        let path = dest.with_file_name(&asset.name);
        std::fs::rename(&dest, &path).map_err(|e| e.to_string())?;
        install(target, &path, &sha)
    }
    .await;
    updates.downloading.store(false, Ordering::SeqCst);
    match result? {
        Installed::Quit => {
            app.exit(0);
            Ok(String::new())
        }
        Installed::Opened(msg) => {
            updates.available.lock().unwrap().take();
            Ok(msg)
        }
    }
}

#[tauri::command]
pub fn update_open_page(updates: State<'_, Updates>) -> Result<(), String> {
    let page = updates
        .available
        .lock()
        .unwrap()
        .as_ref()
        .map(|r| release_view(r).page)
        .unwrap_or_else(|| format!("https://github.com/{REPO}/releases/latest"));
    open_url(&page)
}

fn open_url(url: &str) -> Result<(), String> {
    #[cfg(windows)]
    let r = std::process::Command::new("rundll32").args(["url.dll,FileProtocolHandler", url]).spawn();
    #[cfg(target_os = "macos")]
    let r = std::process::Command::new("open").arg(url).spawn();
    #[cfg(not(any(windows, target_os = "macos")))]
    let r = std::process::Command::new("xdg-open").arg(url).spawn();
    r.map(|_| ()).map_err(|e| e.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn versions_compare_by_numbers() {
        assert!(is_newer("v0.1.1", "0.1.0"));
        assert!(is_newer("0.10.0", "0.9.9"));
        assert!(is_newer("1.0", "0.99.0"));
        assert!(!is_newer("v0.1.0", "0.1.0"));
        assert!(!is_newer("0.0.9", "0.1.0"));
        assert!(!is_newer("0.1.0-beta", "0.1.0"));
        assert!(is_newer("0.2.0", "0.2.0-rc1"));
        assert!(is_newer("0.2.0-rc2", "0.2.0-rc1"));
        assert!(!is_newer("0.2.0+build7", "0.2.0"));
    }

    #[test]
    fn each_system_gets_its_own_file() {
        let r = Release {
            tag_name: "v0.2.0".into(),
            body: None,
            prerelease: false,
            html_url: String::new(),
            assets: [
                "Dicto-v0.2.0.apk",
                "Dicto-0.2.0-setup.exe",
                "dicto_0.2.0_amd64.deb",
                "dicto-0.2.0.x86_64.rpm",
                "Dicto-0.2.0-x86_64.AppImage",
                "Dicto-0.2.0-macos-arm64.dmg",
            ]
            .map(|n| Asset { name: n.into(), browser_download_url: String::new(), size: 1 })
            .to_vec(),
        };
        assert_eq!(asset_for(&r, Target::WindowsSetup).unwrap().name, "Dicto-0.2.0-setup.exe");
        assert_eq!(asset_for(&r, Target::Deb).unwrap().name, "dicto_0.2.0_amd64.deb");
        assert_eq!(asset_for(&r, Target::Rpm).unwrap().name, "dicto-0.2.0.x86_64.rpm");
        assert_eq!(asset_for(&r, Target::AppImage).unwrap().name, "Dicto-0.2.0-x86_64.AppImage");
        assert_eq!(asset_for(&r, Target::Mac("arm64")).unwrap().name, "Dicto-0.2.0-macos-arm64.dmg");
        assert!(asset_for(&r, Target::Mac("x86_64")).is_none());
    }

    #[test]
    fn an_update_without_a_matching_checksum_is_refused() {
        let sums = "abc123  Dicto-0.2.0-setup.exe\nDEF456 *dicto_0.2.0_amd64.deb\n";
        assert_eq!(expected_sha256(sums, "dicto_0.2.0_amd64.deb").as_deref(), Some("def456"));
        assert!(check_sha256(Some(sums), "Dicto-0.2.0-setup.exe", "ABC123").is_ok());
        assert!(check_sha256(Some(sums), "Dicto-0.2.0-setup.exe", "abc124").is_err(), "mauvais hash");
        assert!(check_sha256(Some(sums), "dicto-0.2.0.x86_64.rpm", "abc123").is_err(), "absent de la liste");
        assert!(check_sha256(None, "Dicto-0.2.0-setup.exe", "abc123").is_err(), "pas de liste");
    }

    #[test]
    fn a_changed_file_is_not_installed() {
        let path = std::env::temp_dir().join(format!("dicto-update-test-{}", std::process::id()));
        std::fs::write(&path, b"x").unwrap();
        let err = install(Target::Deb, &path, "0000").err().unwrap();
        assert!(err.contains("changé"), "{err}");
        assert!(!path.exists());
    }
}
