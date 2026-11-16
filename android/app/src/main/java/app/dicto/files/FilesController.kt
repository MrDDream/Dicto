package app.dicto.files

import app.dicto.i18n.tr
import android.app.Notification
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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

    init {
        scope.launch { for (id in queue) process(id) }
    }

    fun add(context: Context, uri: Uri) {
        val name = displayName(context, uri)
        val item = FileItem(nextId++, uri, name)
        _items.update { listOf(item) + it }
        FileWorkService.start(context)
        queue.trySend(item.id)
    }

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
        if (item.status == FileItem.Status.CANCELLED) return
        val app = DictoApp.instance
        currentId = id
        try {
            val engine = app.dictation.currentEngine() ?: throw IllegalStateException(tr("choisis d'abord un modèle"))
            // Séparation des voix : son modèle (~46 Mo) est téléchargé la première fois.
            val voices = if (app.settings.diarize.value) {
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
        } catch (e: uniffi.dicto_ffi.FfiException.Cancelled) {
            update(id) { it.copy(status = FileItem.Status.CANCELLED) }
        } catch (e: Exception) {
            Log.w("DictoFiles", "transcription de ${item.name}", e)
            update(id) { it.copy(status = FileItem.Status.FAILED, error = e.userMessage()) }
        } finally {
            job = null
            currentId = null
            if (_items.value.none { it.status == FileItem.Status.QUEUED || it.active }) FileWorkService.stop(app)
        }
    }

    private fun update(id: Long, f: (FileItem) -> FileItem) =
        _items.update { list -> list.map { if (it.id == id) f(it) else it } }

    private fun displayName(context: Context, uri: Uri): String =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "audio"
}

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
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FileWorkService::class.java))
        }
    }
}
