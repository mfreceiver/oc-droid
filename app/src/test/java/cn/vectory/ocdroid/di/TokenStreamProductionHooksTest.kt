package cn.vectory.ocdroid.di

import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.ui.BundleStamp
import cn.vectory.ocdroid.ui.SharedStateStore
import cn.vectory.ocdroid.ui.SkeletonReloadCoordinator
import cn.vectory.ocdroid.ui.controller.sse.TokenFrameCommitContext
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B-4: verifies the strict `>` revision dedup in
 * [tokenStreamProductionHooks] (V2 §3.x.2:153). Tests the
 * [TokenStreamProductionHooks.dedupPartRevision] lambda and its interaction
 * with [TokenStreamProductionHooks.onMessagePartRemoved] /
 * [TokenStreamProductionHooks.onMessageRemoved] lifecycle cleanup.
 *
 * Each test calls [tokenStreamProductionHooks] with minimal live/mock
 * dependencies and exercises the returned hooks directly.
 */
class TokenStreamProductionHooksTest {

    private val ctx = TokenFrameCommitContext(
        expectedRouteInstance = 0L,
        bundleStamp = BundleStamp(generation = 0L, endpointFp = ""),
    )

    @Test
    fun `dedup rejects duplicate revision`() {
        val hooks = createHooks()
        // First frame with rev=5 accepted.
        assertTrue(hooks.dedupPartRevision("s1", "m1", "p1", 5L, ctx))
        // Second frame with same rev=5 for same (sid,mid,pid) rejected.
        assertFalse(hooks.dedupPartRevision("s1", "m1", "p1", 5L, ctx))
    }

    @Test
    fun `dedup rejects out-of-order lower revision`() {
        val hooks = createHooks()
        // First frame with rev=10 accepted.
        assertTrue(hooks.dedupPartRevision("s1", "m1", "p1", 10L, ctx))
        // Second frame with rev=8 (lower than last applied) rejected.
        assertFalse(hooks.dedupPartRevision("s1", "m1", "p1", 8L, ctx))
    }

    @Test
    fun `dedup accepts higher revision`() {
        val hooks = createHooks()
        // rev=5 accepted.
        assertTrue(hooks.dedupPartRevision("s1", "m1", "p1", 5L, ctx))
        // rev=6 (higher) accepted.
        assertTrue(hooks.dedupPartRevision("s1", "m1", "p1", 6L, ctx))
        // rev=5 again (lower than 6) rejected.
        assertFalse(hooks.dedupPartRevision("s1", "m1", "p1", 5L, ctx))
    }

    @Test
    fun `dedup fail-open on null revision`() {
        val hooks = createHooks()
        // Establish a non-null revision first.
        assertTrue(hooks.dedupPartRevision("s1", "m1", "p1", 5L, ctx))
        // Null revision always returns true regardless of prior state.
        assertTrue(hooks.dedupPartRevision("s1", "m1", "p1", null, ctx))
        assertTrue(hooks.dedupPartRevision("s1", "m1", "p1", null, ctx))
    }

    @Test
    fun `dedup per-part cardinality is isolated across part IDs`() {
        val hooks = createHooks()
        // Two different parts in the same message have independent revision tracking:
        // p1 rev=5 and p2 rev=5 are both accepted (different parts, same rev is fine).
        assertTrue(hooks.dedupPartRevision("s1", "m1", "p1", 5L, ctx))
        assertTrue(hooks.dedupPartRevision("s1", "m1", "p2", 5L, ctx))
        // p2 rev=5 again → rejected (duplicate for p2, strict `>` only).
        assertFalse(hooks.dedupPartRevision("s1", "m1", "p2", 5L, ctx))
        // p1 rev=5 again → rejected (duplicate for p1).
        assertFalse(hooks.dedupPartRevision("s1", "m1", "p1", 5L, ctx))
    }

    @Test
    fun `part removal clears revision entry`() {
        val hooks = createHooks()
        // First rev=5 accepted.
        assertTrue(hooks.dedupPartRevision("s1", "m1", "p1", 5L, ctx))
        // Duplicate rejected.
        assertFalse(hooks.dedupPartRevision("s1", "m1", "p1", 5L, ctx))
        // onMessagePartRemoved clears the entry for this part.
        hooks.onMessagePartRemoved("s1", "m1", "p1", 7L, ctx)
        // Now rev=5 should be accepted again (entry was cleared).
        assertTrue(hooks.dedupPartRevision("s1", "m1", "p1", 5L, ctx))
    }

    @Test
    fun `message removal clears all part entries`() {
        val hooks = createHooks()
        // Two parts for the same message, both accepted.
        assertTrue(hooks.dedupPartRevision("s1", "m1", "p1", 5L, ctx))
        assertTrue(hooks.dedupPartRevision("s1", "m1", "p2", 3L, ctx))
        // Both rejected on re-delivery.
        assertFalse(hooks.dedupPartRevision("s1", "m1", "p1", 5L, ctx))
        assertFalse(hooks.dedupPartRevision("s1", "m1", "p2", 3L, ctx))

        // onMessageRemoved clears all entries for (s1,m1).
        hooks.onMessageRemoved("s1", "m1", ctx)

        // Both should be accepted again after message removal.
        assertTrue(hooks.dedupPartRevision("s1", "m1", "p1", 5L, ctx))
        assertTrue(hooks.dedupPartRevision("s1", "m1", "p2", 3L, ctx))
    }

    @Test
    fun `message removal does not affect other messages`() {
        val hooks = createHooks()
        // Part in m1 and part in m2.
        assertTrue(hooks.dedupPartRevision("s1", "m1", "p1", 5L, ctx))
        assertTrue(hooks.dedupPartRevision("s1", "m2", "p2", 5L, ctx))
        // m2 duplicate rejected.
        assertFalse(hooks.dedupPartRevision("s1", "m2", "p2", 5L, ctx))

        // Remove m1 only.
        hooks.onMessageRemoved("s1", "m1", ctx)

        // m1 part should be re-acceptable.
        assertTrue(hooks.dedupPartRevision("s1", "m1", "p1", 5L, ctx))
        // m2 part should still be rejected (not cleared).
        assertFalse(hooks.dedupPartRevision("s1", "m2", "p2", 5L, ctx))
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
            debounceMs = 0L, // Immediate debounce for deterministic tests.
        )
    }
}
