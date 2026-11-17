package app.dicto.ui

import app.dicto.i18n.tr
import android.Manifest
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dicto.DictoApp
import app.dicto.R
import app.dicto.ime.KeyboardLink
import app.dicto.triggers.BubbleService
import app.dicto.triggers.DictoTileService
import app.dicto.triggers.WakeWordService
import app.dicto.ui.theme.Dicto
import app.dicto.ui.theme.DictoIcons
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

@Composable
fun TriggersScreen() {
    val context = LocalContext.current
    val settings = DictoApp.instance.settings
    // Recalculé au retour des réglages Android (permissions, clavier).
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumed++
        onPauseOrDispose { }
    }
    val imeEnabled = remember(resumed) { KeyboardLink.isEnabled(context) }
    val imeCurrent = remember(resumed) { KeyboardLink.isCurrent(context) }
    val overlay = remember(resumed) { Settings.canDrawOverlays(context) }
    val bubble by settings.bubble.flow.collectAsStateWithLifecycle()
    val wake by settings.wakeWord.flow.collectAsStateWithLifecycle()
    val phrase by settings.wakePhrase.flow.collectAsStateWithLifecycle()
    val edge by settings.edgeSwipe.flow.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader(tr("Déclencheurs"))
        Text(
            tr("Dicte depuis n'importe quelle app, sans ouvrir Dicto. Le texte arrive dans le champ où tu écris."),
            style = MaterialTheme.typography.bodyMedium,
            color = Dicto.InkMuted,
            modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 6.dp, bottom = 16.dp),
        )
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Clavier Dicto : la voie qui écrit dans les autres apps.
            DictoCard {
                TriggerHeader(
                    DictoIcons.Keyboard, Dicto.Accent, Dicto.OnAccent, tr("Clavier Dicto"),
                    when {
                        imeCurrent -> tr("En cours : le texte s'écrit directement")
                        imeEnabled -> tr("Activé")
                        else -> tr("Pour écrire le texte dans tes apps")
                    },
                    info = if (imeEnabled) {
                        tr("Pour passer à Dicto en une touche depuis Gboard : dans ses paramètres, Préférences, active « Afficher la touche de changement de langue ». Le globe 🌐 bascule alors sur le clavier Dicto, qui écoute aussitôt ; touche ■ quand tu as fini, le texte s'écrit et Gboard revient. Pour rester sur Dicto, coupe « Revenir à mon clavier » dans Réglages. La bulle et « OK Dicto » écrivent aussi par lui.")
                    } else {
                        tr("Active « Clavier Dicto » dans la liste des claviers d'Android. Sans lui, le texte dicté depuis la bulle est copié, à coller toi-même.")
                    },
                ) {
                    when {
                        imeCurrent -> Pill(tr("En cours"), Dicto.AccentContainer, Dicto.Accent)
                        imeEnabled -> TonalButton(tr("Choisir"), { context.getSystemService(InputMethodManager::class.java).showInputMethodPicker() })
                        else -> TonalButton(tr("Activer"), {
                            context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        })
                    }
                }
            }

            // Bouton flottant
            DictoCard {
                TriggerHeader(
                    DictoIcons.Mic, Dicto.Accent, Dicto.OnAccent, tr("Bouton flottant"), tr("Une bulle au bord de l'écran"),
                    info = tr("Toucher la bulle lance la dictée dans le mode choisi sur l'écran Dictée. Un appui long écoute tant que ton doigt reste posé. Glisse-la pour la déplacer, ou jusqu'à la croix en bas à droite pour la masquer ; la tuile Réglages rapides la fait revenir."),
                ) {
                    DictoSwitch(bubble && overlay, { on ->
                        if (on && !overlay) requestOverlay(context)
                        settings.bubble.set(on)
                        BubbleService.refresh(context)
                    }, tr("Bouton flottant"))
                }
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GestureHint(tr("Toucher"), tr("dicter"), Modifier.weight(1f))
                    GestureHint(tr("Appui long"), tr("maintenu"), Modifier.weight(1f))
                    GestureHint(tr("Glisser"), tr("déplacer, masquer"), Modifier.weight(1f))
                }
                if (bubble && !overlay) OverlayWarning(context, tr("pour voir la bulle"))
            }

            // À la voix
            DictoCard {
                TriggerHeader(
                    DictoIcons.Wave, Dicto.RecordingContainer, Dicto.OnRecordingContainer, tr("À la voix"), tr("Écouté sur le téléphone uniquement"),
                    info = tr("« Ok Google » est réservé à Google, choisis la tienne : deux ou trois mots, prononcés à l'anglaise. Mise en pause écran éteint et en économie d'énergie."),
                ) {
                    DictoSwitch(wake, { on ->
                        if (on && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                            Toast.makeText(context, tr("Autorise d'abord le micro depuis l'écran Dictée."), Toast.LENGTH_LONG).show()
                            return@DictoSwitch
                        }
                        if (on && !overlay) requestOverlay(context)
                        settings.wakeWord.set(on)
                        if (on) WakeWordService.start(context) else WakeWordService.stop(context)
                    }, tr("Activation à la voix"))
                }
                var draft by remember(phrase) { mutableStateOf(phrase) }
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text(tr("Ta phrase d'appel")) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        if (draft.isNotBlank() && draft != phrase) {
                            androidx.compose.material3.TextButton(onClick = {
                                settings.wakePhrase.set(draft.trim())
                                if (wake) { WakeWordService.stop(context); WakeWordService.start(context) }
                            }) { Text("OK") }
                        }
                    },
                )
                val wakeState by WakeWordService.state.collectAsStateWithLifecycle()
                if (wake) {
                    wakeState?.let {
                        Text(it, style = MaterialTheme.typography.labelMedium, color = if (it.startsWith(tr("J'écoute"))) Dicto.Accent else Dicto.OnRecordingContainer)
                    }
                }
                if (wake && !overlay) OverlayWarning(context, tr("pour que la dictée s'ouvre seule (sinon, une notification « Touche pour dicter »)"))
            }

            // Autres déclencheurs
            DictoCard(padding = PaddingValues(0.dp)) {
                Column {
                    ListRow(tr("Geste depuis le bord"), tr("Glisser vers l'intérieur, à droite")) {
                        DictoSwitch(edge && overlay, { on ->
                            if (on && !overlay) requestOverlay(context)
                            settings.edgeSwipe.set(on)
                            BubbleService.refresh(context)
                        }, tr("Geste depuis le bord"))
                    }
                    HorizontalDivider(color = Dicto.SurfaceSunken)
                    ListRow(tr("Tuile Réglages rapides"), tr("Affiche ou masque la bulle depuis le volet de notifications")) {
                        TonalButton(tr("Ajouter"), { addTile(context) })
                    }
                    HorizontalDivider(color = Dicto.SurfaceSunken)
                    ListRow(tr("Assistant par défaut"), tr("Appui long sur Power ou coin de l'écran")) {
                        TonalButton(tr("Choisir"), {
                            context.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        })
                    }
                }
            }
            if (edge && !overlay) OverlayWarning(context, tr("pour activer le geste"))
        }
    }
}

/**
 * Permission de superposition manquante. Android 13+ la verrouille (« paramètre restreint »)
 * pour les applis installées depuis un navigateur ou une messagerie : il faut d'abord
 * l'autoriser depuis les infos de l'appli.
 */
@Composable
private fun OverlayWarning(context: Context, purpose: String) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Dicto.RecordingContainer).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            tr("Autorise « Afficher par-dessus les autres apps » %1\$s.", purpose),
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
            TonalButton(tr("Autoriser"), { requestOverlay(context) })
        }
    }
}

private fun requestOverlay(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

@Composable
private fun TriggerHeader(
    icon: ImageVector,
    bg: Color,
    fg: Color,
    title: String,
    subtitle: String,
    info: String? = null,
    trailing: @Composable () -> Unit,
) {
    var showInfo by remember { mutableStateOf(false) }
    if (showInfo && info != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text(title, style = MaterialTheme.typography.titleLarge) },
            text = { Text(info, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { showInfo = false }) { Text(tr("Compris")) } },
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (info != null) InfoButton(tr("En savoir plus : %1\$s", title)) { showInfo = true }
            }
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Dicto.InkMuted)
        }
        Spacer(Modifier.width(12.dp))
        trailing()
    }
}

/** Petit « i » rond qui ouvre une explication. */
@Composable
private fun InfoButton(description: String, onClick: () -> Unit) {
    androidx.compose.material3.Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.Transparent,
        contentColor = Dicto.InkMuted,
        border = androidx.compose.foundation.BorderStroke(1.5.dp, Dicto.InkMuted),
        modifier = Modifier.size(20.dp).semantics { contentDescription = description },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text("i", style = MaterialTheme.typography.labelSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        }
    }
}

@Composable
private fun GestureHint(title: String, subtitle: String, modifier: Modifier) {
    Column(
        modifier.fillMaxHeight().clip(RoundedCornerShape(16.dp)).background(Dicto.Background).padding(horizontal = 8.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text(subtitle, style = MaterialTheme.typography.labelSmall, color = Dicto.InkMuted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun ListRow(title: String, subtitle: String, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = Dicto.InkMuted)
        }
        Spacer(Modifier.width(8.dp))
        trailing()
    }
}

private fun addTile(context: Context) {
    if (Build.VERSION.SDK_INT >= 33) {
        context.getSystemService(StatusBarManager::class.java).requestAddTileService(
            ComponentName(context, DictoTileService::class.java),
            tr("Bulle Dicto"),
            Icon.createWithResource(context, R.drawable.ic_launcher_monochrome),
            context.mainExecutor,
        ) { }
    } else {
        Toast.makeText(context, tr("Ouvre le volet des réglages rapides, touche le crayon et ajoute « Dicto »."), Toast.LENGTH_LONG).show()
    }
}
