package app.dicto.triggers

import android.app.Activity
import android.os.Bundle
import app.dicto.data.Journal

/**
 * Fenêtre invisible ouverte par la tuile quand Android refuse de démarrer la bulle depuis
 * l'arrière-plan : au premier plan, le démarrage est permis. Elle se ferme aussitôt.
 */
class BubbleToggleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { BubbleService.refresh(this) }
            .onFailure { Journal.add("tuile", "bulle non démarrée : ${it.message}") }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
