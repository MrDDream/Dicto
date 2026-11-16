package app.dicto.ime

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.InputMethodManager

/**
 * Lien entre les déclencheurs (bulle, dictée rapide, « OK Dicto ») et le clavier Dicto,
 * seule voie pour écrire dans une autre app sans service d'accessibilité.
 *
 * Le texte attend ici jusqu'à ce que le clavier Dicto soit relié au champ de l'app
 * (aussitôt s'il l'est déjà, sinon après le retour du focus ou le choix du clavier).
 * À utiliser sur le fil principal.
 */
object KeyboardLink {
    /** Le clavier Dicto, quand il est le clavier en cours (Android ne lie que celui-là). */
    @Volatile var ime: DictoImeService? = null
        internal set

    @Volatile private var app: String? = null

    /** Dernière app où le clavier Dicto est en cours, pour les profils. */
    val lastApp: String? get() = if (ime != null) app else null

    private class Pending(val text: String, val switchBack: Boolean, val onFail: () -> Unit)

    private val main = Handler(Looper.getMainLooper())
    private var pending: Pending? = null
    private val timeout = Runnable {
        val p = pending ?: return@Runnable
        pending = null
        p.onFail()
    }

    fun component(context: Context) = ComponentName(context, DictoImeService::class.java)

    fun isEnabled(context: Context): Boolean =
        context.getSystemService(InputMethodManager::class.java).enabledInputMethodList
            .any { it.component == component(context) }

    fun isCurrent(): Boolean = ime != null

    /** Un texte attend d'être écrit (passage éclair par le clavier Dicto). */
    fun hasPending(): Boolean = pending != null

    /**
     * Écrit [text] dès que le clavier Dicto est relié à un champ d'une autre app.
     * [switchBack] : remettre ensuite le clavier précédent. [onFail] : rien n'a pu être écrit à temps.
     */
    fun write(text: String, switchBack: Boolean, timeoutMs: Long, onFail: () -> Unit) {
        main.removeCallbacks(timeout)
        pending = Pending(text, switchBack, onFail)
        val keyboard = ime
        if (keyboard != null && keyboard.isBoundToOtherApp()) {
            deliverTo(keyboard)
        } else {
            main.postDelayed(timeout, timeoutMs)
        }
    }

    /** Appelé par le clavier à chaque nouveau champ. */
    internal fun onStartInput(keyboard: DictoImeService, packageName: String?) {
        if (packageName != null && packageName != keyboard.packageName) app = packageName
        if (pending != null && keyboard.isBoundToOtherApp()) deliverTo(keyboard)
    }

    private fun deliverTo(keyboard: DictoImeService) {
        val p = pending ?: return
        pending = null
        main.removeCallbacks(timeout)
        keyboard.writeText(p.text)
        if (p.switchBack) main.post { keyboard.switchBack() }
    }
}
