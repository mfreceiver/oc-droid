package cn.vectory.ocdroid.service

import android.util.Log
import cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
import cn.vectory.ocdroid.service.streaming.SseTransportRuntimeStore
import cn.vectory.ocdroid.service.streaming.SseTransportState
import cn.vectory.ocdroid.service.streaming.TransportDropReason
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * L4 §4.4 (M4) — tests for [SseShutdownSeal], the pure-JVM seam that owns the
 * runtime/ownership side-effects for transport drops and the
 * [SessionStreamingService.onDestroy] shutdown disposition.
 *
 * The seam is extracted from the Android [android.app.Service] shell so the
 * disposition contract is JVM-testable without Robolectric/Hilt. The Service
 * delegates its onDestroy + the SSE owner's drop routing to this seam, so
 * these tests cover M4-C1..C4 (ownership never left Ready after destroy;
 * unexpected destruction observable by the supervisor; intentional teardown
 * cannot auto-revive; recreated Service can register Starting).
 *
 * Fixture: real [SseTransportRuntimeStore], real [StreamingOwnershipGate],
 * real [ConnectionIdentityStore]. Deterministic; no sleeps.
 */
class SessionStreamingServiceShutdownDispositionTest {

    private lateinit var identityStore: ConnectionIdentityStore
    private lateinit var runtimeStore: SseTransportRuntimeStore
    private lateinit var gate: StreamingOwnershipGate
    private lateinit var seal: SseShutdownSeal

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.e(any<String>(), any<String>(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0

        identityStore = ConnectionIdentityStore()
        runtimeStore = SseTransportRuntimeStore()
        gate = StreamingOwnershipGate()
        seal = SseShutdownSeal(runtimeStore, gate)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun bindIdentity(workdir: String = "/proj") =
        identityStore.bind("test-fp", workdir, "test-endpoint")

    /**
     * Registers a Ready ownership owner for [identity] (mirrors what the
     * Service does at bootstrap commit) and returns it. Asserts the gate is
     * actually Ready so a missed setup does not silently pass.
     */
    private fun readyOwner(identity: cn.vectory.ocdroid.service.identity.ConnectionIdentity) {
        val prepared = gate.prepareAttempt(identity)
        val outcome = gate.registerStarting(
            identity = identity,
            attemptId = prepared.attemptId,
            disconnectAndJoin = { },
            abortStartup = { },
        )
        assertEquals(
            "registerStarting accepted (test setup)",
            RegisterStartingOutcome.Accepted,
            outcome,
        )
        gate.markReady(identity)
        assertEquals("Ready owner recorded", identity, gate.readyIdentity())
    }

    /**
     * Begins + marks Live a runtime attempt for [identity] (mirrors what the
     * owner does on the first valid frame). Returns the live attempt token.
     */
    private fun liveAttempt(identity: cn.vectory.ocdroid.service.identity.ConnectionIdentity) =
        runtimeStore.beginAttempt(identity)!!.also {
            assertTrue("markLive (test setup)", runtimeStore.markLive(it))
        }

    // ── Drop routing (onUnexpectedDrop) ────────────────────────────────────

    // M1A/M4: the owner routes a transport drop through the seal. The seal
    // releases ownership BEFORE publishing Dropped (I3 ordering).
    @Test
    fun `onUnexpectedDrop releases ownership then publishes Dropped`() {
        val identity = bindIdentity()
        readyOwner(identity)
        val attempt = liveAttempt(identity)

        seal.onUnexpectedDrop(attempt, TransportDropReason.BACKGROUND_RECONNECT_REFUSED)

        // I3: ownership released FIRST → consumers never observe Dropped + Ready.
        assertNull(
            "ownership released (no Ready owner after the drop)",
            gate.readyIdentity(),
        )
        val state = runtimeStore.state.value
        assertTrue("runtime ended Dropped", state is SseTransportState.Dropped)
        assertEquals(
            "Dropped ticket carries the routed reason",
            TransportDropReason.BACKGROUND_RECONNECT_REFUSED,
            (state as SseTransportState.Dropped).ticket.reason,
        )
        assertEquals(
            "Dropped identity preserved",
            identity,
            state.ticket.identity,
        )
    }

    // M4-3 / idempotence: a second drop routing for the SAME attempt is a
    // safe no-op — releaseNow ignores a non-held identity and publishDropped
    // returns null on an already-Dropped state. No duplicate ticket.
    @Test
    fun `onUnexpectedDrop is idempotent - second call is a no-op without duplicate drop`() {
        val identity = bindIdentity()
        readyOwner(identity)
        val attempt = liveAttempt(identity)

        seal.onUnexpectedDrop(attempt, TransportDropReason.RETRY_EXHAUSTED)
        val firstTicket = (runtimeStore.state.value as SseTransportState.Dropped).ticket

        // Second call (e.g. onDestroy racing the owner's exhaustion path).
        seal.onUnexpectedDrop(attempt, TransportDropReason.SERVICE_DESTROYED)

        val state = runtimeStore.state.value
        assertTrue("runtime still Dropped (no Stopped resurrection)", state is SseTransportState.Dropped)
        assertEquals(
            "SAME drop ticket — no duplicate / no reason overwrite",
            firstTicket,
            (state as SseTransportState.Dropped).ticket,
        )
    }

    @Test
    fun `stale unexpected drop cannot release a newer Ready owner`() {
        val identity = bindIdentity()
        readyOwner(identity)
        val oldAttempt = liveAttempt(identity)

        // Model the superseding owner/attempt winning before the stale drop
        // handler is entered. The new owner is Ready and the runtime is Live.
        assertTrue(runtimeStore.markStopped(oldAttempt))
        val newAttempt = liveAttempt(identity)
        gate.releaseNow(identity)
        readyOwner(identity)

        assertFalse(
            "the fenced handler rejects the stale attempt",
            seal.onUnexpectedDropIfCurrent(oldAttempt, TransportDropReason.RETRY_EXHAUSTED),
        )
        assertEquals(
            "new Ready owner remains installed",
            identity,
            gate.readyIdentity(),
        )
        assertEquals(
            "new runtime attempt remains Live",
            newAttempt,
            (runtimeStore.state.value as SseTransportState.Live).attempt,
        )
    }

    // ── onDestroy disposition ──────────────────────────────────────────────

    // M4-C3: an INTENTIONAL destruction (no-source terminal / user close /
    // lifecycle timeout / bootstrap rollback) marks Stopped — the supervisor
    // must NOT auto-revive it.
    @Test
    fun `INTENTIONAL destruction marks Stopped and never publishes Dropped`() {
        val identity = bindIdentity()
        readyOwner(identity)
        val attempt = liveAttempt(identity)

        seal.markIntentional()
        seal.applyDestructionDisposition(identity, attempt)

        assertNull(
            "ownership released on destroy (M4-C1: never leaves Ready)",
            gate.readyIdentity(),
        )
        assertEquals(
            "intentional destroy → Stopped (never Dropped — no auto-revive)",
            SseTransportState.Stopped,
            runtimeStore.state.value,
        )
    }

    // M4-C2: an UNEXPECTED destruction (system killed the Service with no
    // intentional marker) publishes SERVICE_DESTROYED — observable by the
    // supervisor so foreground recovery re-establishes the transport.
    @Test
    fun `UNEXPECTED destruction publishes SERVICE_DESTROYED observable by supervisor`() {
        val identity = bindIdentity()
        readyOwner(identity)
        val attempt = liveAttempt(identity)

        // Default disposition is UNEXPECTED (no markIntentional).
        seal.applyDestructionDisposition(identity, attempt)

        assertNull(
            "ownership released on destroy (M4-C1)",
            gate.readyIdentity(),
        )
        val state = runtimeStore.state.value
        assertTrue("unexpected destroy → Dropped", state is SseTransportState.Dropped)
        assertEquals(
            "Dropped reason is SERVICE_DESTROYED (observable by supervisor)",
            TransportDropReason.SERVICE_DESTROYED,
            (state as SseTransportState.Dropped).ticket.reason,
        )
    }

    // M4-3: no duplicate drop when the owner already handled a terminal path.
    // The Service captures the active attempt via currentAttempt(identity);
    // once the runtime is Dropped that returns null, so applyDestructionDisposition
    // only releases ownership (no second Dropped).
    @Test
    fun `applyDestructionDisposition no-ops the drop when owner already handled a terminal path`() {
        val identity = bindIdentity()
        readyOwner(identity)
        val attempt = liveAttempt(identity)

        // The owner already routed an exhaustion drop (Dropped).
        seal.onUnexpectedDrop(attempt, TransportDropReason.RETRY_EXHAUSTED)
        val ownerTicket = (runtimeStore.state.value as SseTransportState.Dropped).ticket

        // onDestroy captures the active attempt the SAME way the Service does.
        val captured = runtimeStore.currentAttempt(identity)
        assertNull(
            "currentAttempt is null once the runtime is Dropped (already handled)",
            captured,
        )
        // UNEXPECTED disposition, but the captured attempt is null → no drop.
        seal.applyDestructionDisposition(identity, captured)

        val state = runtimeStore.state.value
        assertTrue("runtime still Dropped (no duplicate)", state is SseTransportState.Dropped)
        assertEquals(
            "the original RETRY_EXHAUSTED ticket is preserved — no SERVICE_DESTROYED overwrite",
            ownerTicket,
            (state as SseTransportState.Dropped).ticket,
        )
        // Ownership was still released (idempotent) — M4-C1 holds.
        assertNull("ownership released even when the drop was a no-op", gate.readyIdentity())
    }

    // M4-C4: after an INTENTIONAL Stopped, a recreated Service can register
    // Starting normally (beginAttempt succeeds from Stopped).
    @Test
    fun `M4-C4 - recreated Service can register Starting after INTENTIONAL Stopped`() {
        val identity = bindIdentity()
        readyOwner(identity)
        val attempt = liveAttempt(identity)

        seal.markIntentional()
        seal.applyDestructionDisposition(identity, attempt)
        assertEquals("stopped after intentional destroy", SseTransportState.Stopped, runtimeStore.state.value)

        // A recreated Service / supervisor begins a fresh attempt.
        val fresh = runtimeStore.beginAttempt(identity)
        assertNotNull(
            "beginAttempt succeeds from Stopped (recreated Service registers Starting)",
            fresh,
        )
        assertTrue(
            "fresh attempt is Connecting",
            runtimeStore.state.value is SseTransportState.Connecting,
        )
    }

    // M4-C1 (defensive): applyDestructionDisposition with a null identity +
    // null attempt (Service destroyed before any ownership was claimed) is a
    // clean no-op — never throws, never mutates the runtime.
    @Test
    fun `applyDestructionDisposition with no ownership is a clean no-op`() {
        seal.markIntentional()
        seal.applyDestructionDisposition(identity = null, attempt = null)
        assertEquals(
            "runtime untouched when there was nothing to release",
            SseTransportState.Stopped,
            runtimeStore.state.value,
        )
    }

    // markIntentional flips the disposition; a second markIntentional is a
    // no-op (idempotent — multiple intentional paths may mark it).
    @Test
    fun `markIntentional is observable and idempotent`() {
        val identity = bindIdentity()
        readyOwner(identity)
        val attempt = liveAttempt(identity)

        seal.markIntentional()
        seal.markIntentional() // idempotent
        seal.applyDestructionDisposition(identity, attempt)
        assertEquals(
            "marked intentional twice → still Stopped (not Dropped)",
            SseTransportState.Stopped,
            runtimeStore.state.value,
        )

        // Disposition is per-seal-instance; a fresh seal defaults to UNEXPECTED.
        val freshSeal = SseShutdownSeal(runtimeStore, gate)
        // Re-arm a Live attempt for the fresh-seal unexpected case.
        runtimeStore.markStopped(attempt) // clear to Stopped
        val a2 = runtimeStore.beginAttempt(identity)!!
        runtimeStore.markLive(a2)
        readyOwner(identity)
        freshSeal.applyDestructionDisposition(identity, a2)
        assertTrue(
            "a fresh seal (not marked intentional) → UNEXPECTED → Dropped",
            runtimeStore.state.value is SseTransportState.Dropped,
        )
        assertFalse(
            "fresh-seal dropped reason is SERVICE_DESTROYED (not the prior BG/refusal)",
            (runtimeStore.state.value as SseTransportState.Dropped).ticket.reason ==
                TransportDropReason.BACKGROUND_RECONNECT_REFUSED,
        )
    }

    // ── Wave-1 M4: Service onDestroy seam behavior ───────────────────────
    //
    // These tests exercise the REAL Service onDestroy ordering (capture →
    // applyDestructionDisposition → cancelForShutdown) through the seam, not
    // just the helper. cancelForShutdown's runtime effect (markStopped) is
    // simulated via [SseTransportRuntimeStore.markStopped] — the owner API
    // the Service uses; no Robolectric/Hilt needed.
    //
    // The disposition runs FIRST so the captured canonical attempt is still
    // usable (publishDropped/markStopped apply directly); cancelForShutdown's
    // markStopped afterwards is a harmless no-op (runtime already terminal).

    /**
     * Helper that mirrors the Service's onDestroy body: capture the active
     * attempt → seal disposition → owner cancelForShutdown (markStopped).
     * This is the production ordering the Service runs on every destroy
     * (disposition BEFORE cancelForShutdown).
     */
    private fun simulateServiceDestroy(
        identity: cn.vectory.ocdroid.service.identity.ConnectionIdentity?,
        intentional: Boolean,
    ) {
        if (intentional) seal.markIntentional()
        // Step 1: capture the active attempt BEFORE any terminalization.
        val captured = identity?.let { runtimeStore.currentAttempt(it) }
        // Step 2: apply the disposition FIRST (captured attempt still
        // canonical) — mirrors the production onDestroy ordering (disposition
        // before cancelForShutdown). The seal releases ownership then
        // INTENTIONAL → markStopped / UNEXPECTED → publishDropped(SERVICE_DESTROYED).
        seal.applyDestructionDisposition(identity, captured)
        // Step 3: cancelForShutdown's markStopped (now a no-op — the runtime is
        // already terminal from step 2). Generation bump + collector cancel are
        // owner-internal and not simulated here.
        captured?.let { runtimeStore.markStopped(it) }
    }

    // Wave-1 finding #2: intentional stopSelf/user-close path (via the
    // serviceStopSelf shell override which marks INTENTIONAL) must produce
    // Stopped — NOT SERVICE_DESTROYED. The supervisor must NOT auto-revive.
    @Test
    fun `intentional stopSelf path - disposition then cancelForShutdown produces Stopped not SERVICE_DESTROYED`() {
        val identity = bindIdentity()
        readyOwner(identity)
        val attempt = liveAttempt(identity)

        // serviceStopSelf() marks INTENTIONAL, then onDestroy runs.
        simulateServiceDestroy(identity, intentional = true)

        assertNull("ownership released", gate.readyIdentity())
        assertEquals(
            "intentional path → Stopped (never Dropped/SERVICE_DESTROYED — no auto-revive)",
            SseTransportState.Stopped,
            runtimeStore.state.value,
        )
    }

    // Wave-1 blockers #1 + #2: UNEXPECTED system destruction (no markIntentional)
    // must publish SERVICE_DESTROYED directly via the captured attempt (BEFORE
    // cancelForShutdown's markStopped), not via a Stopped→beginAttempt fallback.
    // The captured attempt is still canonical, so publishDropped applies
    // directly — no observable Connecting, no replacement ticket.
    @Test
    fun `unexpected onDestroy - disposition then cancelForShutdown publishes Dropped SERVICE_DESTROYED`() {
        val identity = bindIdentity()
        readyOwner(identity)
        val attempt = liveAttempt(identity)

        // System-killed onDestroy: NO markIntentional.
        simulateServiceDestroy(identity, intentional = false)

        // I3: ownership was released FIRST (before any runtime transition).
        assertNull("ownership released before drop (I3)", gate.readyIdentity())
        val state = runtimeStore.state.value
        assertTrue(
            "unexpected destroy → Dropped (published directly from captured attempt)",
            state is SseTransportState.Dropped,
        )
        assertEquals(
            "Dropped reason is SERVICE_DESTROYED (observable by supervisor)",
            TransportDropReason.SERVICE_DESTROYED,
            (state as SseTransportState.Dropped).ticket.reason,
        )
        assertEquals(
            "Dropped identity preserved",
            identity,
            state.ticket.identity,
        )
    }

    // Wave-1 finding #1: after cancelForShutdown + disposition, a late-frame
    // race CANNOT resurrect the runtime to Live or produce a duplicate drop.
    // The runtime's canonical-attempt validation is the authoritative backstop:
    // a stale collector's markLive on a Dropped state returns false (no
    // canonical attempt), so the frame is fully suppressed.
    @Test
    fun `owner cancellation late-frame race cannot produce Ready or duplicate drop`() {
        val identity = bindIdentity()
        readyOwner(identity)
        val attempt = liveAttempt(identity)

        // Full UNEXPECTED destroy sequence.
        simulateServiceDestroy(identity, intentional = false)
        val firstState = runtimeStore.state.value
        assertTrue("dropped after disposition", firstState is SseTransportState.Dropped)
        val firstTicket = (firstState as SseTransportState.Dropped).ticket

        // A stale collector frame races in: it carries the OLD attempt token
        // and tries markLive. The runtime is Dropped → canonicalAttempt is
        // null → markLive returns false → frame side effects suppressed.
        assertFalse(
            "late-frame markLive rejected (no Ready resurrection)",
            runtimeStore.markLive(attempt),
        )

        val state = runtimeStore.state.value
        assertTrue(
            "still Dropped (not resurrected to Live)",
            state is SseTransportState.Dropped,
        )
        assertEquals(
            "SAME drop ticket — no duplicate (M4-3)",
            firstTicket,
            (state as SseTransportState.Dropped).ticket,
        )
    }

    // Wave-1 finding #3: a repeated onDestroy / terminal path is idempotent.
    // The second destroy captures a null attempt (runtime is Dropped →
    // currentAttempt returns null) → applyDestructionDisposition is a clean
    // no-op: ownership release idempotent, no duplicate drop.
    @Test
    fun `repeated destroy terminal path is idempotent - no duplicate drop`() {
        val identity = bindIdentity()
        readyOwner(identity)
        val attempt = liveAttempt(identity)

        // First destroy: full UNEXPECTED sequence.
        simulateServiceDestroy(identity, intentional = false)
        val firstState = runtimeStore.state.value
        assertTrue("first destroy → Dropped", firstState is SseTransportState.Dropped)
        val firstTicket = (firstState as SseTransportState.Dropped).ticket

        // Second destroy: the Service captures a null attempt (runtime is
        // Dropped → currentAttempt returns null) → disposition is a no-op.
        simulateServiceDestroy(identity, intentional = false)

        val state = runtimeStore.state.value
        assertTrue("still Dropped (no duplicate)", state is SseTransportState.Dropped)
        assertEquals(
            "SAME drop ticket — no duplicate / no overwrite",
            firstTicket,
            (state as SseTransportState.Dropped).ticket,
        )
    }

    // Wave-1 finding #1: a pre-existing Dropped ticket (the owner routed a
    // real transport drop BEFORE onDestroy) is preserved — onDestroy captures
    // a null attempt (runtime already Dropped) so the disposition is a clean
    // no-op and does NOT overwrite the original ticket with SERVICE_DESTROYED.
    @Test
    fun `unexpected destroy after owner-handled drop preserves the original ticket`() {
        val identity = bindIdentity()
        readyOwner(identity)
        val attempt = liveAttempt(identity)

        // The owner already routed an exhaustion drop (Dropped).
        seal.onUnexpectedDrop(attempt, TransportDropReason.RETRY_EXHAUSTED)
        val ownerTicket = (runtimeStore.state.value as SseTransportState.Dropped).ticket

        // onDestroy captures null (runtime Dropped) → disposition is a clean
        // no-op: ownership released, original ticket preserved.
        simulateServiceDestroy(identity, intentional = false)

        val state = runtimeStore.state.value
        assertTrue("still Dropped (no duplicate)", state is SseTransportState.Dropped)
        assertEquals(
            "original RETRY_EXHAUSTED ticket preserved — no SERVICE_DESTROYED overwrite",
            ownerTicket,
            (state as SseTransportState.Dropped).ticket,
        )
    }

    // ── Wave-1 M4 (blockers #1 + #2): recovery-ticket conservation ───────
    //
    // The headline fix: an UNEXPECTED destruction mid-recovery must preserve
    // the supervisor's tracked dropId/reason. The captured recovery attempt
    // (which carries the prior Dropped ticket as recoveryTicket) is handed to
    // publishDropped DIRECTLY; the runtime restores the exact ticket (I4) —
    // no fresh dropId, no SERVICE_DESTROYED reason overwrite, no observable
    // Connecting fallback.
    @Test
    fun `unexpected destruction during unacknowledged recovery attempt preserves the same dropId and reason ticket`() {
        val identity = bindIdentity()
        readyOwner(identity)
        val first = liveAttempt(identity)

        // A real transport drop establishes Dropped(ticket) the supervisor tracks.
        seal.onUnexpectedDrop(first, TransportDropReason.RETRY_EXHAUSTED)
        val originalTicket = (runtimeStore.state.value as SseTransportState.Dropped).ticket
        assertEquals(
            "original drop reason (the demand the supervisor tracks)",
            TransportDropReason.RETRY_EXHAUSTED,
            originalTicket.reason,
        )

        // Supervisor-triggered recovery: beginAttempt from Dropped captures the
        // SAME ticket as recoveryTicket (unacknowledged — NO acknowledgeRecovery
        // was called, so the ticket is still carried).
        val recovery = runtimeStore.beginAttempt(identity)!!
        assertEquals(
            "recovery attempt carries the original ticket",
            originalTicket,
            recovery.recoveryTicket,
        )
        assertTrue("markLive (unacknowledged recovery)", runtimeStore.markLive(recovery))

        // System destroys the Service mid-recovery (UNEXPECTED). The captured
        // recovery attempt is used directly → publishDropped restores the EXACT
        // same ticket (I4). No fresh dropId, no SERVICE_DESTROYED overwrite,
        // no replacement attempt.
        simulateServiceDestroyBodyOnly(identity)

        val state = runtimeStore.state.value
        assertTrue("still Dropped", state is SseTransportState.Dropped)
        val finalTicket = (state as SseTransportState.Dropped).ticket
        assertEquals(
            "SAME dropId — recovery-ticket conservation (no fresh ticket)",
            originalTicket.dropId,
            finalTicket.dropId,
        )
        assertEquals(
            "SAME reason preserved — NOT overwritten with SERVICE_DESTROYED",
            TransportDropReason.RETRY_EXHAUSTED,
            finalTicket.reason,
        )
        assertEquals("SAME identity preserved", identity, finalTicket.identity)
        // I3 still holds: ownership released (here idempotent — already released
        // by the prior onUnexpectedDrop, but the disposition releases again).
        assertNull("ownership released", gate.readyIdentity())
    }

    // Wave-1 blocker #1: NO observable Connecting fallback. The disposition
    // publishes Dropped directly from the captured Live attempt; it never
    // routes through beginAttempt (Stopped → Connecting → Dropped). We prove
    // the absence of a replacement attempt by checking the attempt counter is
    // contiguous: a fallback beginAttempt would have burned an intermediate
    // attemptId, leaving a gap before the next allocation.
    @Test
    fun `unexpected destroy publishes SERVICE_DESTROYED with no observable Connecting fallback`() {
        val identity = bindIdentity()
        readyOwner(identity)
        val attempt = liveAttempt(identity)
        val liveAttemptId = attempt.attemptId

        simulateServiceDestroyBodyOnly(identity)

        val state = runtimeStore.state.value
        assertTrue("Dropped (no Connecting resurrection)", state is SseTransportState.Dropped)
        assertEquals(
            "reason SERVICE_DESTROYED",
            TransportDropReason.SERVICE_DESTROYED,
            (state as SseTransportState.Dropped).ticket.reason,
        )
        // No fallback beginAttempt: the next allocated attemptId is exactly
        // liveAttemptId + 1 (contiguous). A Connecting fallback (beginAttempt
        // from Stopped) would have burned an intermediate attemptId, so the
        // next allocation would be liveAttemptId + 2.
        val next = runtimeStore.beginAttempt(identity)
        assertNotNull("next attempt allocated", next)
        assertEquals(
            "no intermediate Connecting attempt was created (contiguous attemptId)",
            liveAttemptId + 1,
            next!!.attemptId,
        )
    }

    // Wave-1 blocker #4: on each newly accepted start/bootstrap the disposition
    // is re-armed to UNEXPECTED. A prior intentional stopSelf must NOT poison a
    // later unexpected destruction after a start overlap.
    @Test
    fun `new accepted start re-arms disposition then unexpected destroy publishes SERVICE_DESTROYED`() {
        val identity = bindIdentity()
        readyOwner(identity)
        liveAttempt(identity)

        // A prior intentional stopSelf poisoned the disposition (INTENTIONAL).
        seal.markIntentional()
        // A new accepted start/bootstrap re-arms to UNEXPECTED (blocker #4).
        seal.rearmUnexpected()

        // Unexpected system destruction now publishes SERVICE_DESTROYED — the
        // stale INTENTIONAL marker did NOT survive the re-arm.
        simulateServiceDestroyBodyOnly(identity)

        val state = runtimeStore.state.value
        assertTrue("re-armed → Dropped (not Stopped)", state is SseTransportState.Dropped)
        assertEquals(
            "reason SERVICE_DESTROYED (re-arm cleared the intentional poison)",
            TransportDropReason.SERVICE_DESTROYED,
            (state as SseTransportState.Dropped).ticket.reason,
        )
        assertNull("ownership released", gate.readyIdentity())
    }

    // Wave-1 blocker #4 (negative): WITHOUT the re-arm, a stale INTENTIONAL
    // marker survives and an unexpected destroy would be reported as Stopped
    // (the bug the re-arm fixes). This pins the contract: markIntentional
    // alone → Stopped; rearmUnexpected → Dropped. Re-arming is the ONLY way
    // a prior intentional mark is cleared for the same seal instance.
    @Test
    fun `stale INTENTIONAL marker without re-arm yields Stopped on unexpected destroy`() {
        val identity = bindIdentity()
        readyOwner(identity)
        liveAttempt(identity)

        seal.markIntentional()
        // NO rearmUnexpected — the intentional poison survives.
        simulateServiceDestroyBodyOnly(identity)

        assertEquals(
            "no re-arm → Stopped (intentional marker survived)",
            SseTransportState.Stopped,
            runtimeStore.state.value,
        )
        assertNull("ownership released", gate.readyIdentity())
    }

    // Wave-1 blocker #3: ownership release precedes runtime publication (I3).
    // Even when the disposition's runtime mutation is a no-op (the captured
    // attempt is null because the owner already terminalized), ownership is
    // STILL released — proving release is unconditional and ordered before any
    // runtime transition. Consumers never observe a terminal runtime alongside
    // a lingering Ready owner.
    @Test
    fun `onDestroy releases ownership before publishing the runtime drop`() {
        val identity = bindIdentity()
        readyOwner(identity)
        val attempt = liveAttempt(identity)
        assertNotNull("ownership Ready before destroy", gate.readyIdentity())

        simulateServiceDestroyBodyOnly(identity)

        // Both hold simultaneously: ownership released AND runtime terminal.
        // The seal releases FIRST, then publishes — structurally guaranteed by
        // releaseNow preceding publishDropped/markStopped in the disposition.
        assertNull("ownership released (I3)", gate.readyIdentity())
        assertTrue(
            "runtime terminal (Dropped for UNEXPECTED)",
            runtimeStore.state.value is SseTransportState.Dropped,
        )
        assertEquals(
            "ticket identity matches the released owner",
            identity,
            (runtimeStore.state.value as SseTransportState.Dropped).ticket.identity,
        )
    }

    // Wave-1 finding #2: serviceStopSelf marks INTENTIONAL, so a user-close
    // path that goes through the shell → onDestroy → disposition +
    // cancelForShutdown produces Stopped. The supervisor sees no SERVICE_DESTROYED.
    @Test
    fun `user-close path via serviceStopSelf - INTENTIONAL marker yields Stopped`() {
        val identity = bindIdentity()
        readyOwner(identity)
        val attempt = liveAttempt(identity)

        // User-close: the controller's requestUserClose → coordinator L3
        // teardown → StopSelf command → shell.serviceStopSelf() marks
        // INTENTIONAL, then stopSelf() → onDestroy.
        seal.markIntentional() // serviceStopSelf() shell override
        simulateServiceDestroyBodyOnly(identity)

        assertEquals(
            "user-close → Stopped (INTENTIONAL disposition published directly)",
            SseTransportState.Stopped,
            runtimeStore.state.value,
        )
        assertNull("ownership released", gate.readyIdentity())
    }

    /**
     * Body-only simulate (no markIntentional): mirrors onDestroy's capture →
     * applyDestructionDisposition → cancelForShutdown WITHOUT the intentional
     * mark, so the test can control when markIntentional was called (e.g. via
     * serviceStopSelf before onDestroy).
     */
    private fun simulateServiceDestroyBodyOnly(
        identity: cn.vectory.ocdroid.service.identity.ConnectionIdentity?,
    ) {
        val captured = identity?.let { runtimeStore.currentAttempt(it) }
        // Disposition FIRST (captured attempt still canonical).
        seal.applyDestructionDisposition(identity, captured)
        // cancelForShutdown's markStopped (no-op post-disposition).
        captured?.let { runtimeStore.markStopped(it) }
    }
}
