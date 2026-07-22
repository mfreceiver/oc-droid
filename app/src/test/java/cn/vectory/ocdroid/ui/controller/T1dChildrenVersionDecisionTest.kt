package cn.vectory.ocdroid.ui.controller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1d freeze — **RED until impl**. R3 childrenVersion decision policy
 * (full-refactor-plan §R3 + handoff "R3 pure only").
 *
 * **Goal**: a PURE decision function that determines whether to refresh a
 * session's children (sub-agents) given local/remote version +
 * server-generation pairs. No production childrenVersion wiring exists yet
 * (slimapi Batch4 pending); this freeze locks the decision API + policy so
 * the impl can wire it later without ambiguity.
 *
 * **Expected impl surface** (impl MUST add — RED until these exist):
 *
 * ```kotlin
 * package cn.vectory.ocdroid.ui.controller
 *
 * sealed interface ChildrenRefreshDecision {
 *     /** Remote version is strictly newer than local within the same
 *      *  server generation — fetch the children. */
 *     data object Refresh : ChildrenRefreshDecision
 *     /** Remote version is equal-or-older within the same generation —
 *      *  skip the fetch (local is authoritative or up-to-date). */
 *     data object Skip : ChildrenRefreshDecision
 *     /** Server generation changed (host switch / server restart) —
 *      *  versions are incomparable across generations; refresh as the
 *      *  Y-gateway fallback (baseline invalidated). */
 *     data object RefreshCrossGeneration : ChildrenRefreshDecision
 *     /** Version info is null/missing on one or both sides — safe
 *      *  fallback refresh (cannot make a version-based decision). */
 *      data object RefreshNullFallback : ChildrenRefreshDecision
 * }
 *
 * fun decideChildrenRefresh(
 *     localVersion: Long?,
 *     remoteVersion: Long?,
 *     localServerGeneration: Long?,
 *     remoteServerGeneration: Long?,
 * ): ChildrenRefreshDecision
 * ```
 *
 * **Policy (locked per handoff §R3)**:
 *  - same generation + remote > local → **Refresh**
 *  - same generation + remote == local → **Skip**
 *  - same generation + remote < local → **Skip** (local is newer — stale
 *    remote response; do not regress)
 *  - **generation mismatch** (local != remote) → **RefreshCrossGeneration**
 *    (never compare version magnitude across generations; Y-fallback: the
 *    baseline is invalidated, refresh to re-establish)
 *  - any null version → **RefreshNullFallback** (safe default: cannot decide)
 *
 * **RED kind**: `compile-error` — `ChildrenRefreshDecision` and
 * `decideChildrenRefresh` are unresolved references.
 */
class T1dChildrenVersionDecisionTest {

    // ── same generation ────────────────────────────────────────────────────

    @Test
    fun `same generation remote newer than local decides Refresh`() {
        val decision = decideChildrenRefresh(
            localVersion = 3L,
            remoteVersion = 5L,
            localServerGeneration = 1L,
            remoteServerGeneration = 1L,
        )
        assertEquals(
            "same gen + remote(5) > local(3) → Refresh",
            ChildrenRefreshDecision.Refresh,
            decision,
        )
    }

    @Test
    fun `same generation remote equals local decides Skip`() {
        val decision = decideChildrenRefresh(
            localVersion = 5L,
            remoteVersion = 5L,
            localServerGeneration = 1L,
            remoteServerGeneration = 1L,
        )
        assertEquals(
            "same gen + remote(5) == local(5) → Skip (already up-to-date)",
            ChildrenRefreshDecision.Skip,
            decision,
        )
    }

    @Test
    fun `same generation remote older than local decides Skip (do not regress)`() {
        // Local is NEWER than remote — the remote response is stale. Do NOT
        // overwrite a newer local with an older remote. This guards against
        // a delayed REST response arriving after a newer SSE update.
        val decision = decideChildrenRefresh(
            localVersion = 7L,
            remoteVersion = 4L,
            localServerGeneration = 1L,
            remoteServerGeneration = 1L,
        )
        assertEquals(
            "same gen + remote(4) < local(7) → Skip (local is newer — stale remote)",
            ChildrenRefreshDecision.Skip,
            decision,
        )
    }

    // ── cross generation ───────────────────────────────────────────────────

    @Test
    fun `generation mismatch decides RefreshCrossGeneration (never compare version magnitude across gens)`() {
        // Even if remote version is "lower", a generation change invalidates
        // the baseline — versions are incomparable across server generations
        // (host switch / server restart resets the version sequence).
        val decision = decideChildrenRefresh(
            localVersion = 10L,
            remoteVersion = 2L,
            localServerGeneration = 1L,
            remoteServerGeneration = 2L, // different generation
        )
        assertEquals(
            "generation mismatch → RefreshCrossGeneration (Y-fallback: baseline invalidated)",
            ChildrenRefreshDecision.RefreshCrossGeneration,
            decision,
        )
    }

    @Test
    fun `generation mismatch with higher remote version still decides RefreshCrossGeneration (not plain Refresh)`() {
        // Even with remote > local, the generation difference means we CANNOT
        // trust the version comparison — it's a cross-generation refresh, not
        // a same-generation version bump.
        val decision = decideChildrenRefresh(
            localVersion = 1L,
            remoteVersion = 100L,
            localServerGeneration = 2L,
            remoteServerGeneration = 1L, // different generation
        )
        assertEquals(
            "generation mismatch (even with remote > local) → RefreshCrossGeneration",
            ChildrenRefreshDecision.RefreshCrossGeneration,
            decision,
        )
    }

    // ── null fallback ──────────────────────────────────────────────────────

    @Test
    fun `null local version decides RefreshNullFallback`() {
        val decision = decideChildrenRefresh(
            localVersion = null,
            remoteVersion = 5L,
            localServerGeneration = 1L,
            remoteServerGeneration = 1L,
        )
        assertEquals(
            "null localVersion → RefreshNullFallback (cannot decide without local baseline)",
            ChildrenRefreshDecision.RefreshNullFallback,
            decision,
        )
    }

    @Test
    fun `null remote version decides RefreshNullFallback`() {
        val decision = decideChildrenRefresh(
            localVersion = 3L,
            remoteVersion = null,
            localServerGeneration = 1L,
            remoteServerGeneration = 1L,
        )
        assertEquals(
            "null remoteVersion → RefreshNullFallback (cannot decide without remote value)",
            ChildrenRefreshDecision.RefreshNullFallback,
            decision,
        )
    }

    @Test
    fun `both null versions decides RefreshNullFallback`() {
        val decision = decideChildrenRefresh(
            localVersion = null,
            remoteVersion = null,
            localServerGeneration = 1L,
            remoteServerGeneration = 1L,
        )
        assertEquals(
            "both null → RefreshNullFallback",
            ChildrenRefreshDecision.RefreshNullFallback,
            decision,
        )
    }

    @Test
    fun `null server generations with non-null versions decides RefreshNullFallback`() {
        // Without generation info, we cannot determine whether the versions
        // are comparable. Safe fallback.
        val decision = decideChildrenRefresh(
            localVersion = 3L,
            remoteVersion = 5L,
            localServerGeneration = null,
            remoteServerGeneration = null,
        )
        assertEquals(
            "null generations + non-null versions → RefreshNullFallback (cannot establish gen parity)",
            ChildrenRefreshDecision.RefreshNullFallback,
            decision,
        )
    }

    // ── decision is Refresh-like (all non-Skip outcomes should trigger a fetch) ─

    @Test
    fun `all non-Skip decisions are Refresh-like (trigger a children fetch)`() {
        // The caller wires: decision is Skip → no-op; else → fetch.
        // Pin that every non-Skip decision is distinct from Skip.
        val refresh = decideChildrenRefresh(3L, 5L, 1L, 1L)
        val crossGen = decideChildrenRefresh(3L, 5L, 1L, 2L)
        val nullFallback = decideChildrenRefresh(null, 5L, 1L, 1L)

        assertNotSkip(refresh, "Refresh")
        assertNotSkip(crossGen, "RefreshCrossGeneration")
        assertNotSkip(nullFallback, "RefreshNullFallback")
    }

    private fun assertNotSkip(decision: ChildrenRefreshDecision, label: String) {
        assertTrue(
            "$label must NOT be Skip (caller treats non-Skip as 'fetch children')",
            decision !is ChildrenRefreshDecision.Skip,
        )
    }
}
