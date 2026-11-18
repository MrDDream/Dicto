package app.dicto.files

import app.dicto.i18n.tr
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dicto.ui.MainActivity
import app.dicto.ui.copy
import app.dicto.ui.share
import app.dicto.ui.theme.Dicto
import app.dicto.ui.theme.DictoIcons
import app.dicto.ui.theme.DictoTheme

/**
 * « Transcrire avec Dicto » depuis le menu Partager (message vocal WhatsApp, Signal…) :
 * un panneau par-dessus l'app d'origine affiche la progression puis le texte, à copier ou
 * à partager, sans ouvrir Dicto. La transcription continue si on le ferme, et va dans
 * l'historique.
 */
class ShareTranscribeActivity : ComponentActivity() {
    private var itemId: Long = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        itemId = savedInstanceState?.getLong(KEY_ID, -1) ?: -1
        if (itemId < 0) {
            val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            if (uri == null) {
                finish()
                return
            }
            // L'app qui partage (com.whatsapp…), quand Android la donne.
            val source = referrer?.host?.takeIf { it.isNotBlank() && it != packageName && it != "android" } ?: SHARED
            itemId = FilesController.add(this, uri, source)
        }
        setContent {
            DictoTheme {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.25f))
                        .clickable(remember { MutableInteractionSource() }, indication = null) { finish() },
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    TranscribePanel(
                        id = itemId,
                        onClose = ::finish,
                        onOpenDicto = ::openDicto,
                        modifier = Modifier.navigationBarsPadding().padding(12.dp)
                            .clickable(remember { MutableInteractionSource() }, indication = null) {},
                    )
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(KEY_ID, itemId)
    }

    override fun onStart() {
        super.onStart()
        FilesController.shownId = itemId
    }

    override fun onStop() {
        if (FilesController.shownId == itemId) FilesController.shownId = null
        super.onStop()
    }

    private fun openDicto() {
        MainActivity.openFiles.value = true
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    private companion object {
        const val KEY_ID = "item"
        /** App d'origine inconnue : le texte va quand même dans l'historique. */
        const val SHARED = "share"
    }
}

@Composable
private fun TranscribePanel(id: Long, onClose: () -> Unit, onOpenDicto: () -> Unit, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val items by FilesController.items.collectAsStateWithLifecycle()
    val item = items.firstOrNull { it.id == id }
    val text = item?.result?.text.orEmpty()
    val maxText = (LocalConfiguration.current.screenHeightDp * 0.5f).dp

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
            Column(Modifier.weight(1f)) {
                Text(tr("Message vocal"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Dicto.OverlayInk)
                Text(
                    listOfNotNull(item?.durationMs?.takeIf { it > 0 }?.let(::clock), item?.let { status(it) }).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (item?.status == FileItem.Status.FAILED) Dicto.OverlayRecordingInk else Dicto.OverlayMuted,
                )
            }
            Surface(
                onClick = onClose,
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = Color.Transparent,
                contentColor = Dicto.OverlayInk,
                border = BorderStroke(1.dp, Dicto.OverlayLine),
            ) { Box(contentAlignment = Alignment.Center) { Icon(DictoIcons.Close, tr("Fermer"), Modifier.size(16.dp)) } }
        }

        if (item != null) when (item.status) {
            FileItem.Status.CANCELLED -> Unit
            FileItem.Status.QUEUED, FileItem.Status.VOICE_MODEL, FileItem.Status.DECODING, FileItem.Status.TRANSCRIBING -> {
                LinearProgressIndicator(
                    progress = { if (item.status == FileItem.Status.QUEUED) 0f else item.progress },
                    modifier = Modifier.fillMaxWidth().clip(CircleShape),
                    color = Dicto.Accent,
                    trackColor = Dicto.OverlayLine,
                )
                Text(
                    tr("Tu peux fermer : le texte arrivera en notification et dans l'historique."),
                    style = MaterialTheme.typography.bodySmall,
                    color = Dicto.OverlayMuted,
                )
            }
            FileItem.Status.DONE -> SelectionContainer {
                Column(Modifier.heightIn(max = maxText).verticalScroll(rememberScrollState())) {
                    Text(
                        text.ifBlank { tr("Aucune parole détectée.") },
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (text.isBlank()) Dicto.OverlayMuted else Dicto.OverlayInk,
                    )
                }
            }
            FileItem.Status.FAILED -> Text(
                item.error ?: tr("Transcription impossible."),
                style = MaterialTheme.typography.bodyMedium,
                color = Dicto.OverlayRecordingInk,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                item?.status == FileItem.Status.DONE && text.isNotBlank() -> {
                    PanelButton(tr("Partager"), DictoIcons.Share, { share(context, text) }, Modifier.weight(1f))
                    PanelButton(tr("Copier"), DictoIcons.Copy, {
                        copy(context, text)
                        Toast.makeText(context, tr("Texte copié."), Toast.LENGTH_SHORT).show()
                        onClose()
                    }, Modifier.weight(1f), primary = true)
                }
                item?.active == true || item?.status == FileItem.Status.QUEUED -> {
                    PanelButton(tr("Annuler"), DictoIcons.Close, {
                        FilesController.cancel(id)
                        onClose()
                    }, Modifier.weight(1f))
                    PanelButton(tr("Ouvrir dans Dicto"), null, onOpenDicto, Modifier.weight(1f))
                }
                else -> PanelButton(tr("Ouvrir dans Dicto"), null, onOpenDicto, Modifier.weight(1f), primary = true)
            }
        }
    }
}

@Composable
private fun PanelButton(text: String, icon: ImageVector?, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(46.dp),
        shape = RoundedCornerShape(23.dp),
        color = if (primary) Dicto.OverlayInk else Color.Transparent,
        contentColor = if (primary) Dicto.Ink else Dicto.OverlayInk,
        border = if (primary) null else BorderStroke(1.dp, Dicto.OverlayLine),
    ) {
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

private fun status(item: FileItem): String = when (item.status) {
    FileItem.Status.QUEUED -> tr("En attente")
    FileItem.Status.VOICE_MODEL -> tr("Modèle des voix %1\$s %%", (item.progress * 100).toInt())
    FileItem.Status.DECODING -> tr("Lecture %1\$s %%", (item.progress * 100).toInt())
    FileItem.Status.TRANSCRIBING -> tr("Transcription %1\$s %%", (item.progress * 100).toInt())
    FileItem.Status.DONE -> tr("Terminé")
    FileItem.Status.FAILED -> tr("Échec")
    FileItem.Status.CANCELLED -> tr("Annulé")
}

private fun clock(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
