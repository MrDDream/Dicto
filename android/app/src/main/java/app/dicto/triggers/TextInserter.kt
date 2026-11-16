package app.dicto.triggers

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import app.dicto.ime.KeyboardLink
import app.dicto.ime.KeyboardSwitchActivity

/**
 * Écrit le texte de la bulle ou de la dictée rapide dans l'app où l'on tape, par le clavier Dicto :
 * - clavier Dicto en cours : il l'écrit aussitôt (ou dès que le champ retrouve le focus) ;
 * - clavier Dicto activé mais pas en cours : le choix du clavier s'ouvre, un appui sur Dicto
 *   écrit le texte puis remet le clavier habituel ;
 * - sinon, ou si aucun champ n'est actif : le texte est copié dans le presse-papiers.
 * À appeler sur le fil principal.
 */
object TextInserter {
    fun insert(context: Context, text: String) {
        if (text.isBlank()) return
        val app = context.applicationContext
        when {
            KeyboardLink.isCurrent() ->
                KeyboardLink.write(text, switchBack = false, timeoutMs = 3_000) {
                    copy(app, text, "Texte copié : aucun champ de saisie actif.")
                }
            KeyboardLink.isEnabled(app) -> {
                KeyboardLink.write(text, switchBack = true, timeoutMs = 20_000) {
                    copy(app, text, "Texte copié. Choisis « Clavier Dicto » pour qu'il s'écrive directement.")
                }
                KeyboardSwitchActivity.start(context)
            }
            else -> copy(app, text, "Texte copié. Active le clavier Dicto dans Déclencheurs pour l'écrire directement.")
        }
    }

    private fun copy(context: Context, text: String, message: String) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Dicto", text))
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
}
