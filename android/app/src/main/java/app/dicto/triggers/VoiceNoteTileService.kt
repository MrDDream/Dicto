package app.dicto.triggers

import app.dicto.i18n.tr
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.dicto.R
import app.dicto.files.ShareTranscribeActivity

/**
 * Tuile « Dernier vocal » : transcrit le message vocal WhatsApp le plus récent, dans le
 * panneau qui s'affiche par-dessus l'app en cours.
 */
class VoiceNoteTileService : TileService() {

    override fun onStartListening() {
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = tr("Dernier vocal")
            icon = Icon.createWithResource(this@VoiceNoteTileService, R.drawable.ic_launcher_monochrome)
            if (Build.VERSION.SDK_INT >= 29) subtitle = tr("Transcrire")
            updateTile()
        }
    }

    override fun onClick() {
        if (isLocked) unlockAndRun { open() } else open()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun open() {
        val intent = ShareTranscribeActivity.latestIntent(this)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 1, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
