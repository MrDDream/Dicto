package app.dicto.dictation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import app.dicto.DictoApp
import app.dicto.data.EngineChoice
import app.dicto.data.label
import app.dicto.data.refineSecretKey
import app.dicto.data.secretKeyFor
import app.dicto.ime.KeyboardLink
import app.dicto.triggers.WakeWordService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uniffi.dicto_ffi.DictationListener
import uniffi.dicto_ffi.DictationResult
import uniffi.dicto_ffi.Dictation
import uniffi.dicto_ffi.DiscardReason
import uniffi.dicto_ffi.Engine
import uniffi.dicto_ffi.FfiException
import uniffi.dicto_ffi.Mode
import uniffi.dicto_ffi.RefineClient
import uniffi.dicto_ffi.State
import uniffi.dicto_ffi.refinePresets
import uniffi.dicto_ffi.TextEdit
import uniffi.dicto_ffi.renderEdits

/** Destination du texte dicté autre que l'écran de Dicto (le clavier vocal, plus tard la bulle). */
interface DictationTarget {
    /** App qui reçoit le texte (pour l'historique et les profils). */
    val targetApp: String?

    /** Appelé sur le thread principal. */
    fun deliver(result: DictationResult)
}

data class EngineStatus(val kind: Kind, val label: String, val detail: String? = null) {
    enum class Kind { NONE, LOADING, READY, ERROR }

    val isReady get() = kind == Kind.READY
}

data class DictationUi(
    val mode: Mode = Mode.PUSH_TO_TALK,
    val state: State = State.IDLE,
    /** Début de l'écoute (horloge `elapsedRealtime`), pour le chronomètre. */
    val startedAt: Long = 0,
    /** Énoncés en cours de transcription. */
    val pending: Int = 0,
    /** Morceaux de texte dictés dans l'écran de Dicto. */
    val chunks: List<String> = emptyList(),
    val message: String? = null,
    /** Texte provisoire pendant qu'on parle (modèles « en direct »). */
    val partial: String = "",
    val engine: EngineStatus = EngineStatus(EngineStatus.Kind.NONE, "Aucun moteur"),
) {
    val isActive get() = state != State.IDLE
    val transcript get() = joinChunks(chunks)
}

/** Ajoute un résultat à une liste de morceaux de texte (« efface ça » retire le dernier). */
fun applyEdits(chunks: List<String>, edits: List<TextEdit>): List<String> {
    val out = chunks.toMutableList()
    val rest = mutableListOf<TextEdit>()
    for (e in edits) {
        if (e is TextEdit.DeletePrevious && rest.isEmpty()) out.removeLastOrNull() else rest += e
    }
    val text = renderEdits(rest)
    if (text.isNotBlank()) out += text
    return out
}

fun joinChunks(chunks: List<String>): String = buildString {
    for (c in chunks) {
        if (isNotEmpty() && !endsWith("\n") && !c.startsWith("\n")) append(' ')
        append(c)
    }
}

class DictationController(private val context: Context) : DictationListener {
    private val app get() = DictoApp.instance
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())

    private val _ui = MutableStateFlow(DictationUi())
    val ui: StateFlow<DictationUi> = _ui.asStateFlow()

    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    private lateinit var dictation: Dictation
    private val recorder = MicRecorder(
        onAudio = { dictation.pushAudio(it) },
        onLevel = { _level.value = it },
    )

    @Volatile var target: DictationTarget? = null

    @Volatile var isCapturing = false
        private set

    private var engineJob: Job? = null
    @Volatile private var loadedEngine: Engine? = null

    /** Moteur chargé (aussi utilisé pour les fichiers audio), ou `null` s'il n'est pas prêt. */
    fun currentEngine(): Engine? = loadedEngine?.takeIf { _ui.value.engine.isReady }
    private var loadedEngineKey: String? = null

    fun init() {
        val settings = app.settings
        dictation = Dictation(settings.mode.value, this)
        _ui.update { it.copy(mode = settings.mode.value) }
        applyOptions()
        refreshEngine()
        setupSilero()
        // Les réglages qui changent le moteur ou les options sont appliqués au fil de l'eau.
        scope.launch {
            combine(settings.engine, settings.cloud) { e, c -> e to c }
                .distinctUntilChanged().drop(1).collect { refreshEngine(force = true) }
        }
        scope.launch { settings.livePreview.flow.collect { dictation.setLivePreview(it) } }
        scope.launch {
            combine(settings.language, settings.voiceCommands) { l, v -> l to v }
                .distinctUntilChanged().drop(1).collect { applyOptions() }
        }
    }

    @Volatile private var sileroPath: String? = null
    @Volatile private var sileroApplied = false

    /** Détection de voix Silero (212 Ko) pour le mode continu, téléchargée une fois. */
    private fun setupSilero() = scope.launch(Dispatchers.IO) {
        try {
            val dir = app.models.supportPath(SILERO) ?: app.models.downloadSupport(
                SILERO,
                object : uniffi.dicto_ffi.DownloadListener {
                    override fun onProgress(downloaded: ULong, total: ULong) = Unit
                },
            )
            sileroPath = "$dir/silero_vad.int8.onnx"
            applySilero()
        } catch (e: Exception) {
            Log.w(TAG, "Silero indisponible, détecteur à énergie conservé", e)
        }
    }

    private fun applySilero() {
        val path = sileroPath ?: return
        if (sileroApplied) return
        sileroApplied = runCatching { dictation.useSilero(path) }.getOrDefault(false)
    }

    // --- Commandes depuis l'interface ------------------------------------------------

    fun setMode(mode: Mode) {
        app.settings.setMode(mode)
        dictation.setMode(mode)
        _ui.update { it.copy(mode = mode) }
    }

    /** Doigt posé / touche enfoncée. Le cœur décide selon le mode (bascule, maintien…). */
    fun press() {
        if (!hasMicPermission()) {
            showMessage("Autorise le micro pour dicter.")
            return
        }
        if (!_ui.value.engine.isReady && !_ui.value.isActive) {
            showMessage(
                when (_ui.value.engine.kind) {
                    EngineStatus.Kind.LOADING -> "Le modèle se charge, encore un instant…"
                    else -> "Choisis un modèle ou un service dans l'onglet Modèles."
                },
            )
            return
        }
        if (!_ui.value.isActive) applyContext(target?.targetApp ?: KeyboardLink.lastApp)
        dictation.trigger(uniffi.dicto_ffi.Trigger.PRESSED)
        if (!_ui.value.isActive && pendingStart()) restoreMode()
    }

    /** Vrai si la session n'a pas démarré (le mode temporaire doit être annulé). */
    private fun pendingStart() = dictation.state() == State.IDLE

    /**
     * Dictée dans un mode temporaire (appui long sur la bulle = maintenu, reconnaissance
     * pour une autre app = continu) sans toucher au réglage. Le mode habituel revient à la fin.
     */
    fun pressWithMode(mode: Mode) {
        if (!_ui.value.isActive && mode != dictation.mode()) {
            overrideMode = mode
            dictation.setMode(mode)
        }
        press()
        if (!_ui.value.isActive) restoreMode()
    }

    private var overrideMode: Mode? = null

    private fun restoreMode() {
        if (overrideMode != null) {
            overrideMode = null
            dictation.setMode(app.settings.mode.value)
        }
    }

    fun release() = dictation.trigger(uniffi.dicto_ffi.Trigger.RELEASED)

    fun cancel() = dictation.trigger(uniffi.dicto_ffi.Trigger.CANCEL)

    fun clearTranscript() = _ui.update { it.copy(chunks = emptyList()) }

    fun dismissMessage() = _ui.update { it.copy(message = null) }

    fun applyOptions() = applyContext(null)

    /**
     * Options de la prochaine dictée : réglages généraux, remplacés par le profil de l'app
     * qui recevra le texte (langue, retouche, mode).
     */
    private fun applyContext(appId: String?) {
        val s = app.settings
        val profile = appId?.let { runCatching { app.storage.profileForApp(it) }.getOrNull() }
        val vocabulary = runCatching { app.storage.vocabulary().map { it.term } }.getOrDefault(emptyList())
        val language = profile?.language?.takeIf { it.isNotBlank() } ?: s.language.value
        dictation.setOptions(language, vocabulary, s.voiceCommands.value)

        val presetId = profile?.refinePreset?.takeIf { it.isNotBlank() } ?: if (s.autoRefine.value) s.refinePreset.value else null
        val instruction = presetInstruction(presetId)
        dictation.setRefiner(instruction?.let { refineClient() }, instruction)

        val mode = profile?.mode?.let { m -> Mode.entries.firstOrNull { it.name == m } }
        if (mode != null && overrideMode == null && !_ui.value.isActive && mode != dictation.mode()) {
            overrideMode = mode
            dictation.setMode(mode)
        }
    }

    /** Client de retouche configuré, ou `null` si la retouche est désactivée. */
    fun refineClient(): RefineClient? {
        val s = app.settings
        val p = s.refineProvider.value ?: return null
        val key = refineSecretKey(p)?.let { app.secrets.get(it) }
        val cfg = s.refineConfigs.value[p]
        return RefineClient(p, cfg?.baseUrl, key, cfg?.model)
    }

    fun presetInstruction(id: String?): String? = when (id) {
        null, "", "none" -> null
        "custom" -> app.settings.refineCustom.value.ifBlank { null }
        else -> refinePresets().firstOrNull { it.id == id }?.instruction
    }

    /** « Reformuler » : retouche tout le texte de l'écran Dictée avec un préréglage. */
    suspend fun refineTranscript(presetId: String): Result<Unit> = runCatching {
        val client = refineClient() ?: throw IllegalStateException("configure la retouche dans les Réglages")
        val instruction = presetInstruction(presetId) ?: throw IllegalStateException("consigne vide")
        val text = _ui.value.transcript
        if (text.isBlank()) return@runCatching
        val refined = client.refine(instruction, text)
        if (refined.isNotBlank()) _ui.update { it.copy(chunks = listOf(refined)) }
    }

    /** (Re)charge le moteur choisi dans les réglages. Le chargement d'un modèle local prend quelques secondes. */
    fun refreshEngine(force: Boolean = false) {
        val choice = app.settings.engine.value
        if (choice == null) {
            setEngineStatus(EngineStatus(EngineStatus.Kind.NONE, "Aucun moteur"))
            loadedEngineKey = null
            return
        }
        if (!force && choice.key == loadedEngineKey && _ui.value.engine.isReady) return
        engineJob?.cancel()
        setEngineStatus(EngineStatus(EngineStatus.Kind.LOADING, engineLabel(choice), "chargement…"))
        engineJob = scope.launch(Dispatchers.IO) {
            try {
                val engine = buildEngine(choice)
                dictation.setEngine(engine)
                loadedEngine = engine
                loadedEngineKey = choice.key
                setEngineStatus(
                    EngineStatus(EngineStatus.Kind.READY, engineLabel(choice), if (engine.isLocal()) "local" else "en ligne"),
                )
            } catch (e: Exception) {
                Log.w(TAG, "moteur ${choice.key}", e)
                loadedEngineKey = null
                setEngineStatus(EngineStatus(EngineStatus.Kind.ERROR, engineLabel(choice), e.userMessage()))
            }
        }
    }

    private fun buildEngine(choice: EngineChoice): Engine = when (choice) {
        is EngineChoice.Local -> {
            if (!app.models.isInstalled(choice.modelId)) throw IllegalStateException("modèle non installé")
            app.models.createEngine(choice.modelId, false)
        }
        is EngineChoice.Cloud -> {
            val cfg = app.settings.cloud.value[choice.provider]
            val model = cfg?.model ?: throw IllegalStateException("aucun modèle choisi")
            Engine.cloud(choice.provider, cfg.baseUrl, app.secrets.get(secretKeyFor(choice.provider)), model)
        }
    }

    private fun engineLabel(choice: EngineChoice): String = when (choice) {
        is EngineChoice.Local -> app.models.models().firstOrNull { it.id == choice.modelId }?.name ?: choice.modelId
        is EngineChoice.Cloud -> {
            val model = app.settings.cloud.value[choice.provider]?.model
            if (model != null) "${choice.provider.label} · $model" else choice.provider.label
        }
    }

    // --- Rappels du cœur Rust (threads variés) -----------------------------------------

    override fun onCapture(active: Boolean) {
        if (active) {
            isCapturing = true
            WakeWordService.pause()
            DictationService.start(context)
            if (!recorder.start()) {
                isCapturing = false
                showMessage("Impossible d'ouvrir le micro.")
                dictation.trigger(uniffi.dicto_ffi.Trigger.CANCEL)
            }
        } else {
            isCapturing = false
            recorder.stop()
            DictationService.stop(context)
            WakeWordService.resume()
        }
    }

    /** Le service n'a pas pu passer au premier plan (permission ou restriction d'arrière-plan). */
    fun onServiceDenied(e: Exception) {
        Log.w(TAG, "service micro refusé", e)
        if (isCapturing) {
            showMessage("Android a refusé l'accès au micro en arrière-plan.")
            cancel()
        }
    }

    override fun onState(state: State) {
        if (state == State.IDLE) {
            restoreMode()
            applySilero()
            _ui.update { it.copy(partial = "") }
        }
        _ui.update {
            val starting = it.state == State.IDLE && state != State.IDLE
            it.copy(state = state, startedAt = if (starting) SystemClock.elapsedRealtime() else it.startedAt)
        }
    }

    override fun onPartial(text: String) {
        _ui.update { it.copy(partial = text) }
    }

    override fun onTranscribing(utteranceId: ULong) {
        _ui.update { it.copy(pending = it.pending + 1) }
    }

    /** Captures d'écran (tests seulement) : un état d'exemple, sans micro ni modèle. */
    @androidx.annotation.VisibleForTesting
    internal fun showSample(sample: DictationUi) {
        _ui.value = sample
    }

    override fun onResult(utteranceId: ULong, result: DictationResult) {
        _ui.update { it.copy(pending = (it.pending - 1).coerceAtLeast(0)) }
        result.warning?.let { showMessage(it) }
        val target = target
        runCatching { app.storage.addHistory(result, target?.targetApp ?: context.packageName) }
            .onFailure { Log.w(TAG, "historique", it) }
        main.post {
            val current = this.target
            if (current != null) current.deliver(result) else appendToTranscript(result.edits)
        }
    }

    override fun onDiscarded(reason: DiscardReason) {
        if (reason == DiscardReason.NO_SPEECH) {
            _ui.update { it.copy(pending = (it.pending - 1).coerceAtLeast(0)) }
        }
    }

    override fun onError(utteranceId: ULong, error: FfiException) {
        _ui.update { it.copy(pending = (it.pending - 1).coerceAtLeast(0)) }
        showMessage("Transcription impossible : ${error.userMessage()}")
    }

    // ------------------------------------------------------------------------------------

    private fun appendToTranscript(edits: List<TextEdit>) {
        _ui.update { ui -> ui.copy(chunks = applyEdits(ui.chunks, edits)) }
    }

    private fun setEngineStatus(status: EngineStatus) = _ui.update { it.copy(engine = status) }

    private fun showMessage(text: String) = _ui.update { it.copy(message = text) }

    private fun hasMicPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "DictoController"
        const val SILERO = "silero-vad"
    }
}

/** Message lisible pour une erreur du cœur ou de Kotlin. */
fun Throwable.userMessage(): String = when (this) {
    is FfiException.Network -> "réseau indisponible ($detail)"
    is FfiException.Api -> when (status.toInt()) {
        401, 403 -> "$provider refuse la clé d'API"
        429 -> "$provider : quota dépassé, réessaie plus tard"
        else -> "$provider ($status) : $detail"
    }
    is FfiException.Model -> detail
    is FfiException.Storage -> "stockage : $detail"
    is FfiException.Cancelled -> "annulé"
    is FfiException.Other -> detail
    else -> message ?: javaClass.simpleName
}
