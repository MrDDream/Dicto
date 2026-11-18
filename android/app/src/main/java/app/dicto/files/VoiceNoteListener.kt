package app.dicto.files

import app.dicto.i18n.tr
import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.graphics.drawable.Icon
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
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

    override fun onListenerConnected() {
        instance = this
        scanActive()
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    /**
     * Vocaux encore annoncés dans le volet (messages non lus) : chaque « 🎤 » est rattaché au
     * fichier arrivé à la même heure, pour retrouver l'expéditeur des vocaux reçus avant.
     * Rien n'est transcrit ici.
     */
    fun scanActive() {
        if (!DictoApp.instance.settings.voiceNotesEnabled.value || !VoiceNotes.isGranted(this)) return
        val voices = runCatching { activeNotifications?.toList() }.getOrNull().orEmpty()
            .filter { it.packageName in VoiceNotes.APPS && it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
            .flatMap { sbn ->
                val n = sbn.notification
                val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n) ?: return@flatMap emptyList()
                val group = style.takeIf { it.isGroupConversation }?.conversationTitle?.toString()
                val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                style.messages
                    .filter { isVoiceMessage(it.text) && !fromMe(style, it) }
                    .mapNotNull { m ->
                        saveAvatars(cleanName(m.person?.name?.toString() ?: title), m.person?.icon, cleanName(group), n.getLargeIcon(), refresh = false)
                        senderLabel(m.person?.name?.toString() ?: title, group)?.let { m.timestamp to it }
                    }
            }
        if (voices.isEmpty()) return
        scope.launch {
            // Tous les fichiers récents, pour qu'un message retrouve son propre fichier même s'il a
            // déjà un expéditeur ; seuls les rattachements à un fichier sans expéditeur comptent.
            // Les tout derniers vocaux restent à onNotificationPosted (transcription automatique).
            val fresh = System.currentTimeMillis() - (SLACK_MS + ATTEMPTS * POLL_MS)
            val files = VoiceNotes.recent(applicationContext, 40)
            val matches = matchByTime(voices.map { it.first }, files.map { it.modifiedAt }, MATCH_MS)
                .filter { (_, f) -> VoiceNotes.sender(files[f].docId) == null && files[f].modifiedAt < fresh }
            for ((m, f) in matches) {
                VoiceNotes.claim(files[f].docId)
                VoiceNotes.rememberSender(files[f].docId, voices[m].second)
            }
            if (matches.isNotEmpty()) Journal.add("vocaux", "${matches.size} expéditeur(s) retrouvé(s) dans les notifications")
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in VoiceNotes.APPS || !DictoApp.instance.settings.voiceNotesEnabled.value) return
        val notification = sbn.notification ?: return
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
        val last = style?.messages?.lastOrNull()
        val text = last?.text ?: notification.extras.getCharSequence(Notification.EXTRA_TEXT)
        if (!isVoiceMessage(text)) return
        // Ton propre vocal (WhatsApp remet ta réponse dans la notification) : rien à faire.
        if (style != null && last != null && fromMe(style, last)) return
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
        saveAvatars(cleanName(person ?: title), last?.person?.icon, cleanName(group), notification.getLargeIcon(), refresh = true)
        val source = sbn.packageName
        scope.launch { attach(sbn.postTime, source, who) }
    }

    /**
     * Photos de la personne et du groupe, gardées en petit sur le téléphone. En conversation
     * privée, la grande icône de la notification est la photo du contact ; dans un groupe, celle
     * du groupe. [refresh] remplace une photo déjà gardée (vocal qui arrive).
     */
    private fun saveAvatars(person: String?, personIcon: IconCompat?, group: String?, large: Icon?, refresh: Boolean) {
        if (person == null && group == null) return
        val context = applicationContext
        scope.launch(Dispatchers.IO) {
            if (person != null && (refresh || VoiceNotes.avatarFile(person) == null)) {
                val photo = personIcon?.let { runCatching { it.loadDrawable(context) }.getOrNull() }
                    ?: large?.takeIf { group == null }?.let { runCatching { it.loadDrawable(context) }.getOrNull() }
                photo?.let { VoiceNotes.saveAvatar(person, it) }
            }
            if (group != null && (refresh || VoiceNotes.avatarFile(group) == null)) {
                large?.let { runCatching { it.loadDrawable(context) }.getOrNull() }?.let { VoiceNotes.saveAvatar(group, it) }
            }
        }
    }

    /** Message écrit par l'utilisateur : sans auteur, ou au nom de l'utilisateur de la conversation. */
    private fun fromMe(style: NotificationCompat.MessagingStyle, m: NotificationCompat.MessagingStyle.Message): Boolean {
        val author = m.person ?: return true
        val me = style.user
        return (author.key != null && author.key == me.key) || (author.name != null && author.name.toString() == me.name?.toString())
    }

    /**
     * Attend le fichier du vocal (téléchargé par WhatsApp juste avant ou après la notification),
     * note son expéditeur, et le transcrit si la transcription automatique est active.
     */
    private suspend fun attach(postedAt: Long, source: String, who: String?) {
        repeat(ATTEMPTS) {
            // Le fichier arrivé le plus près de la notification, dans une fenêtre bornée : un vocal
            // que tu enregistres ensuite pour répondre n'est pas pris pour celui reçu.
            val candidates = VoiceNotes.recent(applicationContext, 8).filter { VoiceNotes.sender(it.docId) == null }
            val note = nearestFile(candidates.map { it.modifiedAt }, postedAt, SLACK_MS, AFTER_MS)
                ?.let { candidates[it] }
                ?.takeIf { VoiceNotes.claim(it.docId) }
            if (note != null) {
                who?.let { VoiceNotes.rememberSender(note.docId, it) }
                val settings = DictoApp.instance.settings
                val auto = settings.voiceNotesEnabled.value && settings.voiceNotesAuto.value
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
        if (instance === this) instance = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /** Fenêtre autour de la notification où chercher le fichier du vocal reçu. */
        private const val SLACK_MS = 30_000L
        private const val AFTER_MS = 60_000L
        private const val POLL_MS = 3_000L
        private const val ATTEMPTS = 22
        /** Écart toléré entre l'heure d'un message et celle de son fichier. */
        private const val MATCH_MS = 3 * 60_000L

        /** Écouteur relié par le système, s'il l'est. */
        @Volatile var instance: VoiceNoteListener? = null
            private set

        fun isEnabled(context: Context): Boolean =
            context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

        /** L'interrupteur vient d'être activé : demande au système de nous relier. */
        fun rebind(context: Context) {
            runCatching { NotificationListenerService.requestRebind(ComponentName(context, VoiceNoteListener::class.java)) }
        }
    }
}
