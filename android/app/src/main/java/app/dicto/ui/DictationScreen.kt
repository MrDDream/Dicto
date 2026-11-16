package app.dicto.ui

import app.dicto.i18n.tr
import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.Layout
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dicto.DictoApp
import app.dicto.data.label
import app.dicto.data.secretKeyFor
import app.dicto.data.EngineChoice
import app.dicto.data.LANGUAGES
import app.dicto.dictation.DictationUi
import app.dicto.dictation.EngineStatus
import app.dicto.ui.theme.Dicto
import app.dicto.ui.theme.DictoIcons
import app.dicto.dictation.userMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uniffi.dicto_ffi.Mode
import uniffi.dicto_ffi.State

private data class ModeInfo(val mode: Mode, val label: String, val hint: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val MODES = listOf(
    ModeInfo(Mode.PUSH_TO_TALK, "Marche/arrêt", "Un appui pour commencer, un second pour terminer.", DictoIcons.PushToTalk),
    ModeInfo(Mode.CONTINUOUS, "Continu", "J’écoute en continu et transcris chaque phrase dès que tu fais une pause.", DictoIcons.Continuous),
    ModeInfo(Mode.HOLD, "Maintenu", "Garde le doigt appuyé pendant que tu parles, relâche pour transcrire.", DictoIcons.Hold),
)

@Composable
fun DictationScreen(onOpenSettings: () -> Unit, onOpenModels: () -> Unit, onOpenFiles: () -> Unit) {
    val app = DictoApp.instance
    val controller = app.dictation
    val ui by controller.ui.collectAsStateWithLifecycle()
    val language by app.settings.language.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val askPermissions = {
        micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 && !granted(context, Manifest.permission.POST_NOTIFICATIONS)) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // La carte de transcription prend la place restante, sans descendre sous une hauteur lisible :
    // sur un petit écran ou avec un grand texte, l'écran défile au lieu d'écraser la carte.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewport = constraints.maxHeight
        val minCard = with(LocalDensity.current) { 220.dp.roundToPx() }
        Layout(
            contents = listOf(
                {
                    Column(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 22.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LogoMark()
                            Spacer(Modifier.width(10.dp))
                            Text("Dicto", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                            // Transcrire un fichier audio : une action à part, pas un réglage de la dictée.
                            ChipButton(onClick = onOpenFiles, accent = true) {
                                Icon(DictoIcons.Share, null, Modifier.size(15.dp))
                                Text(tr("Fichier audio"), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                            }
                            Spacer(Modifier.width(8.dp))
                            IconButton(
                                onClick = onOpenSettings,
                                modifier = Modifier.size(44.dp),
                                colors = IconButtonDefaults.iconButtonColors(containerColor = Dicto.SurfaceSunken, contentColor = Dicto.Ink),
                            ) { Icon(DictoIcons.Settings, contentDescription = tr("Réglages"), modifier = Modifier.size(20.dp)) }
                    }

                    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp)) {
                        Text(tr("Bonjour"), style = MaterialTheme.typography.displaySmall)
                        Text(tr("Que veux-tu dicter aujourd'hui ?"), style = MaterialTheme.typography.bodyMedium, color = Dicto.InkMuted, modifier = Modifier.padding(top = 6.dp))
                    }

                    // Moteur (s'abrège si le nom est long) et langue, toujours sur une ligne.
                    Row(
                        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        EngineSwitcher(ui.engine, onOpenModels, Modifier.weight(1f, fill = false))
                        LanguageChip(language) { app.settings.setLanguage(it) }
                        TranslateChip()
                    }

                    ModeSelector(ui.mode, enabled = !ui.isActive) { controller.setMode(it) }
                    Text(
                        tr(MODES.first { it.mode == ui.mode }.hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = Dicto.InkMuted,
                        modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 10.dp),
                    )

                    if (ui.message != null) {
                        MessageBanner(ui.message!!, onDismiss = controller::dismissMessage)
                    }
                    }
                },
                { TranscriptCard(ui, Modifier.fillMaxSize(), context) },
                {
                    MicButton(
                        ui = ui,
                        onPress = {
                            if (!granted(context, Manifest.permission.RECORD_AUDIO)) askPermissions() else controller.press()
                        },
                        onRelease = controller::release,
                    )
                },
            ),
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        ) { (topM, cardM, micM), c ->
            val loose = Constraints(maxWidth = c.maxWidth)
            val tops = topM.map { it.measure(loose) }
            val mics = micM.map { it.measure(loose) }
            val topH = tops.sumOf { it.height }
            val micH = mics.sumOf { it.height }
            val cardH = maxOf(minCard, viewport - topH - micH)
            val cards = cardM.map { it.measure(Constraints.fixed(c.maxWidth, cardH)) }
            layout(c.maxWidth, topH + cardH + micH) {
                var y = 0
                for (pl in tops + cards + mics) {
                    pl.place(0, y)
                    y += pl.height
                }
            }
        }
    }
}

@Composable
private fun EngineChip(status: EngineStatus, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ChipButton(onClick = onClick, modifier = modifier) {
        val dot = when (status.kind) {
            EngineStatus.Kind.READY -> Dicto.Success
            EngineStatus.Kind.LOADING -> Dicto.Brand
            EngineStatus.Kind.ERROR -> Dicto.Recording
            EngineStatus.Kind.NONE -> Dicto.InkDisabled
        }
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Text(
            if (status.kind == EngineStatus.Kind.NONE) tr("Choisir un modèle") else status.label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

/**
 * Puce du moteur : un toucher ouvre la liste des modèles installés et des services en ligne
 * configurés, pour changer sans quitter l'écran ; « Gérer les modèles » mène à l'onglet Modèles.
 */
@Composable
private fun EngineSwitcher(status: EngineStatus, onOpenModels: () -> Unit, modifier: Modifier = Modifier) {
    val app = DictoApp.instance
    val current by app.settings.engine.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    // Recalculée à chaque ouverture : un modèle a pu être installé ou une clé ajoutée entre-temps.
    val choices = remember(open) {
        if (!open) emptyList()
        else {
            val local = app.models.models().filter { it.installed }.map { EngineChoice.Local(it.id) to tr(it.name) }
            val cloud = uniffi.dicto_ffi.CloudProvider.entries
                .filter { p ->
                    if (p == uniffi.dicto_ffi.CloudProvider.CUSTOM) app.settings.cloud.value[p]?.baseUrl != null
                    else app.secrets.has(secretKeyFor(p))
                }
                .map { p -> EngineChoice.Cloud(p) to tr("%1\$s · en ligne", p.label) }
            local + cloud
        }
    }
    Box(modifier) {
        EngineChip(status, onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (choices.isEmpty()) {
                DropdownMenuItem(
                    text = { Text(tr("Aucun modèle installé ni service configuré"), color = Dicto.InkMuted) },
                    onClick = { open = false; onOpenModels() },
                )
            }
            for ((choice, name) in choices) {
                val selected = choice == current
                DropdownMenuItem(
                    text = { Text(name, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) },
                    trailingIcon = if (selected) ({ Icon(DictoIcons.Check, null, tint = Dicto.Accent, modifier = Modifier.size(18.dp)) }) else null,
                    onClick = {
                        open = false
                        if (!selected) app.settings.setEngine(choice)
                    },
                )
            }
            HorizontalDivider(color = Dicto.SurfaceSunken)
            DropdownMenuItem(
                text = { Text(tr("Gérer les modèles…"), color = Dicto.Accent, fontWeight = FontWeight.Bold) },
                onClick = { open = false; onOpenModels() },
            )
        }
    }
}

/** Traduction à la volée : « → EN » allumé quand elle est active, un toucher la coupe ou la remet. */
@Composable
private fun TranslateChip() {
    val app = DictoApp.instance
    val provider by app.settings.refineProvider.collectAsStateWithLifecycle()
    val target by app.settings.translateTo.flow.collectAsStateWithLifecycle()
    val last by app.settings.lastTranslateTo.flow.collectAsStateWithLifecycle()
    if (provider == null) return
    val active = target != "none"
    val code = if (active) target else last
    ChipButton(
        onClick = {
            app.settings.translateTo.set(if (active) "none" else code)
            app.dictation.applyOptions()
        },
        accent = active,
    ) {
        Text(
            "→ " + code.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            softWrap = false,
            color = if (active) Dicto.Accent else Dicto.InkMuted,
            modifier = Modifier.semantics {
                contentDescription = if (active) tr("Traduction en %1\$s activée", translationLanguageName(code)) else tr("Traduire en %1\$s", translationLanguageName(code))
            },
        )
    }
}

@Composable
private fun LanguageChip(code: String?, onSelect: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ChipButton(onClick = { open = true }) {
            Text(
                code?.uppercase() ?: tr("Auto"),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.semantics { contentDescription = tr("Langue : %1\$s", tr(LANGUAGES.first { it.first == code }.second)) },
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((c, name) in LANGUAGES) {
                DropdownMenuItem(text = { Text(tr(name)) }, onClick = { onSelect(c); open = false })
            }
        }
    }
}

@Composable
private fun ModeSelector(current: Mode, enabled: Boolean, onSelect: (Mode) -> Unit) {
    Row(
        Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp).fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (m in MODES) {
            val selected = m.mode == current
            Column(
                Modifier
                    .weight(1f)
                    .height(72.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (selected) Dicto.Accent else Dicto.Surface)
                    .border(1.dp, if (selected) Dicto.Accent else Dicto.Border, RoundedCornerShape(18.dp))
                    .selectable(selected = selected, enabled = enabled, role = Role.RadioButton) { onSelect(m.mode) }
                    .padding(12.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                val color = if (selected) Dicto.OnAccent else Dicto.Ink
                Icon(m.icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
                Text(tr(m.label), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
            }
        }
    }
}

@Composable
private fun MessageBanner(text: String, onDismiss: () -> Unit) {
    Row(
        Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp).fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)).background(Dicto.RecordingContainer).padding(start = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = Dicto.OnRecordingContainer, modifier = Modifier.weight(1f).padding(vertical = 10.dp))
        GhostButton(null, DictoIcons.Close, onDismiss, contentDescription = tr("Fermer"), tint = Dicto.OnRecordingContainer)
    }
}

@Composable
private fun TranscriptCard(ui: DictationUi, modifier: Modifier, context: Context) {
    val controller = DictoApp.instance.dictation
    Column(
        modifier
            .padding(start = 20.dp, end = 20.dp, top = 16.dp)
            .fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(24.dp), ambientColor = Dicto.Ink.copy(alpha = 0.12f), spotColor = Dicto.Ink.copy(alpha = 0.12f))
            .clip(RoundedCornerShape(24.dp))
            .background(Dicto.Surface)
            .padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Transcription"), style = MaterialTheme.typography.labelMedium, color = Dicto.InkMuted, modifier = Modifier.weight(1f))
            if (ui.isActive) {
                Pill(tr("Je t'écoute · %1\$s", elapsed(ui.startedAt)), Dicto.RecordingContainer, Dicto.OnRecordingContainer, dot = Dicto.Recording)
            } else if (ui.pending > 0) {
                Pill(tr("Transcription…"), Dicto.AccentContainer, Dicto.Accent)
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (ui.chunks.isEmpty() && ui.pending == 0 && ui.partial.isBlank()) {
                Text(
                    tr("Le texte dicté apparaîtra ici."),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Dicto.InkDisabled,
                )
            } else {
                if (ui.transcript.isNotBlank()) Text(ui.transcript, style = MaterialTheme.typography.bodyLarge)
                when {
                    ui.partial.isNotBlank() -> Text(ui.partial, style = MaterialTheme.typography.bodyLarge, color = Dicto.InkDisabled)
                    ui.pending > 0 -> Text("…", style = MaterialTheme.typography.bodyLarge, color = Dicto.InkDisabled)
                }
            }
        }
        HorizontalDivider(color = Dicto.SurfaceSunken)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val text = ui.transcript
            GhostButton(tr("Copier"), DictoIcons.Copy, onClick = { copy(context, text) })
            GhostButton(tr("Partager"), DictoIcons.Share, onClick = { share(context, text) })
            RefineButton(enabled = text.isNotBlank())
            Spacer(Modifier.weight(1f))
            GhostButton(null, DictoIcons.Trash, onClick = controller::clearTranscript, contentDescription = tr("Effacer"), tint = Dicto.InkMuted)
        }
    }
}

/** « Reformuler » : applique un préréglage de retouche à tout le texte. */
@Composable
private fun RefineButton(enabled: Boolean) {
    val app = DictoApp.instance
    val provider by app.settings.refineProvider.collectAsStateWithLifecycle()
    if (provider == null) return
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    Box {
        GhostButton(if (busy) "…" else tr("Reformuler"), DictoIcons.Wave, onClick = { if (enabled && !busy) open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (p in uniffi.dicto_ffi.refinePresets()) {
                DropdownMenuItem(text = { Text(tr(p.name)) }, onClick = {
                    open = false
                    busy = true
                    scope.launch {
                        app.dictation.refineTranscript(p.id).onFailure {
                            android.widget.Toast.makeText(app, tr("Retouche impossible : %1\$s", it.userMessage()), android.widget.Toast.LENGTH_LONG).show()
                        }
                        busy = false
                    }
                })
            }
        }
    }
}

@Composable
private fun MicButton(ui: DictationUi, onPress: () -> Unit, onRelease: () -> Unit) {
    val level by DictoApp.instance.dictation.level.collectAsStateWithLifecycle()
    val recording = ui.isActive
    val label = when (ui.mode) {
        Mode.PUSH_TO_TALK -> if (recording) tr("Appuie pour terminer") else tr("Appuie pour parler")
        Mode.CONTINUOUS -> when (ui.state) {
            State.SPEAKING -> tr("Je transcris dès que tu fais une pause")
            State.LISTENING -> tr("Écoute continue · appuie pour couper")
            else -> tr("Démarrer l’écoute continue")
        }
        Mode.HOLD -> if (recording) tr("Relâche pour transcrire") else tr("Maintiens pour parler")
    }
    Column(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val ring = if (recording) Dicto.RecordingContainer else Dicto.AccentContainer
        val ringSize = 132.dp + if (recording) (level * 16).dp else 0.dp
        Box(
            Modifier
                .size(148.dp)
                .semantics {
                    role = Role.Button
                    contentDescription = label
                    onClick { onPress(); onRelease(); true }
                }
                .pointerInput(Unit) {
                    // Appui et relâchement séparés : le cœur gère bascule (push-to-talk, continu) et maintien.
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        onPress()
                        waitForUpOrCancellation()
                        onRelease()
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(ringSize).clip(CircleShape).background(ring.copy(alpha = 0.5f)))
            Box(Modifier.size(120.dp).clip(CircleShape).background(ring))
            Box(
                Modifier.size(92.dp).shadow(10.dp, CircleShape).clip(CircleShape)
                    .background(if (recording) Dicto.Recording else Dicto.Accent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (recording && ui.mode != Mode.HOLD) DictoIcons.Stop else DictoIcons.MicBold,
                    contentDescription = null,
                    tint = if (recording) Dicto.OnRecording else Dicto.OnAccent,
                    modifier = Modifier.size(36.dp),
                )
            }
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = Dicto.InkSecondary)
    }
}

@Composable
private fun elapsed(startedAt: Long): String {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(startedAt) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(250)
        }
    }
    val s = ((now - startedAt) / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(s / 60, s % 60)
}

private fun granted(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

fun copy(context: Context, text: String) {
    if (text.isBlank()) return
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Dicto", text))
}

fun share(context: Context, text: String) {
    if (text.isBlank()) return
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
