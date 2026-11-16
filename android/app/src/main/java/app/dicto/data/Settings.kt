package app.dicto.data

import app.dicto.i18n.tr
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uniffi.dicto_ffi.CloudProvider
import uniffi.dicto_ffi.Mode
import uniffi.dicto_ffi.RefineProvider
import uniffi.dicto_ffi.Storage

/** Moteur choisi pour la dictée. */
sealed interface EngineChoice {
    val key: String

    data class Local(val modelId: String) : EngineChoice {
        override val key get() = "local:$modelId"
    }

    data class Cloud(val provider: CloudProvider) : EngineChoice {
        override val key get() = "cloud:${provider.slug}"
    }

    companion object {
        fun parse(value: String?): EngineChoice? = when {
            value == null -> null
            value.startsWith("local:") -> Local(value.removePrefix("local:"))
            value.startsWith("cloud:") ->
                CloudProvider.entries.firstOrNull { it.slug == value.removePrefix("cloud:") }?.let(::Cloud)
            else -> null
        }
    }
}

val CloudProvider.slug: String
    get() = when (this) {
        CloudProvider.OPEN_AI -> "openai"
        CloudProvider.MISTRAL -> "mistral"
        CloudProvider.GEMINI -> "gemini"
        CloudProvider.XAI -> "xai"
        CloudProvider.CUSTOM -> "custom"
    }

val CloudProvider.label: String
    get() = when (this) {
        CloudProvider.OPEN_AI -> "OpenAI"
        CloudProvider.MISTRAL -> "Mistral"
        CloudProvider.GEMINI -> "Google Gemini"
        CloudProvider.XAI -> "Grok (xAI)"
        CloudProvider.CUSTOM -> app.dicto.i18n.tr("Mon serveur")
    }

val RefineProvider.slug: String
    get() = when (this) {
        RefineProvider.ANTHROPIC -> "anthropic"
        RefineProvider.OPEN_AI -> "openai"
        RefineProvider.GEMINI -> "gemini"
        RefineProvider.OLLAMA -> "ollama"
        RefineProvider.OPEN_AI_COMPATIBLE -> "compat"
    }

val RefineProvider.label: String
    get() = when (this) {
        RefineProvider.ANTHROPIC -> "Claude"
        RefineProvider.OPEN_AI -> "OpenAI"
        RefineProvider.GEMINI -> "Gemini"
        RefineProvider.OLLAMA -> "Ollama"
        RefineProvider.OPEN_AI_COMPATIBLE -> app.dicto.i18n.tr("Serveur")
    }

/** Clé d'API d'un fournisseur de retouche (partagée avec la transcription quand c'est le même compte). */
fun refineSecretKey(p: RefineProvider): String? = when (p) {
    RefineProvider.ANTHROPIC -> "key.anthropic"
    RefineProvider.OPEN_AI -> secretKeyFor(CloudProvider.OPEN_AI)
    RefineProvider.GEMINI -> secretKeyFor(CloudProvider.GEMINI)
    RefineProvider.OLLAMA -> null
    RefineProvider.OPEN_AI_COMPATIBLE -> "key.refine.compat"
}

/** Modèle proposé par défaut pour un fournisseur de retouche (aucun pour un serveur personnel). */
fun defaultRefineModelOrNull(p: RefineProvider): String? = uniffi.dicto_ffi.defaultRefineModel(p).ifBlank { null }

val RefineProvider.needsUrl get() = this == RefineProvider.OLLAMA || this == RefineProvider.OPEN_AI_COMPATIBLE

/** Langues proposées (code ISO 639-1, `null` = détection automatique). */
val LANGUAGES: List<Pair<String?, String>> = listOf(
    null to "Auto",
    "fr" to "Français",
    "en" to "English",
    "es" to "Español",
    "de" to "Deutsch",
    "it" to "Italiano",
    "pt" to "Português",
    "nl" to "Nederlands",
)

data class CloudConfig(val model: String?, val baseUrl: String?)

/** Réglages persistés dans la base SQLite du cœur, exposés en flux pour l'interface. */
class Settings(private val storage: Storage) {
    private val _mode = MutableStateFlow(readMode())
    val mode: StateFlow<Mode> = _mode.asStateFlow()

    private val _engine = MutableStateFlow(EngineChoice.parse(storage.setting(KEY_ENGINE)))
    val engine: StateFlow<EngineChoice?> = _engine.asStateFlow()

    private val _language = MutableStateFlow(storage.setting(KEY_LANGUAGE)?.takeIf { it.isNotBlank() })
    val language: StateFlow<String?> = _language.asStateFlow()

    private val _voiceCommands = MutableStateFlow(storage.setting(KEY_VOICE_COMMANDS) != "false")
    val voiceCommands: StateFlow<Boolean> = _voiceCommands.asStateFlow()

    private val _wifiOnly = MutableStateFlow(storage.setting(KEY_WIFI_ONLY) != "false")
    val wifiOnly: StateFlow<Boolean> = _wifiOnly.asStateFlow()

    /** Déclencheurs hors de l'app. */
    val bubble = flag("trigger.bubble", false)
    val edgeSwipe = flag("trigger.edge", false)
    val wakeWord = flag("trigger.wake", false)
    val wakePhrase = text("trigger.wake.phrase", "OK DICTO")

    /** Retouche par IA : fournisseur (`null` = désactivée), modèle, adresse, consigne. */
    private val _refineProvider = MutableStateFlow(
        storage.setting("refine.provider")?.let { v -> RefineProvider.entries.firstOrNull { it.slug == v } },
    )
    val refineProvider: StateFlow<RefineProvider?> = _refineProvider.asStateFlow()
    /** Modèle et adresse propres à chaque fournisseur de retouche. */
    private val _refineConfigs = MutableStateFlow(RefineProvider.entries.associateWith(::readRefine))
    val refineConfigs: StateFlow<Map<RefineProvider, CloudConfig>> = _refineConfigs.asStateFlow()

    fun setRefineConfig(p: RefineProvider, config: CloudConfig) {
        storage.setSetting("refine.${p.slug}.model", config.model.orEmpty())
        storage.setSetting("refine.${p.slug}.url", config.baseUrl.orEmpty())
        _refineConfigs.value = _refineConfigs.value + (p to config)
    }

    private fun readRefine(p: RefineProvider) = CloudConfig(
        model = storage.setting("refine.${p.slug}.model")?.takeIf { it.isNotBlank() },
        baseUrl = storage.setting("refine.${p.slug}.url")?.takeIf { it.isNotBlank() },
    )
    /** Identifiant d'un préréglage ou « custom ». */
    val refinePreset = text("refine.preset", "clean")
    val refineCustom = text("refine.custom", "")
    val autoRefine = flag("refine.auto", false)
    /** Texte provisoire pendant qu'on parle, pour les modèles qui transcrivent une phrase entière. */
    val livePreview = flag("dictation.live_preview", true)
    /** Clavier Dicto : écoute dès qu'il s'ouvre. */
    val imeAutoStart = flag("ime.auto_start", true)
    /** Clavier Dicto : remet le clavier précédent une fois la dictée écrite. */
    val imeAutoReturn = flag("ime.auto_return", true)
    /** Langue de l'interface : « auto » (celle du téléphone), « fr » ou « en ». */
    val appLanguage = text("ui.language", "auto")
    /** Thème : « auto » (celui du téléphone), « light » ou « dark ». */
    val theme = text("ui.theme", "auto")

    fun setRefineProvider(p: RefineProvider?) {
        storage.setSetting("refine.provider", p?.slug.orEmpty())
        _refineProvider.value = p
    }

    private val _cloud = MutableStateFlow(CloudProvider.entries.associateWith(::readCloud))
    val cloud: StateFlow<Map<CloudProvider, CloudConfig>> = _cloud.asStateFlow()

    fun setMode(mode: Mode) {
        storage.setSetting(KEY_MODE, mode.name)
        _mode.value = mode
    }

    fun setEngine(choice: EngineChoice?) {
        storage.setSetting(KEY_ENGINE, choice?.key.orEmpty())
        _engine.value = choice
    }

    fun setLanguage(code: String?) {
        storage.setSetting(KEY_LANGUAGE, code.orEmpty())
        _language.value = code
    }

    fun setVoiceCommands(enabled: Boolean) {
        storage.setSetting(KEY_VOICE_COMMANDS, enabled.toString())
        _voiceCommands.value = enabled
    }

    fun setWifiOnly(enabled: Boolean) {
        storage.setSetting(KEY_WIFI_ONLY, enabled.toString())
        _wifiOnly.value = enabled
    }

    fun setCloud(provider: CloudProvider, config: CloudConfig) {
        storage.setSetting("cloud.${provider.slug}.model", config.model.orEmpty())
        storage.setSetting("cloud.${provider.slug}.url", config.baseUrl.orEmpty())
        _cloud.value = _cloud.value + (provider to config)
    }

    /** Réglage booléen observable. */
    inner class Flag(private val key: String, default: Boolean) {
        private val state = MutableStateFlow(storage.setting(key)?.let { it == "true" } ?: default)
        val flow: StateFlow<Boolean> = state.asStateFlow()
        val value get() = state.value
        fun set(enabled: Boolean) {
            storage.setSetting(key, enabled.toString())
            state.value = enabled
        }
    }

    inner class Text(private val key: String, default: String) {
        private val state = MutableStateFlow(storage.setting(key)?.takeIf { it.isNotBlank() } ?: default)
        val flow: StateFlow<String> = state.asStateFlow()
        val value get() = state.value
        fun set(text: String) {
            storage.setSetting(key, text)
            state.value = text
        }
    }

    private fun flag(key: String, default: Boolean) = Flag(key, default)
    private fun text(key: String, default: String) = Text(key, default)

    private fun readMode(): Mode =
        storage.setting(KEY_MODE)?.let { v -> Mode.entries.firstOrNull { it.name == v } } ?: Mode.PUSH_TO_TALK

    private fun readCloud(p: CloudProvider) = CloudConfig(
        model = storage.setting("cloud.${p.slug}.model")?.takeIf { it.isNotBlank() },
        baseUrl = storage.setting("cloud.${p.slug}.url")?.takeIf { it.isNotBlank() },
    )

    private companion object {
        const val KEY_MODE = "mode"
        const val KEY_ENGINE = "engine"
        const val KEY_LANGUAGE = "language"
        const val KEY_VOICE_COMMANDS = "voice_commands"
        const val KEY_WIFI_ONLY = "models.wifi_only"
    }
}

fun secretKeyFor(provider: CloudProvider) = "key.${provider.slug}"
