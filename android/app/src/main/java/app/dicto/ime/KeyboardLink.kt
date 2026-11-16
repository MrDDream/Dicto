package app.dicto.ime

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import app.dicto.data.Journal
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
        Journal.add("bulle", "délai dépassé : le clavier Dicto n'a pas été relié au champ, texte copié")
        p.onFail()
    }

    fun component(context: Context) = ComponentName(context, DictoImeService::class.java)

    fun isEnabled(context: Context): Boolean =
        context.getSystemService(InputMethodManager::class.java).enabledInputMethodList
            .any { it.component == component(context) }

    /**
     * Le clavier Dicto est le clavier choisi dans Android. Le service peut survivre un moment
     * après le retour à Gboard : on se fie au réglage du système, pas seulement à lui.
     */
    fun isCurrent(context: Context): Boolean {
        val chosen = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        return ime != null && chosen != null && ComponentName.unflattenFromString(chosen) == component(context)
    }

    /** Un texte attend d'être écrit (passage éclair par le clavier Dicto). */
    fun hasPending(): Boolean = pending != null

    /** Le clavier vient de s'ouvrir seulement pour écrire le texte de la bulle : pas d'écoute automatique. */
    private var flash = false

    internal fun consumeFlash(): Boolean = flash.also { flash = false }

    /**
     * Écrit [text] dès que le clavier Dicto est relié à un champ d'une autre app.
     * [switchBack] : remettre ensuite le clavier précédent. [onFail] : rien n'a pu être écrit à temps.
     */
    fun write(text: String, switchBack: Boolean, timeoutMs: Long, current: Boolean, onFail: () -> Unit) {
        main.removeCallbacks(timeout)
        pending = Pending(text, switchBack, onFail)
        val keyboard = ime
        // Seulement si Dicto est vraiment le clavier choisi : un service resté en vie après le
        // retour à Gboard garde un champ périmé, le texte s'y perdrait.
        if (current && keyboard != null && keyboard.isBoundToOtherApp()) {
            deliverTo(keyboard)
        } else {
            Journal.add("bulle", "texte en attente du clavier Dicto (${timeoutMs / 1000} s)")
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
        // Champ fermé entre-temps : on ne perd pas le texte, il part dans le presse-papiers.
        if (!keyboard.writeText(p.text)) {
            Journal.add("bulle", "écriture refusée par le champ, texte copié")
            p.onFail()
            return
        }
        Journal.add("bulle", "texte écrit (${p.text.length} caractères), ${keyboard.confirmText(p.text)}")
        if (p.switchBack) {
            flash = true
            // Le texte doit être arrivé dans l'app avant de fermer le clavier : un clavier fermé
            // trop tôt voit son texte ignoré par l'app.
            main.postDelayed({ keyboard.switchBack() }, 400)
        }
    }

    /** Le clavier Dicto se ferme : plus de passage éclair en cours. */
    internal fun onDestroyed(keyboard: DictoImeService) {
        if (ime === keyboard) ime = null
        flash = false
    }
}
