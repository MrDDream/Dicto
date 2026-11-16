package app.dicto

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import uniffi.dicto_ffi.CloudProvider
import uniffi.dicto_ffi.Dictation
import uniffi.dicto_ffi.DictationListener
import uniffi.dicto_ffi.DictationResult
import uniffi.dicto_ffi.DiscardReason
import uniffi.dicto_ffi.Engine
import uniffi.dicto_ffi.FfiException
import uniffi.dicto_ffi.Mode
import uniffi.dicto_ffi.ModelManager
import uniffi.dicto_ffi.State
import uniffi.dicto_ffi.Storage
import uniffi.dicto_ffi.TextEdit
import uniffi.dicto_ffi.Trigger
import uniffi.dicto_ffi.parseVoiceCommands
import java.io.File
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Teste le pont Kotlin ↔ Rust sur la JVM, avec la bibliothèque compilée pour la machine
 * (`core/target/debug/libdicto_ffi.so`, voir le bloc testOptions de build.gradle.kts).
 */
class CoreBridgeTest {

    private class Recorder : DictationListener {
        val events = LinkedBlockingQueue<String>()
        val results = LinkedBlockingQueue<DictationResult>()
        override fun onCapture(active: Boolean) { events += "capture:$active" }
        override fun onState(state: State) { events += "state:$state" }
        override fun onTranscribing(utteranceId: ULong) { events += "transcribing:$utteranceId" }
        override fun onPartial(text: String) = Unit
        override fun onResult(utteranceId: ULong, result: DictationResult) { results += result }
        override fun onDiscarded(reason: DiscardReason) { events += "discarded:$reason" }
        override fun onError(utteranceId: ULong, error: FfiException) { events += "error:${error.javaClass.simpleName}" }
    }

    private fun tmp(): File = Files.createTempDirectory("dicto-test").toFile()

    private fun pcm(samples: ShortArray): ByteArray =
        ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { samples.forEach { putShort(it) } }.array()

    @Test
    fun storageAndVocabulary() {
        val storage = Storage.open(File(tmp(), "dicto.db").path)
        storage.addTerm("Loftia")
        storage.addTerm("loftia")
        assertEquals(listOf("Loftia"), storage.vocabulary().map { it.term })
        storage.setSetting("mode", "HOLD")
        assertEquals("HOLD", storage.setting("mode"))
    }

    @Test
    fun catalogIsExposed() {
        val models = ModelManager(tmp().path, "android").models()
        assertTrue(models.first().recommended)
        assertTrue(models.any { it.id == "canary-180m-flash-int8" && it.engine == "sherpa" && "fr" in it.languages })
        assertTrue("trop lourd pour un téléphone", models.none { it.id == "whisper-large-v3-turbo" })
        val credits = ModelManager(tmp().path, "android").credits()
        assertTrue(credits.any { it.name == "Canary 180M" && it.license == "CC-BY-4.0" && it.credit.contains("NVIDIA") })
    }

    @Test
    fun voiceCommands() {
        assertEquals(
            listOf(TextEdit.Text("Bonjour"), TextEdit.Newline, TextEdit.Text("Salut")),
            parseVoiceCommands("Bonjour, nouvelle ligne. Salut"),
        )
    }

    @Test
    fun pushToTalkThroughCloudEngine() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/audio/transcriptions") { ex ->
            ex.requestBody.readAllBytes()
            val body = """{"text":"Bonjour, nouvelle ligne. À demain."}""".toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val rec = Recorder()
            val d = Dictation(Mode.PUSH_TO_TALK, rec)
            d.setEngine(Engine.cloud(CloudProvider.CUSTOM, "http://127.0.0.1:${server.address.port}/v1", null, "test"))
            d.trigger(Trigger.PRESSED)
            d.pushAudio(pcm(ShortArray(16_000) { 1000 }))
            d.trigger(Trigger.PRESSED)
            val result = rec.results.poll(10, TimeUnit.SECONDS)
            assertNotNull("aucun résultat ; événements : ${rec.events}", result)
            assertEquals("Bonjour\nÀ demain.", result!!.text)
            assertEquals(1000uL, result.durationMs)
            assertTrue(rec.events.containsAll(listOf("capture:true", "state:RECORDING", "capture:false", "transcribing:1")))
        } finally {
            server.stop(0)
        }
    }

    /** Vraie transcription whisper.cpp ; nécessite -Pdicto.whisperModel et -Pdicto.sampleWav. */
    @Test
    fun localWhisperTranscription() {
        val model = System.getProperty("dicto.whisperModel").orEmpty()
        val wav = System.getProperty("dicto.sampleWav").orEmpty()
        assumeTrue("modèle ou échantillon absent", File(model).isFile && File(wav).isFile)

        val bytes = File(wav).readBytes()
        val rec = Recorder()
        val d = Dictation(Mode.HOLD, rec)
        // <dossier>/whisper-tiny-q5_1/ggml-tiny-q5_1.bin
        val manager = ModelManager(File(model).parentFile.parentFile.path, "android")
        d.setEngine(manager.createEngine("whisper-tiny-q5_1", false))
        d.setOptions("en", listOf("Americans"), true)
        d.trigger(Trigger.PRESSED)
        d.pushAudio(bytes.copyOfRange(44, bytes.size)) // en-tête WAV de 44 octets, PCM 16 kHz mono
        d.trigger(Trigger.RELEASED)
        val result = rec.results.poll(60, TimeUnit.SECONDS)
        assertNotNull("aucun résultat ; événements : ${rec.events}", result)
        assertTrue(result!!.text, result.text.contains("ask not what your country can do for you", ignoreCase = true))
        assertEquals("local:whisper-tiny-q5_1", result.engine)
    }

    /** sherpa-onnx (Canary) sur un enregistrement français ; nécessite -Pdicto.frWav. */
    @Test
    fun localSherpaFrench() {
        val model = System.getProperty("dicto.whisperModel").orEmpty()
        val wav = System.getProperty("dicto.frWav").orEmpty()
        assumeTrue("modèles ou échantillon absent", File(model).isFile && File(wav).isFile)
        val manager = ModelManager(File(model).parentFile.parentFile.path, "android")
        assumeTrue(manager.isInstalled("canary-180m-flash-int8"))

        val bytes = File(wav).readBytes()
        val rec = Recorder()
        val d = Dictation(Mode.PUSH_TO_TALK, rec)
        d.setEngine(manager.createEngine("canary-180m-flash-int8", false))
        d.setOptions("fr", emptyList(), true)
        d.trigger(Trigger.PRESSED)
        d.pushAudio(bytes.copyOfRange(44, bytes.size))
        d.trigger(Trigger.PRESSED)
        val result = rec.results.poll(60, TimeUnit.SECONDS)
        assertNotNull("aucun résultat ; événements : ${rec.events}", result)
        assertTrue(result!!.text, result.text.contains("gagnent pas les matchs"))
    }
}
