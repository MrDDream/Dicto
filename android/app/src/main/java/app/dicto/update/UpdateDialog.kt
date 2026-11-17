package app.dicto.update

import app.dicto.i18n.tr
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dicto.BuildConfig
import app.dicto.ui.theme.Dicto
import kotlinx.coroutines.launch
import java.io.File

/** Les fenêtres des mises à jour : la question du premier lancement, puis chaque nouvelle version. */
@Composable
fun UpdateDialogs() {
    val consent by UpdateChecker.consent.collectAsStateWithLifecycle()
    if (consent == null) ConsentDialog() else UpdateDialog()
}

/** « Vérifier automatiquement les mises à jour ? », posée une seule fois. */
@Composable
private fun ConsentDialog() {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = {},
        containerColor = Dicto.Surface,
        title = { Text(tr("Vérifier les mises à jour ?")) },
        text = {
            Text(
                tr("Une fois par jour, Dicto peut regarder sur GitHub si une nouvelle version est sortie et te la proposer. Rien d'autre n'est envoyé. Tu pourras changer d'avis dans Réglages › À propos."),
                color = Dicto.InkMuted,
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        dismissButton = {
            TextButton(onClick = { UpdateChecker.setEnabled(context, false) }) { Text(tr("Non merci"), color = Dicto.InkMuted) }
        },
        confirmButton = {
            Button(
                onClick = { UpdateChecker.setEnabled(context, true) },
                colors = ButtonDefaults.buttonColors(containerColor = Dicto.Accent, contentColor = Dicto.OnAccent),
            ) { Text(tr("Oui, vérifier")) }
        },
    )
}

/** Une nouvelle version : ses notes, puis « Télécharger et installer » avec la progression. */
@Composable
private fun UpdateDialog() {
    val context = LocalContext.current
    val release by UpdateChecker.available.collectAsStateWithLifecycle()
    val r = release ?: return
    val scope = rememberCoroutineScope()
    var progress by remember(r.tag) { mutableStateOf<Float?>(null) }
    var error by remember(r.tag) { mutableStateOf<String?>(null) }
    var downloaded by remember(r.tag) { mutableStateOf<File?>(null) }
    val apk = remember(r.tag) { UpdateChecker.apkFor(r) }
    val busy = progress != null && downloaded == null
    AlertDialog(
        onDismissRequest = { if (!busy) UpdateChecker.later() },
        containerColor = Dicto.Surface,
        title = { Text(tr("Dicto %1\$s est disponible", r.tag)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr("Tu as la version %1\$s", BuildConfig.VERSION_NAME), color = Dicto.InkMuted, style = MaterialTheme.typography.bodySmall)
                Text(
                    plain(r.notes).ifBlank { tr("Pas de notes pour cette version.") },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                )
                progress?.let {
                    LinearProgressIndicator(
                        progress = { it },
                        color = Dicto.Accent,
                        trackColor = Dicto.SurfaceSunken,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                error?.let { Text(it, color = Dicto.OnRecordingContainer, style = MaterialTheme.typography.bodySmall) }
                if (downloaded != null) {
                    Text(
                        tr("Autorise Dicto à installer des applis, puis touche « Installer »."),
                        color = Dicto.InkMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        dismissButton = {
            if (!busy) {
                TextButton(onClick = { UpdateChecker.skip(context) }) { Text(tr("Ignorer"), color = Dicto.InkMuted) }
                TextButton(onClick = { UpdateChecker.later() }) { Text(tr("Plus tard"), color = Dicto.Ink) }
            }
        },
        confirmButton = {
            Button(
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = Dicto.Accent, contentColor = Dicto.OnAccent),
                onClick = {
                    downloaded?.let { f -> if (UpdateChecker.install(context, f)) UpdateChecker.later(); return@Button }
                    if (apk == null) {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.page)))
                        return@Button
                    }
                    error = null
                    progress = 0f
                    scope.launch {
                        UpdateChecker.download(context, r, apk) { done, total -> progress = if (total > 0) done.toFloat() / total else 0f }
                            .onSuccess { f -> downloaded = f; if (UpdateChecker.install(context, f)) UpdateChecker.later() }
                            .onFailure { e -> progress = null; error = e.message ?: e.javaClass.simpleName }
                    }
                },
            ) {
                Text(
                    when {
                        downloaded != null -> tr("Installer")
                        busy -> tr("Téléchargement…")
                        apk == null -> tr("Ouvrir la page")
                        else -> tr("Télécharger et installer")
                    },
                )
            }
        },
    )
}

/** Le Markdown en texte simple : titres, gras et puces retirés. */
internal fun plain(md: String): String = md.lineSequence().joinToString("\n") { line ->
    line.trimEnd()
        .replace(Regex("^#{1,6}\\s*"), "")
        .replace(Regex("^\\s*[-*]\\s+"), "• ")
        .replace("**", "").replace("`", "")
}.trim()
