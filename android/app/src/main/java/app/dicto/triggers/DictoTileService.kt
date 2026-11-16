package app.dicto.triggers

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.dicto.DictoApp
import app.dicto.R
import app.dicto.data.Journal
import app.dicto.ui.MainActivity

/** Tuile « Dicto » du volet des réglages rapides : affiche ou masque la bulle flottante. */
class DictoTileService : TileService() {

    override fun onStartListening() = updateTile()

    private fun updateTile() {
        val on = DictoApp.instance.settings.bubble.value && BubbleService.canRun(this)
        qsTile?.apply {
            state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "Bulle Dicto"
            icon = Icon.createWithResource(this@DictoTileService, R.drawable.ic_launcher_monochrome)
            if (Build.VERSION.SDK_INT >= 29) subtitle = if (on) "Affichée" else "Masquée"
            updateTile()
        }
    }

    override fun onClick() {
        // Sans « Afficher par-dessus les autres apps », la bulle ne peut pas s'afficher :
        // on ouvre Dicto, où l'écran Déclencheurs explique comment l'autoriser.
        if (!BubbleService.canRun(this)) {
            open(Intent(this, MainActivity::class.java))
            return
        }
        val settings = DictoApp.instance.settings.bubble
        settings.set(!settings.value)
        Journal.add("tuile", if (settings.value) "bulle affichée" else "bulle masquée")
        try {
            BubbleService.refresh(this)
        } catch (e: IllegalStateException) {
            // Android 12+ peut refuser un service de premier plan lancé depuis l'arrière-plan :
            // on passe par une fenêtre invisible, au premier plan.
            Journal.add("tuile", "démarrage direct refusé, passage par une fenêtre : ${e.message}")
            open(Intent(this, BubbleToggleActivity::class.java))
        }
        updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun open(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
