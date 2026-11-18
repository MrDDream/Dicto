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
 * Vocaux WhatsApp vus par leurs notifications : quand WhatsApp annonce un vocal (« 🎤 »), on
 * attend que le fichier arrive dans le dossier accordé et on note son expéditeur (personne,
 * groupe). Si la transcription automatique est active, on le transcrit aussi ; le texte arrive
 * dans une notification « Vocal de … ». Seules les notifications de WhatsApp sont regardées,
 * et seul le nom de l'expéditeur en est gardé.
 */
class VoiceNoteListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** Dernier message traité par conversation : WhatsApp republie la même notification. */
    private val handled = mutableMapOf<String, Long>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
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
        val person = last?.person?.name?.toString()
        val group = style?.takeIf { it.isGroupConversation }?.conversationTitle?.toString()
        val title = notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val who = senderLabel(person ?: title, group)
        val source = sbn.packageName
        scope.launch { attach(sbn.postTime - SLACK_MS, source, who) }
    }

    /**
     * Attend le fichier du vocal (téléchargé par WhatsApp juste avant ou après la notification),
     * note son expéditeur, et le transcrit si la transcription automatique est active.
     */
    private suspend fun attach(since: Long, source: String, who: String?) {
        repeat(ATTEMPTS) {
            val note = VoiceNotes.recent(applicationContext, 5).firstOrNull {
                it.modifiedAt >= since && VoiceNotes.sender(it.docId) == null && VoiceNotes.claim(it.docId)
            }
            if (note != null) {
                who?.let { VoiceNotes.rememberSender(note.docId, it) }
                val auto = DictoApp.instance.settings.voiceNotesAuto.value
                if (auto && VoiceNotes.transcript(note.docId) == null && FilesController.findVoiceNote(note.docId) == null) {
                    Journal.add("vocaux", "transcription automatique de ${note.name}")
                    val title = who?.let { tr("Vocal de %1\$s", it) } ?: tr("Vocal transcrit")
                    withContext(Dispatchers.Main) {
                        FilesController.add(applicationContext, note.uri, source, voiceNote = note.docId, title = title)
                    }
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
