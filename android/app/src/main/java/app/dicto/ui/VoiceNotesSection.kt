package app.dicto.ui

import app.dicto.i18n.tr
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.rotate
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.Icon
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dicto.DictoApp
import app.dicto.files.FileItem
import app.dicto.files.FilesController
import app.dicto.files.ShareTranscribeActivity
import app.dicto.files.VoiceNote
import app.dicto.files.VoiceNoteListener
import app.dicto.files.VoiceNotes
import app.dicto.triggers.VoiceNoteTileService
import app.dicto.ui.theme.Dicto
import app.dicto.ui.theme.DictoIcons

/**
 * Vocaux WhatsApp, en tête de l'écran Fichiers : accès au dossier, dernier vocal, tuile,
 * transcription automatique et liste des vocaux récents.
 * [onOpen] ouvre le texte complet d'un élément de la file.
 */
@Composable
fun VoiceNotesSection(onOpen: (Long) -> Unit) {
    val context = LocalContext.current
    val settings = DictoApp.instance.settings
    // Recalculé au retour des réglages Android (accès aux notifications, dossier).
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumed++
        onPauseOrDispose { }
    }
    var granted by remember { mutableStateOf(VoiceNotes.isGranted(context)) }
    val listener = remember(resumed) { VoiceNoteListener.isEnabled(context) }
    val auto by settings.voiceNotesAuto.flow.collectAsStateWithLifecycle()
    val engine by settings.engine.collectAsStateWithLifecycle()
    val texts by VoiceNotes.texts.collectAsStateWithLifecycle()
    val items by FilesController.items.collectAsStateWithLifecycle()
    val senders by VoiceNotes.senders.collectAsStateWithLifecycle()
    var notes by remember { mutableStateOf<List<VoiceNote>?>(null) }
    var expanded by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(resumed) { granted = VoiceNotes.isGranted(context) }
    // Liste rechargée à l'ouverture, au retour dans l'app, quand un vocal se termine ou arrive.
    val done = items.count { it.voiceNote != null && it.status == FileItem.Status.DONE }
    LaunchedEffect(granted, resumed, done, senders.size) {
        notes = if (granted) VoiceNotes.recent(context) else null
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { VoiceNotes.grant(context, uri) }
                .onFailure { Toast.makeText(context, tr("Accès au dossier impossible."), Toast.LENGTH_LONG).show() }
            granted = VoiceNotes.isGranted(context)
        }
    }

    SectionTitle(tr("Vocaux WhatsApp"), tr("Transcrire les messages vocaux sans passer par Partager"))
    if (!granted) {
        DictoCard {
            Text(
                tr("Dicto a besoin de lire le dossier où WhatsApp range les vocaux. Le sélecteur s'ouvre dessus : touche « Utiliser ce dossier », puis « Autoriser »."),
                style = MaterialTheme.typography.bodySmall,
                color = Dicto.InkMuted,
            )
            PrimaryButton(tr("Autoriser les vocaux WhatsApp"), { pick.launch(VoiceNotes.initialUri) })
        }
        return
    }

    val list = notes

    DictoCard(padding = PaddingValues(0.dp)) {
        Column {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    PrimaryButton(
                        tr("Dernier vocal"),
                        { context.startActivity(ShareTranscribeActivity.latestIntent(context)) },
                        Modifier.weight(1f),
                        icon = DictoIcons.Mic,
                    )
                    TonalButton(tr("Ajouter la tuile"), {
                        requestTile(context, VoiceNoteTileService::class.java, tr("Dernier vocal"), tr("Dernier vocal"))
                    }, icon = DictoIcons.Plus)
                }
                SettingRow(
                    tr("Transcription automatique"),
                    tr("Chaque vocal reçu est transcrit, le texte arrive en notification. Seules les notifications de WhatsApp sont lues."),
                ) {
                    DictoSwitch(auto, { on ->
                        settings.voiceNotesAuto.set(on)
                        if (on) {
                            if (VoiceNoteListener.isEnabled(context)) VoiceNoteListener.rebind(context) else openListenerSettings(context)
                        }
                    }, tr("Transcription automatique"))
                }
                when {
                    auto && !listener -> ListenerWarning(context)
                    !listener -> SendersHint(context)
                }
                if (auto && engine is app.dicto.data.EngineChoice.Cloud) {
                    Text(
                        tr("Avec un service en ligne comme moteur, l'audio de chaque vocal lui est envoyé."),
                        style = MaterialTheme.typography.labelSmall,
                        color = Dicto.InkMuted,
                    )
                }
            }
            HorizontalDivider(color = Dicto.SurfaceSunken)
            Text(
                tr("Vocaux récents"),
                style = MaterialTheme.typography.labelMedium,
                color = Dicto.InkMuted,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
            )
            when {
                list == null -> Text(tr("Lecture du dossier…"), style = MaterialTheme.typography.bodySmall, color = Dicto.InkMuted, modifier = Modifier.padding(16.dp))
                list.isEmpty() -> Text(
                    tr("Aucun vocal dans ce dossier. Vérifie que c'est bien « WhatsApp Voice Notes » (WhatsApp › Media)."),
                    style = MaterialTheme.typography.bodySmall,
                    color = Dicto.InkMuted,
                    modifier = Modifier.padding(16.dp),
                )
                else -> {
                    val shown = if (expanded) list else list.take(COLLAPSED)
                    shown.forEachIndexed { i, note ->
                        if (i > 0) HorizontalDivider(color = Dicto.SurfaceSunken, modifier = Modifier.padding(start = 68.dp))
                        VoiceNoteRow(context, note, texts[note.docId], senders[note.docId], items.firstOrNull { it.voiceNote == note.docId }, onOpen)
                    }
                    if (list.size > COLLAPSED) {
                        HorizontalDivider(color = Dicto.SurfaceSunken)
                        Row(
                            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (expanded) tr("Réduire") else tr("Voir les %1\$s autres", list.size - COLLAPSED),
                                style = MaterialTheme.typography.labelLarge,
                                color = Dicto.Accent,
                            )
                            Spacer(Modifier.width(6.dp))
                            Icon(DictoIcons.ChevronDown, null, tint = Dicto.Accent, modifier = Modifier.size(18.dp).rotate(if (expanded) 180f else 0f))
                        }
                    }
                }
            }
            if (expanded || list.isNullOrEmpty()) {
                HorizontalDivider(color = Dicto.SurfaceSunken)
                Text(
                    tr("Changer de dossier (%1\$s)", VoiceNotes.folderName() ?: "?"),
                    style = MaterialTheme.typography.labelSmall,
                    color = Dicto.InkMuted,
                    modifier = Modifier.fillMaxWidth().clickable { pick.launch(VoiceNotes.initialUri) }.padding(16.dp),
                )
            }
        }
    }
}

/** Vocaux affichés tant que la liste est repliée. */
private const val COLLAPSED = 3

@Composable
private fun VoiceNoteRow(context: Context, note: VoiceNote, text: String?, sender: String?, item: FileItem?, onOpen: (Long) -> Unit) {
    val transcribe = {
        FilesController.add(context, note.uri, VoiceNotes.WHATSAPP, voiceNote = note.docId, title = sender?.let { tr("Vocal de %1\$s", it) }, notify = false)
        Unit
    }
    val open = {
        val id = item?.takeIf { it.status == FileItem.Status.DONE }?.id
            ?: FilesController.addCached(note.name, text.orEmpty(), note.docId)
        onOpen(id)
    }
    val busy = item?.takeIf { it.active || it.status == FileItem.Status.QUEUED }
    val failed = item?.takeIf { it.status == FileItem.Status.FAILED }
    Row(
        Modifier.fillMaxWidth()
            .then(if (text != null) Modifier.clickable { open() } else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Initiale de l'expéditeur, ou un micro quand on ne le connaît pas.
        Box(Modifier.size(40.dp).clip(CircleShape).background(Dicto.AccentContainer), contentAlignment = Alignment.Center) {
            val initial = sender?.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()
            if (initial != null) {
                Text(initial.toString(), style = MaterialTheme.typography.titleMedium, color = Dicto.Accent)
            } else {
                Icon(DictoIcons.Mic, null, tint = Dicto.Accent, modifier = Modifier.size(18.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    sender ?: tr("Message vocal"),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                Text(relativeTime(context, note.modifiedAt), style = MaterialTheme.typography.labelSmall, color = Dicto.InkMuted, maxLines = 1)
            }
            when {
                text != null -> Text(
                    text.ifBlank { tr("Aucune parole détectée.") },
                    style = MaterialTheme.typography.bodySmall,
                    color = Dicto.InkMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                busy != null -> Text(
                    when (busy.status) {
                        FileItem.Status.TRANSCRIBING -> tr("Transcription %1\$s %%", (busy.progress * 100).toInt())
                        FileItem.Status.DECODING -> tr("Lecture %1\$s %%", (busy.progress * 100).toInt())
                        else -> tr("En attente")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = Dicto.Accent,
                )
                failed != null -> Text(
                    tr("Échec : %1\$s", failed.error),
                    style = MaterialTheme.typography.labelSmall,
                    color = Dicto.OnRecordingContainer,
                    maxLines = 2,
                )
            }
        }
        when {
            text != null -> GhostButton(null, DictoIcons.Copy, {
                copy(context, text)
                // Android 13+ confirme lui-même la copie.
                if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, tr("Texte copié."), Toast.LENGTH_SHORT).show()
            }, contentDescription = tr("Copier"))
            busy != null -> Unit
            else -> TonalButton(tr("Transcrire"), transcribe)
        }
    }
}

/** « Aujourd'hui 14:22 », « Hier 13:35 », sinon « 17 nov. 13:02 ». */
private fun relativeTime(context: Context, ms: Long): String {
    val time = DateUtils.formatDateTime(context, ms, DateUtils.FORMAT_SHOW_TIME)
    return when {
        DateUtils.isToday(ms) -> tr("Aujourd'hui %1\$s", time)
        DateUtils.isToday(ms + DateUtils.DAY_IN_MILLIS) -> tr("Hier %1\$s", time)
        else -> DateUtils.formatDateTime(context, ms, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH) + " " + time
    }
}

/** Sans l'accès aux notifications, les vocaux n'ont pas d'expéditeur. */
@Composable
private fun SendersHint(context: Context) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            tr("Pour voir qui a envoyé chaque nouveau vocal (personne ou groupe), autorise Dicto à lire les notifications de WhatsApp."),
            style = MaterialTheme.typography.labelSmall,
            color = Dicto.InkMuted,
            modifier = Modifier.weight(1f),
        )
        TonalButton(tr("Autoriser"), { openListenerSettings(context) })
    }
}

/** Accès aux notifications manquant (ou bloqué en « paramètre restreint » sur Android 13+). */
@Composable
private fun ListenerWarning(context: Context) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Dicto.RecordingContainer).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            tr("Autorise Dicto à lire les notifications, pour repérer les vocaux de WhatsApp."),
            style = MaterialTheme.typography.labelMedium,
            color = Dicto.OnRecordingContainer,
        )
        if (Build.VERSION.SDK_INT >= 33) {
            Text(
                tr("« Accès refusé » ou réglage grisé ? Android verrouille ce réglage pour les applis installées hors boutique. Ouvre les infos de Dicto, touche ⋮ en haut à droite, puis « Autoriser les paramètres restreints ». Reviens ensuite activer l'autorisation."),
                style = MaterialTheme.typography.labelSmall,
                color = Dicto.OnRecordingContainer,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (Build.VERSION.SDK_INT >= 33) {
                TonalButton(tr("Infos de Dicto"), {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                })
            }
            TonalButton(tr("Autoriser"), { openListenerSettings(context) })
        }
    }
}

private fun openListenerSettings(context: Context) {
    val detail = if (Build.VERSION.SDK_INT >= 30) {
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(context, VoiceNoteListener::class.java).flattenToString())
    } else {
        null
    }
    val list = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    runCatching { context.startActivity((detail ?: list).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { runCatching { context.startActivity(list.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
}
