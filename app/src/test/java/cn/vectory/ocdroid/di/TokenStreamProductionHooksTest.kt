package cn.vectory.ocdroid.di

import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.ui.BundleStamp
import cn.vectory.ocdroid.ui.SharedStateStore
import cn.vectory.ocdroid.ui.SkeletonReloadCoordinator
import cn.vectory.ocdroid.ui.controller.sse.TokenFrameCommitContext
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * B-4: verifies the strict `>` revision dedup in
 * [tokenStreamProductionHooks] (V2 §3.x.2:153), now backed by a
 * [PartRevisionLedger] with terminal tombstone semantics (rev-gpt fix).
 *
 * Tests the [TokenStreamProductionHooks.dedupPartRevision] lambda and its
 * interaction with [TokenStreamProductionHooks.onPartDone] (terminal
 * tombstone, NOT remove), [TokenStreamProductionHooks.onMessagePartRemoved],
 * [TokenStreamProductionHooks.onMessageRemoved], and
 * [TokenStreamProductionHooks.clearSessionRevisions].
 *
 * # Semantic changes from pre-rev-gpt:
 * - `onPartDone` marks the revision entry as TERMINAL instead of removing it.
 *   ALL revisions (same, lower, higher) after terminal are REJECTED.
 * - `dedupPartRevision` no longer bypasses the ledger for null revisions;
 *   null is handled within the ledger (active key: fail-open; terminal:
 *   fail-closed; new null key: bounded slot).
 * - The dedup map is per-session bounded at [PartRevisionLedger.MAX_REVISIONS_PER_SESSION].
 *
 * Each test calls [tokenStreamProductionHooks] with minimal live/mock
 * dependencies and exercises the returned hooks directly.
 */
class TokenStreamProductionHooksTest {

    private val ctx = TokenFrameCommitContext(
        expectedRouteInstance = 0L,
        bundleStamp = BundleStamp(generation = 0L, endpointFp = ""),
    )

    // ── Baseline strict `>` dedup ──────────────────────────────────────────

    @Test
    fun `dedup rejects duplicate revision`() {
        val hooks = createHooks()
        assertTrue(hooks.dedupPartRevision("s1", "m1", "p1", 5L, ctx))
        assertFalse(hooks.dedupPartRevision("s1", "m1", "p1", 5L, ctx))
    }

    @Test
    fun `dedup rejects out-of-order lower revision`() {
        val hooks = createHooks()
        assertTrue(hooks.dedupPartRevision("s1", "m1", "p1", 10L, ctx))
        assertFalse(hooks.dedupPartRevision("s1", "m1", "p1", 8L, ctx))
    }

    @Test
    fun `dedup accepts higher revision`() {
        val hooks = createHooks()
        assertTrue(hooks.dedupPartRevision("s1", "m1", "p1", 5L, ctx))
        assertTrue(hooks.dedupPartRevision("s1", "m1", "p1", 6L, ctx))
        assertFalse(hooks.dedupPartRevision("s1", "m1", "p1", 5L, ctx))
    }

    // ── Null revision semantics through production hooks ──────────────────

    @Test
    fun `dedup fail-open on null revision for active key`() {
        val hooks = createHooks()
        assertTrue(hooks.dedupPartRevision("s1", "m1", "p1", 5L, ctx))
        // Null after non-null active key: accepted (fail-open compatible).
        assertTrue(hooks.dedupPartRevision("s1", "m1", "p1", null, ctx))
        // Repeated null: still accepted.
        assertTrue(hooks.dedupPartRevision("s1", "m1", "p1", null, ctx))
    }

    @Test
    fun `dedup fail-closed on null revision after terminal`() {
        val hooks = createHooks()
        assertTrue(hooks.dedupPartRevision("s1", "m1", "p1", 5L, ctx))
        hooks.onPartDone("s1", "m1", "p1")
        // Null after terminal: must be rejected.
        assertFalse("null after terminal must be rejected", hooks.dedupPartRevision("s1", "m1", "p1", null, ctx))
    }

    @Test
    fun `dedup per-part cardinality is isolated across part IDs`() {
        val hooks = createHooks()
        assertTrue(hooks.dedupPartRevision("s1", "m1", "p1", 5L, ctx))
        assertTrue(hooks.dedupPartRevision("s1", "m1", "p2", 5L, ctx))
        assertFalse(hooks.dedupPartRevision("s1", "m1", "p2", 5L, ctx))
        assertFalse(hooks.dedupPartRevision("s1", "m1", "p1", 5L, ctx))
    }

    // ── Lifecycle cleanup (removals still clear entries) ───────────────────

    @Test
    fun `part removal clears revision entry`() {
        val hooks = createHooks()
        assertTrue(hooks.dedupPartRevision("s1", "m1", "p1", 5L, ctx))
        assertFalse(hooks.dedupPartRevision("s1", "m1", "p1", 5L, ctx))
        // onMessagePartRemoved clears the entry for this part.
        hooks.onMessagePartRemoved("s1", "m1", "p1", 7L, ctx)
        // Now rev=5 should be accepted again (entry was cleared).
        assertTrue(hooks.dedupPartRevision("s1", "m1", "p1", 5L, ctx))
    }

    @Test
    fun `message removal clears all part entries`() {
        val hooks = createHooks()
        assertTrue(hooks.dedupPartRevision("s1", "m1", "p1", 5L, ctx))
        assertTrue(hooks.dedupPartRevision("s1", "m1", "p2", 3L, ctx))
        assertFalse(hooks.dedupPartRevision("s1", "m1", "p1", 5L, ctx))
        assertFalse(hooks.dedupPartRevision("s1", "m1", "p2", 3L, ctx))

        hooks.onMessageRemoved("s1", "m1", ctx)

        assertTrue(hooks.dedupPartRevision("s1", "m1", "p1", 5L, ctx))
        assertTrue(hooks.dedupPartRevision("s1", "m1", "p2", 3L, ctx))
    }

    @Test
    fun `message removal does not affect other messages`() {
        val hooks = createHooks()
        assertTrue(hooks.dedupPartRevision("s1", "m1", "p1", 5L, ctx))
        assertTrue(hooks.dedupPartRevision("s1", "m2", "p2", 5L, ctx))
        assertFalse(hooks.dedupPartRevision("s1", "m2", "p2", 5L, ctx))

        hooks.onMessageRemoved("s1", "m1", ctx)

        assertTrue(hooks.dedupPartRevision("s1", "m1", "p1", 5L, ctx))
        assertFalse(hooks.dedupPartRevision("s1", "m2", "p2", 5L, ctx))
    }

    // ── E: onPartDone marks terminal, rejects ALL revisions ───────────────

    @Test
    fun `E onPartDone marks terminal rejects same revision`() {
        val hooks = createHooks()
        assertTrue(hooks.dedupPartRevision("s1", "m1", "p1", 5L, ctx))
        assertFalse(hooks.dedupPartRevision("s1", "m1", "p1", 5L, ctx))
        // onPartDone marks the revision entry as TERMINAL (not remove).
        hooks.onPartDone("s1", "m1", "p1")
        // Same revision after terminal: must be REJECTED (no resurrection).
        assertFalse(
            "terminal done must reject same revision, not resurrect",
            hooks.dedupPartRevision("s1", "m1", "p1", 5L, ctx),
        )
    }

    @Test
    fun `E onPartDone terminal rejects lower revision`() {
        val hooks = createHooks()
        assertTrue(hooks.dedupPartRevision("s1", "m1", "p1", 10L, ctx))
        hooks.onPartDone("s1", "m1", "p1")
        assertFalse(
            "terminal done must reject lower revision",
            hooks.dedupPartRevision("s1", "m1", "p1", 8L, ctx),
        )
    }

    @Test
    fun `E onPartDone terminal rejects strictly higher revision`() {
        val hooks = createHooks()
        assertTrue(hooks.dedupPartRevision("s1", "m1", "p1", 5L, ctx))
        hooks.onPartDone("s1", "m1", "p1")
        // A strictly higher revision after terminal: must be REJECTED
        // (terminal tombstone prevents ALL revisions until clearSession).
        assertFalse(
            "terminal done must reject strictly higher revision",
            hooks.dedupPartRevision("s1", "m1", "p1", 11L, ctx),
        )
    }

    // ── A: Terminal tombstone through production hooks ─────────────────────

    @Test
    fun `A onPartDone terminal does not affect other parts`() {
        val hooks = createHooks()
        assertTrue(hooks.dedupPartRevision("s1", "m1", "p1", 5L, ctx))
        assertTrue(hooks.dedupPartRevision("s1", "m1", "p2", 5L, ctx))
        hooks.onPartDone("s1", "m1", "p1")
        // p1 terminal → same revision rejected.
        assertFalse(hooks.dedupPartRevision("s1", "m1", "p1", 5L, ctx))
        // p2 unaffected.
        assertFalse(hooks.dedupPartRevision("s1", "m1", "p2", 5L, ctx))
        assertTrue(hooks.dedupPartRevision("s1", "m1", "p2", 6L, ctx))
    }

    // ── Capacity limiting (B: 33+ parts cap) ───────────────────────────────

    @Test
    fun `B ledger rejects 33rd distinct partId through hooks`() {
        val hooks = createHooks()
        // Admit 32 distinct (sid,mid,pid) combos.
        for (i in 1..32) {
            assertTrue("entry $i must be accepted", hooks.dedupPartRevision("s1", "m$i", "p$i", 1L, ctx))
        }
        // 33rd distinct key must be rejected (fail-closed cap).
        assertFalse(
            "33rd distinct partId must be rejected at cap",
            hooks.dedupPartRevision("s1", "m33", "p33", 1L, ctx),
        )
    }

    @Test
    fun `B clearSession recovers capacity`() {
        val hooks = createHooks()
        for (i in 1..32) {
            hooks.dedupPartRevision("s1", "m$i", "p$i", 1L, ctx)
        }
        assertFalse(hooks.dedupPartRevision("s1", "m33", "p33", 1L, ctx))

        hooks.clearSessionRevisions("s1")

        // After clear, new entries accepted.
        assertTrue("clearSession must recover capacity", hooks.dedupPartRevision("s1", "m33", "p33", 1L, ctx))
    }

    @Test
    fun `B capacity is per-session`() {
        val hooks = createHooks()
        for (i in 1..32) {
            hooks.dedupPartRevision("s1", "m$i", "p$i", 1L, ctx)
        }
        assertFalse("s1 at cap", hooks.dedupPartRevision("s1", "m33", "p33", 1L, ctx))
        // Different session still accepts.
        assertTrue("s2 must have independent capacity", hooks.dedupPartRevision("s2", "m1", "p1", 1L, ctx))
    }

    // ── C: Still-live key strict `>` ───────────────────────────────────────

    @Test
    fun `C non-terminal strict greater works`() {
        val hooks = createHooks()
        assertTrue(hooks.dedupPartRevision("s1", "m1", "p1", 5L, ctx))
        assertTrue(hooks.dedupPartRevision("s1", "m1", "p1", 6L, ctx))
        assertFalse(hooks.dedupPartRevision("s1", "m1", "p1", 5L, ctx))
        assertFalse(hooks.dedupPartRevision("s1", "m1", "p1", 6L, ctx))
        assertTrue(hooks.dedupPartRevision("s1", "m1", "p1", 10L, ctx))
    }

    @Test
    fun `C terminal rejects strictly higher revision`() {
        val hooks = createHooks()
        assertTrue(hooks.dedupPartRevision("s1", "m1", "p1", 5L, ctx))
        hooks.onPartDone("s1", "m1", "p1")
        // After terminal, even a strictly higher revision is rejected
        // (the terminal tombstone blocks ALL revisions until clearSession).
        assertFalse("terminal must reject higher revision", hooks.dedupPartRevision("s1", "m1", "p1", 10L, ctx))
    }

    @Test
    fun `C different partId with same revision after terminal unaffected`() {
        val hooks = createHooks()
        assertTrue(hooks.dedupPartRevision("s1", "m1", "p1", 5L, ctx))
        hooks.onPartDone("s1", "m1", "p1")
        // Same revision for a different partId is still accepted.
        assertTrue("different partId must be unaffected by terminal", hooks.dedupPartRevision("s1", "m1", "p2", 5L, ctx))
    }

    // ── Concurrent safety ─────────────────────────────────────────────────

    @Test
    fun `concurrent same revision accepts exactly once`() {
        val hooks = createHooks()
        val n = 50
        val accepted = AtomicInteger(0)
        val barrier = CountDownLatch(1)
        val ready = CountDownLatch(n)
        val threads = (1..n).map {
            Thread {
                ready.countDown()
                barrier.await()
                if (hooks.dedupPartRevision("s1", "m1", "p1", 5L, ctx)) {
                    accepted.incrementAndGet()
                }
            }
        }
        threads.forEach { it.start() }
        ready.await()
        barrier.countDown()
        threads.forEach { it.join(5000) } // 5s timeout
        assertEquals(1, accepted.get())
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun createHooks(): TokenStreamProductionHooks {
        val repo = mockk<OpenCodeRepository>(relaxed = true)
        val store = SharedStateStore()
        val skeletonReloadCoordinator = SkeletonReloadCoordinator(
            scope = CoroutineScope(Dispatchers.Unconfined),
            repository = repo,
            slices = store.slices,
            currentServerGroupFp = { "" },
        )
        return tokenStreamProductionHooks(
            store = store,
            skeletonReloadCoordinator = skeletonReloadCoordinator,
            appScope = CoroutineScope(Dispatchers.Unconfined),
            debounceMs = 0L,
        )
    }
}
