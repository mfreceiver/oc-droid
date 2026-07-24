package cn.vectory.ocdroid.di

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §defect-A-1B: pure-JVM coverage for [shouldTriggerBackgroundCatchUp] — every
 * branch of the freshness-probe decision (no Android / Robolectric needed).
 *
 * The decision is the single seam between "the server's newest message id is
 * not yet known locally" and emitting a [cn.vectory.ocdroid.ui.controller.ControllerEffect.CatchUpAfterDisconnect]
 * from the background poll. Keeping it pure + branch-covered here means the
 * Android-heavy [AppLifecycleMonitor.pollPendingItems] path only needs ONE
 * integration assertion per outcome (emit / suppress), not a matrix.
 */
class FreshnessProbeDecisionTest {

    private val known = setOf("m1", "m2", "m3")

    /** Convenience wrapper defaulting every fence to "clear" (the trigger
     *  path); per-branch tests override exactly the one fence under test. */
    private fun decision(
        serverLatestId: String?,
        knownLocalIds: Set<String> = known,
        isLoadingMessages: Boolean = false,
        isForeground: Boolean = false,
        generationChanged: Boolean = false,
    ): Boolean = shouldTriggerBackgroundCatchUp(
        serverLatestId, knownLocalIds, isLoadingMessages, isForeground, generationChanged,
    )

    @Test
    fun `null serverLatest never triggers`() {
        assertFalse(decision(serverLatestId = null))
    }

    @Test
    fun `empty serverLatest never triggers`() {
        assertFalse(decision(serverLatestId = ""))
    }

    @Test
    fun `serverLatest already known locally never triggers`() {
        // Every locally-known id is a no-op regardless of order.
        known.forEach { id ->
            assertFalse("known id $id must not trigger", decision(serverLatestId = id))
        }
    }

    @Test
    fun `new server id with all fences clear triggers`() {
        assertTrue(decision(serverLatestId = "m-new"))
    }

    @Test
    fun `new server id but a load is in flight never triggers`() {
        assertFalse(decision("m-new", isLoadingMessages = true))
    }

    @Test
    fun `new server id but app is foreground never triggers`() {
        assertFalse(decision("m-new", isForeground = true))
    }

    @Test
    fun `new server id but lifecycle generation changed mid-probe never triggers`() {
        assertFalse(decision("m-new", generationChanged = true))
    }

    @Test
    fun `new server id against empty known set triggers`() {
        // Cold-start slice with no locally-known ids: any server id is new.
        assertTrue(decision("m-first", knownLocalIds = emptySet()))
    }

    @Test
    fun `multiple fences failing at once never triggers`() {
        assertFalse(
            decision(
                "m-new",
                isLoadingMessages = true,
                isForeground = true,
                generationChanged = true,
            ),
        )
    }
}
