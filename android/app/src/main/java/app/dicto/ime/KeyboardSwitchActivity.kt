package app.dicto.ime

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.inputmethod.InputMethodManager

/**
 * Fenêtre invisible qui affiche le choix du clavier d'Android (il ne s'ouvre que depuis
 * une fenêtre active). Une fois le clavier Dicto choisi, le texte en attente s'écrit
 * dans le champ d'origine et le clavier habituel revient.
 */
class KeyboardSwitchActivity : Activity() {
    private var pickerShown = false

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        if (!pickerShown) {
            pickerShown = true
            getSystemService(InputMethodManager::class.java).showInputMethodPicker()
        } else {
            // Retour du focus : le choix est fait (ou abandonné).
            finish()
            overridePendingTransition(0, 0)
        }
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(
                Intent(context, KeyboardSwitchActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS),
            )
        }
    }
}
