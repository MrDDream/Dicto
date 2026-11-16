package app.dicto.triggers

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.dicto.DictoApp
import app.dicto.R
import app.dicto.dictation.MicRecorder
import app.dicto.dictation.micType
import app.dicto.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import uniffi.dicto_ffi.DownloadListener
import uniffi.dicto_ffi.WakeWordDetector

/**
 * Écoute « OK Dicto » en continu (service de premier plan micro). L'audio est analysé
 * sur le téléphone, rien n'est envoyé. Pause automatique écran éteint et en économie
 * d'énergie, et pendant chaque dictée (le micro est rendu).
 */
class WakeWordService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var detector: WakeWordDetector? = null
    private val recorder = MicRecorder(onAudio = ::onAudio, onLevel = {})
    private var status = "Préparation…"

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = updateListening()
    }

    /** Une autre app enregistre (micro de Gboard, appel, message vocal) : on lui laisse le micro. */
    @Volatile private var othersRecording = false

    private val recordings = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
            val mine = recorder.sessionId
            othersRecording = configs.any { it.clientAudioSessionId != mine || it.isClientSilenced }
            updateListening()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        registerReceiver(
            receiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            },
        )
        getSystemService(AudioManager::class.java).registerAudioRecordingCallback(recordings, null)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), micType())
        } catch (e: RuntimeException) {
            Log.w(TAG, "démarrage refusé", e)
            state.value = "Android a refusé de démarrer l'écoute en arrière-plan : ouvre Dicto pour la relancer."
            stopSelf()
            return START_NOT_STICKY
        }
        instance = this
        if (detector == null) prepare() else updateListening()
        return START_STICKY
    }

    private fun prepare() = scope.launch {
        val app = DictoApp.instance
        try {
            val dir = app.models.supportPath(KWS_MODEL) ?: run {
                setStatus("Téléchargement du modèle (18 Mo)…")
                app.models.downloadSupport(KWS_MODEL, object : DownloadListener {
                    override fun onProgress(downloaded: ULong, total: ULong) = Unit
                })
            }
            val phrase = app.settings.wakePhrase.value
            // « OK » se dit aussi « Okay » : on écoute les deux. Sensibilité assez haute, car le
            // modèle est anglais et la prononciation française s'en écarte un peu.
            val variants = buildList {
                add(phrase)
                if (phrase.trim().uppercase().startsWith("OK ")) add("OKAY " + phrase.trim().substring(3))
            }
            detector = WakeWordDetector(dir, variants, 0.75f)
            setStatus("J'écoute « ${phrase.lowercase().replaceFirstChar { it.uppercase() }} »")
            updateListening()
        } catch (e: Exception) {
            Log.w(TAG, "préparation", e)
            setStatus("Indisponible : ${e.message}")
        }
    }

    /** Écoute seulement écran allumé, hors économie d'énergie, et hors dictée. */
    private fun updateListening() {
        val power = getSystemService(PowerManager::class.java)
        val should = detector != null && !paused && !othersRecording && power.isInteractive && !power.isPowerSaveMode &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (should && !recorder.isRunning) recorder.start()
        if (!should && recorder.isRunning) recorder.stop()
    }

    private fun onAudio(pcm: ByteArray) {
        val phrase = detector?.push(pcm) ?: return
        Log.i(TAG, "phrase détectée : $phrase")
        paused = true
        recorder.stop()
        // Le micro est libéré avant que la dictée ne le prenne.
        android.os.Handler(mainLooper).postDelayed({ launchDictation() }, 150)
    }

    private fun launchDictation() {
        when {
            // « Afficher par-dessus les autres apps » autorise l'ouverture depuis l'arrière-plan.
            Settings.canDrawOverlays(this) -> QuickDictateActivity.launch(this, source = "wake")
            else -> {
                // Android interdit d'ouvrir une fenêtre depuis l'arrière-plan sans ces permissions.
                val intent = PendingIntent.getActivity(
                    this, 3, Intent(this, QuickDictateActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE,
                )
                getSystemService(NotificationManager::class.java).notify(
                    NOTIFICATION_ID + 1,
                    NotificationCompat.Builder(this, DictoApp.CHANNEL_DICTATION)
                        .setSmallIcon(R.drawable.ic_launcher_monochrome)
                        .setContentTitle("Touche pour dicter")
                        .setContentText("Autorise « Afficher par-dessus les autres apps » pour ouvrir la dictée directement.")
                        .setContentIntent(intent)
                        .setAutoCancel(true)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .build(),
                )
                paused = false
                updateListening()
            }
        }
    }

    private fun setStatus(text: String) {
        status = text
        state.value = text
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, DictoApp.CHANNEL_DICTATION)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Mot d'activation")
            .setContentText(status)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .build()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        recorder.stop()
        runCatching { unregisterReceiver(receiver) }
        getSystemService(AudioManager::class.java).unregisterAudioRecordingCallback(recordings)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "DictoWake"
        private const val NOTIFICATION_ID = 3
        private const val KWS_MODEL = "kws-gigaspeech"
        @Volatile private var paused = false
        @Volatile private var instance: WakeWordService? = null
        @Volatile var running = false
            private set

        /** État lisible de l'écoute (préparation, téléchargement, écoute, erreur), pour Déclencheurs. */
        val state = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

        fun start(context: Context) {
            running = true
            paused = false
            ContextCompat.startForegroundService(context, Intent(context, WakeWordService::class.java))
        }

        fun stop(context: Context) {
            running = false
            state.value = null
            context.stopService(Intent(context, WakeWordService::class.java))
        }

        /** Rend le micro pendant une dictée, puis reprend l'écoute. */
        fun pause() {
            paused = true
            instance?.let { s -> android.os.Handler(s.mainLooper).post { s.updateListening() } }
        }

        fun resume() {
            paused = false
            instance?.let { s -> android.os.Handler(s.mainLooper).postDelayed({ s.updateListening() }, 300) }
        }
    }
}
