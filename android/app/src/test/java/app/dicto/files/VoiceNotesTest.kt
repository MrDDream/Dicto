package app.dicto.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceNotesTest {
    private fun dir(name: String, modified: Long) = DocEntry(docId = "id/$name", name = name, isDir = true, modified = modified, size = 0)

    @Test
    fun voiceFilesAreWhatsAppAudio() {
        assertTrue(isVoiceFile("PTT-20261118-WA0012.opus"))
        assertTrue(isVoiceFile("AUD-20261118-WA0003.M4A"))
        assertFalse(isVoiceFile(".nomedia"))
        assertFalse(isVoiceFile(".trashed-1700000000-PTT-20261118-WA0001.opus"))
        assertFalse(isVoiceFile("IMG-20261118-WA0001.jpg"))
        assertFalse(isVoiceFile("sans-extension"))
    }

    @Test
    fun newestWeeksComeFirst() {
        val folders = listOf(dir("202645", 100), dir("202647", 300), dir(".hidden", 999), dir("202646", 200))
        assertEquals(listOf("202647", "202646"), newestFolders(folders, 2).map { it.name })
    }

    @Test
    fun sameDateFallsBackToTheWeekNumber() {
        val folders = listOf(dir("202646", 0), dir("202647", 0))
        assertEquals("202647", newestFolders(folders, 1).single().name)
    }

    @Test
    fun voiceMessagesAreSpottedByTheMicrophone() {
        assertTrue(isVoiceMessage("🎤 Message vocal (0:12)"))
        assertTrue(isVoiceMessage("🎤 Voice message (1:03)"))
        assertFalse(isVoiceMessage("On se voit à 14 h ?"))
        assertFalse(isVoiceMessage(null))
    }
}
