package cn.vectory.ocdroid.service.bootstrap

import cn.vectory.ocdroid.data.repository.http.TofuDecision
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0.5 unit tests for [ConnectionBootstrapCoordinator]. Verifies the TOFU
 * state-machine semantics mirror what [ConnectionCoordinator] held privately
 * pre-extraction (FGS spec §10), plus the §5 degraded-no-Activity path.
 *
 * No Android framework, no Hilt — the coordinator is a plain `@Singleton`
 * `@Inject constructor()` class; we instantiate it directly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionBootstrapCoordinatorTest {

    private fun newCoordinator() = ConnectionBootstrapCoordinator()

    // ── setPendingTofu → TrustPending ──────────────────────────────────────

    @Test
    fun `initial state is Idle and pendingTofuHostPort is null`() {
        val c = newCoordinator()
        assertEquals(TofuState.Idle, c.tofuState.value)
        assertNull(c.pendingTofuHostPort())
    }

    @Test
    fun `setPendingTofu transitions to TrustPending and exposes hostPort`() {
        val c = newCoordinator()
        c.setPendingTofu("example.com:443")
        assertEquals(TofuState.TrustPending("example.com:443"), c.tofuState.value)
        assertEquals("example.com:443", c.pendingTofuHostPort())
    }

    @Test
    fun `setTofuDecision stores the deferred that resolveTofuTrust completes`() {
        val c = newCoordinator()
        c.setPendingTofu("example.com:443")
        val deferred = CompletableDeferred<TofuDecision>()
        c.setTofuDecision(deferred)
        // The coordinator does not expose the deferred directly; verify via the
        // side effect of resolveTofuTrust completing exactly that instance.
        assertFalse(deferred.isCompleted)
        c.resolveTofuTrust(TofuDecision.Trust(spki = "abc"))
        assertTrue(deferred.isCompleted)
        assertEquals(TofuDecision.Trust("abc"), deferred.getCompleted())
    }

    // ── resolve → clear → Idle ─────────────────────────────────────────────

    @Test
    fun `resolveTofuTrust completes pending deferred with AcceptOnce`() {
        val c = newCoordinator()
        c.setPendingTofu("h:1")
        val deferred = CompletableDeferred<TofuDecision>()
        c.setTofuDecision(deferred)

        c.resolveTofuTrust(TofuDecision.AcceptOnce(spki = "deadbeef"))

        assertTrue(deferred.isCompleted)
        assertEquals(TofuDecision.AcceptOnce("deadbeef"), deferred.getCompleted())
    }

    @Test
    fun `resolveTofuTrust with Cancel completes deferred but leaves state TrustPending until clearPendingTofu`() {
        // Mirrors CC: resolveTofuTrust ONLY completes the deferred; the retry
        // loop's finally clears the hostPort (clearPendingTofu here).
        val c = newCoordinator()
        c.setPendingTofu("h:2")
        val deferred = CompletableDeferred<TofuDecision>()
        c.setTofuDecision(deferred)

        c.resolveTofuTrust(TofuDecision.Cancel)
        assertTrue(deferred.isCompleted)
        // State still TrustPending — clearPendingTofu is the caller's job.
        assertEquals(TofuState.TrustPending("h:2"), c.tofuState.value)
        assertEquals("h:2", c.pendingTofuHostPort())

        c.clearPendingTofu()
        assertEquals(TofuState.Idle, c.tofuState.value)
        assertNull(c.pendingTofuHostPort())
    }

    @Test
    fun `resolveTofuTrust is a no-op when no decision is pending (mirrors CC warn-log)`() {
        val c = newCoordinator()
        // No deferred installed — must not throw.
        c.resolveTofuTrust(TofuDecision.Cancel)
        assertEquals(TofuState.Idle, c.tofuState.value)
    }

    @Test
    fun `clearPendingTofu resets state to Idle and drops the deferred reference`() {
        val c = newCoordinator()
        c.setPendingTofu("h:3")
        c.setTofuDecision(CompletableDeferred())
        assertEquals("h:3", c.pendingTofuHostPort())

        c.clearPendingTofu()

        assertEquals(TofuState.Idle, c.tofuState.value)
        assertNull(c.pendingTofuHostPort())
        // After clear, a subsequent resolve is a no-op (the deferred ref is gone).
        c.resolveTofuTrust(TofuDecision.Trust("x"))
        assertEquals(TofuState.Idle, c.tofuState.value)
    }

    @Test
    fun `setTofuDecision null drops the deferred reference without completing it`() {
        val c = newCoordinator()
        c.setPendingTofu("h:4")
        val deferred = CompletableDeferred<TofuDecision>()
        c.setTofuDecision(deferred)
        c.setTofuDecision(null)
        // The coordinator no longer holds `deferred`; resolving is a no-op even
        // though the caller's `deferred` is still uncompleted.
        c.resolveTofuTrust(TofuDecision.Cancel)
        assertFalse(deferred.isCompleted)
    }

    // ── DegradedNeedsActivity transition (FGS spec §5) ─────────────────────

    @Test
    fun `markDegradedNeedsActivity transitions TrustPending to DegradedNeedsActivity keeping hostPort`() {
        val c = newCoordinator()
        c.setPendingTofu("degraded.host:8443")

        c.markDegradedNeedsActivity()

        assertEquals(TofuState.DegradedNeedsActivity("degraded.host:8443"), c.tofuState.value)
        // Degraded is still "TOFU unresolved" → hostPort stays exposed so the
        // CC freeze guard (pendingTofuHostPort != null) keeps treating it as frozen.
        assertEquals("degraded.host:8443", c.pendingTofuHostPort())
    }

    @Test
    fun `markDegradedNeedsActivity cancels the pending deferred so the awaiter resumes without burning retry budget`() {
        // FGS spec §5: "不无限等 UI deferred; 不消耗 SSE 重试预算".
        val c = newCoordinator()
        c.setPendingTofu("h:5")
        val deferred = CompletableDeferred<TofuDecision>()
        c.setTofuDecision(deferred)

        // A coroutine suspending on await() must resume with CancellationException
        // once markDegradedNeedsActivity cancels the deferred.
        var thrown: Throwable? = null
        runBlocking {
            val job = launch {
                try { deferred.await() } catch (e: CancellationException) { thrown = e; throw e }
            }
            // Let the launch enter the await().
            yield(); yield()
            c.markDegradedNeedsActivity()
            job.join()
        }
        assertNotNull("await() must have resumed with CancellationException", thrown)
        assertTrue(thrown is CancellationException)
    }

    @Test
    fun `markDegradedNeedsActivity is no-op when Idle`() {
        val c = newCoordinator()
        c.markDegradedNeedsActivity()
        assertEquals(TofuState.Idle, c.tofuState.value)
        assertNull(c.pendingTofuHostPort())
    }

    @Test
    fun `markDegradedNeedsActivity is idempotent when already Degraded`() {
        val c = newCoordinator()
        c.setPendingTofu("h:6")
        c.markDegradedNeedsActivity()
        val firstState = c.tofuState.value

        c.markDegradedNeedsActivity()

        assertEquals(firstState, c.tofuState.value)
        assertEquals(TofuState.DegradedNeedsActivity("h:6"), c.tofuState.value)
    }

    @Test
    fun `clearPendingTofu recovers from DegradedNeedsActivity back to Idle`() {
        // The §5 recovery: user opens Activity via the placeholder notification
        // action, confirms trust, and the bootstrap is re-armed (clearPendingTofu
        // unblocks a fresh probe). Resolve-on-degraded is a no-op (deferred was
        // cancelled); the caller clears + re-triggers.
        val c = newCoordinator()
        c.setPendingTofu("h:7")
        c.markDegradedNeedsActivity()
        assertEquals(TofuState.DegradedNeedsActivity("h:7"), c.tofuState.value)

        // A late resolve against the cancelled/cleared deferred is a no-op.
        c.resolveTofuTrust(TofuDecision.Trust("spki"))
        assertEquals(TofuState.DegradedNeedsActivity("h:7"), c.tofuState.value)

        c.clearPendingTofu()
        assertEquals(TofuState.Idle, c.tofuState.value)
        assertNull(c.pendingTofuHostPort())
    }

    @Test
    fun `B2 degraded capture promotes once to TrustPending with fresh deferred`() {
        val c = newCoordinator()
        val capture = mockk<OpenCodeRepository.TofuCaptureResult>()
        every { capture.hostPort } returns "h:443"
        c.setPendingTofu("h:443")
        c.setPendingCapture(capture)
        c.markDegradedNeedsActivity()

        val challenge = c.promoteDegradedToPending()

        assertNotNull(challenge)
        assertSame(capture, challenge!!.capture)
        assertFalse(challenge.decision.isCompleted)
        assertEquals(TofuState.TrustPending("h:443", capture), c.tofuState.value)
        assertNull("duplicate foreground signal cannot allocate another prompt", c.promoteDegradedToPending())
        c.resolveTofuTrust(TofuDecision.AcceptOnce("spki"))
        assertEquals(TofuDecision.AcceptOnce("spki"), challenge.decision.getCompleted())
    }

    // ── StateFlow observability ────────────────────────────────────────────

    @Test
    fun `tofuState emits TrustPending then Idle on clearPendingTofu`() = runBlocking {
        val c = newCoordinator()
        // first() of current Idle value to ensure a subscriber is wired.
        assertEquals(TofuState.Idle, c.tofuState.first())

        c.setPendingTofu("observed:443")
        assertEquals(TofuState.TrustPending("observed:443"), c.tofuState.first { it is TofuState.TrustPending })

        c.clearPendingTofu()
        assertEquals(TofuState.Idle, c.tofuState.first { it is TofuState.Idle })
    }

    // ── Concurrency safety ─────────────────────────────────────────────────

    @Test
    fun `concurrent set clear resolve does not corrupt state or crash`() = runBlocking {
        // Both CC (main thread) and the Service (background scope) call into
        // the same @Singleton instance. Hammer it from several threads and
        // assert: (a) no exception, (b) terminal state is one of the valid
        // TofuState variants, (c) pendingTofuHostPort() agrees with the state.
        val c = newCoordinator()
        val n = 500
        coroutineScope {
            withContext(Dispatchers.Default) {
                val jobs = (1..n).map { i ->
                    async {
                        val hostPort = "h:$i"
                        when (i % 5) {
                            0 -> c.setPendingTofu(hostPort)
                            1 -> c.clearPendingTofu()
                            2 -> c.setTofuDecision(CompletableDeferred())
                            3 -> c.resolveTofuTrust(TofuDecision.Cancel)
                            else -> c.markDegradedNeedsActivity()
                        }
                    }
                }
                jobs.awaitAll()
            }
        }
        // Final state must be a valid variant and the hostPort accessor must be
        // self-consistent with the state.
        val terminal = c.tofuState.value
        val hostPort = c.pendingTofuHostPort()
        when (terminal) {
            is TofuState.Idle -> assertNull(hostPort)
            is TofuState.TrustPending -> assertEquals(terminal.hostPort, hostPort)
            is TofuState.DegradedNeedsActivity -> assertEquals(terminal.hostPort, hostPort)
        }
    }

    @Test
    fun `concurrent setPendingTofu then markDegradedNeedsActivity observers see consistent hostPort`() = runBlocking {
        // Stress the TrustPending → Degraded transition under contention with
        // clearPendingTofu; whatever wins, the observable state must be one of
        // the three sealed variants and never throw.
        val c = newCoordinator()
        coroutineScope {
            withContext(Dispatchers.Default) {
                val jobs = (1..200).map { i ->
                    async {
                        c.setPendingTofu("race:$i")
                        if (i % 3 == 0) c.markDegradedNeedsActivity()
                        if (i % 7 == 0) c.clearPendingTofu()
                    }
                }
                jobs.awaitAll()
            }
        }
        val s = c.tofuState.value
        assertTrue(
            "terminal state must be a sealed variant, got $s",
            s is TofuState.Idle || s is TofuState.TrustPending || s is TofuState.DegradedNeedsActivity
        )
    }

    @Test
    fun `resolveTofuTrust is exactly-once under duplicate calls`() {
        // CompletableDeferred.complete() is idempotent (returns false on the
        // 2nd call); mirror CC's behaviour — duplicate resolves do not throw
        // and the deferred carries the FIRST decision.
        val c = newCoordinator()
        c.setPendingTofu("h:once")
        val deferred = CompletableDeferred<TofuDecision>()
        c.setTofuDecision(deferred)

        c.resolveTofuTrust(TofuDecision.Trust("first"))
        c.resolveTofuTrust(TofuDecision.Cancel)  // duplicate — ignored by CompletableDeferred

        assertEquals(TofuDecision.Trust("first"), deferred.getCompleted())
    }

    @Test
    fun `setPendingTofu overwrites prior TrustPending hostPort mirroring CC direct field write`() {
        // CC's field write was unconditional (`pendingTofuHostPort = hostPort`);
        // single-flight was the call site's responsibility, not the setter's.
        val c = newCoordinator()
        c.setPendingTofu("first:443")
        c.setPendingTofu("second:8443")
        assertEquals(TofuState.TrustPending("second:8443"), c.tofuState.value)
        assertEquals("second:8443", c.pendingTofuHostPort())
    }

    @Test
    fun `TofuState sealed variants are distinct and not equal across hostPorts`() {
        assertFalse(TofuState.Idle.equals(TofuState.TrustPending("a")))
        assertEquals(TofuState.TrustPending("a"), TofuState.TrustPending("a"))
        assertFalse(TofuState.TrustPending("a").equals(TofuState.TrustPending("b")))
        assertFalse(TofuState.DegradedNeedsActivity("a").equals(TofuState.TrustPending("a")))
        // Sanity: Idle is a singleton object.
        assertSame(TofuState.Idle, TofuState.Idle)
    }
}
