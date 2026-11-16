package app.dicto.dictation

import app.dicto.i18n.tr
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.dicto.DictoApp
import app.dicto.R
import app.dicto.ui.MainActivity

/**
 * Service de premier plan « micro » : garde le droit d'enregistrer quand Dicto
 * n'est plus à l'écran (clavier vocal, puis bulle et mot d'activation).
 * La capture elle-même est faite par [DictationController].
 */
class DictationService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            DictoApp.instance.dictation.cancel()
            stopSelf()
            return START_NOT_STICKY
        }
        // startForeground doit toujours être appelé après startForegroundService, même si la
        // dictée vient de s'arrêter : sinon Android arrête l'app.
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification(),
                micType(),
            )
        } catch (e: RuntimeException) {
            // Permission micro retirée, ou démarrage depuis l'arrière-plan refusé.
            DictoApp.instance.dictation.onServiceDenied(e)
            stopSelf()
            return START_NOT_STICKY
        }
        started = true
        if (!DictoApp.instance.dictation.isCapturing) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        started = false
        super.onDestroy()
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, DictationService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, DictoApp.CHANNEL_DICTATION)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(tr("Je t'écoute"))
            .setContentText(tr("Dicto transcrit ce que tu dis."))
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, tr("Annuler"), stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "app.dicto.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, DictationService::class.java))
        }

        @Volatile private var started = false

        /** Sans effet si le service n'est pas encore passé au premier plan : il s'arrêtera alors de lui-même. */
        fun stop(context: Context) {
            if (started) context.stopService(Intent(context, DictationService::class.java))
        }
    }
}

/** Le type « micro » n'existe qu'à partir d'Android 11 ; Android 10 n'en a pas besoin. */
internal fun micType(): Int =
    if (android.os.Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
