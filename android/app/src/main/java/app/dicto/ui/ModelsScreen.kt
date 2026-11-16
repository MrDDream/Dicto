package app.dicto.ui

import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dicto.DictoApp
import app.dicto.data.EngineChoice
import app.dicto.data.LANGUAGES
import app.dicto.dictation.EngineStatus
import app.dicto.models.DownloadState
import app.dicto.models.ModelDownloads
import app.dicto.ui.providers.Brand
import app.dicto.ui.providers.ProviderLogo
import app.dicto.ui.providers.TranscriptionProviders
import app.dicto.ui.theme.Dicto
import app.dicto.ui.theme.DictoIcons
import uniffi.dicto_ffi.ModelInfo

private enum class ModelsTab(val label: String) { LOCAL("Sur le téléphone"), ONLINE("En ligne") }

@Composable
fun ModelsScreen() {
    var tab by rememberSaveable { mutableStateOf(ModelsTab.LOCAL) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader("Modèles")
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            ActiveEngineCard()
            Tabs(tab) { tab = it }
            when (tab) {
                ModelsTab.LOCAL -> LocalModels()
                ModelsTab.ONLINE -> OnlineServices()
            }
        }
    }
}

/** Le moteur en service, en tête de l'écran. */
@Composable
private fun ActiveEngineCard() {
    val app = DictoApp.instance
    val ui by app.dictation.ui.collectAsStateWithLifecycle()
    val engine by app.settings.engine.collectAsStateWithLifecycle()
    val status = ui.engine
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(if (engine == null) Dicto.SurfaceSunken else Dicto.Accent)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val onAccent = engine != null
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(if (onAccent) Dicto.OnAccent.copy(alpha = 0.16f) else Dicto.Surface),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (engine is EngineChoice.Cloud) DictoIcons.Share else DictoIcons.MicBold, null,
                tint = if (onAccent) Dicto.OnAccent else Dicto.InkMuted, modifier = Modifier.size(20.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                if (engine == null) "Aucun moteur choisi" else "En service",
                style = MaterialTheme.typography.labelSmall,
                color = if (onAccent) Dicto.OnAccent.copy(alpha = 0.8f) else Dicto.InkMuted,
            )
            Text(
                if (engine == null) "Choisis un modèle ci-dessous" else status.label,
                style = MaterialTheme.typography.titleMedium,
                color = if (onAccent) Dicto.OnAccent else Dicto.Ink,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (engine != null) {
                Text(
                    when (status.kind) {
                        EngineStatus.Kind.READY -> if (engine is EngineChoice.Local) "Hors ligne · prêt" else "En ligne · prêt"
                        EngineStatus.Kind.LOADING -> "Chargement du modèle…"
                        EngineStatus.Kind.ERROR -> "Indisponible : ${status.detail}"
                        EngineStatus.Kind.NONE -> ""
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = Dicto.OnAccent.copy(alpha = 0.85f),
                    maxLines = 2,
                )
            }
        }
        if (status.kind == EngineStatus.Kind.LOADING) {
            CircularProgressIndicator(Modifier.size(22.dp), color = Dicto.OnAccent, strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun Tabs(current: ModelsTab, onSelect: (ModelsTab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Dicto.SurfaceSunken).padding(4.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (t in ModelsTab.entries) {
            val selected = t == current
            Box(
                Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (selected) Dicto.Surface else Color.Transparent)
                    .selectable(selected, role = Role.Tab) { onSelect(t) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    t.label, style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                    color = if (selected) Dicto.Accent else Dicto.InkSecondary,
                )
            }
        }
    }
}

@Composable
private fun LocalModels() {
    val app = DictoApp.instance
    val context = LocalContext.current
    val downloads by remember { ModelDownloads.observe(context) }.collectAsStateWithLifecycle(emptyMap())
    val engine by app.settings.engine.collectAsStateWithLifecycle()
    val wifiOnly by app.settings.wifiOnly.collectAsStateWithLifecycle()
    val dictationLanguage by app.settings.language.collectAsStateWithLifecycle()
    var refresh by remember { mutableIntStateOf(0) }
    val models = remember(refresh, downloads.values.count { !it.running && !it.queued }) { app.models.models() }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var toDelete by remember { mutableStateOf<ModelInfo?>(null) }
    // Langue du filtre : celle de la dictée, sinon celle du téléphone.
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val lang = dictationLanguage ?: locale.language
    val langName = LANGUAGES.firstOrNull { it.first == lang }?.second ?: lang.uppercase()
    var onlyMyLanguage by rememberSaveable { mutableStateOf(true) }
    val shown = models.filter { !onlyMyLanguage || "multi" in it.languages || lang in it.languages }
    val installed = models.filter { it.installed }

    Text(
        "Hors ligne, ta voix reste sur le téléphone. Touche une carte pour ses détails.",
        style = MaterialTheme.typography.bodySmall,
        color = Dicto.InkMuted,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        ChipButton(onClick = { onlyMyLanguage = true }, accent = onlyMyLanguage) { Text(langName, style = MaterialTheme.typography.labelMedium) }
        ChipButton(onClick = { onlyMyLanguage = false }, accent = !onlyMyLanguage) { Text("Toutes les langues", style = MaterialTheme.typography.labelMedium) }
    }

    val recommended = shown.filter { it.recommended }
    val others = shown.filterNot { it.recommended }
    if (recommended.isNotEmpty()) {
        SectionTitle("Conseillés")
        ModelList(recommended, engine, downloads, expanded, { expanded = if (expanded == it) null else it }, wifiOnly, onDelete = { toDelete = it })
    }
    if (others.isNotEmpty()) {
        SectionTitle("Autres modèles")
        ModelList(others, engine, downloads, expanded, { expanded = if (expanded == it) null else it }, wifiOnly, onDelete = { toDelete = it })
    }

    DictoCard(padding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
        SettingRow(
            "Wi-Fi seulement",
            if (installed.isEmpty()) "Aucun modèle installé" else "${installed.size} installé(s) · ${size(installed.sumOf { it.sizeBytes.toLong() })} utilisés",
        ) { DictoSwitch(wifiOnly, app.settings::setWifiOnly, "Télécharger en Wi-Fi seulement") }
    }

    toDelete?.let { m ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Supprimer ${m.name} ?") },
            text = { Text("${size(m.sizeBytes.toLong())} seront libérés. Tu pourras le retélécharger.") },
            confirmButton = {
                TextButton(onClick = {
                    if (engine == EngineChoice.Local(m.id)) app.settings.setEngine(null)
                    runCatching { app.models.delete(m.id) }
                    toDelete = null
                    refresh++
                }) { Text("Supprimer", color = Dicto.OnRecordingContainer) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Annuler") } },
        )
    }
}

@Composable
private fun ModelList(
    models: List<ModelInfo>,
    engine: EngineChoice?,
    downloads: Map<String, DownloadState>,
    expanded: String?,
    onToggle: (String) -> Unit,
    wifiOnly: Boolean,
    onDelete: (ModelInfo) -> Unit,
) {
    val app = DictoApp.instance
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (m in models) {
            ModelCard(
                model = m,
                inUse = engine == EngineChoice.Local(m.id),
                download = downloads[m.id],
                expanded = expanded == m.id,
                onToggle = { onToggle(m.id) },
                onGet = { ModelDownloads.start(context, m.id, wifiOnly) },
                onCancel = { ModelDownloads.cancel(context, m.id) },
                onUse = { app.settings.setEngine(EngineChoice.Local(m.id)) },
                onDelete = { onDelete(m) },
            )
        }
    }
}

/**
 * Carte d'un modèle : qui l'a fait, ses langues, sa rapidité et sa précision d'un coup d'œil,
 * et l'action à droite (télécharger avec la taille, progression, utiliser, en service).
 * Le reste (description, licence, suppression) s'ouvre au toucher.
 */
@Composable
private fun ModelCard(
    model: ModelInfo,
    inUse: Boolean,
    download: DownloadState?,
    expanded: Boolean,
    onToggle: () -> Unit,
    onGet: () -> Unit,
    onCancel: () -> Unit,
    onUse: () -> Unit,
    onDelete: () -> Unit,
) {
    val wifiOnlyHint by DictoApp.instance.settings.wifiOnly.collectAsStateWithLifecycle()
    val total = model.sizeBytes.toLong()
    val active = download != null && (download.running || download.queued)
    val done = maxOf(download?.downloaded ?: 0, model.downloadedBytes.toLong())
    val fraction = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
    val maker = makerOf(model)

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(if (inUse) Dicto.AccentContainer.copy(alpha = 0.45f) else Dicto.Surface)
            .border(if (inUse) 1.5.dp else 1.dp, if (inUse) Dicto.Accent else Dicto.SurfaceSunken, RoundedCornerShape(20.dp))
            .clickable(onClickLabel = if (expanded) "Masquer les détails" else "Voir les détails", onClick = onToggle)
            .animateContentSize()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MakerBadge(maker)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(model.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        active && download?.queued == true -> "En attente du réseau${if (wifiOnlyHint) " (Wi-Fi)" else ""}…"
                        active -> "${(fraction * 100).toInt()} % · ${size(done)} sur ${size(total)}"
                        download?.failed != null -> "Échec, touche ↓ pour réessayer"
                        inUse -> "En service · ${languagesLabel(model)}"
                        model.installed -> "Installé · ${languagesLabel(model)}"
                        done > 0 -> "Interrompu à ${(fraction * 100).toInt()} %"
                        else -> listOf(maker.name, languagesLabel(model)).filter { it.isNotEmpty() }.joinToString(" · ")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        download?.failed != null -> Dicto.OnRecordingContainer
                        inUse -> Dicto.Accent
                        else -> Dicto.InkMuted
                    },
                    fontWeight = if (inUse) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            ModelAction(model, inUse, active, download?.queued == true, fraction, onGet, onCancel, onUse)
        }

        // L'essentiel pour choisir, sans ouvrir la carte.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            MiniMeter("Rapide", model.speed.toInt())
            MiniMeter("Précis", model.accuracy.toInt())
            Spacer(Modifier.weight(1f))
            // Streaming natif, ou modèle rapide retranscrit en continu (réglage « Texte en direct »).
            val livePreview by DictoApp.instance.settings.livePreview.flow.collectAsStateWithLifecycle()
            if (model.streaming || (livePreview && model.livePreview)) {
                MiniPill("En direct", Dicto.RecordingContainer, Dicto.OnRecordingContainer)
            }
        }

        AnimatedVisibility(expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HorizontalDivider(color = Dicto.SurfaceSunken)
                Text(model.description, style = MaterialTheme.typography.bodySmall, color = Dicto.InkSecondary)
                Text(
                    listOf(
                        languagesLabel(model, full = true),
                        if (model.punctuation) "ponctuation et majuscules" else "sans ponctuation",
                        if (model.engine == "sherpa") "sherpa-onnx" else "whisper.cpp",
                        "licence ${model.license}",
                        size(total),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = Dicto.InkMuted,
                )
                if (model.installed) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!inUse) TonalButton("Utiliser", onUse, icon = DictoIcons.Check)
                        Spacer(Modifier.weight(1f))
                        GhostButton("Supprimer", DictoIcons.Trash, onDelete, tint = Dicto.OnRecordingContainer)
                    }
                }
            }
        }
    }
}

/** Qui a créé le modèle : son nom et sa pastille. */
private class Maker(val name: String, val mark: String, val bg: Color, val fg: Color, val logo: Brand? = null)

private fun makerOf(m: ModelInfo): Maker = when {
    m.id.startsWith("whisper") -> Maker("OpenAI", "", Dicto.Background, Dicto.Ink, logo = Brand.OPENAI)
    m.id.startsWith("canary") || m.id.startsWith("parakeet") || m.id.startsWith("nemotron") ->
        Maker("NVIDIA", "", Dicto.Background, Dicto.Ink, logo = Brand.NVIDIA)
    m.id.startsWith("omnilingual") -> Maker("Meta", "", Dicto.Background, Dicto.Ink, logo = Brand.META)
    else -> Maker("", "?", Dicto.Background, Dicto.InkSecondary)
}

@Composable
private fun MakerBadge(maker: Maker) {
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).background(maker.bg),
        contentAlignment = Alignment.Center,
    ) {
        if (maker.logo != null) {
            ProviderLogo(maker.logo, size = 24.dp, tint = maker.fg)
        } else {
            Text(maker.mark, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = maker.fg)
        }
    }
}

/** Bouton d'action à droite de la carte. */
@Composable
private fun ModelAction(
    model: ModelInfo,
    inUse: Boolean,
    active: Boolean,
    queued: Boolean,
    fraction: Float,
    onGet: () -> Unit,
    onCancel: () -> Unit,
    onUse: () -> Unit,
) {
    when {
        inUse -> Box(
            Modifier.size(36.dp).clip(CircleShape).background(Dicto.Accent).semantics { contentDescription = "En service" },
            contentAlignment = Alignment.Center,
        ) { Icon(DictoIcons.Check, null, tint = Dicto.OnAccent, modifier = Modifier.size(18.dp)) }

        active -> Surface(
            onClick = onCancel, shape = CircleShape, color = Color.Transparent, modifier = Modifier.size(44.dp)
                .semantics { contentDescription = "Annuler le téléchargement de ${model.name}" },
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (queued) CircularProgressIndicator(Modifier.size(40.dp), color = Dicto.Accent, trackColor = Dicto.SurfaceSunken, strokeWidth = 3.dp)
                else CircularProgressIndicator(progress = { fraction }, modifier = Modifier.size(40.dp), color = Dicto.Accent, trackColor = Dicto.SurfaceSunken, strokeWidth = 3.dp)
                Icon(DictoIcons.Close, null, tint = Dicto.InkSecondary, modifier = Modifier.size(16.dp))
            }
        }

        model.installed -> Surface(
            onClick = onUse, shape = RoundedCornerShape(18.dp), color = Dicto.Accent, contentColor = Dicto.OnAccent,
            modifier = Modifier.heightIn(min = 36.dp),
        ) {
            Box(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                Text("Utiliser", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            }
        }

        // Télécharger : la taille est sur le bouton, on sait ce qu'on télécharge.
        else -> Surface(
            onClick = onGet, shape = RoundedCornerShape(18.dp), color = Dicto.AccentContainer, contentColor = Dicto.Accent,
            modifier = Modifier.heightIn(min = 36.dp)
                .semantics { contentDescription = "Télécharger ${model.name}, ${size(model.sizeBytes.toLong())}" },
        ) {
            Row(
                Modifier.padding(start = 10.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(DictoIcons.Download, null, modifier = Modifier.size(16.dp))
                Text(size(model.sizeBytes.toLong()), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
    }
}

/** Petite jauge en quatre traits, avec son libellé. */
@Composable
private fun MiniMeter(label: String, value: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "$label : $value sur 4" },
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Dicto.InkMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            repeat(4) { i ->
                Box(Modifier.width(10.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(if (i < value) Dicto.Accent else Dicto.SurfaceSunken))
            }
        }
    }
}

@Composable
private fun MiniPill(text: String, bg: Color, fg: Color) {
    Box(Modifier.clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 6.dp, vertical = 1.dp)) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = fg, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun OnlineServices() {
    Text(
        "L'audio est envoyé au service choisi. Le service activé remplace le modèle local ; la roue dentée règle la clé, " +
            "l'adresse et le modèle. Les clés restent chiffrées sur l'appareil (Android Keystore).",
        style = MaterialTheme.typography.bodySmall,
        color = Dicto.InkMuted,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
    TranscriptionProviders()
    DictoCard {
        Text("Claude, Ollama et la retouche", style = MaterialTheme.typography.titleSmall)
        Text(
            "Claude, OpenAI, Gemini et Ollama peuvent retoucher le texte (ponctuation, style, traduction) : " +
                "Réglages › Retouche par IA. Claude ne transcrit pas l'audio lui-même.",
            style = MaterialTheme.typography.bodySmall,
            color = Dicto.InkMuted,
        )
    }
}

/** Langues, avec le nombre réel pour les modèles « multilingues ». */
private fun languagesLabel(m: ModelInfo, full: Boolean = false): String {
    val codes = m.languages
    if (codes.isEmpty() || codes == listOf("multi")) {
        return when {
            m.id.startsWith("whisper") -> "99 langues"
            m.id.startsWith("omnilingual") -> "1 600+ langues"
            m.id.startsWith("nemotron") -> "36 langues"
            else -> "multilingue"
        }
    }
    return when {
        codes.size > 4 && !full -> "${codes.size} langues"
        codes.size > 6 -> "${codes.size} langues dont ${codes.take(4).joinToString(", ") { it.uppercase() }}"
        else -> codes.joinToString(", ") { it.uppercase() }
    }
}

private fun size(bytes: Long): String = Formatter.formatShortFileSize(DictoApp.instance, bytes)
