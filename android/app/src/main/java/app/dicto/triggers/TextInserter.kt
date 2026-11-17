package app.dicto.triggers

import app.dicto.i18n.tr
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import app.dicto.DictoApp
import app.dicto.data.Journal
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
        val current = KeyboardLink.isCurrent(app)
        val enabled = KeyboardLink.isEnabled(app)
        Journal.add("bulle", "insérer : clavier Dicto ${if (current) "en cours" else if (enabled) "activé, pas en cours" else "non activé"}")
        when {
            current ->
                KeyboardLink.write(text, switchBack = false, timeoutMs = 3_000, current = true) {
                    copy(app, text, tr("Texte copié : aucun champ de saisie actif."))
                }
            enabled -> {
                // « Revenir à mon clavier » coupé : le clavier Dicto reste, prêt pour la phrase suivante.
                val back = DictoApp.instance.settings.imeAutoReturn.value
                KeyboardLink.write(text, switchBack = back, timeoutMs = 20_000, current = false) {
                    copy(app, text, tr("Texte copié. Choisis « Dicto » dans la liste des claviers pour qu'il s'écrive directement."))
                }
                KeyboardSwitchActivity.start(context)
            }
            else -> copy(app, text, tr("Texte copié. Active le clavier Dicto dans Réglages › Déclencheurs pour l'écrire directement."))
        }
    }

    private fun copy(context: Context, text: String, message: String) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Dicto", text))
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
}
