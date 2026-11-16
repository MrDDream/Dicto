package app.dicto.ui

import app.dicto.i18n.tr
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dicto.DictoApp
import app.dicto.dictation.userMessage
import app.dicto.ui.theme.Dicto
import app.dicto.ui.theme.DictoIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.dicto_ffi.HistoryItem
import uniffi.dicto_ffi.refinePresets
import java.text.DateFormat
import java.util.Date

@Composable
fun HistoryScreen() {
    val app = DictoApp.instance
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var items by remember { mutableStateOf<List<HistoryItem>>(emptyList()) }
    var refining by remember { mutableStateOf<Long?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var exportMenu by remember { mutableStateOf(false) }
    var exportMarkdown by remember { mutableStateOf(false) }
    val provider by app.settings.refineProvider.collectAsStateWithLifecycle()
    // Recharge à chaque nouvelle dictée terminée.
    val pending by remember { app.dictation.ui }.collectAsStateWithLifecycle()

    LaunchedEffect(query, refresh, pending.pending) {
        items = withContext(Dispatchers.IO) {
            runCatching { app.storage.history(query.ifBlank { null }, 200u, 0u) }.getOrDefault(emptyList())
        }
    }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val ok = runCatching {
            val all = app.storage.history(null, 100_000u, 0u)
            context.contentResolver.openOutputStream(uri)?.use { it.write(exportHistory(all, exportMarkdown).toByteArray()) }
        }.isSuccess
        Toast.makeText(context, if (ok) tr("Historique exporté.") else tr("Export impossible."), Toast.LENGTH_SHORT).show()
    }

    fun retouch(item: HistoryItem, presetId: String) {
        val client = app.dictation.refineClient() ?: return
        val instruction = app.dictation.presetInstruction(presetId) ?: return
        refining = item.id
        scope.launch {
            try {
                val text = client.refine(instruction, item.text)
                if (text.isNotBlank()) withContext(Dispatchers.IO) { app.storage.updateHistoryText(item.id, text) }
                refresh++
            } catch (e: Exception) {
                Toast.makeText(context, tr("Retouche impossible : %1\$s", e.userMessage()), Toast.LENGTH_LONG).show()
            } finally {
                refining = null
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = Dicto.Surface,
            title = { Text(tr("Effacer l'historique ?")) },
            text = { Text(tr("Toutes les dictées seront supprimées, sauf celles que tu as épinglées."), color = Dicto.InkMuted) },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(tr("Annuler"), color = Dicto.InkMuted) } },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { app.storage.clearHistory() }
                    confirmClear = false
                    refresh++
                }) { Text(tr("Effacer"), color = Dicto.OnRecordingContainer) }
            },
        )
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(tr("Historique"))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp),
            placeholder = { Text(tr("Rechercher")) },
            leadingIcon = { Icon(DictoIcons.Search, null, Modifier.size(18.dp)) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = Dicto.Surface,
                focusedContainerColor = Dicto.Surface,
                unfocusedBorderColor = Dicto.Border,
            ),
        )
        if (items.isNotEmpty() || query.isNotBlank()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box {
                    TonalButton(tr("Exporter"), { exportMenu = true }, icon = DictoIcons.Download)
                    DropdownMenu(expanded = exportMenu, onDismissRequest = { exportMenu = false }) {
                        DropdownMenuItem(text = { Text(tr("Texte (.txt)")) }, onClick = {
                            exportMenu = false
                            exportMarkdown = false
                            save.launch("dicto-historique.txt")
                        })
                        DropdownMenuItem(text = { Text(tr("Markdown (.md)")) }, onClick = {
                            exportMenu = false
                            exportMarkdown = true
                            save.launch("dicto-historique.md")
                        })
                    }
                }
                TonalButton(tr("Tout effacer"), { confirmClear = true }, icon = DictoIcons.Trash)
            }
        }
        if (items.isEmpty()) {
            Text(
                if (query.isBlank()) tr("Tes dictées s'afficheront ici, y compris celles faites avec le clavier Dicto.") else tr("Aucun résultat."),
                style = MaterialTheme.typography.bodyMedium,
                color = Dicto.InkMuted,
                modifier = Modifier.padding(24.dp),
            )
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(items, key = { it.id }) { item ->
                HistoryCard(
                    item = item,
                    busy = refining == item.id,
                    canRefine = provider != null,
                    onPin = {
                        runCatching { app.storage.setHistoryPinned(item.id, !item.pinned) }
                        refresh++
                    },
                    onCopy = { copy(context, item.text) },
                    onShare = { share(context, item.text) },
                    onRefine = { retouch(item, it) },
                    onDelete = {
                        runCatching { app.storage.deleteHistory(item.id) }
                        refresh++
                    },
                )
            }
            item { Spacer(Modifier.size(8.dp)) }
        }
    }
}

@Composable
private fun HistoryCard(
    item: HistoryItem,
    busy: Boolean,
    canRefine: Boolean,
    onPin: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onRefine: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    DictoCard(padding = PaddingValues(start = 16.dp, end = 6.dp, top = 14.dp, bottom = 6.dp)) {
        if (item.pinned) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(DictoIcons.Pin, null, Modifier.size(14.dp), tint = Dicto.Accent)
                Text(tr("Épinglée"), style = MaterialTheme.typography.labelSmall, color = Dicto.Accent)
            }
        }
        Text(
            if (busy) tr("Retouche…") else item.text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (busy) Dicto.InkMuted else Dicto.Ink,
            modifier = Modifier.padding(end = 10.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                DateUtils.getRelativeTimeSpanString(item.createdAtMs).toString() + " · " + engineName(item.engine),
                style = MaterialTheme.typography.labelSmall,
                color = Dicto.InkMuted,
                maxLines = 2,
                modifier = Modifier.weight(1f),
            )
            GhostButton(
                null, DictoIcons.Pin, onPin,
                contentDescription = if (item.pinned) tr("Désépingler") else tr("Épingler"),
                tint = if (item.pinned) Dicto.Accent else Dicto.InkMuted,
            )
            GhostButton(null, DictoIcons.Copy, onCopy, contentDescription = tr("Copier"))
            GhostButton(null, DictoIcons.Share, onShare, contentDescription = tr("Partager"))
            if (canRefine) {
                Box {
                    GhostButton(null, DictoIcons.Sparkle, { if (!busy) menu = true }, contentDescription = tr("Retoucher"))
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        for (p in refinePresets()) {
                            DropdownMenuItem(text = { Text(tr(p.name)) }, onClick = { menu = false; onRefine(p.id) })
                        }
                        if (DictoApp.instance.settings.refineCustom.value.isNotBlank()) {
                            DropdownMenuItem(text = { Text(tr("Personnalisée")) }, onClick = { menu = false; onRefine("custom") })
                        }
                    }
                }
            }
            GhostButton(null, DictoIcons.Trash, onDelete, contentDescription = tr("Supprimer"), tint = Dicto.InkMuted)
        }
    }
}

private fun engineName(id: String) = id.substringAfter(':').ifBlank { id }

/** L'historique en texte : une dictée par bloc, avec sa date (Markdown : un titre par dictée). */
internal fun exportHistory(items: List<HistoryItem>, markdown: Boolean): String {
    val format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    return buildString {
        if (markdown) append("# ").append(tr("Historique Dicto")).append("\n\n")
        for (item in items) {
            val date = format.format(Date(item.createdAtMs))
            val pin = if (item.pinned) " · " + tr("épinglée") else ""
            if (markdown) {
                append("## ").append(date).append(" · ").append(engineName(item.engine)).append(pin).append("\n\n")
                append(item.text.trim()).append("\n\n")
            } else {
                append(date).append(pin).append("\n").append(item.text.trim()).append("\n\n")
            }
        }
    }.trimEnd() + "\n"
}
