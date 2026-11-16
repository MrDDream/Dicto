package app.dicto.i18n

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class I18nTest {
    @After
    fun reset() {
        I18n.override = null
    }

    @Test
    fun englishComesFromTheTable() {
        I18n.override = "en"
        assertTrue(EnStrings.map.size > 300)
        assertEquals("Settings", tr("Réglages"))
        assertEquals("Delete Parakeet v3?", tr("Supprimer %1\$s ?", "Parakeet v3"))
        assertEquals("42% · 1 MB of 2 MB", tr("%1\$s %% · %2\$s sur %3\$s", 42, "1 MB", "2 MB"))
    }

    @Test
    fun frenchStaysAndUnknownTextFallsBack() {
        I18n.override = "fr"
        assertEquals("Réglages", tr("Réglages"))
        assertEquals("Interrompu à 12 %", tr("Interrompu à %1\$s %%", 12))
        I18n.override = "en"
        assertEquals("Texte sans traduction", tr("Texte sans traduction"))
    }

    @Test
    fun everyTranslationKeepsItsPlaceholders() {
        val placeholder = Regex("%(\\d+\\$)?[sd%]")
        for ((fr, en) in EnStrings.map) {
            assertEquals("« $fr »", placeholder.findAll(fr).map { it.value }.sorted().toList(), placeholder.findAll(en).map { it.value }.sorted().toList())
        }
    }
}
