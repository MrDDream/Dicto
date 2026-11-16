package app.dicto.i18n

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/**
 * Langue de l'interface : le texte source est en français ; [tr] rend sa version anglaise (table [EnStrings])
 * quand l'anglais est actif. « auto » suit la langue du téléphone (français s'il est en français, sinon anglais).
 * L'état est observable par Compose : changer de langue redessine les écrans ouverts.
 */
object I18n {
    private var current by mutableStateOf("auto")

    /** « auto », « fr » ou « en ». */
    val mode: String get() = current

    /** Forcé par les tests de captures (« fr » ou « en »), prioritaire sur [mode]. */
    @Volatile var override: String? = null

    val english: Boolean
        get() = when (override ?: mode) {
            "en" -> true
            "fr" -> false
            else -> !Locale.getDefault().language.equals("fr", ignoreCase = true)
        }

    fun setMode(value: String) {
        current = value.takeIf { it == "fr" || it == "en" } ?: "auto"
    }

    /** La langue active, pour les réglages qui en dépendent (« fr » ou « en »). */
    val code: String get() = if (english) "en" else "fr"
}

/** [fr] dans la langue active ; les formats utilisent `%1$s`, `%2$s`… Sans traduction, le français reste. */
fun tr(fr: String, vararg args: Any?): String {
    val pattern = if (I18n.english) EnStrings.map[fr] ?: fr else fr
    return if (args.isEmpty()) pattern else String.format(Locale.ROOT, pattern, *args)
}
