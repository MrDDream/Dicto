package app.dicto.ime

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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dicto.DictoApp
import app.dicto.dictation.EngineStatus
import app.dicto.ui.theme.Dicto
import app.dicto.ui.theme.DictoIcons
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uniffi.dicto_ffi.Mode
import uniffi.dicto_ffi.State

private val MODE_LABELS = mapOf(
    Mode.PUSH_TO_TALK to "Push-to-talk",
    Mode.CONTINUOUS to "Continu",
    Mode.HOLD to "Maintenu",
)

@Composable
fun KeyboardPanel(
    hasMicPermission: Boolean,
    onPress: () -> Unit,
    onRelease: () -> Unit,
    onBackspace: () -> Unit,
    onEnter: () -> Unit,
    onSpace: () -> Unit,
    onSwitchKeyboard: () -> Unit,
    onOpenApp: () -> Unit,
) {
    val controller = DictoApp.instance.dictation
    val ui by controller.ui.collectAsStateWithLifecycle()
    val bars = remember { mutableStateListOf<Float>().apply { repeat(BARS) { add(0f) } } }
    LaunchedEffect(ui.isActive) {
        while (ui.isActive) {
            bars.removeAt(0)
            bars.add(controller.level.value)
            delay(80)
        }
        for (i in bars.indices) bars[i] = 0f
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(Dicto.OverlayBackground)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (text, active) = when {
                !hasMicPermission -> "Micro non autorisé" to false
                ui.isActive -> (if (ui.state == State.SPEAKING || ui.mode != Mode.CONTINUOUS) "Je t'écoute" else "En écoute") to true
                ui.pending > 0 -> "Transcription…" to false
                ui.engine.kind == EngineStatus.Kind.LOADING -> "Chargement du modèle…" to false
                !ui.engine.isReady -> "Aucun modèle choisi" to false
                else -> ui.engine.label to false
            }
            Row(
                Modifier.height(26.dp).clip(RoundedCornerShape(13.dp))
                    .background(if (active) Dicto.OverlayRecordingContainer else Dicto.OverlayLine)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (active) Box(Modifier.size(7.dp).clip(CircleShape).background(Dicto.RecordingWave))
                Text(
                    text,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (active) Dicto.OverlayRecordingInk else Dicto.OverlayInk,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.weight(1f))
            Surface(
                onClick = {
                    val modes = Mode.entries
                    controller.setMode(modes[(modes.indexOf(ui.mode) + 1) % modes.size])
                },
                enabled = !ui.isActive,
                color = androidx.compose.ui.graphics.Color.Transparent,
                contentColor = Dicto.OverlayMuted,
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    MODE_LABELS.getValue(ui.mode) + " ▾",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        .semantics { contentDescription = "Mode : ${MODE_LABELS.getValue(ui.mode)}, toucher pour changer" },
                )
            }
        }

        Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)) {
            for (b in bars) {
                val h = (4 + b * 24).dp
                Box(Modifier.width(4.dp).height(h).clip(RoundedCornerShape(2.dp)).background(if (b > 0.05f) Dicto.RecordingWave else Dicto.OverlayLine))
            }
        }

        if (ui.partial.isNotBlank()) {
            Text(ui.partial, style = MaterialTheme.typography.bodyMedium, color = Dicto.OverlayMuted, maxLines = 2)
        }

        if (!hasMicPermission) {
            Surface(onClick = onOpenApp, color = Dicto.OverlayInk, contentColor = Dicto.Ink, shape = RoundedCornerShape(23.dp), modifier = Modifier.fillMaxWidth().height(46.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Text("Ouvrir Dicto pour autoriser le micro", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                }
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            RoundKey(DictoIcons.Keyboard, "Changer de clavier", onSwitchKeyboard)
            WideKey("espace", onSpace)
            MicKey(ui.isActive, ui.mode, onPress, onRelease)
            RepeatKey(DictoIcons.Backspace, "Effacer", onBackspace)
            RoundKey(DictoIcons.Enter, "Entrée", onEnter)
        }
    }
}

@Composable
private fun MicKey(active: Boolean, mode: Mode, onPress: () -> Unit, onRelease: () -> Unit) {
    val label = when {
        mode == Mode.HOLD -> if (active) "Relâche pour transcrire" else "Maintiens pour parler"
        active -> "Arrêter la dictée"
        else -> "Dicter"
    }
    Box(
        Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(if (active) Dicto.Recording else Dicto.Accent)
            .border(5.dp, if (active) Dicto.OverlayRecordingContainer else Dicto.OverlayLine, CircleShape)
            .semantics {
                role = Role.Button
                contentDescription = label
                onClick { onPress(); onRelease(); true }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    onPress()
                    waitForUpOrCancellation()
                    onRelease()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (active && mode != Mode.HOLD) DictoIcons.Stop else DictoIcons.MicBold, null, tint = Dicto.OnAccent, modifier = Modifier.size(30.dp))
    }
}

@Composable
private fun RoundKey(icon: ImageVector, description: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(48.dp),
        shape = CircleShape,
        color = androidx.compose.ui.graphics.Color.Transparent,
        contentColor = Dicto.OverlayInk,
        border = androidx.compose.foundation.BorderStroke(1.dp, Dicto.OverlayLine),
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, description, Modifier.size(20.dp)) }
    }
}

@Composable
private fun WideKey(text: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.width(72.dp).height(48.dp),
        shape = RoundedCornerShape(24.dp),
        color = androidx.compose.ui.graphics.Color.Transparent,
        contentColor = Dicto.OverlayMuted,
        border = androidx.compose.foundation.BorderStroke(1.dp, Dicto.OverlayLine),
    ) {
        Box(contentAlignment = Alignment.Center) { Text(text, style = MaterialTheme.typography.labelMedium) }
    }
}

/** Touche qui se répète tant qu'on la maintient (effacement). */
@Composable
private fun RepeatKey(icon: ImageVector, description: String, onAction: () -> Unit) {
    val scope = rememberCoroutineScope()
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .border(1.dp, Dicto.OverlayLine, CircleShape)
            .semantics {
                role = Role.Button
                contentDescription = description
                onClick { onAction(); true }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    onAction()
                    var job: Job? = scope.launch {
                        delay(400)
                        while (true) {
                            onAction()
                            delay(60)
                        }
                    }
                    waitForUpOrCancellation()
                    job?.cancel()
                    job = null
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = Dicto.OverlayInk, modifier = Modifier.size(20.dp))
    }
}

private const val BARS = 24
