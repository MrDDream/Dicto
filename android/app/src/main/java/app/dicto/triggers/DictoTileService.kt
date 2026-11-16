package app.dicto.triggers

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.dicto.R

/** Tuile « Dicto » du volet des réglages rapides : ouvre la dictée rapide. */
class DictoTileService : TileService() {

    override fun onStartListening() {
        qsTile?.apply {
            state = if (QuickDictateActivity.current != null) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "Dicto"
            icon = Icon.createWithResource(this@DictoTileService, R.drawable.ic_launcher_monochrome)
            if (Build.VERSION.SDK_INT >= 29) subtitle = "Dicter"
            updateTile()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        QuickDictateActivity.current?.let {
            it.toggle()
            return
        }
        val intent = Intent(this, QuickDictateActivity::class.java)
            .putExtra("source", "tile")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
