package app.dicto.triggers

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import app.dicto.DictoApp
import app.dicto.dictation.DictationTarget
import app.dicto.dictation.applyEdits
import app.dicto.dictation.joinChunks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import uniffi.dicto_ffi.DictationResult
import uniffi.dicto_ffi.Mode

/**
 * Dicto comme « assistant numérique » : appui long sur Power ou geste depuis le coin
 * ouvrent la dictée rapide. À choisir dans Réglages › Applications par défaut › Assistant.
 */
class DictoVoiceInteractionService : VoiceInteractionService()

class DictoSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = DictoSession(this)
}

class DictoSession(private val service: VoiceInteractionSessionService) : VoiceInteractionSession(service) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        startAssistantActivity(
            Intent(context, QuickDictateActivity::class.java).putExtra("source", "assistant"),
        )
        hide()
    }
}

/**
 * Reconnaissance vocale du système (`SpeechRecognizer`) : les apps qui demandent une
 * saisie vocale peuvent utiliser Dicto et ses modèles locaux.
 * Écoute une phrase (fin à la pause), puis renvoie le texte.
 */
class DictoRecognitionService : RecognitionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var callback: Callback? = null

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback) {
        val controller = DictoApp.instance.dictation
        if (callback != null || controller.ui.value.isActive) {
            listener.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
            return
        }
        if (!controller.ui.value.engine.isReady) {
            listener.error(SpeechRecognizer.ERROR_SERVER)
            return
        }
        callback = listener
        var chunks = emptyList<String>()
        val target = object : DictationTarget {
            override val targetApp: String? = null
            override fun deliver(result: DictationResult) {
                chunks = applyEdits(chunks, result.edits)
                // Une phrase suffit : on arrête l'écoute.
                if (controller.ui.value.isActive) {
                    controller.press()
                    controller.release()
                }
            }
        }
        controller.target = target
        controller.pressWithMode(Mode.CONTINUOUS)
        if (!controller.ui.value.isActive) {
            controller.target = null
            callback = null
            listener.error(SpeechRecognizer.ERROR_AUDIO)
            return
        }
        listener.readyForSpeech(Bundle())
        job = scope.launch {
            controller.ui.first { it.isActive }
            controller.ui.first { !it.isActive && it.pending == 0 }
            if (controller.target === target) controller.target = null
            val text = joinChunks(chunks)
            val cb = callback
            callback = null
            if (text.isBlank()) {
                cb?.error(SpeechRecognizer.ERROR_NO_MATCH)
            } else {
                cb?.endOfSpeech()
                cb?.results(Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text)) })
            }
        }
    }

    override fun onStopListening(listener: Callback) {
        val controller = DictoApp.instance.dictation
        if (controller.ui.value.isActive) {
            controller.press()
            controller.release()
        }
    }

    override fun onCancel(listener: Callback) {
        job?.cancel()
        callback = null
        val controller = DictoApp.instance.dictation
        if (controller.ui.value.isActive) controller.cancel()
    }

    override fun onDestroy() {
        Handler(Looper.getMainLooper()).post { scope.coroutineContext[Job]?.cancel() }
        super.onDestroy()
    }
}
