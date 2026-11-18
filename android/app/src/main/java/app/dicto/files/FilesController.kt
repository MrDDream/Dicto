package app.dicto.files

import app.dicto.i18n.tr
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.dicto.DictoApp
import app.dicto.R
import app.dicto.dictation.userMessage
import app.dicto.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uniffi.dicto_ffi.DictationResult
import uniffi.dicto_ffi.FileJob
import uniffi.dicto_ffi.FileTranscript
import uniffi.dicto_ffi.ProgressListener

data class FileItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val status: Status = Status.QUEUED,
    val progress: Float = 0f,
    val durationMs: Long = 0,
    val result: FileTranscript? = null,
    val error: String? = null,
    /** App d'où vient le fichier partagé (ex. com.whatsapp) : la transcription va dans l'historique. */
    val source: String? = null,
    /** Copie locale d'un fichier partagé, supprimée une fois traité. */
    val cached: java.io.File? = null,
    /** Vocal du dossier WhatsApp (docId) : son texte est gardé pour ne pas le retranscrire. */
    val voiceNote: String? = null,
    /** Titre de la notification de résultat (« Vocal de Marie »). */
    val title: String? = null,
    /** Notification du texte si le panneau n'est pas affiché (non : lancé depuis la liste, dans l'app). */
    val notify: Boolean = true,
) {
    enum class Status { QUEUED, VOICE_MODEL, DECODING, TRANSCRIBING, DONE, FAILED, CANCELLED }

    val active get() = status == Status.VOICE_MODEL || status == Status.DECODING || status == Status.TRANSCRIBING
}

/** File de transcription des fichiers audio, traitée un par un hors de l'interface. */
object FilesController {
    /** Modèle de séparation des voix (catalogue, fichiers annexes). */
    const val DIARIZATION = "diarization"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val queue = Channel<Long>(Channel.UNLIMITED)
    private val _items = MutableStateFlow<List<FileItem>>(emptyList())
    val items: StateFlow<List<FileItem>> = _items.asStateFlow()
    private var nextId = 1L
    @Volatile private var job: FileJob? = null
    @Volatile private var currentId: Long? = null
    /** Élément affiché par le panneau de partage : pas de notification pour lui. */
    @Volatile var shownId: Long? = null

    init {
        scope.launch { for (id in queue) process(id) }
    }

    /**
     * Met un fichier en file. Partagé depuis une autre app (`source`), il est d'abord copié :
     * le droit de lecture accordé par le partage peut tomber dès que le panneau se ferme.
     */
    fun add(
        context: Context,
        uri: Uri,
        source: String? = null,
        voiceNote: String? = null,
        title: String? = null,
        notify: Boolean = true,
    ): Long {
        val name = displayName(context, uri)
        val item = FileItem(nextId++, uri, name, source = source, voiceNote = voiceNote, title = title, notify = notify)
        _items.update { listOf(item) + it }
        FileWorkService.start(context)
        if (source == null) {
            queue.trySend(item.id)
        } else {
            val app = context.applicationContext
            scope.launch(Dispatchers.IO) {
                val local = runCatching { copyToCache(app, uri, item.id, name) }
                    .onFailure { Log.w("DictoFiles", "copie de $name", it) }
                    .getOrNull()
                if (local != null) update(item.id) { it.copy(uri = Uri.fromFile(local), cached = local) }
                queue.trySend(item.id)
            }
        }
        return item.id
    }

    /** Vocal déjà transcrit : affiché tel quel, sans le retranscrire. */
    fun addCached(name: String, text: String, voiceNote: String?): Long {
        val transcript = FileTranscript(text = text, language = null, segments = emptyList(), durationMs = 0u, engine = "")
        val item = FileItem(nextId++, Uri.EMPTY, name, status = FileItem.Status.DONE, progress = 1f, result = transcript, voiceNote = voiceNote)
        _items.update { listOf(item) + it }
        return item.id
    }

    /** Élément de la file pour ce vocal, s'il est en attente, en cours ou terminé. */
    fun findVoiceNote(docId: String): FileItem? =
        _items.value.firstOrNull { it.voiceNote == docId && it.status != FileItem.Status.FAILED && it.status != FileItem.Status.CANCELLED }

    fun cancel(id: Long) {
        if (currentId == id) job?.cancel()
        update(id) { if (it.status == FileItem.Status.QUEUED) it.copy(status = FileItem.Status.CANCELLED) else it }
    }

    fun remove(id: Long) {
        cancel(id)
        _items.update { list -> list.filterNot { it.id == id } }
    }

    private suspend fun process(id: Long) {
        val item = _items.value.firstOrNull { it.id == id } ?: return
        if (item.status == FileItem.Status.CANCELLED) {
            item.cached?.delete()
            return
        }
        val app = DictoApp.instance
        currentId = id
        try {
            val engine = app.dictation.currentEngine() ?: throw IllegalStateException(tr("choisis d'abord un modèle"))
            // Séparation des voix : son modèle (~46 Mo) est téléchargé la première fois.
            // Pas pour un message vocal partagé : une seule voix, et le texte doit venir vite.
            val voices = if (app.settings.diarize.value && item.source == null) {
                app.models.supportPath(DIARIZATION) ?: run {
                    update(id) { it.copy(status = FileItem.Status.VOICE_MODEL, progress = 0f) }
                    app.models.downloadSupport(
                        DIARIZATION,
                        object : uniffi.dicto_ffi.DownloadListener {
                            override fun onProgress(downloaded: ULong, total: ULong) {
                                if (total > 0u) update(id) { it.copy(progress = downloaded.toFloat() / total.toFloat()) }
                            }
                        },
                    )
                }
            } else {
                null
            }
            update(id) { it.copy(status = FileItem.Status.DECODING, progress = 0f) }
            val decoded = AudioDecoder.decode(app, item.uri) { p -> update(id) { it.copy(progress = p) } }
            update(id) { it.copy(status = FileItem.Status.TRANSCRIBING, progress = 0f, durationMs = decoded.durationMs) }
            val fileJob = FileJob().also { job = it }
            val vocabulary = runCatching { app.storage.vocabulary().map { it.term } }.getOrDefault(emptyList())
            val result = fileJob.transcribe(
                engine, decoded.pcm, AudioDecoder.TARGET_RATE.toUInt(), 1u.toUShort(),
                app.settings.language.value, vocabulary, voices,
                object : ProgressListener {
                    override fun onProgress(fraction: Float) = update(id) { it.copy(progress = fraction) }
                },
            )
            update(id) { it.copy(status = FileItem.Status.DONE, progress = 1f, result = result) }
            item.voiceNote?.let { VoiceNotes.remember(it, result.text) }
            if (item.source != null) {
                runCatching { app.storage.addHistory(result.asDictation(), item.source) }
                    .onFailure { Log.w("DictoFiles", "historique de ${item.name}", it) }
                if (item.notify && shownId != id) notifyResult(app, id, item.title, result.text)
            }
        } catch (e: uniffi.dicto_ffi.FfiException.Cancelled) {
            update(id) { it.copy(status = FileItem.Status.CANCELLED) }
        } catch (e: Exception) {
            Log.w("DictoFiles", "transcription de ${item.name}", e)
            update(id) { it.copy(status = FileItem.Status.FAILED, error = e.userMessage()) }
        } finally {
            item.cached?.delete()
            job = null
            currentId = null
            if (_items.value.none { it.status == FileItem.Status.QUEUED || it.active }) FileWorkService.stop(app)
        }
    }

    private fun copyToCache(context: Context, uri: Uri, id: Long, name: String): java.io.File {
        val dir = java.io.File(context.cacheDir, "shared").apply { mkdirs() }
        val file = java.io.File(dir, "$id.${name.substringAfterLast('.', "audio").take(8)}")
        context.contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
        return file
    }

    /** Transcription d'un fichier partagé, terminée alors que le panneau était fermé. */
    private fun notifyResult(context: Context, id: Long, title: String?, text: String) {
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(
            context,
            id.toInt(),
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_FILES, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val body = text.ifBlank { tr("Aucune parole détectée.") }
        val builder = NotificationCompat.Builder(context, DictoApp.CHANNEL_VOICE_NOTES)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title ?: tr("Vocal transcrit"))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
        if (text.isNotBlank()) {
            builder.addAction(0, tr("Copier"), TranscriptActionReceiver.copyAction(context, id, text))
            builder.addAction(0, tr("Partager"), TranscriptActionReceiver.shareAction(context, id, text))
        }
        val notification = builder.build()
        runCatching { manager.notify(RESULT_NOTIFICATION + id.toInt(), notification) }
    }

    private const val RESULT_NOTIFICATION = 100

    private fun update(id: Long, f: (FileItem) -> FileItem) =
        _items.update { list -> list.map { if (it.id == id) f(it) else it } }

    private fun displayName(context: Context, uri: Uri): String =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "audio"
}

/** Entrée d'historique d'un fichier transcrit (pas de commandes vocales ni de retouche). */
private fun FileTranscript.asDictation() = DictationResult(
    text = text,
    edits = emptyList(),
    rawText = text,
    language = language,
    engine = engine,
    durationMs = durationMs,
    warning = null,
    newParagraph = false,
)

/** Garde Dicto actif pendant la transcription d'un fichier, écran éteint ou app fermée. */
class FileWorkService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        runCatching { ServiceCompat.startForeground(this, 4, notification(), type) }.onFailure { stopSelf() }
        return START_NOT_STICKY
    }

    private fun notification(): Notification =
        NotificationCompat.Builder(this, DictoApp.CHANNEL_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(tr("Transcription de fichiers"))
            .setContentText(tr("Dicto transcrit tes enregistrements sur le téléphone."))
            .setOngoing(true)
            .setSilent(true)
            .setProgress(0, 0, true)
            .build()

    companion object {
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, FileWorkService::class.java)) }
                .onFailure { app.dicto.data.Journal.add("fichiers", "service de premier plan refusé : ${it.message}") }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FileWorkService::class.java))
        }
    }
}
