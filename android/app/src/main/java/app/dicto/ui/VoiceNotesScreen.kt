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
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import app.dicto.files.topNames
import app.dicto.files.matchesFilter
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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

/** Compteur qui avance à chaque retour sur l'écran (réglages d'Android, autre app). */
@Composable
private fun rememberResumes(): Int {
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumed++
        onPauseOrDispose { }
    }
    return resumed
}

// --- Réglages › Vocaux WhatsApp -------------------------------------------------------

/** Résumé de la rubrique sur l'accueil des réglages. */
@Composable
fun voiceNotesSummary(): String {
    val s = DictoApp.instance.settings
    val enabled by s.voiceNotesEnabled.flow.collectAsStateWithLifecycle()
    val auto by s.voiceNotesAuto.flow.collectAsStateWithLifecycle()
    if (!enabled) return tr("Désactivés")
    return listOfNotNull(tr("onglet WhatsApp"), tr("transcription auto").takeIf { auto }).joinToString(" · ").replaceFirstChar { it.uppercase() }
}

/** Activation de l'onglet WhatsApp, dossier des vocaux, tuile, transcription automatique, expéditeurs. */
@Composable
fun VoiceNotesSettingsPage() {
    val context = LocalContext.current
    val settings = DictoApp.instance.settings
    val resumed = rememberResumes()
    val enabled by settings.voiceNotesEnabled.flow.collectAsStateWithLifecycle()
    val auto by settings.voiceNotesAuto.flow.collectAsStateWithLifecycle()
    val engine by settings.engine.collectAsStateWithLifecycle()
    val folder by settings.voiceNotesFolder.flow.collectAsStateWithLifecycle()
    val granted = remember(resumed, folder) { VoiceNotes.isGranted(context) }
    val listener = remember(resumed) { VoiceNoteListener.isEnabled(context) }
    val pick = rememberFolderPicker(context)

    DictoCard {
        SettingRow(tr("Vocaux WhatsApp"), tr("Ajoute l'onglet WhatsApp : tes vocaux, qui les envoie, et leur texte.")) {
            DictoSwitch(enabled, settings.voiceNotesEnabled::set, tr("Vocaux WhatsApp"))
        }
    }
    if (!enabled) return

    SectionTitle(tr("Dossier des vocaux"))
    DictoCard {
        if (!granted) {
            Text(
                tr("Dicto a besoin de lire le dossier où WhatsApp range les vocaux. Le sélecteur s'ouvre dessus : touche « Utiliser ce dossier », puis « Autoriser »."),
                style = MaterialTheme.typography.bodySmall,
                color = Dicto.InkMuted,
            )
            PrimaryButton(tr("Autoriser les vocaux WhatsApp"), { pick() })
        } else {
            SettingRow(VoiceNotes.folderName() ?: tr("Dossier"), tr("Accès accordé")) {
                TonalButton(tr("Changer"), { pick() })
            }
        }
    }

    SectionTitle(tr("Raccourcis"))
    DictoCard {
        SettingRow(tr("Tuile « Dernier vocal »"), tr("Dans le volet des réglages rapides : transcrit le vocal le plus récent, par-dessus l'app en cours.")) {
            TonalButton(tr("Ajouter"), { requestTile(context, VoiceNoteTileService::class.java, tr("Dernier vocal"), tr("Dernier vocal")) })
        }
        HorizontalDivider(color = Dicto.SurfaceSunken)
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
        if (auto && engine is app.dicto.data.EngineChoice.Cloud) {
            Text(
                tr("Avec un service en ligne comme moteur, l'audio de chaque vocal lui est envoyé."),
                style = MaterialTheme.typography.labelSmall,
                color = Dicto.InkMuted,
            )
        }
        HorizontalDivider(color = Dicto.SurfaceSunken)
        SettingRow(
            tr("Expéditeurs"),
            if (listener) tr("Notés depuis les notifications de WhatsApp, pour les vocaux reçus depuis l'activation.")
            else tr("Pour voir qui a envoyé chaque nouveau vocal (personne ou groupe), autorise Dicto à lire les notifications de WhatsApp."),
        ) {
            if (listener) Pill(tr("Actif"), Dicto.AccentContainer, Dicto.Accent) else TonalButton(tr("Autoriser"), { openListenerSettings(context) })
        }
        if (!listener && auto) ListenerWarning(context)
    }
}

/** Sélecteur du dossier des vocaux, ouvert sur celui de WhatsApp. */
@Composable
private fun rememberFolderPicker(context: Context): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { VoiceNotes.grant(context, uri) }
                .onFailure { Toast.makeText(context, tr("Accès au dossier impossible."), Toast.LENGTH_LONG).show() }
        }
    }
    return { launcher.launch(VoiceNotes.initialUri) }
}

// --- Onglet WhatsApp ------------------------------------------------------------------

/** Onglet « WhatsApp » : dernier vocal et historique des vocaux, avec expéditeur et texte. */
@Composable
fun VoiceNotesScreen(onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val settings = DictoApp.instance.settings
    val resumed = rememberResumes()
    val folder by settings.voiceNotesFolder.flow.collectAsStateWithLifecycle()
    val auto by settings.voiceNotesAuto.flow.collectAsStateWithLifecycle()
    val granted = remember(resumed, folder) { VoiceNotes.isGranted(context) }
    val texts by VoiceNotes.texts.collectAsStateWithLifecycle()
    val senders by VoiceNotes.senders.collectAsStateWithLifecycle()
    val items by FilesController.items.collectAsStateWithLifecycle()
    var notes by remember { mutableStateOf<List<VoiceNote>?>(null) }
    var open by rememberSaveable { mutableStateOf<Long?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var person by rememberSaveable { mutableStateOf<String?>(null) }

    // Rechargée à l'ouverture, au retour dans l'app, quand un vocal se termine ou arrive.
    val done = items.count { it.voiceNote != null && it.status == FileItem.Status.DONE }
    LaunchedEffect(granted, resumed, done, senders.size) {
        // Expéditeurs des vocaux encore dans le volet de notifications.
        if (granted) VoiceNoteListener.instance?.scanActive()
        notes = if (granted) VoiceNotes.recent(context, LIMIT) else null
    }

    val opened = items.firstOrNull { it.id == open && it.result != null }
    if (opened != null) {
        BackHandler { open = null }
        TranscriptView(opened, onBack = { open = null })
        return
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader(tr("WhatsApp"))
        Text(
            if (auto) tr("Tes vocaux, transcrits dès qu'ils arrivent") else tr("Tes messages vocaux, un toucher pour les transcrire"),
            style = MaterialTheme.typography.bodyMedium,
            color = Dicto.InkMuted,
            modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 4.dp),
        )
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!granted) {
                DictoCard {
                    Text(
                        tr("Autorise d'abord Dicto à lire le dossier des vocaux de WhatsApp."),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    PrimaryButton(tr("Configurer"), onOpenSettings)
                }
            } else {
                VoiceNotesList(
                    context, notes, texts, senders, items,
                    query = query, onQuery = { query = it },
                    person = person, onPerson = { person = it },
                    onOpen = { open = it },
                )
            }
        }
    }
}

@Composable
private fun VoiceNotesList(
    context: Context,
    notes: List<VoiceNote>?,
    texts: Map<String, String>,
    senders: Map<String, String>,
    items: List<FileItem>,
    query: String,
    onQuery: (String) -> Unit,
    person: String?,
    onPerson: (String?) -> Unit,
    onOpen: (Long) -> Unit,
) {
    PrimaryButton(
        tr("Dernier vocal"),
        { context.startActivity(ShareTranscribeActivity.latestIntent(context)) },
        Modifier.fillMaxWidth(),
        icon = DictoIcons.Mic,
    )
    // Filtre rapide : un mot-clé (nom ou texte) et les noms qui reviennent le plus.
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text(tr("Nom ou mot-clé")) },
        leadingIcon = { Icon(DictoIcons.Search, null, Modifier.size(18.dp)) },
        trailingIcon = if (query.isNotEmpty()) {
            { GhostButton(null, DictoIcons.Close, { onQuery("") }, contentDescription = tr("Effacer"), tint = Dicto.InkMuted) }
        } else {
            null
        },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = Dicto.Surface,
            focusedContainerColor = Dicto.Surface,
            unfocusedBorderColor = Dicto.Border,
        ),
    )
    val names = remember(notes, senders) { topNames(notes.orEmpty().mapNotNull { senders[it.docId] }, 8) }
    if (names.isNotEmpty()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (name in names) {
                ChipButton(onClick = { onPerson(if (person == name) null else name) }, accent = person == name) {
                    Text(name, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
            }
        }
    }
    val filtering = person != null || query.isNotBlank()
    val list = notes?.filter { matchesFilter(senders[it.docId], texts[it.docId], person, query) }
    when {
        list == null -> Text(tr("Lecture du dossier…"), style = MaterialTheme.typography.bodySmall, color = Dicto.InkMuted)
        list.isEmpty() && filtering -> Text(
            tr("Aucun vocal ne correspond."),
            style = MaterialTheme.typography.bodySmall,
            color = Dicto.InkMuted,
        )
        list.isEmpty() -> Text(
            tr("Aucun vocal dans ce dossier. Vérifie que c'est bien « WhatsApp Voice Notes » (WhatsApp › Media)."),
            style = MaterialTheme.typography.bodySmall,
            color = Dicto.InkMuted,
        )
        // Un bloc par jour : Aujourd'hui, Hier, puis la date.
        else -> list.groupBy { dayLabel(context, it.modifiedAt) }.forEach { (day, dayNotes) ->
            SectionTitle(day)
            DictoCard(padding = PaddingValues(0.dp)) {
                Column {
                    dayNotes.forEachIndexed { i, note ->
                        if (i > 0) HorizontalDivider(color = Dicto.SurfaceSunken, modifier = Modifier.padding(start = 68.dp))
                        VoiceNoteRow(
                            context,
                            note,
                            texts[note.docId],
                            senders[note.docId],
                            items.firstOrNull { it.voiceNote == note.docId },
                            onOpen,
                        )
                    }
                }
            }
        }
    }
}

/** Vocaux affichés dans l'onglet. */
private const val LIMIT = 50

@Composable
private fun VoiceNoteRow(context: Context, note: VoiceNote, text: String?, sender: String?, item: FileItem?, onOpen: (Long) -> Unit) {
    val title = sender?.let { tr("Vocal de %1\$s", it) }
    val transcribe = {
        FilesController.add(context, note.uri, VoiceNotes.WHATSAPP, voiceNote = note.docId, title = title, notify = false)
        Unit
    }
    val open = {
        val id = item?.takeIf { it.status == FileItem.Status.DONE }?.id
            ?: FilesController.addCached(note.name, text.orEmpty(), note.docId, title)
        onOpen(id)
    }
    val busy = item?.takeIf { it.active || it.status == FileItem.Status.QUEUED }
    val failed = item?.takeIf { it.status == FileItem.Status.FAILED }
    val time = DateUtils.formatDateTime(context, note.modifiedAt, DateUtils.FORMAT_SHOW_TIME)
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
            // Expéditeur connu : son nom et l'heure. Sinon l'heure seule.
            Text(
                listOfNotNull(sender, time).joinToString(" · "),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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

/** « Aujourd'hui », « Hier », sinon la date (« lun. 17 nov. »). */
private fun dayLabel(context: Context, ms: Long): String = when {
    DateUtils.isToday(ms) -> tr("Aujourd'hui")
    DateUtils.isToday(ms + DateUtils.DAY_IN_MILLIS) -> tr("Hier")
    else -> DateUtils.formatDateTime(context, ms, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_ALL)
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
