package app.dicto.files

import app.dicto.i18n.tr
import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.dicto.DictoApp
import app.dicto.data.Journal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Transcription automatique des vocaux WhatsApp : quand une notification de WhatsApp annonce
 * un vocal (« 🎤 »), on attend que le fichier arrive dans le dossier accordé, puis on le
 * transcrit ; le texte arrive dans une notification « Vocal de … ». Seules les notifications
 * de WhatsApp sont regardées, et rien n'en est gardé.
 */
class VoiceNoteListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** Dernier message traité par conversation : WhatsApp republie la même notification. */
    private val handled = mutableMapOf<String, Long>()

    override fun onListenerConnected() {
        // Interrupteur coupé : on rend la main au système, qui ne nous réveillera plus.
        if (!DictoApp.instance.settings.voiceNotesAuto.value) runCatching { requestUnbind() }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val app = DictoApp.instance
        if (!app.settings.voiceNotesAuto.value) {
            runCatching { requestUnbind() }
            return
        }
        if (sbn.packageName !in VoiceNotes.APPS) return
        val notification = sbn.notification ?: return
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
        val last = style?.messages?.lastOrNull()
        val text = last?.text ?: notification.extras.getCharSequence(Notification.EXTRA_TEXT)
        if (!isVoiceMessage(text)) return
        val stamp = last?.timestamp ?: sbn.postTime
        synchronized(handled) {
            if (handled[sbn.key] == stamp) return
            handled[sbn.key] = stamp
        }
        if (!VoiceNotes.isGranted(this)) {
            Journal.add("vocaux", "vocal reçu, mais pas d'accès au dossier WhatsApp")
            return
        }
        val sender = last?.person?.name?.toString()
        val chat = style?.conversationTitle?.toString() ?: notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val who = listOfNotNull(sender, chat?.takeIf { it != sender }).joinToString(" · ").ifBlank { null }
        val title = who?.let { tr("Vocal de %1\$s", it) } ?: tr("Vocal transcrit")
        val source = sbn.packageName
        scope.launch { transcribeNew(sbn.postTime - SLACK_MS, source, title) }
    }

    /** Attend le fichier du vocal (téléchargé par WhatsApp juste avant ou après la notification). */
    private suspend fun transcribeNew(since: Long, source: String, title: String) {
        repeat(ATTEMPTS) {
            val note = VoiceNotes.recent(applicationContext, 5).firstOrNull {
                it.modifiedAt >= since &&
                    VoiceNotes.transcript(it.docId) == null &&
                    FilesController.findVoiceNote(it.docId) == null &&
                    VoiceNotes.claim(it.docId)
            }
            if (note != null) {
                Journal.add("vocaux", "transcription automatique de ${note.name}")
                withContext(Dispatchers.Main) {
                    FilesController.add(applicationContext, note.uri, source, voiceNote = note.docId, title = title)
                }
                return
            }
            delay(POLL_MS)
        }
        Journal.add("vocaux", "vocal annoncé, mais aucun nouveau fichier dans le dossier")
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val SLACK_MS = 30_000L
        private const val POLL_MS = 3_000L
        private const val ATTEMPTS = 30

        fun isEnabled(context: Context): Boolean =
            context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

        /** L'interrupteur vient d'être activé : demande au système de nous relier. */
        fun rebind(context: Context) {
            runCatching { NotificationListenerService.requestRebind(ComponentName(context, VoiceNoteListener::class.java)) }
        }
    }
}
