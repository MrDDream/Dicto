package app.dicto.dictation

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import kotlin.math.sqrt

/**
 * Capture du micro en PCM 16 bits mono 16 kHz, par blocs de 50 ms.
 * Le thread de lecture s'arrête et libère le micro de lui-même : `stop()` peut être
 * appelé depuis n'importe quel thread, y compris depuis `onAudio`.
 */
class MicRecorder(
    private val onAudio: (ByteArray) -> Unit,
    private val onLevel: (Float) -> Unit,
) {
    /**
     * Drapeau de la capture en cours. Chaque capture a le sien : un arrêt suivi d'un
     * redémarrage rapide (changement de clavier) ne laisse pas l'ancien fil couper le nouveau.
     */
    @Volatile private var current: java.util.concurrent.atomic.AtomicBoolean? = null
    private var thread: Thread? = null

    val isRunning get() = current?.get() == true

    /** Session audio de la capture en cours (0 sans capture), pour la reconnaître parmi celles d'Android. */
    @Volatile var sessionId = 0
        private set

    @SuppressLint("MissingPermission") // vérifiée par l'appelant
    @Synchronized
    fun start(): Boolean {
        if (isRunning) return true
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, CHUNK_BYTES * 4),
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "micro refusé", e)
            return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }
        val suppressor = if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(record.audioSessionId) else null
        suppressor?.enabled = true
        sessionId = record.audioSessionId
        try {
            record.startRecording()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "démarrage du micro", e)
            sessionId = 0
            suppressor?.release()
            record.release()
            return false
        }
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        current = running
        thread = Thread({ loop(record, suppressor, running) }, "dicto-mic").apply { start() }
        return true
    }

    fun stop() {
        current?.set(false)
    }

    private fun loop(record: AudioRecord, suppressor: NoiseSuppressor?, running: java.util.concurrent.atomic.AtomicBoolean) {
        val buf = ByteArray(CHUNK_BYTES)
        try {
            while (running.get()) {
                var read = 0
                while (read < buf.size && running.get()) {
                    val n = record.read(buf, read, buf.size - read)
                    if (n <= 0) break
                    read += n
                }
                if (read <= 0) break
                val chunk = if (read == buf.size) buf.copyOf() else buf.copyOf(read)
                onLevel(level(chunk))
                onAudio(chunk)
            }
        } catch (e: Exception) {
            Log.e(TAG, "lecture du micro", e)
        } finally {
            running.set(false)
            // Une nouvelle capture a peut-être déjà démarré : on ne touche qu'à la sienne.
            if (sessionId == record.audioSessionId) sessionId = 0
            runCatching { record.stop() }
            runCatching { suppressor?.release() }
            runCatching { record.release() }
            if (current === running) onLevel(0f)
        }
    }

    /** Niveau 0..1 (RMS, échelle racine pour que la voix normale occupe le milieu). */
    private fun level(pcm: ByteArray): Float {
        var sum = 0.0
        val n = pcm.size / 2
        for (i in 0 until n) {
            val s = (pcm[2 * i].toInt() and 0xFF) or (pcm[2 * i + 1].toInt() shl 8)
            val v = s.toShort() / 32768.0
            sum += v * v
        }
        val rms = sqrt(sum / maxOf(n, 1))
        return sqrt(rms * 4).coerceIn(0.0, 1.0).toFloat()
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val CHUNK_BYTES = SAMPLE_RATE / 20 * 2 // 50 ms
        private const val TAG = "DictoMic"
    }
}
