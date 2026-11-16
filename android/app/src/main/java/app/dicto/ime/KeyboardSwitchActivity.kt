package app.dicto.ime

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import app.dicto.data.Journal

/**
 * Fenêtre invisible qui affiche le choix du clavier d'Android. Android n'ouvre ce choix
 * que pour la fenêtre reliée au clavier, c'est-à-dire celle qui a un champ de saisie actif :
 * d'où un champ caché (sans clavier affiché). Une fois Dicto choisi, le texte en attente
 * s'écrit dans le champ d'origine et le clavier habituel revient.
 */
class KeyboardSwitchActivity : Activity() {
    private var pickerShown = false
    private val main = Handler(Looper.getMainLooper())

    /** Si Android tarde à relier le champ caché, on tente quand même d'ouvrir le choix. */
    private val fallback = Runnable {
        if (!pickerShown) {
            Journal.add("choix du clavier", "champ pas relié après 800 ms, ouverture quand même")
            showPicker()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        val field = EditText(this).apply {
            showSoftInputOnFocus = false
            alpha = 0f
            isFocusableInTouchMode = true
            layoutParams = FrameLayout.LayoutParams(1, 1)
        }
        setContentView(FrameLayout(this).apply { addView(field) }, ViewGroup.LayoutParams(1, 1))
        field.requestFocus()
        Journal.add("choix du clavier", "fenêtre ouverte")
        main.postDelayed(fallback, 800)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        Journal.add("choix du clavier", if (hasFocus) "focus reçu" else "focus perdu")
        if (!hasFocus) return
        if (!pickerShown) {
            // Le temps qu'Android relie le champ caché au clavier.
            main.postDelayed({ if (!pickerShown) showPicker() }, 150)
        } else {
            // Retour du focus : le choix est fait (ou abandonné).
            finish()
            overridePendingTransition(0, 0)
        }
    }

    private fun showPicker() {
        pickerShown = true
        main.removeCallbacks(fallback)
        getSystemService(InputMethodManager::class.java).showInputMethodPicker()
        Journal.add("choix du clavier", "liste demandée à Android")
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        Journal.add("choix du clavier", "fenêtre fermée")
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            runCatching {
                context.startActivity(
                    Intent(context, KeyboardSwitchActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS),
                )
            }.onFailure { Journal.add("choix du clavier", "ouverture refusée : ${it.message}") }
        }
    }
}
