package app.dicto.update

import app.dicto.i18n.tr
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import app.dicto.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * « Mises à jour » : la dernière version publiée sur GitHub (MrDDream/Dicto), vérifiée au démarrage une fois
 * par jour au plus, et seulement si l'utilisateur l'a accepté. L'APK du processeur du téléphone est téléchargé,
 * vérifié contre le SHA256SUMS.txt de la version (obligatoire) puis contre la clé de signature de l'app
 * installée, et confié à l'installeur d'Android. Le bureau fait de même dans `update.rs`.
 */
object UpdateChecker {
    const val REPO = "MrDDream/Dicto"
    private const val PREFS = "dicto_updates"
    private const val KEY_CONSENT = "auto_check_consent"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class Asset(val name: String, val url: String, val size: Long)
    data class Release(val tag: String, val notes: String, val prerelease: Boolean, val page: String, val assets: List<Asset>) {
        val version get() = tag.removePrefix("v")
    }

    private val _available = MutableStateFlow<Release?>(null)
    /** Une version plus récente à proposer (la fenêtre l'affiche). */
    val available: StateFlow<Release?> = _available.asStateFlow()

    private val _consent = MutableStateFlow<Boolean?>(null)
    /**
     * La réponse à « Vérifier automatiquement les mises à jour ? », posée une fois au premier lancement puis
     * modifiable dans Réglages › À propos. Null avant : rien n'est envoyé à GitHub sans réponse.
     */
    val consent: StateFlow<Boolean?> = _consent.asStateFlow()

    fun init(context: Context) {
        val p = prefs(context)
        _consent.value = if (p.contains(KEY_CONSENT)) p.getBoolean(KEY_CONSENT, false) else null
    }

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_CONSENT, on).apply()
        _consent.value = on
        if (on) checkAtStart(context)
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** La vérification au démarrage : acceptée, et pas faite depuis 20 heures. */
    fun checkAtStart(context: Context) {
        val p = prefs(context)
        if (_consent.value != true || System.currentTimeMillis() - p.getLong("last", 0) < 20 * 3600_000L) return
        scope.launch {
            val r = latest().getOrNull() ?: return@launch
            p.edit().putLong("last", System.currentTimeMillis()).apply()
            if (!r.prerelease && isNewer(r.version, BuildConfig.VERSION_NAME) && p.getString("skipped", null) != r.version) {
                _available.value = r
            }
        }
    }

    /** « Vérifier maintenant » : la version plus récente, ou null quand Dicto est à jour. */
    suspend fun checkNow(context: Context): Result<Release?> = latest().map { r ->
        prefs(context).edit().putLong("last", System.currentTimeMillis()).apply()
        r.takeIf { !it.prerelease && isNewer(it.version, BuildConfig.VERSION_NAME) }?.also { _available.value = it }
    }

    fun later() { _available.value = null }

    fun skip(context: Context) {
        _available.value?.let { prefs(context).edit().putString("skipped", it.version).apply() }
        _available.value = null
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 20_000
        readTimeout = 60_000
        setRequestProperty("User-Agent", "Dicto/${BuildConfig.VERSION_NAME}")
    }

    suspend fun latest(): Result<Release> = withContext(Dispatchers.IO) {
        runCatching {
            val c = open("https://api.github.com/repos/$REPO/releases/latest")
            c.setRequestProperty("Accept", "application/vnd.github+json")
            try {
                if (c.responseCode != 200) throw IOException("GitHub : HTTP ${c.responseCode}")
                val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                val assets = o.optJSONArray("assets")
                Release(
                    tag = o.getString("tag_name"),
                    notes = o.optString("body"),
                    prerelease = o.optBoolean("prerelease"),
                    page = o.optString("html_url"),
                    assets = (0 until (assets?.length() ?: 0)).map { i ->
                        val a = assets!!.getJSONObject(i)
                        Asset(a.getString("name"), a.getString("browser_download_url"), a.optLong("size"))
                    },
                )
            } finally {
                c.disconnect()
            }
        }
    }

    /** Si [candidate] est plus récente que [current] (nombres comparés un à un, « -beta » ignoré). */
    fun isNewer(candidate: String, current: String): Boolean {
        fun parts(v: String) = v.removePrefix("v").substringBefore('-').substringBefore('+').split('.').map { it.toLongOrNull() ?: 0 }
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** L'APK du processeur du téléphone (noms donnés par release.yml), sinon l'universel. */
    fun apkFor(r: Release, abis: List<String> = Build.SUPPORTED_ABIS.toList()): Asset? =
        abis.firstNotNullOfOrNull { abi -> r.assets.firstOrNull { it.name == "Dicto-${r.tag}-$abi.apk" } }
            ?: r.assets.firstOrNull { it.name == "Dicto-${r.tag}.apk" }

    /** Le SHA-256 de [file] dans une liste au format de `sha256sum`. */
    fun expectedSha256(sums: String, file: String): String? = sums.lineSequence().firstNotNullOfOrNull { line ->
        val parts = line.trim().split(Regex("\\s+"), limit = 2)
        if (parts.size == 2 && parts[1].trimStart('*') == file) parts[0].lowercase() else null
    }

    /** La somme que doit avoir [file] ; sans liste, ou sans ligne pour ce fichier, la mise à jour est refusée. */
    fun requireSha256(sums: String?, file: String): String =
        sums?.let { expectedSha256(it, file) }?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
            ?: throw IOException(tr("Somme de contrôle introuvable pour %1\$s : mise à jour refusée.", file))

    private const val MAX_SUMS_BYTES = 1 shl 20

    /** La liste des sommes de la version, null quand elle n'en a pas ou qu'on ne peut pas la lire. */
    private fun fetchSums(r: Release): String? {
        val asset = r.assets.firstOrNull { it.name == "SHA256SUMS.txt" } ?: return null
        val c = open(asset.url)
        return try {
            if (c.responseCode != 200) null
            else c.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8 * 1024)
                while (out.size() < MAX_SUMS_BYTES) {
                    val n = input.read(buf, 0, minOf(buf.size, MAX_SUMS_BYTES - out.size()))
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                out.toString(Charsets.UTF_8.name())
            }
        } finally {
            c.disconnect()
        }
    }

    /**
     * Si un APK signé par [archiveCurrent] (avec l'historique de rotation [archiveHistory]) peut remplacer une app
     * signée par [installed] : les mêmes clés, ou une clé renouvelée dont l'historique contient l'installée.
     */
    fun signersMatch(installed: Set<String>, archiveCurrent: Set<String>, archiveHistory: Set<String>): Boolean =
        installed.isNotEmpty() && archiveCurrent.isNotEmpty() &&
            (installed == archiveCurrent || (installed.size == 1 && installed.single() in archiveHistory))

    private class Signers(val current: Set<String>, val history: Set<String>)

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo?): Signers? {
        info ?: return null
        fun digest(sigs: Array<Signature>?) = sigs.orEmpty().map { s ->
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
        info.signingInfo?.let { si ->
            val current = digest(si.apkContentsSigners)
            val history = if (si.hasMultipleSigners()) emptySet() else digest(si.signingCertificateHistory)
            if (current.isNotEmpty()) return Signers(current, history)
        }
        return digest(info.signatures).takeIf { it.isNotEmpty() }?.let { Signers(it, emptySet()) }
    }

    /** Refuse un APK qui n'est pas Dicto, ou qui n'est pas signé par la clé de l'app installée. */
    private fun checkSigner(context: Context, apk: File) {
        val pm = context.packageManager
        @Suppress("DEPRECATION") // Seulement en repli, quand l'archive ne remplit pas ses signatures autrement.
        val legacy = PackageManager.GET_SIGNATURES
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val installed = signers(pm.packageInfo(context.packageName, flags))
        val info = pm.archiveInfo(apk.path, flags)?.takeIf { signers(it) != null } ?: pm.archiveInfo(apk.path, legacy)
        val archive = signers(info)
        if (info?.packageName != context.packageName || installed == null || archive == null ||
            !signersMatch(installed.current, archive.current, archive.history)
        ) {
            apk.delete()
            throw IOException(tr("La mise à jour n'est pas signée par la même clé que l'application installée : elle a été supprimée."))
        }
    }

    /** Télécharge l'APK dans le cache, le vérifie (somme, puis signature) et le rend. */
    suspend fun download(context: Context, r: Release, asset: Asset, progress: (Long, Long) -> Unit): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            // Vérifié avant de télécharger : sans somme de contrôle, l'APK n'est jamais installé.
            val expected = requireSha256(fetchSums(r), asset.name)
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, asset.name)
            val digest = MessageDigest.getInstance("SHA-256")
            val c = open(asset.url).apply { readTimeout = 120_000 }
            try {
                if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
                val total = c.contentLengthLong.takeIf { it > 0 } ?: asset.size
                c.inputStream.use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var shown = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            done += n
                            if (done - shown > 256 * 1024 || done == total) { shown = done; progress(done, total) }
                        }
                    }
                }
            } finally {
                c.disconnect()
            }
            val got = digest.digest().joinToString("") { "%02x".format(it) }
            if (expected != got) {
                file.delete()
                throw IOException(tr("Le fichier téléchargé ne correspond pas à la somme de contrôle de la version : il a été supprimé."))
            }
            checkSigner(context, file)
            file
        }
    }

    /**
     * Confie l'APK à l'installeur d'Android ; demande d'abord d'autoriser Dicto à installer des applis quand il le
     * faut. Rend false quand c'est cet écran d'autorisation qui a été ouvert.
     */
    fun install(context: Context, apk: File): Boolean {
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return false
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".updates", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return true
    }
}

private fun PackageManager.packageInfo(name: String, flags: Int): PackageInfo =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) getPackageInfo(name, PackageManager.PackageInfoFlags.of(flags.toLong()))
    else @Suppress("DEPRECATION") getPackageInfo(name, flags)

private fun PackageManager.archiveInfo(path: String, flags: Int): PackageInfo? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(flags.toLong()))
    else @Suppress("DEPRECATION") getPackageArchiveInfo(path, flags)
