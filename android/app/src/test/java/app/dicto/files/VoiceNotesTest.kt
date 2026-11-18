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
        val folders = listOf(dir("202645", 100), dir("202647", 300), dir(".hidden", 999), dir("Sent", 999), dir("202646", 200))
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

    @Test
    fun senderShowsThePersonThenTheGroup() {
        assertEquals("Marie · Famille", senderLabel("Marie", "Famille (3 messages)"))
        assertEquals("Paul", senderLabel("Paul", null))
        assertEquals("Paul", senderLabel("Paul", "Paul"))
        assertEquals("Famille", senderLabel(null, "Famille"))
        assertEquals(null, senderLabel(" ", null))
    }

    @Test
    fun messagesFindTheFileOfTheSameTime() {
        val minute = 60_000L
        // Deux vocaux à 10:00 et 10:05 ; fichiers à 10:05:20, 10:00:10 et 9:30.
        val matches = matchByTime(listOf(600 * minute, 605 * minute), listOf(605 * minute + 20_000, 600 * minute + 10_000, 570 * minute), 3 * minute)
        assertEquals(mapOf(0 to 1, 1 to 0), matches)
        // Trop loin : pas de rattachement.
        assertEquals(emptyMap<Int, Int>(), matchByTime(listOf(600 * minute), listOf(500 * minute), 3 * minute))
        // Un seul fichier pour deux messages : il va au plus proche.
        assertEquals(mapOf(1 to 0), matchByTime(listOf(600 * minute, 601 * minute), listOf(601 * minute), 3 * minute))
    }

    @Test
    fun theReceivedFileIsTheOneClosestToTheNotification() {
        val posted = 1_000_000L
        // Reçu 5 s avant la notification ; ton vocal de réponse 40 s après : le reçu gagne.
        assertEquals(0, nearestFile(listOf(posted - 5_000, posted + 40_000), posted, 30_000, 60_000))
        // Seul ton vocal enregistré 2 min plus tard : hors fenêtre, rien n'est pris.
        assertEquals(null, nearestFile(listOf(posted + 120_000), posted, 30_000, 60_000))
        // Un vieux vocal d'avant la notification n'est pas pris non plus.
        assertEquals(null, nearestFile(listOf(posted - 300_000), posted, 30_000, 60_000))
    }

    @Test
    fun filterByNameAndKeyword() {
        val text = "J'ai trouvé un petit manteau et repris de la litière pour le chat."
        assertTrue(matchesFilter("Marie · Famille", text, null, ""))
        assertTrue(matchesFilter("Marie · Famille", text, "Famille", ""))
        assertTrue(matchesFilter("Marie · Famille", text, "Marie", "litiere chat"))
        assertTrue(matchesFilter("Élodie", null, null, "elodie"))
        assertFalse(matchesFilter("Marie · Famille", text, "Paul", ""))
        assertFalse(matchesFilter(null, text, "Marie", ""))
        assertFalse(matchesFilter("Marie", text, null, "chien"))
        assertTrue(matchesFilter(null, text, null, "  MANTEAU  "))
    }

    @Test
    fun quickFiltersAreTheMostFrequentNames() {
        val senders = listOf("Marie · Famille", "Paul", "Marie · Famille", "Paul", "Paul", "Zoé")
        assertEquals(listOf("Paul", "Famille", "Marie"), topNames(senders, 3))
    }
}
