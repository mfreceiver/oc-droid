package cn.vectory.ocdroid.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * ③ ServerCompat layer A — version parsing & [ServerCompatProfile.isAtLeast]
 * semantics. Behavioural flag consumers are added alongside each future shim
 * migration; this suite pins the parsing contract they will rely on.
 */
class ServerCompatProfileTest {

    @Test
    fun `parses plain semver`() {
        val p = ServerCompatProfile()
        p.update("1.17.13")
        assertEquals("1.17.13", p.version)
        assertEquals(1, p.major)
        assertEquals(17, p.minor)
        assertEquals(13, p.patch)
    }

    @Test
    fun `strips build metadata and pre-release suffixes`() {
        val p = ServerCompatProfile()
        p.update("1.17.13+sha.abc")
        assertEquals(1, p.major); assertEquals(17, p.minor); assertEquals(13, p.patch)
        p.update("1.17.13-rc1")
        assertEquals(1, p.major); assertEquals(17, p.minor); assertEquals(13, p.patch)
    }

    @Test
    fun `accepts leading v`() {
        val p = ServerCompatProfile()
        p.update("v1.17.13")
        assertEquals(1, p.major); assertEquals(17, p.minor); assertEquals(13, p.patch)
    }

    @Test
    fun `two-component version leaves patch null`() {
        val p = ServerCompatProfile()
        p.update("1.17")
        assertEquals(1, p.major); assertEquals(17, p.minor); assertNull(p.patch)
    }

    @Test
    fun `malformed version resets components to null without throwing`() {
        val p = ServerCompatProfile()
        p.update("1.17.13")
        // then a garbage value wipes the parsed state
        p.update("not-a-version")
        assertEquals("not-a-version", p.version)
        assertNull(p.major); assertNull(p.minor); assertNull(p.patch)
    }

    @Test
    fun `null or blank resets to unknown`() {
        val p = ServerCompatProfile()
        p.update("1.17.13")
        p.update(null)
        assertNull(p.version); assertNull(p.major)
        p.update("1.17.13")
        p.update("   ")
        assertNull(p.major)
    }

    @Test
    fun `isAtLeast compares major then minor then patch`() {
        val p = ServerCompatProfile()
        p.update("1.17.13")
        assertTrue(p.isAtLeast(1, 17, 13))   // equal
        assertTrue(p.isAtLeast(1, 17, 0))    // newer patch
        assertTrue(p.isAtLeast(1, 16, 99))   // newer minor
        assertTrue(p.isAtLeast(0, 99, 99))   // newer major
        assertFalse(p.isAtLeast(1, 17, 14))  // older patch
        assertFalse(p.isAtLeast(1, 18, 0))   // older minor
        assertFalse(p.isAtLeast(2, 0, 0))    // older major
    }

    @Test
    fun `isAtLeast is fail-open when version is unknown`() {
        // Critical contract: an unrecognized server must NOT silently disable
        // gated features. Callers needing "known-new only" check version==null.
        val p = ServerCompatProfile()
        assertTrue("unknown server must compare as newest", p.isAtLeast(99, 99, 99))
    }

    @Test
    fun `isAtLeast treats null patch as zero`() {
        val p = ServerCompatProfile()
        p.update("1.17") // patch null
        assertTrue(p.isAtLeast(1, 17, 0))
        assertFalse(p.isAtLeast(1, 17, 1))
    }

    @Test
    fun `update is idempotent for the same version`() {
        val p = ServerCompatProfile()
        repeat(5) { p.update("1.17.13") }
        assertEquals(1, p.major); assertEquals(17, p.minor); assertEquals(13, p.patch)
    }
}
