package cn.vectory.ocdroid.service.streaming

import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.data.state.AuthorityState
import cn.vectory.ocdroid.data.state.EntryOrigin
import cn.vectory.ocdroid.data.state.Freshness
import cn.vectory.ocdroid.data.state.OptimisticClaim
import cn.vectory.ocdroid.data.state.ScopeKey
import cn.vectory.ocdroid.data.state.SessionEntry
import cn.vectory.ocdroid.service.identity.ConnectionIdentity
import cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
import cn.vectory.ocdroid.ui.OPTIMISTIC_CONFIRM_TIMEOUT_MS
import cn.vectory.ocdroid.ui.StaleClaim
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §U-P2 (Batch 2) — unit tests for [OptimisticClaimWatchdogCoordinator].
 *
 * Covers the design-doc §2.U-P2 test strategy (lines 543-549):
 *  1. **SLA**: a stale unconfirmed claim is detected within ONE tick of the
 *     [OPTIMISTIC_CONFIRM_TIMEOUT_MS] window (the independent 5s timer, not
 *     the 30s poller tick).
 *  2. **idempotent start/stop**: repeat start() does not stack sinks; stop()
 *     leaves no residual tick.
 *  3. **identity switch**: a host switch between ticks → tick does NOT sink
 *     (identity re-check).
 *  4. **empty authority**: no claim → no sink.
 *  5. **generation fence**: stop() invalidates an in-flight tick (no sink).
 *
 * Uses `runTest { backgroundScope }` + virtual time (`advanceTimeBy` +
 * `runCurrent`) so the coordinator's `delay(tickIntervalMs)` is deterministic.
 * The `clock` thunk is a hand-driven mutable so the age comparison inside the
 * pure [cn.vectory.ocdroid.ui.selectStaleClaimsForReconcile] is controllable
 * independently of the virtual-time scheduler (mirrors production: wall-clock
 * for age, scheduler for cadence).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OptimisticClaimWatchdogCoordinatorTest {

    private val scope = ScopeKey(serverGroupFp = "grp", endpointFp = "ep")

    private fun entry(claim: OptimisticClaim?): SessionEntry = SessionEntry(
        status = SessionStatus(type = "busy"),
        serverRound = null,
        optimisticClaim = claim,
        origin = EntryOrigin.OPTIMISTIC,
        freshness = Freshness.Fresh,
        updatedMonotonic = 0L,
        workdir = null,
        scopeKey = scope,
    )

    /** An unconfirmed optimistic claim stamped at [claimedAtMonotonic]. */
    private fun claim(claimedAtMonotonic: Long, clientSeq: Long = 1L) = OptimisticClaim(
        clientSeq = clientSeq,
        claimedAtMonotonic = claimedAtMonotonic,
        serverEchoed = false,
        guardedIdleDrop = false,
    )

    private fun boundStore(): Pair<ConnectionIdentityStore, ConnectionIdentity> {
        val store = ConnectionIdentityStore()
        val identity = store.bind("grp", "/work/dir", "endpoint-fp")
        return store to identity
    }

    // ── 1. SLA: stale claim detected within one tick of the timeout ─────────

    @Test
    fun `U-P2 SLA - stale unconfirmed claim is reconciled within one tick of timeout`() = runTest {
        // Wall-clock for the age comparison (independent of virtual time).
        var wallClock = 0L
        // Claim stamped at POST success (claimedAtMonotonic=0), unconfirmed.
        val staleClaim = claim(claimedAtMonotonic = 0L)
        val authority = AuthorityState(bySid = mapOf("A" to entry(staleClaim)))
        val (store, _) = boundStore()

        val reconciled = mutableListOf<StaleClaim>()
        val coordinator = OptimisticClaimWatchdogCoordinator(
            scope = backgroundScope,
            authorityState = { authority },
            identityStore = store,
            clock = { wallClock },
            staleClaimReconcileSink = { _, claims -> reconciled.addAll(claims) },
            tickIntervalMs = OPTIMISTIC_CONFIRM_TIMEOUT_MS,
        )

        coordinator.start()
        // Wall-clock has moved past the 5s timeout → age = 5001 > 5000 → stale.
        wallClock = OPTIMISTIC_CONFIRM_TIMEOUT_MS + 1L
        // Advance the watchdog's virtual timer past one tick window.
        advanceTimeBy(OPTIMISTIC_CONFIRM_TIMEOUT_MS)
        runCurrent()

        assertTrue(
            "stale claim detected + sinked within one tick of the timeout",
            reconciled.isNotEmpty(),
        )
        assertEquals("the stale sid is reconciled", "A", reconciled[0].sid)
        coordinator.stop()
    }

    @Test
    fun `U-P2 SLA - claim NOT stale yet is not reconciled at the first tick`() = runTest {
        var wallClock = 0L
        // Claim stamped at wall-clock 4000 → at tick time age = 4001 < 5000.
        val freshClaim = claim(claimedAtMonotonic = 4000L)
        val authority = AuthorityState(bySid = mapOf("A" to entry(freshClaim)))
        val (store, _) = boundStore()

        val reconciled = mutableListOf<StaleClaim>()
        val coordinator = OptimisticClaimWatchdogCoordinator(
            scope = backgroundScope,
            authorityState = { authority },
            identityStore = store,
            clock = { wallClock },
            staleClaimReconcileSink = { _, claims -> reconciled.addAll(claims) },
            tickIntervalMs = OPTIMISTIC_CONFIRM_TIMEOUT_MS,
        )

        coordinator.start()
        wallClock = 4001L
        advanceTimeBy(OPTIMISTIC_CONFIRM_TIMEOUT_MS)
        runCurrent()

        assertTrue("claim not yet stale → no reconcile at first tick", reconciled.isEmpty())
        coordinator.stop()
    }

    // ── 2. idempotent start / stop ──────────────────────────────────────────

    @Test
    fun `U-P2 - repeat start does not stack reconcile sinks per tick`() = runTest {
        var wallClock = 0L
        val staleClaim = claim(claimedAtMonotonic = 0L)
        val authority = AuthorityState(bySid = mapOf("A" to entry(staleClaim)))
        val (store, _) = boundStore()

        var sinkCount = 0
        val coordinator = OptimisticClaimWatchdogCoordinator(
            scope = backgroundScope,
            authorityState = { authority },
            identityStore = store,
            clock = { wallClock },
            staleClaimReconcileSink = { _, _ -> sinkCount += 1 },
            tickIntervalMs = OPTIMISTIC_CONFIRM_TIMEOUT_MS,
        )

        // Triple-start (single-flight: each start cancels the prior loop).
        coordinator.start()
        coordinator.start()
        coordinator.start()
        wallClock = OPTIMISTIC_CONFIRM_TIMEOUT_MS + 1L
        advanceTimeBy(OPTIMISTIC_CONFIRM_TIMEOUT_MS)
        runCurrent()

        assertEquals(
            "exactly ONE reconcile sink per tick despite repeated start()",
            1,
            sinkCount,
        )
        coordinator.stop()
    }

    @Test
    fun `U-P2 - stop leaves no residual tick`() = runTest {
        var wallClock = 0L
        val staleClaim = claim(claimedAtMonotonic = 0L)
        val authority = AuthorityState(bySid = mapOf("A" to entry(staleClaim)))
        val (store, _) = boundStore()

        var sinkCount = 0
        val coordinator = OptimisticClaimWatchdogCoordinator(
            scope = backgroundScope,
            authorityState = { authority },
            identityStore = store,
            clock = { wallClock },
            staleClaimReconcileSink = { _, _ -> sinkCount += 1 },
            tickIntervalMs = OPTIMISTIC_CONFIRM_TIMEOUT_MS,
        )

        coordinator.start()
        wallClock = OPTIMISTIC_CONFIRM_TIMEOUT_MS + 1L
        advanceTimeBy(OPTIMISTIC_CONFIRM_TIMEOUT_MS)
        runCurrent()
        val beforeStop = sinkCount
        assertTrue("one tick fired before stop", beforeStop >= 1)

        coordinator.stop()
        // Advance well past several more tick windows — no further sinks.
        repeat(5) {
            advanceTimeBy(OPTIMISTIC_CONFIRM_TIMEOUT_MS)
            runCurrent()
        }
        assertEquals(
            "no residual tick after stop",
            beforeStop,
            sinkCount,
        )
    }

    // ── 3. identity switch → tick does NOT sink ─────────────────────────────

    @Test
    fun `U-P2 - host switch between ticks prevents the reconcile sink`() = runTest {
        var wallClock = 0L
        val staleClaim = claim(claimedAtMonotonic = 0L)
        val authority = AuthorityState(bySid = mapOf("A" to entry(staleClaim)))
        val (store, _) = boundStore()

        var sinkCount = 0
        val coordinator = OptimisticClaimWatchdogCoordinator(
            scope = backgroundScope,
            authorityState = { authority },
            identityStore = store,
            clock = { wallClock },
            staleClaimReconcileSink = { _, _ -> sinkCount += 1 },
            tickIntervalMs = OPTIMISTIC_CONFIRM_TIMEOUT_MS,
        )

        coordinator.start()
        wallClock = OPTIMISTIC_CONFIRM_TIMEOUT_MS + 1L
        // Host switch BEFORE the tick runs: bump epoch + null the identity.
        store.beginReconfigure()
        advanceTimeBy(OPTIMISTIC_CONFIRM_TIMEOUT_MS)
        runCurrent()

        assertEquals(
            "identity re-check drops the tick after a host switch",
            0,
            sinkCount,
        )
        coordinator.stop()
    }

    // ── 4. empty authority → no sink ────────────────────────────────────────

    @Test
    fun `U-P2 - empty authority (no claim) does not trigger the sink`() = runTest {
        var wallClock = 0L
        val emptyAuthority = AuthorityState()
        val (store, _) = boundStore()

        var sinkCount = 0
        val coordinator = OptimisticClaimWatchdogCoordinator(
            scope = backgroundScope,
            authorityState = { emptyAuthority },
            identityStore = store,
            clock = { wallClock },
            staleClaimReconcileSink = { _, _ -> sinkCount += 1 },
            tickIntervalMs = OPTIMISTIC_CONFIRM_TIMEOUT_MS,
        )

        coordinator.start()
        wallClock = OPTIMISTIC_CONFIRM_TIMEOUT_MS * 10L  // way past any timeout
        repeat(3) {
            advanceTimeBy(OPTIMISTIC_CONFIRM_TIMEOUT_MS)
            runCurrent()
        }

        assertEquals("no claim → no sink", 0, sinkCount)
        coordinator.stop()
    }

    @Test
    fun `U-P2 - echoed claim is not reconciled (confirmation short-circuits)`() = runTest {
        var wallClock = 0L
        // serverEchoed = true → selectStaleClaimsForReconcile skips it.
        val echoedClaim = OptimisticClaim(
            clientSeq = 1L,
            claimedAtMonotonic = 0L,
            serverEchoed = true,
            guardedIdleDrop = false,
        )
        val authority = AuthorityState(bySid = mapOf("A" to entry(echoedClaim)))
        val (store, _) = boundStore()

        var sinkCount = 0
        val coordinator = OptimisticClaimWatchdogCoordinator(
            scope = backgroundScope,
            authorityState = { authority },
            identityStore = store,
            clock = { wallClock },
            staleClaimReconcileSink = { _, _ -> sinkCount += 1 },
            tickIntervalMs = OPTIMISTIC_CONFIRM_TIMEOUT_MS,
        )

        coordinator.start()
        wallClock = OPTIMISTIC_CONFIRM_TIMEOUT_MS + 1L
        advanceTimeBy(OPTIMISTIC_CONFIRM_TIMEOUT_MS)
        runCurrent()

        assertEquals("echoed (confirmed) claim is not reconciled", 0, sinkCount)
        coordinator.stop()
    }

    // ── 5. generation fence — stop() invalidates an in-flight tick ──────────

    @Test
    fun `U-P2 generation fence - stop invalidates a scheduled tick before it sinks`() = runTest {
        var wallClock = 0L
        val staleClaim = claim(claimedAtMonotonic = 0L)
        val authority = AuthorityState(bySid = mapOf("A" to entry(staleClaim)))
        val (store, _) = boundStore()

        var sinkCount = 0
        val coordinator = OptimisticClaimWatchdogCoordinator(
            scope = backgroundScope,
            authorityState = { authority },
            identityStore = store,
            clock = { wallClock },
            staleClaimReconcileSink = { _, _ -> sinkCount += 1 },
            tickIntervalMs = OPTIMISTIC_CONFIRM_TIMEOUT_MS,
        )

        coordinator.start()
        wallClock = OPTIMISTIC_CONFIRM_TIMEOUT_MS + 1L
        // Advance the timer so the tick's delay has completed (continuation
        // is ready to resume) but do NOT runCurrent yet.
        advanceTimeBy(OPTIMISTIC_CONFIRM_TIMEOUT_MS)
        // NOW stop() — generation bumped + job cancelled.
        coordinator.stop()
        // Run the scheduled continuation: the cancelled job resumes into a
        // CancellationException; the tick body (generation check / sink) never
        // executes.
        runCurrent()

        assertEquals(
            "stop() after delay-completed but before runCurrent → no sink",
            0,
            sinkCount,
        )
    }
}
