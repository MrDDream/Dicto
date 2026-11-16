package app.dicto.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class UpdateCheckerTest {
    @Test
    fun versionsCompareByNumbers() {
        assertTrue(UpdateChecker.isNewer("v0.1.1", "0.1.0"))
        assertTrue(UpdateChecker.isNewer("0.10.0", "0.9.9"))
        assertTrue(UpdateChecker.isNewer("1.0", "0.99.0"))
        assertFalse(UpdateChecker.isNewer("v0.1.0", "0.1.0"))
        assertFalse(UpdateChecker.isNewer("0.0.9", "0.1.0"))
        assertFalse(UpdateChecker.isNewer("0.1.0-beta", "0.1.0"))
    }

    private fun release(vararg names: String) = UpdateChecker.Release(
        tag = "v0.2.0",
        notes = "",
        prerelease = false,
        page = "",
        assets = names.map { UpdateChecker.Asset(it, "https://example.org/$it", 1) },
    )

    @Test
    fun thePhonesApkIsPickedThenTheUniversalOne() {
        val r = release("Dicto-v0.2.0.apk", "Dicto-v0.2.0-arm64-v8a.apk", "Dicto-v0.2.0-x86_64.apk", "Dicto-0.2.0-setup.exe")
        assertEquals("Dicto-v0.2.0-arm64-v8a.apk", UpdateChecker.apkFor(r, listOf("arm64-v8a", "armeabi-v7a"))?.name)
        assertEquals("Dicto-v0.2.0.apk", UpdateChecker.apkFor(r, listOf("armeabi-v7a"))?.name)
        assertNull(UpdateChecker.apkFor(release("Dicto-0.2.0-setup.exe"), listOf("arm64-v8a")))
    }

    @Test
    fun checksumsAreReadLikeSha256sumWritesThem() {
        val a = "a".repeat(64)
        val sums = "$a  Dicto-v0.2.0.apk\n${"B".repeat(64)} *Dicto-v0.2.0-x86_64.apk\n"
        assertEquals(a, UpdateChecker.expectedSha256(sums, "Dicto-v0.2.0.apk"))
        assertEquals("b".repeat(64), UpdateChecker.expectedSha256(sums, "Dicto-v0.2.0-x86_64.apk"))
        assertNull(UpdateChecker.expectedSha256(sums, "missing.apk"))
        assertEquals(a, UpdateChecker.requireSha256(sums, "Dicto-v0.2.0.apk"))
        assertThrows(IOException::class.java) { UpdateChecker.requireSha256(null, "Dicto-v0.2.0.apk") }
        assertThrows(IOException::class.java) { UpdateChecker.requireSha256(sums, "missing.apk") }
    }

    @Test
    fun onlyTheSameKeyOrItsRotationMayUpdate() {
        assertTrue(UpdateChecker.signersMatch(setOf("k1"), setOf("k1"), emptySet()))
        assertTrue(UpdateChecker.signersMatch(setOf("k1"), setOf("k2"), setOf("k1", "k2")))
        assertFalse(UpdateChecker.signersMatch(setOf("k1"), setOf("k2"), emptySet()))
        assertFalse(UpdateChecker.signersMatch(emptySet(), setOf("k1"), emptySet()))
    }

    @Test
    fun markdownBecomesPlainText() {
        assertEquals("Nouveau\n• x devient x", plain("## Nouveau\n- **x** devient `x`"))
    }
}
