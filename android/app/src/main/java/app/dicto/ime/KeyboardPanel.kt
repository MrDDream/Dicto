package app.dicto.ime

import app.dicto.i18n.tr
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
import androidx.compose.ui.graphics.Color
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
    Mode.PUSH_TO_TALK to "Marche/arrêt",
    Mode.CONTINUOUS to "Continu",
    Mode.HOLD to "Maintenu",
)

/** Couleurs du clavier, claires ou sombres selon le thème du téléphone. */
private class KeyboardColors(
    val background: Color,
    val key: Color,
    val ink: Color,
    val muted: Color,
    val line: Color,
    val recContainer: Color,
    val recInk: Color,
    val strong: Color,
    val onStrong: Color,
)

private val DARK = KeyboardColors(
    background = Dicto.OverlayBackground,
    key = Dicto.OverlayLine,
    ink = Dicto.OverlayInk,
    muted = Dicto.OverlayMuted,
    line = Dicto.OverlayLine,
    recContainer = Dicto.OverlayRecordingContainer,
    recInk = Dicto.OverlayRecordingInk,
    strong = Dicto.OverlayInk,
    onStrong = Dicto.Ink,
)

private val LIGHT = KeyboardColors(
    background = Dicto.SurfaceSunken,
    key = Dicto.Surface,
    ink = Dicto.Ink,
    muted = Dicto.InkMuted,
    line = Dicto.Border,
    recContainer = Dicto.RecordingContainer,
    recInk = Dicto.OnRecordingContainer,
    strong = Dicto.Accent,
    onStrong = Dicto.OnAccent,
)

private val K: KeyboardColors
    @Composable get() = if (app.dicto.ui.theme.dictoIsDark()) DARK else LIGHT

/** Le clavier est-il sombre (barre de navigation assortie). */
fun keyboardIsDark(context: android.content.Context): Boolean = when (app.dicto.ui.theme.ThemeMode.mode) {
    "dark" -> true
    "light" -> false
    else -> (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES
}

/** Fond du clavier, pour colorer la barre de navigation dessous. */
fun keyboardBackground(dark: Boolean): Color = if (dark) DARK.background else LIGHT.background

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
            .background(K.background)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (text, active) = when {
                !hasMicPermission -> tr("Micro non autorisé") to false
                ui.isActive -> (if (ui.state == State.SPEAKING || ui.mode != Mode.CONTINUOUS) tr("Je t'écoute") else tr("En écoute")) to true
                ui.pending > 0 -> tr("Transcription…") to false
                ui.engine.kind == EngineStatus.Kind.LOADING -> tr("Chargement du modèle…") to false
                !ui.engine.isReady -> tr("Aucun modèle choisi") to false
                else -> ui.engine.label to false
            }
            Row(
                Modifier.height(26.dp).clip(RoundedCornerShape(13.dp))
                    .background(if (active) K.recContainer else K.line)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (active) Box(Modifier.size(7.dp).clip(CircleShape).background(Dicto.RecordingWave))
                Text(
                    text,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (active) K.recInk else K.ink,
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
                contentColor = K.muted,
                shape = RoundedCornerShape(13.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, K.line),
                modifier = Modifier.height(26.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        tr(MODE_LABELS.getValue(ui.mode)) + "  ▾",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 10.dp)
                            .semantics { contentDescription = tr("Mode : %1\$s, toucher pour changer", tr(MODE_LABELS.getValue(ui.mode))) },
                    )
                }
            }
        }

        // Zone de hauteur fixe : la phrase en direct, sinon l'onde ou une consigne. Le clavier ne saute pas.
        Box(Modifier.fillMaxWidth().height(52.dp), contentAlignment = Alignment.Center) {
            when {
                !hasMicPermission -> Surface(
                    onClick = onOpenApp, color = K.strong, contentColor = K.onStrong,
                    shape = RoundedCornerShape(23.dp), modifier = Modifier.fillMaxWidth().height(46.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(tr("Ouvrir Dicto pour autoriser le micro"), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    }
                }
                ui.partial.isNotBlank() -> Text(
                    ui.partial,
                    style = MaterialTheme.typography.bodyLarge,
                    color = K.ink,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
                ui.isActive -> Row(
                    Modifier.fillMaxWidth().height(28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                ) {
                    for (b in bars) {
                        Box(
                            Modifier.width(4.dp).height((4 + b * 24).dp).clip(RoundedCornerShape(2.dp))
                                .background(if (b > 0.05f) Dicto.RecordingWave else K.line),
                        )
                    }
                }
                else -> Text(
                    if (ui.pending > 0) tr("Le texte arrive…") else tr("Touche le micro et parle : le texte s'écrit tout seul."),
                    style = MaterialTheme.typography.bodyMedium,
                    color = K.muted,
                )
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Key(Modifier.width(52.dp), tr("Changer de clavier"), onSwitchKeyboard) {
                    Icon(DictoIcons.Keyboard, null, Modifier.size(22.dp))
                }
                Key(Modifier.weight(1f), tr("Espace"), onSpace) {
                    Box(Modifier.width(28.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(K.muted))
                }
            }
            MicKey(ui.isActive, ui.mode, onPress, onRelease)
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RepeatKey(Modifier.weight(1f), DictoIcons.Backspace, tr("Effacer"), onBackspace)
                Key(Modifier.weight(1f), tr("Entrée"), onEnter) {
                    Icon(DictoIcons.Enter, null, Modifier.size(22.dp))
                }
            }
        }
    }
}

private val KEY_SHAPE = RoundedCornerShape(14.dp)
private val KEY_HEIGHT = 52.dp

/** Touche pleine, comme sur un clavier. */
@Composable
private fun Key(modifier: Modifier, description: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(KEY_HEIGHT).semantics { contentDescription = description },
        shape = KEY_SHAPE,
        color = K.key,
        contentColor = K.ink,
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
private fun MicKey(active: Boolean, mode: Mode, onPress: () -> Unit, onRelease: () -> Unit) {
    val label = when {
        mode == Mode.HOLD -> if (active) tr("Relâche pour transcrire") else tr("Maintiens pour parler")
        active -> tr("Arrêter la dictée")
        else -> tr("Dicter")
    }
    Box(
        Modifier
            .size(68.dp)
            .clip(CircleShape)
            .background(if (active) Dicto.Recording else Dicto.Accent)
            .border(5.dp, if (active) K.recContainer else K.line, CircleShape)
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

/** Touche qui se répète tant qu'on la maintient (effacement). */
@Composable
private fun RepeatKey(modifier: Modifier, icon: ImageVector, description: String, onAction: () -> Unit) {
    val scope = rememberCoroutineScope()
    Box(
        modifier
            .height(KEY_HEIGHT)
            .clip(KEY_SHAPE)
            .background(K.key)
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
        Icon(icon, null, tint = K.ink, modifier = Modifier.size(22.dp))
    }
}

private const val BARS = 24
