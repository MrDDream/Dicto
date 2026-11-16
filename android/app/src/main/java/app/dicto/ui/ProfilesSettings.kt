package app.dicto.ui

import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.dicto.DictoApp
import app.dicto.data.LANGUAGES
import app.dicto.ui.theme.Dicto
import app.dicto.ui.theme.DictoIcons
import uniffi.dicto_ffi.AppProfile
import uniffi.dicto_ffi.Mode
import uniffi.dicto_ffi.refinePresets

private data class AppEntry(val pkg: String, val label: String)

private val MODE_LABELS = listOf(null to "Habituel", Mode.PUSH_TO_TALK.name to "Push-to-talk", Mode.CONTINUOUS.name to "Continu", Mode.HOLD.name to "Maintenu")

/** Profils par app : langue, retouche et mode appliqués quand on dicte dans cette app. */
@Composable
fun ProfilesCard() {
    val app = DictoApp.instance
    var refresh by remember { mutableIntStateOf(0) }
    val profiles = remember(refresh) { runCatching { app.storage.profiles() }.getOrDefault(emptyList()) }
    var editing by remember { mutableStateOf<AppProfile?>(null) }

    DictoCard {
        Text(
            "Quand tu dictes dans une app, ses réglages remplacent les réglages généraux. " +
                "L'app est reconnue quand le clavier Dicto est ton clavier en cours.",
            style = MaterialTheme.typography.bodySmall,
            color = Dicto.InkMuted,
        )
        for (p in profiles) {
            Row(Modifier.fillMaxWidth().clickable { editing = p }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(p.label ?: p.appId, style = MaterialTheme.typography.titleSmall)
                    Text(describe(p), style = MaterialTheme.typography.labelSmall, color = Dicto.InkMuted)
                }
                GhostButton(null, DictoIcons.Trash, {
                    p.id?.let { runCatching { app.storage.deleteProfile(it) } }
                    refresh++
                }, contentDescription = "Supprimer le profil", tint = Dicto.InkMuted)
            }
        }
        TonalButton("Ajouter un profil", { editing = AppProfile(null, "", null, null, null, null, null) }, icon = DictoIcons.Plus)
    }

    editing?.let { p ->
        ProfileDialog(p, onDismiss = { editing = null }, onSave = {
            runCatching { app.storage.saveProfile(it) }
            editing = null
            refresh++
        })
    }
}

private fun describe(p: AppProfile): String = listOfNotNull(
    p.language?.let { code -> LANGUAGES.firstOrNull { it.first == code }?.second },
    p.refinePreset?.let { id -> if (id == "none") "sans retouche" else refinePresets().firstOrNull { it.id == id }?.name },
    MODE_LABELS.firstOrNull { it.first == p.mode && p.mode != null }?.second,
).joinToString(" · ").ifBlank { "réglages généraux" }

@Composable
private fun ProfileDialog(initial: AppProfile, onDismiss: () -> Unit, onSave: (AppProfile) -> Unit) {
    val context = LocalContext.current
    var profile by remember { mutableStateOf(initial) }
    var query by remember { mutableStateOf("") }
    val apps = remember {
        val pm = context.packageManager
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.MATCH_ALL)
            .map { AppEntry(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .filter { it.pkg != context.packageName }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (profile.appId.isBlank()) "Choisir une app" else profile.label ?: profile.appId) },
        text = {
            if (profile.appId.isBlank()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = query, onValueChange = { query = it }, placeholder = { Text("Rechercher") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(apps.filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }, key = { it.pkg }) { a ->
                            Text(a.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth().clickable {
                                profile = profile.copy(appId = a.pkg, label = a.label)
                            }.padding(vertical = 10.dp))
                            HorizontalDivider(color = Dicto.SurfaceSunken)
                        }
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Langue", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ChipButton({ profile = profile.copy(language = null) }, accent = profile.language == null) { Text("Habituelle", style = MaterialTheme.typography.labelMedium) }
                        for ((code, name) in LANGUAGES.drop(1)) {
                            ChipButton({ profile = profile.copy(language = code) }, accent = profile.language == code) { Text(name, style = MaterialTheme.typography.labelMedium) }
                        }
                    }
                    Text("Retouche", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ChipButton({ profile = profile.copy(refinePreset = null) }, accent = profile.refinePreset == null) { Text("Habituelle", style = MaterialTheme.typography.labelMedium) }
                        ChipButton({ profile = profile.copy(refinePreset = "none") }, accent = profile.refinePreset == "none") { Text("Aucune", style = MaterialTheme.typography.labelMedium) }
                        for (pr in refinePresets()) {
                            ChipButton({ profile = profile.copy(refinePreset = pr.id) }, accent = profile.refinePreset == pr.id) { Text(pr.name, style = MaterialTheme.typography.labelMedium) }
                        }
                    }
                    Text("Mode", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for ((m, name) in MODE_LABELS) {
                            ChipButton({ profile = profile.copy(mode = m) }, accent = profile.mode == m) { Text(name, style = MaterialTheme.typography.labelMedium) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (profile.appId.isNotBlank()) TextButton(onClick = { onSave(profile) }) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
