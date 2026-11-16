package app.dicto.ui.providers

import app.dicto.i18n.tr
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dicto.DictoApp
import app.dicto.data.CloudConfig
import app.dicto.data.EngineChoice
import app.dicto.data.defaultRefineModelOrNull
import app.dicto.data.label
import app.dicto.data.refineSecretKey
import app.dicto.data.secretKeyFor
import app.dicto.ui.DictoCard
import app.dicto.ui.theme.Dicto
import uniffi.dicto_ffi.CloudProvider
import uniffi.dicto_ffi.RefineClient
import uniffi.dicto_ffi.RefineProvider
import uniffi.dicto_ffi.listCloudModels

private val CloudProvider.brand
    get() = when (this) {
        CloudProvider.OPEN_AI -> Brand.OPENAI
        CloudProvider.MISTRAL -> Brand.MISTRAL
        CloudProvider.GEMINI -> Brand.GEMINI
        CloudProvider.XAI -> Brand.XAI
        CloudProvider.CUSTOM -> Brand.SERVER
    }

private val RefineProvider.brand
    get() = when (this) {
        RefineProvider.ANTHROPIC -> Brand.CLAUDE
        RefineProvider.OPEN_AI -> Brand.OPENAI
        RefineProvider.GEMINI -> Brand.GEMINI
        RefineProvider.OLLAMA -> Brand.OLLAMA
        RefineProvider.OPEN_AI_COMPATIBLE -> Brand.SERVER
    }

/** Un service de transcription en ligne, décrit pour la liste et le panneau de réglage. */
fun transcriptionSpec(p: CloudProvider): ProviderSpec {
    val app = DictoApp.instance
    val secret = secretKeyFor(p)
    return ProviderSpec(
        brand = p.brand,
        label = p.label,
        needsKey = p != CloudProvider.CUSTOM,
        keyHint = when (p) {
            CloudProvider.OPEN_AI -> "sk-…"
            CloudProvider.GEMINI -> "AIza…"
            CloudProvider.XAI -> "xai-…"
            else -> tr("clé")
        },
        urlPlaceholder = if (p == CloudProvider.CUSTOM) tr("http://IP-du-serveur:8000/v1") else null,
        urlHint = if (p == CloudProvider.CUSTOM) tr("Serveur compatible OpenAI : speaches, faster-whisper-server, LocalAI…") else null,
        savedKey = { app.secrets.has(secret) },
        savedModel = { app.settings.cloud.value[p]?.model },
        savedUrl = { app.settings.cloud.value[p]?.baseUrl },
        loadModels = { key, url -> listCloudModels(p, url ?: app.settings.cloud.value[p]?.baseUrl, key ?: app.secrets.get(secret)) },
        save = { key, model, url ->
            if (key != null) app.secrets.set(secret, key)
            app.settings.setCloud(p, CloudConfig(model.ifBlank { null }, url))
            // Une nouvelle clé seule ne change pas les réglages observés : on recharge explicitement.
            if (app.settings.engine.value == EngineChoice.Cloud(p)) app.dictation.refreshEngine(force = true)
        },
        forgetKey = {
            app.secrets.set(secret, null)
            if (app.settings.engine.value == EngineChoice.Cloud(p)) app.settings.setEngine(null)
        },
    )
}

/** Un fournisseur de retouche par IA. */
fun refineSpec(p: RefineProvider): ProviderSpec {
    val app = DictoApp.instance
    val secret = refineSecretKey(p)
    return ProviderSpec(
        brand = p.brand,
        label = when (p) {
            RefineProvider.ANTHROPIC -> "Claude (Anthropic)"
            RefineProvider.OLLAMA -> tr("Local (Ollama, LM Studio…)")
            RefineProvider.OPEN_AI_COMPATIBLE -> tr("Autre (compatible OpenAI)")
            else -> p.label
        },
        needsKey = p == RefineProvider.ANTHROPIC || p == RefineProvider.OPEN_AI || p == RefineProvider.GEMINI,
        keyHint = when (p) {
            RefineProvider.ANTHROPIC -> "sk-ant-…"
            RefineProvider.OPEN_AI -> "sk-…"
            RefineProvider.GEMINI -> "AIza…"
            else -> tr("clé")
        },
        urlPlaceholder = when (p) {
            RefineProvider.OLLAMA -> tr("http://IP-du-PC:11434")
            RefineProvider.OPEN_AI_COMPATIBLE -> tr("http://IP-du-PC:1234/v1")
            else -> null
        },
        urlHint = when (p) {
            RefineProvider.OLLAMA -> tr("Ollama : lancer avec OLLAMA_HOST=0.0.0.0 pour l'atteindre depuis le téléphone.")
            RefineProvider.OPEN_AI_COMPATIBLE -> tr("LM Studio, llama.cpp server, vLLM… (adresse se terminant par /v1).")
            else -> null
        },
        chatFilters = true,
        savedKey = { secret != null && app.secrets.has(secret) },
        savedModel = { app.settings.refineConfigs.value[p]?.model },
        savedUrl = { app.settings.refineConfigs.value[p]?.baseUrl },
        loadModels = { key, url ->
            val cfg = app.settings.refineConfigs.value[p]
            RefineClient(p, url ?: cfg?.baseUrl, key ?: secret?.let { app.secrets.get(it) }, null).listModels()
        },
        save = { key, model, url ->
            if (key != null && secret != null) app.secrets.set(secret, key)
            app.settings.setRefineConfig(p, CloudConfig(model.ifBlank { null }, url))
            app.dictation.applyOptions()
        },
        forgetKey = {
            secret?.let { app.secrets.set(it, null) }
            if (app.settings.refineProvider.value == p) app.settings.setRefineProvider(null)
            app.dictation.applyOptions()
        },
    )
}

/**
 * Services de transcription en ligne. Un seul moteur sert à la fois : activer un service
 * le met en service (et remplace le modèle local).
 */
@Composable
fun TranscriptionProviders() {
    val app = DictoApp.instance
    val engine by app.settings.engine.collectAsStateWithLifecycle()
    var revision by remember { mutableIntStateOf(0) }
    var configuring by remember { mutableStateOf<CloudProvider?>(null) }
    var enableAfter by remember { mutableStateOf(false) }

    DictoCard(padding = PaddingValues(0.dp)) {
        Column {
            CloudProvider.entries.forEachIndexed { i, p ->
                if (i > 0) HorizontalDivider(color = Dicto.SurfaceSunken)
                val spec = remember(p) { transcriptionSpec(p) }
                val configured = remember(p, revision) { spec.isConfigured() }
                val on = engine == EngineChoice.Cloud(p) && configured
                ProviderRow(
                    spec, on, badge = if (on) tr("En service") else null, revision = revision,
                    onToggle = { want ->
                        when {
                            want && !configured -> { enableAfter = true; configuring = p }
                            want -> app.settings.setEngine(EngineChoice.Cloud(p))
                            else -> app.settings.setEngine(null)
                        }
                    },
                    onConfigure = { enableAfter = false; configuring = p },
                )
            }
        }
    }
    configuring?.let { p ->
        ProviderSheet(
            remember(p) { transcriptionSpec(p) },
            onSaved = { ok -> if (enableAfter && ok) app.settings.setEngine(EngineChoice.Cloud(p)) },
            onDismiss = { configuring = null; enableAfter = false; revision++ },
        )
    }
}

/** Fournisseurs de retouche par IA : un seul actif à la fois. */
@Composable
fun RefineProviders() {
    val app = DictoApp.instance
    val active by app.settings.refineProvider.collectAsStateWithLifecycle()
    var revision by remember { mutableIntStateOf(0) }
    var configuring by remember { mutableStateOf<RefineProvider?>(null) }
    var enableAfter by remember { mutableStateOf(false) }

    fun activate(p: RefineProvider) {
        app.settings.setRefineProvider(p)
        app.dictation.applyOptions()
    }

    DictoCard(padding = PaddingValues(0.dp)) {
        Column {
            RefineProvider.entries.forEachIndexed { i, p ->
                if (i > 0) HorizontalDivider(color = Dicto.SurfaceSunken)
                val spec = remember(p) { refineSpec(p) }
                val configured = remember(p, revision) { spec.isConfigured() }
                val on = active == p && configured
                ProviderRow(
                    spec, on, badge = if (on) tr("Actif") else null, revision = revision,
                    onToggle = { want ->
                        when {
                            want && !configured -> {
                                // Modèle proposé par défaut, modifiable dans le panneau.
                                if (spec.savedModel().isNullOrBlank()) defaultRefineModelOrNull(p)?.let {
                                    app.settings.setRefineConfig(p, CloudConfig(it, spec.savedUrl()))
                                }
                                enableAfter = true
                                configuring = p
                            }
                            want -> activate(p)
                            else -> { app.settings.setRefineProvider(null); app.dictation.applyOptions() }
                        }
                    },
                    onConfigure = {
                        if (spec.savedModel().isNullOrBlank()) defaultRefineModelOrNull(p)?.let {
                            app.settings.setRefineConfig(p, CloudConfig(it, spec.savedUrl()))
                        }
                        enableAfter = false
                        configuring = p
                    },
                )
            }
        }
    }
    configuring?.let { p ->
        ProviderSheet(
            remember(p) { refineSpec(p) },
            onSaved = { ok -> if (enableAfter && ok) activate(p) },
            onDismiss = { configuring = null; enableAfter = false; revision++ },
        )
    }
}

