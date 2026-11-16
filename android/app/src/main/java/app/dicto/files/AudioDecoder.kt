package app.dicto.files

import app.dicto.i18n.tr
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.nio.ByteOrder

/**
 * Décode n'importe quel fichier lisible par Android (Opus des messages vocaux, AAC/M4A,
 * MP3, FLAC, WAV, audio des MP4/MKV) en PCM 16 bits mono 16 kHz, par blocs, pour ne
 * pas garder le fichier décodé complet en mémoire.
 */
object AudioDecoder {
    data class Decoded(val pcm: ByteArray, val durationMs: Long)

    fun decode(context: Context, uri: Uri, onProgress: (Float) -> Unit = {}): Decoded {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: throw IllegalArgumentException(tr("aucune piste audio dans ce fichier"))
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()
        val out = ByteArrayOutputStream()
        val resampler = Resampler(out)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        try {
            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buf = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            if (durationUs > 0) onProgress((extractor.sampleTime.toFloat() / durationUs).coerceIn(0f, 1f))
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> resampler.configure(codec.outputFormat)
                    outIndex >= 0 -> {
                        if (info.size > 0) {
                            if (!resampler.configured) resampler.configure(codec.outputFormat)
                            val buf = codec.getOutputBuffer(outIndex)!!.order(ByteOrder.LITTLE_ENDIAN)
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            resampler.push(buf.asShortBuffer())
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } finally {
            codec.stop()
            codec.release()
            extractor.release()
        }
        val pcm = out.toByteArray()
        return Decoded(pcm, pcm.size / 2 * 1000L / TARGET_RATE)
    }

    /** Mixage mono et rééchantillonnage par moyenne de fenêtre (suffisant pour la voix). */
    private class Resampler(private val out: ByteArrayOutputStream) {
        var configured = false
        private var channels = 1
        private var step = 1.0
        private var acc = 0.0
        private var n = 0
        private var inPos = 0L
        private var boundary = 1.0

        fun configure(format: MediaFormat) {
            channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            step = format.getInteger(MediaFormat.KEY_SAMPLE_RATE).toDouble() / TARGET_RATE
            boundary = inPos + step
            configured = true
        }

        /** Chaque échantillon de sortie est la moyenne des entrées de sa fenêtre (pas fractionnaire exact). */
        fun push(samples: java.nio.ShortBuffer) {
            while (samples.remaining() >= channels) {
                var mono = 0.0
                repeat(channels) { mono += samples.get() }
                acc += mono / channels
                n++
                inPos++
                if (inPos >= boundary) {
                    val v = (acc / n).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    out.write(v and 0xFF)
                    out.write((v shr 8) and 0xFF)
                    acc = 0.0
                    n = 0
                    boundary += step
                }
            }
        }
    }

    const val TARGET_RATE = 16_000
}
