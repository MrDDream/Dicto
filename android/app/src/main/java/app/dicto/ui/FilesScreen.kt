package app.dicto.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dicto.DictoApp
import app.dicto.data.LANGUAGES
import app.dicto.dictation.userMessage
import app.dicto.files.FileItem
import app.dicto.files.FilesController
import app.dicto.ui.theme.Dicto
import app.dicto.ui.theme.DictoIcons
import app.dicto.ui.theme.Mono
import kotlinx.coroutines.launch
import uniffi.dicto_ffi.ExportFormat
import uniffi.dicto_ffi.FileTranscript
import uniffi.dicto_ffi.exportTranscript

/** Écran « Fichiers audio » : transcrire un enregistrement, une réunion, un message vocal. */
@Composable
fun FilesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = DictoApp.instance
    val items by FilesController.items.collectAsStateWithLifecycle()
    val ui by app.dictation.ui.collectAsStateWithLifecycle()
    val language by app.settings.language.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf<Long?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { FilesController.add(context, it) }
    }

    val opened = items.firstOrNull { it.id == open && it.result != null }
    if (opened != null) {
        TranscriptView(opened, onBack = { open = null })
        return
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader("Fichiers audio", onBack)
        Text(
            "Transcrire un enregistrement",
            style = MaterialTheme.typography.bodyMedium,
            color = Dicto.InkMuted,
            modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 4.dp),
        )
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Dicto.Surface)
                    .border(1.5.dp, Dicto.Border, RoundedCornerShape(22.dp)).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.size(48.dp).clip(CircleShape).background(Dicto.AccentContainer), contentAlignment = Alignment.Center) {
                    Icon(DictoIcons.Share, null, tint = Dicto.Accent, modifier = Modifier.size(22.dp))
                }
                Text("MP3, M4A, WAV, OGG, OPUS, FLAC", style = MaterialTheme.typography.titleSmall)
                Text("et l'audio des vidéos MP4 ou MKV", style = MaterialTheme.typography.labelSmall, color = Dicto.InkMuted)
                PrimaryButton("Choisir un fichier", { pick.launch(arrayOf("audio/*", "video/*")) })
                Text(
                    "Ou partage un message vocal vers Dicto depuis n'importe quelle app.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Dicto.InkMuted,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            DictoCard {
                SettingRow("Moteur", null) {
                    Text(ui.engine.label, style = MaterialTheme.typography.labelMedium, color = if (ui.engine.isReady) Dicto.Accent else Dicto.OnRecordingContainer)
                }
                HorizontalDivider(color = Dicto.SurfaceSunken)
                SettingRow("Langue", null) {
                    Text(LANGUAGES.first { it.first == language }.second, style = MaterialTheme.typography.labelMedium)
                }
                HorizontalDivider(color = Dicto.SurfaceSunken)
                SettingRow("Horodatage", "Chaque passage avec son minutage") { Pill("Toujours", Dicto.AccentContainer, Dicto.Accent) }
                HorizontalDivider(color = Dicto.SurfaceSunken)
                SettingRow("Séparer les voix", "Arrive dans une prochaine version") { Pill("Bientôt", Dicto.SurfaceSunken, Dicto.InkSecondary) }
            }
            if (items.isNotEmpty()) {
                SectionTitle("File d'attente")
                DictoCard(padding = PaddingValues(0.dp)) {
                    Column {
                        items.forEachIndexed { i, item ->
                            if (i > 0) HorizontalDivider(color = Dicto.SurfaceSunken)
                            QueueRow(item, onOpen = { open = item.id })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueRow(item: FileItem, onOpen: () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(
                    listOfNotNull(
                        item.durationMs.takeIf { it > 0 }?.let(::duration),
                        when (item.status) {
                            FileItem.Status.QUEUED -> "En attente"
                            FileItem.Status.DECODING -> "Lecture ${(item.progress * 100).toInt()} %"
                            FileItem.Status.TRANSCRIBING -> "${(item.progress * 100).toInt()} %"
                            FileItem.Status.DONE -> "Terminé"
                            FileItem.Status.FAILED -> "Échec : ${item.error}"
                            FileItem.Status.CANCELLED -> "Annulé"
                        },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (item.status == FileItem.Status.FAILED) Dicto.OnRecordingContainer else Dicto.InkMuted,
                )
            }
            when (item.status) {
                FileItem.Status.DONE -> TonalButton("Ouvrir", onOpen)
                FileItem.Status.QUEUED, FileItem.Status.DECODING, FileItem.Status.TRANSCRIBING ->
                    TextButton(onClick = { FilesController.cancel(item.id) }) { Text("Annuler") }
                else -> GhostButton(null, DictoIcons.Close, { FilesController.remove(item.id) }, contentDescription = "Retirer", tint = Dicto.InkMuted)
            }
        }
        if (item.active) {
            LinearProgressIndicator(
                progress = { item.progress },
                modifier = Modifier.fillMaxWidth().clip(CircleShape),
                color = Dicto.Accent,
                trackColor = Dicto.SurfaceSunken,
            )
        }
    }
}

@Composable
private fun TranscriptView(item: FileItem, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = DictoApp.instance
    val t = item.result ?: return
    val scope = rememberCoroutineScope()
    var summary by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<Pair<ExportFormat, String>?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val (format, title) = pending ?: return@rememberLauncherForActivityResult
        if (uri != null) context.contentResolver.openOutputStream(uri)?.use { it.write(exportTranscript(t, format, title).toByteArray()) }
        pending = null
    }
    val base = item.name.substringBeforeLast('.')
    val refineProvider by app.settings.refineProvider.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(item.name, onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            summary?.let {
                DictoCard {
                    Text("Résumé", style = MaterialTheme.typography.titleSmall)
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
            }
            DictoCard {
                for (s in t.segments) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(timestamp(s.startMs.toLong()), fontFamily = Mono, style = MaterialTheme.typography.labelSmall, color = Dicto.InkMuted, modifier = Modifier.width(52.dp))
                        Text(s.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    }
                }
                if (t.segments.isEmpty()) Text(t.text.ifBlank { "Aucune parole détectée." }, style = MaterialTheme.typography.bodyMedium)
            }
        }
        FlowRow(
            Modifier.fillMaxWidth().background(Dicto.Surface).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TonalButton("Copier", { copy(context, t.text) }, icon = DictoIcons.Copy)
            for ((format, ext) in listOf(ExportFormat.TXT to "txt", ExportFormat.SRT to "srt", ExportFormat.VTT to "vtt", ExportFormat.MD to "md")) {
                TonalButton(".$ext", {
                    pending = format to base
                    save.launch("$base.$ext")
                })
            }
            if (refineProvider != null) {
                PrimaryButton(if (summary == null) "Résumer avec un LLM" else "Résumé ✓", {
                    scope.launch {
                        summary = "…"
                        summary = try {
                            app.dictation.refineClient()?.refine(
                                "Résume cette transcription en quelques puces claires (décisions, actions, points clés), dans sa langue.",
                                t.text,
                            )
                        } catch (e: Exception) {
                            "Résumé impossible : ${e.userMessage()}"
                        }
                    }
                })
            }
            Spacer(Modifier.size(1.dp))
        }
    }
}

private fun timestamp(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
}

private fun duration(ms: Long): String {
    val s = ms / 1000
    return when {
        s >= 3600 -> "${s / 3600} h ${"%02d".format(s / 60 % 60)}"
        s >= 60 -> "${s / 60} min"
        else -> "0:%02d".format(s)
    }
}
