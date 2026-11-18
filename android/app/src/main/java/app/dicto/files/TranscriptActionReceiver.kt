package app.dicto.files

import app.dicto.i18n.tr
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import app.dicto.ui.copy

/** Bouton « Copier » de la notification d'un vocal transcrit. */
class TranscriptActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        copy(context, text)
        // Android 13+ confirme lui-même la copie.
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, tr("Texte copié."), Toast.LENGTH_SHORT).show()
    }

    companion object {
        fun copyAction(context: Context, id: Long, text: String): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                id.toInt(),
                Intent(context, TranscriptActionReceiver::class.java).putExtra(Intent.EXTRA_TEXT, text),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        /** Partager : une activité directement (Android bloque l'ouverture d'une activité depuis un récepteur de notification). */
        fun shareAction(context: Context, id: Long, text: String): PendingIntent {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            return PendingIntent.getActivity(
                context,
                id.toInt(),
                Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
