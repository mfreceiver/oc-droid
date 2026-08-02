package cn.vectory.ocdroid.service.streaming

import cn.vectory.ocdroid.di.AppLifecycleMonitor
import cn.vectory.ocdroid.service.OwnershipRefusal
import cn.vectory.ocdroid.service.OwnershipStartResult
import cn.vectory.ocdroid.service.StreamingOwnershipGate
import cn.vectory.ocdroid.service.identity.ConnectionIdentity
import cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * M3 focused tests — [DefaultSseReconnectSupervisor] (rev-ogpt rework).
 *
 * All tests are deterministic: virtual time via [runTest] + [TestScope], real
 * [SseTransportRuntimeStore] / [StreamingOwnershipGate] / [ConnectionIdentityStore]
 * (pure-Kotlin, no Android), and hand-written fakes for [StreamingServiceLauncher]
 * + [ForegroundTransportStartPreparer]. [AppLifecycleMonitor] is mocked because
 * its real constructor requires an Android [android.app.Application]. No sleeps,
 * no flaky timing. `runCurrent()`/`advanceTimeBy` pump the supervisor's
 * background jobs; `advanceUntilIdle` is avoided (it does not pump
 * backgroundScope-launched jobs in this coroutines-test version).
 */
class DefaultSseReconnectSupervisorTest {

    // ── Retry schedule values (M3-C5 schedule + cap) ──────────────────

    @Test
    fun `retry schedule matches approved values and caps at 300s`() {
        val schedule = DefaultSseReconnectRetrySchedule()
        assertEquals(0L, schedule.delayMillis(0))
        assertEquals(2_000L, schedule.delayMillis(1))
        assertEquals(10_000L, schedule.delayMillis(2))
        assertEquals(30_000L, schedule.delayMillis(3))
        assertEquals(60_000L, schedule.delayMillis(4))
        assertEquals(120_000L, schedule.delayMillis(5))
        // Steady-state cap: every retry beyond the 6th waits 300s.
        assertEquals(300_000L, schedule.delayMillis(6))
        assertEquals(300_000L, schedule.delayMillis(7))
        assertEquals(300_000L, schedule.delayMillis(100))
    }

    // ── Lifecycle: idempotent start/stop, ensureConnected→start (fix 1) ─

    @Test
    fun `start is idempotent - observation launched exactly once`() = runTest {
        val ctx = newCtx(backgroundScope)
        assertFalse("not running before start", ctx.supervisor.isObservationActive())

        ctx.supervisor.start()
        assertTrue("running after start", ctx.supervisor.isObservationActive())
        assertTrue("observing after start", ctx.supervisor.isObserving())

        ctx.supervisor.start() // second call must be a no-op
        assertTrue("still running after second start", ctx.supervisor.isObservationActive())

        // Only one observation job → only one launch for a matching drop.
        ctx.dropFor()
        runCurrent()
        assertEquals(1, ctx.launcher.callCount())
    }

    @Test
    fun `ensureConnected before start does not prevent later start from observing`() = runTest {
        val ctx = newCtx(backgroundScope)
        // ensureConnected first — pre-creates the scope, NO observation yet.
        val first = ctx.supervisor.ensureConnected(
            ctx.identity,
            SseReconnectTrigger.HEALTH_CONFIRMED,
        )
        assertEquals(OwnershipStartResult.Ready(ctx.identity), first)
        assertEquals(1, ctx.launcher.callCount())
        assertFalse("observation not launched by ensureConnected", ctx.supervisor.isObserving())

        // A later start MUST still launch observation.
        ctx.supervisor.start()
        assertTrue("start launches observation", ctx.supervisor.isObserving())

        // Observation reacts to a new drop → a second launch.
        ctx.dropFor()
        runCurrent()
        assertEquals(2, ctx.launcher.callCount())
    }

    @Test
    fun `stop cancels observation and in-flight retry without leaking jobs`() = runTest {
        val ctx = newCtx(backgroundScope)
        ctx.launcher.defaultResult = OwnershipStartResult.Refused(OwnershipRefusal.BootstrapFailed)
        ctx.supervisor.start()

        ctx.dropFor()
        runCurrent() // first attempt fires immediately (delay 0)
        val callsAfterFirst = ctx.launcher.callCount()
        assertEquals(1, callsAfterFirst)
        assertTrue("demand in flight before stop", ctx.supervisor.hasInflightDemand())

        ctx.supervisor.stop()
        assertFalse("observation cancelled", ctx.supervisor.isObservationActive())
        assertFalse("flight cleared", ctx.supervisor.hasInflightDemand())

        // Advancing the next retry delay must NOT trigger further launches.
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(callsAfterFirst, ctx.launcher.callCount())
    }

    // ── Single-flight: 100-way concurrency → one launch (M3-C1) ───────

    @Test
    fun `100 concurrent ensureConnected calls launch exactly once`() = runTest {
        val ctx = newCtx(backgroundScope)
        ctx.launcher.paused = true
        ctx.dropFor()

        val results = (1..100).map {
            async { ctx.supervisor.ensureConnected(ctx.identity, SseReconnectTrigger.DROPPED_TRANSPORT) }
        }
        runCurrent() // all 100 enter; first creates the flight, rest join

        assertEquals("launcher invoked once while in flight", 1, ctx.launcher.callCount())

        ctx.launcher.pause.complete(Unit) // release the single launch
        val all = results.awaitAll()

        assertEquals("still one launch after release", 1, ctx.launcher.callCount())
        assertTrue("all callers got Ready", all.all { it is OwnershipStartResult.Ready })
    }

    @Test
    fun `concurrent ensureConnected returns identical Ready identity`() = runTest {
        val ctx = newCtx(backgroundScope)
        ctx.dropFor()

        val results = (1..32).map {
            async { ctx.supervisor.ensureConnected(ctx.identity, SseReconnectTrigger.DROPPED_TRANSPORT) }
        }.awaitAll()

        assertEquals(1, ctx.launcher.callCount())
        assertTrue(results.all { it == OwnershipStartResult.Ready(ctx.identity) })
    }

    // ── Matching Dropped + foreground launches once (M3-C3) ───────────

    @Test
    fun `observation launches exactly once for matching Dropped and foreground`() = runTest {
        val ctx = newCtx(backgroundScope)
        ctx.supervisor.start()

        ctx.dropFor()
        runCurrent()

        assertEquals(1, ctx.launcher.callCount())
        assertEquals(ctx.identity, ctx.launcher.requestedIdentities().single())
    }

    @Test
    fun `ensureConnected for matching Dropped and foreground launches once and succeeds`() = runTest {
        val ctx = newCtx(backgroundScope)
        ctx.dropFor()

        val result = ctx.supervisor.ensureConnected(
            ctx.identity,
            SseReconnectTrigger.DROPPED_TRANSPORT,
        )

        assertEquals(OwnershipStartResult.Ready(ctx.identity), result)
        assertEquals(1, ctx.launcher.callCount())
    }

    // ── Background suppression + immediate cancel / immediate retry (fix 5) ─

    @Test
    fun `background suppresses launcher and preserves drop ticket`() = runTest {
        val ctx = newCtx(backgroundScope)
        ctx.dropFor()

        ctx.foreground.value = false
        val result = ctx.supervisor.ensureConnected(
            ctx.identity,
            SseReconnectTrigger.DROPPED_TRANSPORT,
        )
        runCurrent()

        assertEquals(
            OwnershipStartResult.Refused(OwnershipRefusal.Background),
            result,
        )
        assertEquals("launcher never called in background", 0, ctx.launcher.callCount())
        // Ticket preserved: supervisor never touches runtime state.
        assertEquals(1L, ctx.runtime.currentDropTicket(ctx.identity)?.dropId)
    }

    @Test
    fun `background cancels the current delay immediately and foreground retries immediately`() = runTest {
        val ctx = newCtx(backgroundScope)
        ctx.launcher.defaultResult = OwnershipStartResult.Refused(OwnershipRefusal.BootstrapFailed)
        ctx.supervisor.start()
        ctx.dropFor()
        runCurrent() // observer fires → attempt 0 (delay 0) → now awaiting 2s retry

        assertEquals(1, ctx.launcher.callCount())
        assertTrue(ctx.supervisor.hasInflightDemand())

        // Background transition → observer cancels the flight IMMEDIATELY
        // (no virtual time advanced yet).
        ctx.foreground.value = false
        runCurrent()
        assertFalse("delay cancelled immediately, no in-flight demand", ctx.supervisor.hasInflightDemand())

        // Advancing well past the pending 2s retry triggers no launch.
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals("no launch while background", 1, ctx.launcher.callCount())
        // Ticket retained.
        assertEquals(1L, ctx.runtime.currentDropTicket(ctx.identity)?.dropId)

        // Foreground return → IMMEDIATE retry (attempt 0, delay 0), no 2s wait.
        ctx.foreground.value = true
        runCurrent()
        assertEquals("foreground retries immediately", 2, ctx.launcher.callCount())
    }

    // ── Supersession: same-identity newer dropId (fix 2/6) ─────────────

    @Test
    fun `same-identity newer dropId supersedes the old flight and resolves it`() = runTest {
        val ctx = newCtx(backgroundScope)
        ctx.dropFor() // dropId = 1
        val firstDropId = ctx.runtime.currentDropTicket(ctx.identity)!!.dropId
        // First launcher call rejects → flight lingers on the retry timer.
        ctx.launcher.enqueue(OwnershipStartResult.Refused(OwnershipRefusal.BootstrapFailed))

        val f1 = async {
            ctx.supervisor.ensureConnected(ctx.identity, SseReconnectTrigger.DROPPED_TRANSPORT)
        }
        runCurrent() // attempt 0 fired (Refused), now awaiting 2s retry
        assertEquals(1, ctx.launcher.callCount())
        assertTrue("flight 1 in flight", ctx.supervisor.hasInflightDemand())

        // A NEWER drop for the SAME identity lands (dropId = 2).
        val newDropId = ctx.dropForFresh()
        assertNotEquals("a genuinely new dropId was allocated", firstDropId, newDropId)

        // A new demand for the current (newer) drop must supersede flight 1.
        val f2 = async {
            ctx.supervisor.ensureConnected(ctx.identity, SseReconnectTrigger.DROPPED_TRANSPORT)
        }
        runCurrent()

        // Old flight resolved (no permanently-pending deferred); new flight serviced.
        val r1 = f1.await()
        assertEquals(
            "old flight superseded → StaleIdentity",
            OwnershipStartResult.Refused(OwnershipRefusal.StaleIdentity),
            r1,
        )
        val r2 = f2.await()
        assertEquals(OwnershipStartResult.Ready(ctx.identity), r2)
        assertEquals("one launch per flight", 2, ctx.launcher.callCount())
    }

    @Test
    fun `superseded flight stops retrying`() = runTest {
        val ctx = newCtx(backgroundScope)
        ctx.launcher.defaultResult = OwnershipStartResult.Refused(OwnershipRefusal.BootstrapFailed)
        ctx.supervisor.start()
        ctx.dropFor()
        runCurrent() // flight 1 attempt 0 (calls=1), awaiting 2s
        assertEquals(1, ctx.launcher.callCount())

        // Newer drop supersedes flight 1 via the observer path; flight 2 attempt 0.
        ctx.dropForFresh()
        runCurrent()
        assertEquals(2, ctx.launcher.callCount())

        // Advance the 2s retry window. ONLY flight 2 retries (flight 1 was
        // cancelled); had flight 1 wrongly retried too, callCount would be 4.
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals("only the newer flight retries", 3, ctx.launcher.callCount())
    }

    // ── Demand liveness: intentional Stopped cancels the flight (fix 1) ──

    @Test
    fun `Dropped flight cancelled by intentional Stopped - no later launcher or preparer call`() = runTest {
        val ctx = newCtx(backgroundScope)
        ctx.launcher.defaultResult = OwnershipStartResult.Refused(OwnershipRefusal.BootstrapFailed)
        ctx.supervisor.start()

        ctx.dropFor()
        runCurrent() // observer fires → attempt 0 (delay 0) → Refused → awaiting 2s
        val launcherCallsAfterFirst = ctx.launcher.callCount()
        val preparerCallsAfterFirst = ctx.preparer.callCount()
        assertEquals(1, launcherCallsAfterFirst)
        assertTrue("flight in flight before Stopped", ctx.supervisor.hasInflightDemand())

        // Intentional teardown while the flight waits on its 2s retry: the
        // owner/coordinator begins a recovery attempt then marks it Stopped.
        ctx.stopIntentionally()
        assertTrue("runtime is Stopped", ctx.runtime.state.value is SseTransportState.Stopped)
        runCurrent()
        assertFalse("Stopped cancels the flight immediately", ctx.supervisor.hasInflightDemand())

        // Advance well past the 2s retry window.
        advanceTimeBy(10_000)
        runCurrent()

        // rev-ogpt fix 1: the flight detected the demand is gone (Stopped,
        // not a valid recovery attempt) and cancelled itself — NEVER
        // downgrading to HEALTH_CONFIRMED. No further launcher/preparer call.
        assertEquals(
            "no further launcher call after Stopped",
            launcherCallsAfterFirst,
            ctx.launcher.callCount(),
        )
        assertEquals(
            "no further preparer call after Stopped",
            preparerCallsAfterFirst,
            ctx.preparer.callCount(),
        )
        assertFalse("flight cancelled, no in-flight demand", ctx.supervisor.hasInflightDemand())
    }

    @Test
    fun `Dropped flight cancelled immediately by an unrelated same-identity attempt`() = runTest {
        val ctx = newCtx(backgroundScope)
        ctx.launcher.defaultResult = OwnershipStartResult.Refused(OwnershipRefusal.BootstrapFailed)
        ctx.supervisor.start()

        ctx.dropFor()
        runCurrent() // first attempt fails and waits on the 2s retry
        assertEquals(1, ctx.launcher.callCount())
        assertTrue(ctx.supervisor.hasInflightDemand())

        ctx.stopIntentionally()
        val freshAttempt = ctx.runtime.beginAttempt(ctx.identity)
            ?: error("fresh attempt should be accepted from Stopped")
        assertEquals(null, freshAttempt.recoveryTicket)
        runCurrent()

        assertFalse("fresh attempt supersedes the old drop demand immediately", ctx.supervisor.hasInflightDemand())
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals("no retry for the stale drop", 1, ctx.launcher.callCount())
    }

    @Test
    fun `paused launcher returning Ready after runtime Stopped is rejected`() = runTest {
        val ctx = newCtx(backgroundScope)
        ctx.launcher.paused = true
        ctx.supervisor.start()
        ctx.dropFor()

        val result = async {
            ctx.supervisor.ensureConnected(ctx.identity, SseReconnectTrigger.DROPPED_TRANSPORT)
        }
        runCurrent()
        assertEquals(1, ctx.launcher.callCount())

        ctx.stopIntentionally()
        runCurrent()
        ctx.launcher.pause.complete(Unit)
        runCurrent()

        assertEquals(
            OwnershipStartResult.Refused(OwnershipRefusal.StaleIdentity),
            result.await(),
        )
        assertFalse("stopped runtime must not leave a flight", ctx.supervisor.hasInflightDemand())
    }

    @Test
    fun `matching recovery attempts remain valid while a Dropped flight retries`() = runTest {
        val ctx = newCtx(backgroundScope)
        ctx.launcher.defaultResult = OwnershipStartResult.Refused(OwnershipRefusal.BootstrapFailed)
        ctx.supervisor.start()
        ctx.dropFor()
        runCurrent() // first attempt fails and waits on the 2s retry
        assertTrue(ctx.supervisor.hasInflightDemand())

        val recoveryAttempt = ctx.runtime.beginAttempt(ctx.identity)
            ?: error("recovery attempt should be accepted")
        runCurrent()
        assertTrue("Connecting with the same ticket remains valid", ctx.supervisor.hasInflightDemand())

        assertTrue(ctx.runtime.markLive(recoveryAttempt))
        runCurrent()
        assertTrue("Live with the same ticket remains valid", ctx.supervisor.hasInflightDemand())

        assertTrue(ctx.runtime.markRetrying(recoveryAttempt))
        runCurrent()
        assertTrue("Retrying with the same ticket remains valid", ctx.supervisor.hasInflightDemand())

        advanceTimeBy(10_000)
        runCurrent()
        assertTrue("matching recovery demand is not cancelled", ctx.supervisor.hasInflightDemand())
    }

    @Test
    fun `Dropped-triggered flight never downgrades to HEALTH_CONFIRMED preparer reason`() = runTest {
        val ctx = newCtx(backgroundScope)
        ctx.supervisor.start()
        ctx.dropFor()
        runCurrent() // flight fires (delay 0) → succeeds
        assertEquals(1, ctx.launcher.callCount())

        // The preparer must have been called with DROPPED_TRANSPORT (the
        // flight was Dropped-triggered), never HEALTH_CONFIRMED.
        assertEquals(
            ForegroundTransportStartReason.DROPPED_TRANSPORT,
            ctx.preparer.lastReason(),
        )
    }

    // ── Stale identity rejection (M3-C4) ───────────────────────────────

    @Test
    fun `stale identity ensureConnected is rejected without launch`() = runTest {
        val ctx = newCtx(backgroundScope)
        ctx.dropFor()

        ctx.identityStore.beginReconfigure() // identity no longer current
        val result = ctx.supervisor.ensureConnected(
            ctx.identity,
            SseReconnectTrigger.DROPPED_TRANSPORT,
        )

        assertEquals(
            OwnershipStartResult.Refused(OwnershipRefusal.StaleIdentity),
            result,
        )
        assertEquals(0, ctx.launcher.callCount())
    }

    // ── Post-launch freshness (fix 3/6) ───────────────────────────────

    @Test
    fun `stale result after launcher Ready is rejected, not acknowledged`() = runTest {
        val ctx = newCtx(backgroundScope)
        ctx.dropFor()
        ctx.launcher.paused = true // hold the launcher mid-call
        ctx.launcher.defaultResult = OwnershipStartResult.Ready(ctx.identity)

        val f = async {
            ctx.supervisor.ensureConnected(ctx.identity, SseReconnectTrigger.DROPPED_TRANSPORT)
        }
        runCurrent() // flight created, launcher in-flight (paused)
        assertEquals(1, ctx.launcher.callCount())

        // Identity becomes stale WHILE the launcher call is in flight.
        ctx.identityStore.beginReconfigure()
        ctx.launcher.pause.complete(Unit) // launcher now returns Ready
        runCurrent()

        val result = f.await()
        assertEquals(
            "stale result must not be acknowledged as Ready",
            OwnershipStartResult.Refused(OwnershipRefusal.StaleIdentity),
            result,
        )
    }

    // ── Retryable exceptions retried on schedule (fix 4/6) ─────────────

    @Test
    fun `thrown launcher failure is retried on the approved schedule`() = runTest {
        val ctx = newCtx(backgroundScope)
        ctx.dropFor()
        ctx.launcher.throwOnce = true // first call throws

        val job = launch {
            ctx.supervisor.ensureConnected(ctx.identity, SseReconnectTrigger.DROPPED_TRANSPORT)
        }
        runCurrent() // attempt 0: launcher throws → retryable → awaiting 2s
        assertEquals(1, ctx.launcher.callCount())

        advanceTimeBy(2_000)
        runCurrent() // attempt 1: launcher succeeds (throwOnce consumed)
        assertEquals(2, ctx.launcher.callCount())
        job.cancel()
    }

    @Test
    fun `thrown preparer failure is retried on the approved schedule`() = runTest {
        val ctx = newCtx(backgroundScope)
        ctx.dropFor()
        ctx.preparer.throwOnce = true // first prepare throws

        val job = launch {
            ctx.supervisor.ensureConnected(ctx.identity, SseReconnectTrigger.DROPPED_TRANSPORT)
        }
        runCurrent() // attempt 0: preparer throws → retryable → awaiting 2s
        assertEquals("launcher NOT called when preparer throws", 0, ctx.launcher.callCount())

        advanceTimeBy(2_000)
        runCurrent() // attempt 1: preparer ok → launcher succeeds
        assertEquals(1, ctx.launcher.callCount())
        job.cancel()
    }

    @Test
    fun `SseDisabled pauses the retry timer and requestReconcile resumes`() = runTest {
        val ctx = newCtx(backgroundScope)
        ctx.dropFor()
        ctx.launcher.defaultResult = OwnershipStartResult.Refused(OwnershipRefusal.SseDisabled)

        val result = ctx.supervisor.ensureConnected(
            ctx.identity,
            SseReconnectTrigger.DROPPED_TRANSPORT,
        )
        assertEquals(
            OwnershipStartResult.Refused(OwnershipRefusal.SseDisabled),
            result,
        )
        assertEquals(1, ctx.launcher.callCount())

        // No timer retries while paused, even far past the max interval.
        advanceTimeBy(300_000)
        runCurrent()
        assertEquals("SseDisabled pauses the timer", 1, ctx.launcher.callCount())

        // Setting change → caller flips the launcher + explicit requestReconcile.
        ctx.launcher.defaultResult = OwnershipStartResult.Ready(ctx.identity)
        ctx.supervisor.requestReconcile()
        runCurrent()

        assertEquals("requestReconcile resumes after SseDisabled", 2, ctx.launcher.callCount())
    }

    // ── Retry intervals (M3-C2) ───────────────────────────────────────

    @Test
    fun `rejected attempts retry at approved virtual-time intervals`() = runTest {
        val ctx = newCtx(backgroundScope)
        ctx.launcher.defaultResult = OwnershipStartResult.Refused(OwnershipRefusal.BootstrapFailed)
        ctx.dropFor()

        val job = launch {
            ctx.supervisor.ensureConnected(ctx.identity, SseReconnectTrigger.DROPPED_TRANSPORT)
        }
        runCurrent() // attempt 0: immediate (delay 0)
        assertEquals(1, ctx.launcher.callCount())

        advanceTimeBy(2_000); runCurrent() // attempt 1: +2s
        assertEquals(2, ctx.launcher.callCount())
        advanceTimeBy(10_000); runCurrent() // attempt 2: +10s
        assertEquals(3, ctx.launcher.callCount())
        advanceTimeBy(30_000); runCurrent() // attempt 3: +30s
        assertEquals(4, ctx.launcher.callCount())
        advanceTimeBy(60_000); runCurrent() // attempt 4: +60s
        assertEquals(5, ctx.launcher.callCount())
        advanceTimeBy(120_000); runCurrent() // attempt 5: +120s
        assertEquals(6, ctx.launcher.callCount())
        advanceTimeBy(300_000); runCurrent() // attempt 6+: steady-state 300s
        assertEquals(7, ctx.launcher.callCount())
        advanceTimeBy(300_000); runCurrent() // next steady-state retry
        assertEquals(8, ctx.launcher.callCount())
        job.cancel()
    }

    // ── requestReconcile deduplication ────────────────────────────────

    @Test
    fun `requestReconcile wakes reconciliation without duplicating launches`() = runTest {
        val ctx = newCtx(backgroundScope)
        ctx.launcher.paused = true
        ctx.supervisor.start()

        ctx.dropFor()
        // Observer + multiple reconcile requests must collapse into one flight.
        ctx.supervisor.requestReconcile()
        ctx.supervisor.requestReconcile()
        ctx.supervisor.requestReconcile()
        runCurrent()

        assertEquals(1, ctx.launcher.callCount())

        ctx.launcher.pause.complete(Unit)
        runCurrent()
        assertEquals("still one launch after release", 1, ctx.launcher.callCount())
    }

    @Test
    fun `requestReconcile is a no-op while backgrounded`() = runTest {
        val ctx = newCtx(backgroundScope, foreground = false)
        ctx.supervisor.start()
        ctx.dropFor()

        ctx.supervisor.requestReconcile()
        runCurrent()

        assertEquals("no launch while background", 0, ctx.launcher.callCount())
    }

    // ── Stop / create-flight race (fix 6) ─────────────────────────────

    @Test
    fun `stop during in-flight launch cancels the flight and resolves the awaiter with ServiceStopped`() = runTest {
        val ctx = newCtx(backgroundScope)
        ctx.supervisor.start()
        ctx.dropFor()
        ctx.launcher.paused = true // hold the launcher

        val deferred = async {
            ctx.supervisor.ensureConnected(ctx.identity, SseReconnectTrigger.DROPPED_TRANSPORT)
        }
        runCurrent() // flight created, launcher paused (calls = 1)
        assertEquals(1, ctx.launcher.callCount())

        ctx.supervisor.stop()
        assertFalse("observation cancelled", ctx.supervisor.isObservationActive())
        assertFalse("no in-flight demand after stop", ctx.supervisor.hasInflightDemand())

        // Releasing the pause after stop must not create a new launch.
        ctx.launcher.pause.complete(Unit)
        runCurrent()
        assertEquals("no further launch after stop", 1, ctx.launcher.callCount())

        // Truly await the ensureConnected result — it MUST resolve with
        // ServiceStopped, not hang. (rev-ogpt fix 3: the old test aliased the
        // Job as "result" and cancelled it, never asserting the outcome.)
        val result = deferred.await()
        assertEquals(
            "stop resolves the awaiter with ServiceStopped",
            OwnershipStartResult.Refused(OwnershipRefusal.ServiceStopped),
            result,
        )
        assertFalse("no lingering in-flight demand", ctx.supervisor.hasInflightDemand())
    }

    // ── Success cancels watchdog (M3-C5) ──────────────────────────────

    @Test
    fun `success cancels retry watchdog`() = runTest {
        val ctx = newCtx(backgroundScope)
        ctx.dropFor()

        val result = ctx.supervisor.ensureConnected(
            ctx.identity,
            SseReconnectTrigger.DROPPED_TRANSPORT,
        )
        assertEquals(OwnershipStartResult.Ready(ctx.identity), result)
        assertEquals(1, ctx.launcher.callCount())

        // Far beyond the max retry interval — no further launches.
        advanceTimeBy(300_000)
        runCurrent()
        assertEquals(1, ctx.launcher.callCount())
    }

    // ═══════════════════════════════════════════════════════════════════
    // Test scaffolding
    // ═══════════════════════════════════════════════════════════════════

    /** Drives the real runtime from Stopped → Live → Dropped for [identity]. */
    private fun Ctx.dropFor(reason: TransportDropReason = TransportDropReason.SERVICE_DESTROYED) {
        val attempt = runtime.beginAttempt(identity)
            ?: error("beginAttempt returned null (runtime not Stopped)")
        assertTrue("markLive should succeed", runtime.markLive(attempt))
        assertTrue("publishDropped should return a ticket", runtime.publishDropped(attempt, reason) != null)
    }

    /**
     * Produces a NEW drop-id for the SAME identity (runtime currently Dropped):
     * beginAttempt captures the recovery ticket → markLive → acknowledgeRecovery
     * clears it → publishDropped allocates a fresh monotonic drop-id.
     */
    private fun Ctx.dropForFresh(reason: TransportDropReason = TransportDropReason.SERVICE_DESTROYED): Long {
        val attempt = runtime.beginAttempt(identity)
            ?: error("beginAttempt returned null (runtime not Dropped)")
        assertTrue("markLive should succeed", runtime.markLive(attempt))
        assertTrue("acknowledgeRecovery should clear the ticket", runtime.acknowledgeRecovery(attempt))
        val ticket = runtime.publishDropped(attempt, reason)
            ?: error("publishDropped returned null")
        return ticket.dropId
    }

    /**
     * Intentional teardown while Dropped: beginAttempt (Dropped → Connecting,
     * capturing the recovery ticket) → markStopped (Connecting → Stopped).
     * Simulates the owner/coordinator intentionally stopping during the
     * supervisor's retry-delay window.
     */
    private fun Ctx.stopIntentionally() {
        val attempt = runtime.beginAttempt(identity)
            ?: error("beginAttempt returned null (runtime not Dropped)")
        assertTrue("markStopped should succeed from Connecting", runtime.markStopped(attempt))
    }

    private fun newCtx(scope: CoroutineScope, foreground: Boolean = true): Ctx {
        val runtime = SseTransportRuntimeStore()
        val gate = StreamingOwnershipGate()
        val identityStore = ConnectionIdentityStore()
        val identity = identityStore.bind("sgA", "/wdA", "epA")
        val fg = MutableStateFlow(foreground)
        val monitor = mockk<AppLifecycleMonitor>(relaxed = true)
        every { monitor.isInForeground } returns fg
        val preparer = FakePreparer()
        val launcher = FakeLauncher()
        val supervisor = DefaultSseReconnectSupervisor(
            runtimeStore = runtime,
            launcher = launcher.ensureStarted,
            ownershipGate = gate,
            identityStore = identityStore,
            appLifecycleMonitor = monitor,
            parentScope = scope,
            preparer = preparer,
        )
        return Ctx(
            runtime = runtime,
            gate = gate,
            identityStore = identityStore,
            foreground = fg,
            monitor = monitor,
            preparer = preparer,
            launcher = launcher,
            supervisor = supervisor,
            identity = identity,
        )
    }

    private class Ctx(
        val runtime: SseTransportRuntimeStore,
        val gate: StreamingOwnershipGate,
        val identityStore: ConnectionIdentityStore,
        val foreground: MutableStateFlow<Boolean>,
        val monitor: AppLifecycleMonitor,
        val preparer: FakePreparer,
        val launcher: FakeLauncher,
        val supervisor: DefaultSseReconnectSupervisor,
        val identity: ConnectionIdentity,
    )

    private class FakeLauncher {
        private val calls = AtomicInteger(0)
        private val requested = mutableListOf<ConnectionIdentity>()
        private val queue = ConcurrentLinkedQueue<OwnershipStartResult>()

        fun callCount(): Int = calls.get()
        fun requestedIdentities(): List<ConnectionIdentity> =
            synchronized(requested) { requested.toList() }

        /** Consumed in FIFO order before falling back to [defaultResult]. */
        fun enqueue(result: OwnershipStartResult) { queue.add(result) }

        /** Sticky fallback when the queue is empty (null ⇒ Ready(identity)). */
        @Volatile
        var defaultResult: OwnershipStartResult? = null

        /** When true, each call suspends until [pause] completes. */
        @Volatile
        var paused: Boolean = false
        val pause = CompletableDeferred<Unit>()

        /** When true, the next call throws once, then resets to false. */
        @Volatile
        var throwOnce: Boolean = false

        /** Lambda matching the supervisor's launcher param. */
        val ensureStarted: suspend (ConnectionIdentity) -> OwnershipStartResult = { identity ->
            calls.incrementAndGet()
            synchronized(requested) { requested += identity }
            if (paused) pause.await()
            if (throwOnce) {
                throwOnce = false
                throw IllegalStateException("simulated launcher failure")
            }
            queue.poll() ?: defaultResult ?: OwnershipStartResult.Ready(identity)
        }
    }

    private class FakePreparer : ForegroundTransportStartPreparer {
        private val calls = AtomicInteger(0)
        fun callCount(): Int = calls.get()

        @Volatile
        private var lastReasonValue: ForegroundTransportStartReason? = null
        fun lastReason(): ForegroundTransportStartReason =
            lastReasonValue ?: error("prepareForegroundTransportStart was never called")

        @Volatile
        var nextResult: ForegroundTransportStartPreparation =
            ForegroundTransportStartPreparation.Ready

        /** When true, the next call throws once, then resets to false. */
        @Volatile
        var throwOnce: Boolean = false

        override suspend fun prepareForegroundTransportStart(
            identity: ConnectionIdentity,
            dropId: Long?,
            reason: ForegroundTransportStartReason,
        ): ForegroundTransportStartPreparation {
            calls.incrementAndGet()
            lastReasonValue = reason
            if (throwOnce) {
                throwOnce = false
                throw IllegalStateException("simulated preparer failure")
            }
            return nextResult
        }
    }
}
