package app.dicto.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dicto.DictoApp
import app.dicto.ui.theme.Dicto
import app.dicto.ui.theme.DictoIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.dicto_ffi.HistoryItem

@Composable
fun HistoryScreen() {
    val app = DictoApp.instance
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var items by remember { mutableStateOf<List<HistoryItem>>(emptyList()) }
    // Recharge à chaque nouvelle dictée terminée.
    val pending by remember { app.dictation.ui }.collectAsStateWithLifecycle()

    LaunchedEffect(query, refresh, pending.pending) {
        items = withContext(Dispatchers.IO) {
            runCatching { app.storage.history(query.ifBlank { null }, 200u, 0u) }.getOrDefault(emptyList())
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Historique")
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp),
            placeholder = { Text("Rechercher") },
            leadingIcon = { Icon(DictoIcons.Search, null, Modifier.size(18.dp)) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = Dicto.Surface,
                focusedContainerColor = Dicto.Surface,
                unfocusedBorderColor = Dicto.Border,
            ),
        )
        if (items.isEmpty()) {
            Text(
                if (query.isBlank()) "Tes dictées s'afficheront ici, y compris celles faites avec le clavier Dicto." else "Aucun résultat.",
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
                DictoCard(padding = PaddingValues(start = 16.dp, end = 6.dp, top = 14.dp, bottom = 6.dp)) {
                    Text(item.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            DateUtils.getRelativeTimeSpanString(item.createdAtMs).toString() + " · " + engineName(item.engine),
                            style = MaterialTheme.typography.labelSmall,
                            color = Dicto.InkMuted,
                            modifier = Modifier.weight(1f),
                        )
                        GhostButton(null, DictoIcons.Copy, { copy(context, item.text) }, contentDescription = "Copier")
                        GhostButton(null, DictoIcons.Trash, {
                            runCatching { app.storage.deleteHistory(item.id) }
                            refresh++
                        }, contentDescription = "Supprimer", tint = Dicto.InkMuted)
                    }
                }
            }
            item { Spacer(Modifier.size(8.dp)) }
        }
    }
}

private fun engineName(id: String) = id.substringAfter(':').ifBlank { id }
