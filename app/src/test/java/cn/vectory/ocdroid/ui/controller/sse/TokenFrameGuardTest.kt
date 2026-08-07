package cn.vectory.ocdroid.ui.controller.sse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [TokenFrameGuard].
 */
class TokenFrameGuardTest {

    private val lock = Any()
    private val guard = TokenFrameGuard(lock)

    @Test
    fun `filterClearByGeneration allows when owner matches`() {
        val (_, gen) = guard.beginStreamIncarnation("s1")
        guard.onPartOwned("s1", gen, "p1")
        val allowed = guard.filterClearByGeneration("s1", gen, setOf("p1"))
        assertEquals(setOf("p1"), allowed)
    }

    @Test
    fun `filterClearByGeneration drops when newer generation owns the part`() {
        val (_, gen1) = guard.beginStreamIncarnation("s1")
        guard.onPartOwned("s1", gen1, "p1")
        val (_, gen2) = guard.beginStreamIncarnation("s1")
        assertTrue("gen2 must be > gen1", gen2 > gen1)
        guard.onPartOwned("s1", gen2, "p1")
        // Stale clear targeting gen1 — should be rejected
        val allowed = guard.filterClearByGeneration("s1", gen1, setOf("p1"))
        assertTrue("stale clear must be rejected", allowed.isEmpty())
    }

    @Test
    fun `filterClearByGeneration allows when no owner is registered`() {
        guard.beginStreamIncarnation("s1")
        val allowed = guard.filterClearByGeneration("s1", 1L, setOf("p-no-owner"))
        assertEquals(setOf("p-no-owner"), allowed)
    }

    @Test
    fun `onPartOwned drops stale gen claim`() {
        val (_, gen1) = guard.beginStreamIncarnation("s1")
        // Try to claim with gen=0 (stale — prior to first incarnation)
        guard.onPartOwned("s1", 0L, "p1")
        assertTrue("stale claim must be dropped", guard.ownedPartsForSid("s1").isEmpty())
        // A fresh claim with gen1 should work
        guard.onPartOwned("s1", gen1, "p1")
        assertEquals(setOf("p1"), guard.ownedPartsForSid("s1"))
    }

    @Test
    fun `beginStreamIncarnation bumps both epoch and generation monotonically`() {
        val (epoch1, gen1) = guard.beginStreamIncarnation("s1")
        assertEquals(1L, epoch1)
        assertEquals(1L, gen1)

        val (epoch2, gen2) = guard.beginStreamIncarnation("s1")
        assertEquals(2L, epoch2)
        assertTrue("generation must increase", gen2 > gen1)

        // Different sid starts at 1
        val (epoch3, gen3) = guard.beginStreamIncarnation("s2")
        assertEquals(1L, epoch3)
        assertEquals(1L, gen3)
    }

    @Test
    fun `beginSession bumps only generation`() {
        guard.beginStreamIncarnation("s1")
        val genBefore = guard.genOf("s1")
        guard.beginSession("s1")
        val genAfter = guard.genOf("s1")
        assertTrue("generation must increase on beginSession", genAfter > genBefore)
    }

    @Test
    fun `isEpochCurrent returns true for matching epoch`() {
        guard.beginStreamIncarnation("s1")
        assertTrue(guard.isEpochCurrent("s1", 1L))
    }

    @Test
    fun `isEpochCurrent returns false for stale epoch`() {
        guard.beginStreamIncarnation("s1")
        guard.beginStreamIncarnation("s1")
        assertFalse(guard.isEpochCurrent("s1", 1L))
    }

    @Test
    fun `isEpochCurrent returns false for unknown sid`() {
        assertFalse(guard.isEpochCurrent("unknown", 1L))
    }

    @Test
    fun `removeSid cleans up ownership entries`() {
        val (_, gen1) = guard.beginStreamIncarnation("s1")
        val (_, gen2) = guard.beginStreamIncarnation("s2")
        guard.onPartOwned("s1", gen1, "p1")
        guard.onPartOwned("s1", gen1, "p2")
        guard.onPartOwned("s2", gen2, "p3")
        guard.removeSid("s1")
        assertTrue("s1 entries must be removed", guard.ownedPartsForSid("s1").isEmpty())
        assertEquals("s2 entries must survive", setOf("p3"), guard.ownedPartsForSid("s2"))
    }

    @Test
    fun `epochOf and genOf return 0 for unknown sid`() {
        assertEquals(0L, guard.epochOf("unknown"))
        assertEquals(0L, guard.genOf("unknown"))
    }

    @Test
    fun `bumpEpochForTest increments epoch`() {
        guard.beginStreamIncarnation("s1")
        val bumped = guard.bumpEpochForTest("s1")
        assertEquals(2L, bumped)
        assertEquals(2L, guard.epochOf("s1"))
    }

    @Test
    fun `filterClearByGeneration removes entry on match`() {
        val (_, gen) = guard.beginStreamIncarnation("s1")
        guard.onPartOwned("s1", gen, "p1")
        guard.filterClearByGeneration("s1", gen, setOf("p1"))
        // After filterClearByGeneration removes the match, ownedPartsForSid should be empty
        assertTrue(guard.ownedPartsForSid("s1").isEmpty())
    }
}
