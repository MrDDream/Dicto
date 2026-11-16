package app.dicto.triggers

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
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
import app.dicto.dictation.DictationUi
import app.dicto.ui.theme.Dicto
import app.dicto.ui.theme.DictoIcons
import kotlinx.coroutines.delay
import uniffi.dicto_ffi.Mode
import uniffi.dicto_ffi.State

private val MODE_NAMES = mapOf(Mode.PUSH_TO_TALK to "Push-to-talk", Mode.CONTINUOUS to "Continu", Mode.HOLD to "Maintenu")

/**
 * Panneau sombre « Je t'écoute » (écran « Bulle flottante en usage » du canvas) :
 * statut, onde, texte transcrit, annuler / insérer.
 */
@Composable
fun DictationPanel(
    text: String,
    onCancel: () -> Unit,
    onInsert: () -> Unit,
    onMicPress: () -> Unit,
    onMicRelease: () -> Unit,
    modifier: Modifier = Modifier,
    insertLabel: String = "Insérer le texte",
) {
    val controller = DictoApp.instance.dictation
    val ui by controller.ui.collectAsStateWithLifecycle()
    val bars = remember { mutableStateListOf<Float>().apply { repeat(20) { add(0f) } } }
    LaunchedEffect(ui.isActive) {
        while (ui.isActive) {
            bars.removeAt(0)
            bars.add(controller.level.value)
            delay(80)
        }
        for (i in bars.indices) bars[i] = 0f
    }

    Column(
        modifier
            .fillMaxWidth()
            .shadow(18.dp, RoundedCornerShape(26.dp))
            .clip(RoundedCornerShape(26.dp))
            .background(Dicto.OverlayBackground)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusPill(ui)
            Spacer(Modifier.weight(1f))
            Text(MODE_NAMES.getValue(ui.mode), style = MaterialTheme.typography.labelSmall, color = Dicto.OverlayMuted)
        }
        Row(Modifier.height(26.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (b in bars) {
                Box(
                    Modifier.width(4.dp).height((4 + b * 22).dp).clip(RoundedCornerShape(2.dp))
                        .background(if (b > 0.05f) Dicto.RecordingWave else Dicto.OverlayLine),
                )
            }
        }
        if (text.isNotBlank() || ui.pending > 0 || ui.partial.isNotBlank()) {
            Column(Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState())) {
                if (text.isNotBlank()) Text(text, style = MaterialTheme.typography.bodyLarge, color = Dicto.OverlayInk)
                when {
                    ui.partial.isNotBlank() -> Text(ui.partial, style = MaterialTheme.typography.bodyLarge, color = Dicto.OverlayMuted)
                    ui.pending > 0 -> Text("…", style = MaterialTheme.typography.bodyLarge, color = Dicto.OverlayMuted)
                }
            }
        } else if (!ui.engine.isReady) {
            Text(
                ui.message ?: "Choisis un modèle dans l'onglet Modèles de Dicto.",
                style = MaterialTheme.typography.bodyMedium,
                color = Dicto.OverlayRecordingInk,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                onClick = onCancel,
                modifier = Modifier.size(46.dp),
                shape = CircleShape,
                color = Color.Transparent,
                contentColor = Dicto.OverlayInk,
                border = androidx.compose.foundation.BorderStroke(1.dp, Dicto.OverlayLine),
            ) { Box(contentAlignment = Alignment.Center) { Icon(DictoIcons.Close, "Annuler la dictée", Modifier.size(18.dp)) } }
            PanelMic(ui, onMicPress, onMicRelease)
            Surface(
                onClick = onInsert,
                enabled = text.isNotBlank(),
                modifier = Modifier.weight(1f).height(46.dp),
                shape = RoundedCornerShape(23.dp),
                color = if (text.isNotBlank()) Dicto.OverlayInk else Dicto.OverlayLine,
                contentColor = Dicto.Ink,
            ) {
                Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(DictoIcons.Check, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(insertLabel, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun StatusPill(ui: DictationUi) {
    val active = ui.isActive
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(active) {
        while (active) {
            now = android.os.SystemClock.elapsedRealtime()
            delay(250)
        }
    }
    val label = when {
        active && ui.state == State.LISTENING -> "En écoute"
        active -> {
            val s = ((now - ui.startedAt) / 1000).coerceAtLeast(0)
            "Je t'écoute · %02d:%02d".format(s / 60, s % 60)
        }
        ui.pending > 0 -> "Transcription…"
        else -> "Prêt"
    }
    Row(
        Modifier.height(26.dp).clip(RoundedCornerShape(13.dp))
            .background(if (active) Dicto.OverlayRecordingContainer else Dicto.OverlayLine)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (active) Box(Modifier.size(7.dp).clip(CircleShape).background(Dicto.RecordingWave))
        Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = if (active) Dicto.OverlayRecordingInk else Dicto.OverlayInk)
    }
}

@Composable
private fun PanelMic(ui: DictationUi, onPress: () -> Unit, onRelease: () -> Unit) {
    val active = ui.isActive
    Box(
        Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(if (active) Dicto.Recording else Dicto.Accent)
            .semantics {
                role = Role.Button
                contentDescription = if (active) "Arrêter" else "Dicter"
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
        Icon(if (active && ui.mode != Mode.HOLD) DictoIcons.Stop else DictoIcons.MicBold, null, tint = Dicto.OnAccent, modifier = Modifier.size(20.dp))
    }
}
