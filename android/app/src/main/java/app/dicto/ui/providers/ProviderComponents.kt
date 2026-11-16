package app.dicto.ui.providers

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.dicto.dictation.userMessage
import app.dicto.ui.theme.Dicto
import app.dicto.ui.theme.DictoIcons
import app.dicto.ui.theme.Mono
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Un service externe (transcription ou retouche) tel que l'interface le règle : présentation,
 * état enregistré, et actions. Même rendu que les fournisseurs d'IA de Nautile.
 */
class ProviderSpec(
    val brand: Brand,
    val label: String,
    /** Une clé est indispensable (sinon facultative, ex. serveur personnel). */
    val needsKey: Boolean,
    val keyHint: String,
    /** Champ d'adresse affiché (serveur personnel, Ollama). */
    val urlPlaceholder: String? = null,
    val urlHint: String? = null,
    /** Filtres rapides dans le choix du modèle (longues listes des modèles de langage). */
    val chatFilters: Boolean = false,
    val savedKey: () -> Boolean,
    val savedModel: () -> String?,
    val savedUrl: () -> String?,
    /** Modèles disponibles ; `key` = clé saisie (sinon la clé enregistrée). */
    val loadModels: suspend (key: String?, url: String?) -> List<String>,
    val save: (key: String?, model: String, url: String?) -> Unit,
    val forgetKey: () -> Unit,
) {
    val editableUrl get() = urlPlaceholder != null
    fun isConfigured() = !savedModel().isNullOrBlank() && (!needsKey || savedKey()) && (!editableUrl || !savedUrl().isNullOrBlank())
}

/** Nom lisible d'un identifiant de modèle (« gpt-4o-mini-transcribe » → « GPT-4o mini transcribe »). */
fun prettyModel(id: String): String {
    if (id.isBlank()) return id
    val m = id.substringAfterLast('/').lowercase()
        .replace(Regex("-(latest|preview)$"), "")
        .replace(Regex("-(\\d{8}|\\d{4}-\\d{2}-\\d{2})$"), "")
    fun cap(w: String) = w.replaceFirstChar { it.uppercase() }
    Regex("^claude-(opus|sonnet|haiku|fable)-(\\d+)(?:[-.](\\d+))?").find(m)?.let { r ->
        val (family, major, minor) = r.destructured
        return cap(family) + " " + major + if (minor.isNotEmpty()) ".$minor" else ""
    }
    Regex("^gpt-([a-z0-9.]+)(?:-(.+))?$").find(m)?.let { r ->
        val (version, rest) = r.destructured
        return "GPT-$version" + if (rest.isNotEmpty()) " " + rest.replace('-', ' ') else ""
    }
    return m.split('-').filter { it.isNotEmpty() }.joinToString(" ") { if (it.first().isLetter()) cap(it) else it }
}

/**
 * Ligne d'un fournisseur : logo, nom, modèle choisi (ou « Non configuré »), pastille,
 * réglages et interrupteur. Toucher la ligne ouvre les réglages.
 */
@Composable
fun ProviderRow(
    spec: ProviderSpec,
    on: Boolean,
    badge: String?,
    revision: Int,
    onToggle: (Boolean) -> Unit,
    onConfigure: () -> Unit,
) {
    val configured = remember(spec.label, revision) { spec.isConfigured() }
    val model = remember(spec.label, revision) { spec.savedModel().orEmpty() }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onConfigure).padding(start = 14.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(Dicto.Background), contentAlignment = Alignment.Center) {
            ProviderLogo(spec.brand, 22.dp, tint = if (on) Dicto.Ink else Dicto.InkSecondary)
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    spec.label, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                if (badge != null) {
                    Box(Modifier.clip(RoundedCornerShape(6.dp)).background(Dicto.AccentContainer).padding(horizontal = 6.dp, vertical = 1.dp)) {
                        Text(badge, color = Dicto.Accent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Text(
                if (configured) prettyModel(model) else "Non configuré",
                fontSize = 12.sp,
                color = if (configured) Dicto.InkMuted else Dicto.OnRecordingContainer,
                maxLines = 1,
            )
        }
        IconButton(onClick = onConfigure) {
            Icon(DictoIcons.Gear, contentDescription = "Configurer ${spec.label}", tint = Dicto.InkSecondary, modifier = Modifier.size(20.dp))
        }
        Switch(
            on, onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Dicto.Surface, checkedTrackColor = Dicto.Accent, checkedBorderColor = Dicto.Accent,
                uncheckedThumbColor = Dicto.Surface, uncheckedTrackColor = Dicto.Border, uncheckedBorderColor = Dicto.Border,
            ),
        )
    }
}

/** Panneau de réglage d'un fournisseur : adresse, clé, modèle, puis tester ou enregistrer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderSheet(spec: ProviderSpec, onSaved: (configured: Boolean) -> Unit, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Dicto.Surface) {
        ProviderSheetContent(spec, onSaved, onDismiss)
    }
}

/** Contenu du panneau (aussi dessiné par les captures d'écran). */
@Composable
fun ProviderSheetContent(spec: ProviderSpec, onSaved: (configured: Boolean) -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var keySaved by remember { mutableStateOf(spec.savedKey()) }
    var key by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var model by remember { mutableStateOf(spec.savedModel().orEmpty()) }
    var url by remember { mutableStateOf(spec.savedUrl().orEmpty()) }
    var status by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }

    fun typedKey() = key.trim().ifEmpty { null }
    fun typedUrl() = url.trim().ifEmpty { null }
    fun save() {
        spec.save(typedKey(), model.trim(), typedUrl())
        if (key.isNotBlank()) keySaved = true
        key = ""
    }
    val canSave = model.isNotBlank() && (!spec.needsKey || keySaved || key.isNotBlank()) && (!spec.editableUrl || url.isNotBlank())

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Dicto.Background), contentAlignment = Alignment.Center) {
                ProviderLogo(spec.brand, 26.dp)
            }
            Column(Modifier.weight(1f)) {
                Text(spec.label, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        keySaved -> "Clé enregistrée"
                        spec.needsKey -> "Clé API requise"
                        else -> "Clé API facultative"
                    },
                    color = if (spec.needsKey && !keySaved) Dicto.OnRecordingContainer else Dicto.InkMuted, fontSize = 12.sp,
                )
            }
            IconButton(onClick = onDismiss) { Icon(DictoIcons.Close, contentDescription = "Fermer", tint = Dicto.InkSecondary) }
        }

        if (spec.editableUrl) {
            OutlinedTextField(
                value = url, onValueChange = { url = it },
                label = { Text("Adresse de l'API") },
                placeholder = { Text(spec.urlPlaceholder.orEmpty(), color = Dicto.InkDisabled, fontFamily = Mono) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                textStyle = TextStyle(fontFamily = Mono, fontSize = 14.sp, color = Dicto.Ink),
                shape = RoundedCornerShape(12.dp), colors = fieldColors(),
            )
            spec.urlHint?.let { Text(it, color = Dicto.InkMuted, fontSize = 12.sp, lineHeight = 17.sp) }
        }

        OutlinedTextField(
            value = key, onValueChange = { key = it },
            label = {
                Text(when {
                    keySaved -> "Nouvelle clé API (facultatif)"
                    spec.needsKey -> "Clé API"
                    else -> "Clé API (facultative)"
                })
            },
            placeholder = { Text(if (keySaved) "••••••••••••" else spec.keyHint, color = Dicto.InkDisabled, fontFamily = Mono) },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showKey = !showKey }) {
                    Icon(
                        if (showKey) DictoIcons.EyeOff else DictoIcons.Eye,
                        contentDescription = if (showKey) "Masquer" else "Afficher", tint = Dicto.InkSecondary, modifier = Modifier.size(20.dp),
                    )
                }
            },
            textStyle = TextStyle(fontFamily = Mono, fontSize = 14.sp, color = Dicto.Ink),
            shape = RoundedCornerShape(12.dp), colors = fieldColors(),
        )

        // Le modèle se choisit dans la liste du fournisseur (un nom saisi à la main reste possible).
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Modèle", color = Dicto.InkSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 58.dp).clip(RoundedCornerShape(12.dp))
                    .border(1.dp, Dicto.Border, RoundedCornerShape(12.dp))
                    .clickable(role = Role.DropdownList) { picking = true }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    if (model.isBlank()) {
                        Text("Choisir un modèle", color = Dicto.InkDisabled, fontSize = 15.sp)
                    } else {
                        Text(prettyModel(model), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Dicto.Ink)
                        Text(model, fontSize = 11.sp, fontFamily = Mono, color = Dicto.InkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Icon(DictoIcons.ChevronDown, contentDescription = null, tint = Dicto.InkSecondary, modifier = Modifier.size(20.dp))
            }
        }

        if (busy) LinearProgressIndicator(color = Dicto.Accent, trackColor = Dicto.Border, modifier = Modifier.fillMaxWidth())
        status?.let { (ok, text) ->
            val color = if (ok) Dicto.Success else Dicto.OnRecordingContainer
            Text(
                text, color = color, fontSize = 13.sp, lineHeight = 18.sp,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.10f)).padding(10.dp),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                enabled = !busy && canSave, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Dicto.Border),
                onClick = {
                    busy = true
                    status = null
                    val k = typedKey()
                    val u = typedUrl()
                    save()
                    scope.launch {
                        status = try {
                            val n = spec.loadModels(k, u).size
                            onSaved(spec.isConfigured())
                            true to "Connexion OK · $n modèle(s) disponible(s)"
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            false to "Échec : ${e.userMessage()}"
                        }
                        busy = false
                    }
                },
            ) { Text("Tester", color = Dicto.Ink, fontWeight = FontWeight.SemiBold) }
            Button(
                enabled = !busy && canSave, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Dicto.Accent, contentColor = Dicto.OnAccent),
                onClick = {
                    save()
                    onSaved(spec.isConfigured())
                    onDismiss()
                },
            ) { Text("Enregistrer", fontWeight = FontWeight.SemiBold) }
        }

        if (keySaved) {
            TextButton(onClick = {
                spec.forgetKey()
                keySaved = false
                onSaved(spec.isConfigured())
            }) { Text("Oublier la clé ${spec.label}", color = Dicto.OnRecordingContainer) }
        }
    }

    if (picking) {
        ModelPicker(
            current = model,
            chatFilters = spec.chatFilters,
            load = { spec.loadModels(typedKey(), typedUrl()) },
            onPick = { model = it; picking = false },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    unfocusedContainerColor = Dicto.Surface, focusedContainerColor = Dicto.Surface,
    unfocusedBorderColor = Dicto.Border, focusedBorderColor = Dicto.Accent,
    unfocusedLabelColor = Dicto.InkSecondary, focusedLabelColor = Dicto.Accent, cursorColor = Dicto.Accent,
)

/** Filtres rapides des listes de modèles de langage. */
enum class ModelFilter(val label: String, private val match: Regex?) {
    CHAT("Texte", null),
    FAST("Rapides", Regex("mini|nano|flash|haiku|lite|small|tiny|instant|fast|\\b[1-9]b\\b|-[1-9]b")),
    STRONG("Puissants", Regex("opus|sonnet|pro\\b|-pro|large|ultra|max\\b|70b|405b|^gpt-5(\\.\\d)?$")),
    REASONING("Raisonnement", Regex("^o\\d|reason|think|r1|qwq|magistral")),
    ALL("Tous", null);

    fun keep(id: String): Boolean {
        val m = id.lowercase()
        if (this == ALL) return true
        if (NON_CHAT.containsMatchIn(m)) return false
        return match?.containsMatchIn(m) ?: true
    }

    companion object {
        /** Embeddings, synthèse vocale, images… : sans intérêt pour retoucher du texte. */
        private val NON_CHAT = Regex("embed|tts|whisper|dall-e|dalle|moderation|transcri|image|audio|realtime|search|computer-use|davinci|babbage|imagen|veo|aqa|rerank|ocr|guard")
    }
}

/** Liste des modèles du fournisseur : recherche, filtres rapides, ou nom saisi à la main. */
@Composable
fun ModelPicker(current: String, chatFilters: Boolean, load: suspend () -> List<String>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var models by remember { mutableStateOf<List<String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        error = null
        try {
            models = load().distinct().sortedDescending()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.userMessage()
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.86f), shape = RoundedCornerShape(24.dp), color = Dicto.Surface) {
            ModelPickerContent(models, error, current, chatFilters, onRetry = { attempt++ }, onPick = onPick, onClose = onDismiss)
        }
    }
}

@Composable
fun ModelPickerContent(
    models: List<String>?,
    error: String?,
    current: String,
    chatFilters: Boolean,
    onRetry: () -> Unit,
    onPick: (String) -> Unit,
    onClose: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(if (chatFilters) ModelFilter.CHAT else ModelFilter.ALL) }
    val q = query.trim().lowercase()
    val all = models.orEmpty()
    val shown = all.filter { filter.keep(it) && (q.isEmpty() || it.lowercase().contains(q) || prettyModel(it).lowercase().contains(q)) }

    Column(Modifier.padding(top = 18.dp)) {
        Row(Modifier.padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Choisir un modèle", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(DictoIcons.Close, contentDescription = "Fermer", tint = Dicto.InkSecondary) }
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { Text("Rechercher un modèle", color = Dicto.InkDisabled) },
            leadingIcon = { Icon(DictoIcons.Search, contentDescription = null, tint = Dicto.InkMuted, modifier = Modifier.size(20.dp)) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) { Icon(DictoIcons.Close, contentDescription = "Effacer", tint = Dicto.InkMuted, modifier = Modifier.size(18.dp)) }
                }
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            textStyle = TextStyle(fontSize = 15.sp, color = Dicto.Ink), shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = Dicto.Background, focusedContainerColor = Dicto.Background,
                unfocusedBorderColor = Dicto.Border, focusedBorderColor = Dicto.Accent, cursorColor = Dicto.Accent,
            ),
        )
        if (models != null && chatFilters) {
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ModelFilter.entries.forEach { f ->
                    val n = all.count { f.keep(it) }
                    if (n > 0 || f == filter) {
                        val on = f == filter
                        Box(
                            Modifier.height(34.dp).clip(RoundedCornerShape(17.dp))
                                .background(if (on) Dicto.AccentContainer else Color.Transparent)
                                .border(1.dp, if (on) Dicto.Accent else Dicto.Border, RoundedCornerShape(17.dp))
                                .clickable(role = Role.RadioButton) { filter = f }.padding(horizontal = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text("${f.label} · $n", fontSize = 13.sp, color = if (on) Dicto.Accent else Dicto.InkSecondary, fontWeight = FontWeight.Medium) }
                    }
                }
            }
        }
        HorizontalDivider(color = Dicto.Border, modifier = Modifier.padding(top = 12.dp))
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            val typed = query.trim()
            if (typed.isNotEmpty() && all.none { it.equals(typed, ignoreCase = true) }) {
                item { PickRow(DictoIcons.Pencil, "Utiliser « $typed »", "Nom saisi à la main", selected = false) { onPick(typed) } }
            }
            when {
                error != null && models == null -> item {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Impossible de charger la liste : $error", color = Dicto.OnRecordingContainer, fontSize = 13.sp)
                        Text("Vérifie la clé et l'adresse, ou saisis le nom du modèle ci-dessus.", color = Dicto.InkMuted, fontSize = 12.sp)
                        TextButton(onClick = onRetry) { Text("Réessayer", color = Dicto.Accent) }
                    }
                }
                models == null -> item {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LinearProgressIndicator(color = Dicto.Accent, trackColor = Dicto.Border, modifier = Modifier.fillMaxWidth())
                        Text("Chargement des modèles…", color = Dicto.InkMuted, fontSize = 13.sp)
                    }
                }
                shown.isEmpty() -> item {
                    Text("Aucun modèle ne correspond.", color = Dicto.InkMuted, fontSize = 13.sp, modifier = Modifier.padding(20.dp))
                }
            }
            items(shown, key = { it }) { id -> PickRow(null, prettyModel(id), id, selected = id == current) { onPick(id) } }
        }
    }
}

@Composable
private fun PickRow(icon: ImageVector?, title: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.RadioButton, onClick = onClick)
            .background(if (selected) Dicto.AccentContainer.copy(alpha = 0.6f) else Color.Transparent)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = Dicto.Accent, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (selected) Dicto.Accent else Dicto.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, fontSize = 11.sp, fontFamily = Mono, color = Dicto.InkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (selected) Icon(DictoIcons.Check, contentDescription = "Sélectionné", tint = Dicto.Accent, modifier = Modifier.size(20.dp))
    }
}
