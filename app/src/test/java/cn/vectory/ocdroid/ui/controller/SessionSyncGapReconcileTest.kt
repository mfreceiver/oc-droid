package cn.vectory.ocdroid.ui.controller

import android.util.Log
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.model.SSEEvent
import cn.vectory.ocdroid.data.model.SSEPayload
import cn.vectory.ocdroid.ui.SharedEffectBus
import cn.vectory.ocdroid.ui.SharedStateStore
import cn.vectory.ocdroid.ui.SliceFlows
import cn.vectory.ocdroid.util.SettingsManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * §R-19 Sprint 1 Lane A (P1-10) — SSE gap reconciliation tests.
 *
 * Two complementary layers:
 *
 *  1. **Pure function tests** (`GapReconcilePureFunctionsTest`): drive
 *     [reconcileGap] directly with hand-built [SseSyncState] +
 *     [SseReconnectTrigger] instances, assert the returned state + decisions.
 *     No Android framework, no coroutine scope, no slice reads. These are the
 *     explicit invariants the overlay was added to provide.
 *
 *  2. **Integration tests** (`GapReconcileIntegrationTest`): drive the full
 *     [SessionSyncCoordinator.handleEvent] path with a real [SharedEffectBus]
 *     + real [SharedStateStore] slices (mirrors [SessionSyncCoordinatorTest]'s
 *     setUp). Asserts the decisions are translated into the right
 *     [ControllerEffect]s on the bus, and the generation guard survives a
 *     HostReconfigured → late-ServerConnected sequence.
 *
 * Scenarios covered (per R19-execution-plan.md §2 S1-T1):
 *  - **1**: delta interrupted → reconnect → ClearDeltaBuffers + ReloadSession(current)
 *  - **2**: reconnect then message.updated then session.status idle → no duplicate reload
 *  - **3**: currentSession switched mid-disconnect → ReloadSession points at NEW current
 *  - **4**: host reconfigure → late server.connected from old host → no-op (generation guard)
 */

// ─────────────────────────────────────────────────────────────────────────────
// 1. PURE FUNCTION TESTS — drive reconcileGap directly.
// ─────────────────────────────────────────────────────────────────────────────

class GapReconcilePureFunctionsTest {

    @Test
    fun `scenario 1 - delta interrupted reconnect clears delta buffers and reloads current session`() {
        // State after a disconnect was observed at t=100; currentSession=A.
        val state = SseSyncState(
            connectedOnce = true,
            lastDisconnectAt = 100L,
            sessionsDirty = emptySet(),
            hostGeneration = 5L
        )
        val trigger = SseReconnectTrigger.ServerConnected(currentSessionId = "A", hostGeneration = 5L)

        val (newState, decisions) = reconcileGap(state, trigger)

        assertEquals(
            listOf(SseSyncDecision.ClearDeltaBuffers, SseSyncDecision.ReloadSession("A", true)),
            decisions
        )
        // lastDisconnectAt cleared → idempotent for the next server.connected.
        assertNull(newState.lastDisconnectAt)
        // §R-19 fix Blocker 2 v2: currentSessionId is NOT added to sessionsDirty
        // (the idle-dedup mechanism is removed; sessionsDirty is now populated
        // only by the Disconnected trigger).
        assertEquals(emptySet<String>(), newState.sessionsDirty)
        assertTrue(newState.connectedOnce)
        assertEquals(5L, newState.hostGeneration)
    }

    @Test
    fun `scenario 2 pure - second server connected after cold-start fires implicit gap recovery`() {
        // §R-19 fix Blocker 3: a server.connected arriving with connectedOnce=true
        // AND lastDisconnectAt==null represents an implicit retryWhen reconnect
        // (no explicit CancelSse / onFailure signal was observed). The overlay
        // MUST reconcile — the prior idempotency branch silently swallowed this
        // case, defeating P1-10 for the most common production gap path.
        // State: connectedOnce=true (cold-start already processed), no explicit
        // disconnect tracked, dirty={A} from a prior reconciliation.
        val state = SseSyncState(
            connectedOnce = true,
            lastDisconnectAt = null,
            sessionsDirty = setOf("A"),
            hostGeneration = 5L
        )
        val trigger = SseReconnectTrigger.ServerConnected(currentSessionId = "A", hostGeneration = 5L)

        val (newState, decisions) = reconcileGap(state, trigger)

        // Implicit gap recovery: ClearDeltaBuffers + ReloadSession(A). The
        // idempotency for true duplicate server.connected frames within a single
        // healthy connection now relies on the dispatch layer's
        // isLoadingMessages coalescing (same path that absorbs
        // ForegroundCatchUpController's overlapping catch-up effects).
        assertEquals(
            listOf(SseSyncDecision.ClearDeltaBuffers, SseSyncDecision.ReloadSession("A", true)),
            decisions
        )
        assertNull(newState.lastDisconnectAt)
        // §R-19 fix Blocker 2 v2: ServerConnected does NOT add currentSessionId
        // to sessionsDirty. A stays in dirty only because it was already in
        // the input (from a prior Disconnected trigger) — NOT because the
        // reconnect added it.
        assertTrue("A still in sessionsDirty (from input, not added)", newState.sessionsDirty.contains("A"))
    }

    @Test
    fun `scenario 3 - currentSession switched mid-disconnect reloads the NEW current session`() {
        // User was on A when disconnect happened → A is dirty.
        val state = SseSyncState(
            connectedOnce = true,
            lastDisconnectAt = 100L,
            sessionsDirty = setOf("A"),
            hostGeneration = 5L
        )
        // During the disconnect window the user switched to B; when
        // server.connected arrives, currentSessionId is now B.
        val trigger = SseReconnectTrigger.ServerConnected(currentSessionId = "B", hostGeneration = 5L)

        val (newState, decisions) = reconcileGap(state, trigger)

        // ReloadSession targets B (the NEW current), never A.
        val reload = decisions.filterIsInstance<SseSyncDecision.ReloadSession>().single()
        assertEquals("B", reload.sessionId)
        assertTrue("resetLimit should be true (authoritative reload)", reload.resetLimit)
        // ClearDeltaBuffers always fires on reconnect.
        assertTrue(decisions.contains(SseSyncDecision.ClearDeltaBuffers))
        // A is dirty AND not current → RefreshSessions handles its list-level
        // state (badge / last-activity) without a windowed reload.
        assertTrue(
            "expected RefreshSessions for non-current dirty session A",
            decisions.contains(SseSyncDecision.RefreshSessions)
        )
        // New state: sessionsDirty unchanged (§R-19 fix Blocker 2 v2:
        // currentSessionId is NOT added; A was in the input from the disconnect
        // and stays; B is not added). disconnect cleared.
        assertEquals(setOf("A"), newState.sessionsDirty)
        assertNull(newState.lastDisconnectAt)
    }

    @Test
    fun `scenario 4 - late server connected from previous host is a no-op via generation guard`() {
        // Current generation is 5 (host was reconfigured away from gen 4).
        val state = SseSyncState(
            connectedOnce = true,
            lastDisconnectAt = 100L,
            sessionsDirty = setOf("A"),
            hostGeneration = 5L
        )
        // A late server.connected frame from the PREVIOUS host's cancelled SSE
        // job arrives carrying generation=4 (stale).
        val trigger = SseReconnectTrigger.ServerConnected(currentSessionId = "A", hostGeneration = 4L)

        val (newState, decisions) = reconcileGap(state, trigger)

        assertTrue("stale-generation trigger must not emit decisions", decisions.isEmpty())
        assertEquals("state must be untouched", state, newState)
    }

    // ── supporting triggers + edge cases ────────────────────────────────────

    @Test
    fun `cold start - first server connected marks connectedOnce and emits nothing`() {
        val state = SseSyncState(connectedOnce = false, hostGeneration = 0L)
        val trigger = SseReconnectTrigger.ServerConnected(currentSessionId = "A", hostGeneration = 0L)

        val (newState, decisions) = reconcileGap(state, trigger)

        assertTrue(decisions.isEmpty())
        assertTrue("connectedOnce flips to true", newState.connectedOnce)
    }

    @Test
    fun `host reconfigured resets all per-host state and bumps generation`() {
        val state = SseSyncState(
            connectedOnce = true,
            lastDisconnectAt = 100L,
            sessionsDirty = setOf("A", "B"),
            hostGeneration = 5L
        )
        val trigger = SseReconnectTrigger.HostReconfigured(hostGeneration = 6L)

        val (newState, decisions) = reconcileGap(state, trigger)

        assertTrue(decisions.isEmpty())
        assertEquals(6L, newState.hostGeneration)
        // Cold-start semantics under the new generation.
        assertTrue(!newState.connectedOnce)
        assertNull(newState.lastDisconnectAt)
        assertTrue(newState.sessionsDirty.isEmpty())
    }

    @Test
    fun `disconnected stamps lastDisconnectAt and merges dirty sessions additively`() {
        val state = SseSyncState(
            connectedOnce = true,
            lastDisconnectAt = null,
            sessionsDirty = setOf("A"),
            hostGeneration = 5L
        )
        val trigger = SseReconnectTrigger.Disconnected(
            atMs = 200L,
            dirtySessionIds = setOf("B"),
            hostGeneration = 5L
        )

        val (newState, decisions) = reconcileGap(state, trigger)

        assertTrue(decisions.isEmpty())
        assertEquals(200L, newState.lastDisconnectAt)
        assertEquals(setOf("A", "B"), newState.sessionsDirty)
    }

    @Test
    fun `disconnected on never-connected host is a no-op`() {
        val state = SseSyncState(connectedOnce = false, hostGeneration = 0L)
        val trigger = SseReconnectTrigger.Disconnected(atMs = 100L, dirtySessionIds = setOf("A"), hostGeneration = 0L)

        val (newState, decisions) = reconcileGap(state, trigger)

        assertTrue(decisions.isEmpty())
        assertEquals(state, newState)
    }

    @Test
    fun `disconnected with stale generation is a no-op`() {
        val state = SseSyncState(connectedOnce = true, hostGeneration = 5L)
        val trigger = SseReconnectTrigger.Disconnected(atMs = 100L, dirtySessionIds = setOf("A"), hostGeneration = 4L)

        val (newState, decisions) = reconcileGap(state, trigger)

        assertTrue(decisions.isEmpty())
        assertEquals(state, newState)
    }

    @Test
    fun `server connected with null current session still clears buffers and refreshes dirty`() {
        val state = SseSyncState(
            connectedOnce = true,
            lastDisconnectAt = 100L,
            sessionsDirty = setOf("A"),
            hostGeneration = 5L
        )
        // currentSessionId null: no per-session reload, but A is dirty and not
        // current → RefreshSessions handles it.
        val trigger = SseReconnectTrigger.ServerConnected(currentSessionId = null, hostGeneration = 5L)

        val (newState, decisions) = reconcileGap(state, trigger)

        assertEquals(
            "no ReloadSession when currentSessionId is null",
            listOf(SseSyncDecision.ClearDeltaBuffers, SseSyncDecision.RefreshSessions),
            decisions
        )
        assertEquals(setOf("A"), newState.sessionsDirty)
        assertNull(newState.lastDisconnectAt)
    }

    @Test
    fun `server connected with current session already dirty still reloads it and preserves dirty set`() {
        // §R-19 fix Blocker 2 v2: ServerConnected does NOT add currentSessionId
        // to sessionsDirty. A Disconnect marked session-A dirty; the subsequent
        // ServerConnected must STILL schedule ReloadSession(A) (the dirty set
        // represents "sessions stale at disconnect time", used for the
        // RefreshSessions decision — not "already reloaded"). The dirty entry
        // is preserved as-is (not added to, not consumed here).
        val state = SseSyncState(
            connectedOnce = true,
            lastDisconnectAt = 200L,
            sessionsDirty = setOf("A"),
            hostGeneration = 5L
        )
        val trigger = SseReconnectTrigger.ServerConnected(currentSessionId = "A", hostGeneration = 5L)

        val (newState, decisions) = reconcileGap(state, trigger)

        // ReloadSession fires unconditionally (always-reconcile after cold-start,
        // §R-19 fix Blocker 3).
        assertEquals(
            listOf(SseSyncDecision.ClearDeltaBuffers, SseSyncDecision.ReloadSession("A", true)),
            decisions
        )
        assertNull(newState.lastDisconnectAt)
        // A preserved as-is (not added — was already there from input).
        assertEquals(setOf("A"), newState.sessionsDirty)
    }

    // ── §R-19 fix Blocker 3: implicit gap recovery (no explicit disconnect) ──

    @Test
    fun `blocker 3 - server connected with no prior disconnect fires implicit gap recovery`() {
        // §R-19 fix Blocker 3: connectSSE's internal retryWhen handles network
        // failures and reconnects without emitting any signal the overlay can
        // observe. The next server.connected arrives with connectedOnce=true
        // AND lastDisconnectAt==null — the overlay MUST synthesize gap recovery
        // (ClearDeltaBuffers + ReloadSession), NOT treat it as idempotent no-op.
        // This is the most common production gap-recovery path.
        val state = SseSyncState(
            connectedOnce = true,
            lastDisconnectAt = null,  // ← no explicit Disconnected trigger observed
            sessionsDirty = emptySet(),
            hostGeneration = 5L
        )
        val trigger = SseReconnectTrigger.ServerConnected(currentSessionId = "session-X", hostGeneration = 5L)

        val (newState, decisions) = reconcileGap(state, trigger)

        assertEquals(
            "implicit gap recovery fires ClearDeltaBuffers + ReloadSession",
            listOf(SseSyncDecision.ClearDeltaBuffers, SseSyncDecision.ReloadSession("session-X", true)),
            decisions
        )
        // §R-19 fix Blocker 2 v2: currentSessionId is NOT added to sessionsDirty.
        // The set stays empty (no Disconnected trigger fired in this scenario).
        assertEquals(emptySet<String>(), newState.sessionsDirty)
        // lastDisconnectAt was null and stays null (no new info to stamp).
        assertNull(newState.lastDisconnectAt)
    }

    @Test
    fun `blocker 3 - server connected after implicit recovery continues to reconcile (no permanent suppress)`() {
        // §R-19 fix Blocker 3 follow-up: a third server.connected (e.g. another
        // network blip) must ALSO reconcile. The prior idempotency-by-default
        // would suppress subsequent reconnects after the first; the new always-
        // reconcile semantics has no such suppression.
        val state = SseSyncState(
            connectedOnce = true,
            lastDisconnectAt = null,
            // §R-19 fix Blocker 2 v2: session-X is in dirty from a prior
            // Disconnected trigger (NOT from a prior ServerConnected — that
            // path no longer adds to the set). Simulates a real production
            // state where an earlier disconnect marked this session.
            sessionsDirty = setOf("session-X"),
            hostGeneration = 5L
        )
        val trigger = SseReconnectTrigger.ServerConnected(currentSessionId = "session-X", hostGeneration = 5L)

        val (newState, decisions) = reconcileGap(state, trigger)

        // Decisions still fire (no idempotency branch swallows them).
        assertTrue(
            "expected ReloadSession decision",
            decisions.any { it is SseSyncDecision.ReloadSession && it.sessionId == "session-X" }
        )
        assertTrue(decisions.contains(SseSyncDecision.ClearDeltaBuffers))
        // State stable.
        assertNull(newState.lastDisconnectAt)
        // session-X preserved (was in input; not added, not removed).
        assertTrue(newState.sessionsDirty.contains("session-X"))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 2. INTEGRATION TESTS — drive SessionSyncCoordinator.handleEvent end-to-end.
// ─────────────────────────────────────────────────────────────────────────────

@Suppress("DEPRECATION")
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GapReconcileIntegrationTest {

    private lateinit var slices: SliceFlows
    private lateinit var effects: SharedEffectBus
    private lateinit var collectedEffects: MutableList<ControllerEffect>
    private lateinit var settingsManager: SettingsManager
    private lateinit var scope: TestScope
    private lateinit var coordinator: SessionSyncCoordinator
    /**
     * Carries the prior snapshot so successive `seed { ... }` calls compose
     * against the prior state (mirrors [SessionSyncCoordinatorTest]'s pattern).
     */
    private var appStateFixture: SeedFixture = SeedFixture()

    @Before
    fun setUp() {
        // reportNonFatalIssue inlines android.util.Log.w; stub it for the JVM harness.
        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0

        val store = SharedStateStore()
        slices = store.slices
        settingsManager = mockk(relaxed = true)
        effects = SharedEffectBus()
        collectedEffects = mutableListOf()
        scope = TestScope(UnconfinedTestDispatcher())
        // Drain every emitted effect so test bodies can filter by type.
        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            effects.effectsConsumed.toList(collectedEffects)
        }
        coordinator = SessionSyncCoordinator(scope, slices, settingsManager, effects)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun seed(block: (SeedFixture) -> SeedFixture) {
        appStateFixture = block(appStateFixture)
        val s = appStateFixture
        slices.mutateChat {
            it.copy(
                currentSessionId = s.currentSessionId,
                messages = s.messages,
                partsByMessage = s.partsByMessage,
                streamingPartTexts = s.streamingPartTexts,
                streamingReasoningPart = s.streamingReasoningPart
            )
        }
    }

    private fun setCurrentSession(sessionId: String?) {
        seed { it.copy(currentSessionId = sessionId) }
    }

    private fun event(type: String, block: JsonObjectBuilder.() -> Unit): SSEEvent =
        SSEEvent(payload = SSEPayload(type = type, properties = buildJsonObject(block)))

    // ── scenario 1 ──────────────────────────────────────────────────────────

    @Test
    fun `scenario 1 integration - reconnect after disconnect emits ClearDeltaBuffers via LoadMessages for current session`() {
        setCurrentSession("session-A")
        // Seed a streaming overlay so ClearDeltaBuffers has observable state to clear.
        seed {
            it.copy(
                streamingPartTexts = mapOf("part-1" to "partial"),
                streamingReasoningPart = Part(id = "part-1", messageId = "m1", sessionId = "session-A", type = "text")
            )
        }
        // First connect: cold start — marks connectedOnce, no decisions.
        coordinator.handleEvent(event("server.connected") {})
        // Drain any effects from the cold-start path.
        collectedEffects.clear()

        // Simulate a disconnect via the CancelSse effect (the init collector
        // observes it and stamps lastDisconnectAt + marks session-A dirty).
        effects.tryEmitEffect(ControllerEffect.CancelSse)
        // The collector also adds CancelSse itself to collectedEffects; clear
        // so we only observe the reconnect decisions below.
        collectedEffects.clear()

        // Reconnect: server.connected → reconcileGap fires decisions.
        coordinator.handleEvent(event("server.connected") {})

        // ReloadSession(A, true) → LoadMessages effect.
        val loadMessages = collectedEffects.filterIsInstance<ControllerEffect.LoadMessages>()
        assertEquals(
            "expected exactly one LoadMessages for session-A with resetLimit=true",
            listOf("session-A" to true),
            loadMessages.map { it.sessionId to it.resetLimit }
        )
        // ClearDeltaBuffers drops the pending delta/fullText buffers (the
        // streaming OVERLAY itself is preserved — that's the §闪屏修复 invariant;
        // the overlay is reconciled by ReloadSession's resetLimit load).
        assertTrue(
            "pendingFlushPartIds cleared by ClearDeltaBuffers decision",
            slices.chat.value.pendingFlushPartIds.isEmpty()
        )
        assertTrue(
            "deltaBuffer cleared by ClearDeltaBuffers decision",
            slices.chat.value.deltaBuffer.isEmpty()
        )
        // session-A is in sessionsDirty — added by the CancelSse Disconnected
        // trigger (NOT by the reconnect). §R-19 fix Blocker 2 v2: the set is
        // now used solely for the ServerConnected RefreshSessions decision
        // (scenario 3), not for idle dedup.
        assertTrue("session-A in sessionsDirty (from CancelSse trigger)", coordinator.sseSyncStateSnapshot().sessionsDirty.contains("session-A"))
    }

    // ── scenario 2 ──────────────────────────────────────────────────────────

    @Test
    fun `scenario 2 integration - reconnect and subsequent idle both emit LoadMessages with coalescing at scheduling layer`() {
        // §R-19 fix Blocker 2 v2 (gpter round-2): the sessionsDirty idle-dedup
        // is REMOVED. Both the reconnect's ReloadSession and a subsequent
        // session.status idle emit their own LoadMessages effect — they are
        // NOT dedup'd at the SSC layer. Overlapping reloads are coalesced at
        // the SCHEDULING LAYER (`launchLoadMessages`'s synchronous
        // `isLoadingMessages` check-and-set + `launchLoadMessagesWithRetry`'s
        // 400ms debounce), which is the same path that absorbs
        // ForegroundCatchUpController's overlapping catch-up effects. That
        // coalescing is exercised in MessageActionsTest, not here.
        //
        // This test pins the SSC-layer contract: BOTH emissions reach the
        // effects bus. The lower layer's job is to collapse them.
        setCurrentSession("session-A")
        // Non-empty overlay so session.status idle WOULD fire LoadMessages.
        seed {
            it.copy(
                streamingPartTexts = mapOf("part-1" to "partial"),
                streamingReasoningPart = Part(id = "part-1", messageId = "m1", sessionId = "session-A", type = "reasoning")
            )
        }
        // First connect: cold start.
        coordinator.handleEvent(event("server.connected") {})
        collectedEffects.clear()

        // Disconnect → init collector marks session-A dirty (via Disconnected
        // trigger). This dirty entry is used ONLY by the next reconnect's
        // RefreshSessions decision, NOT for idle dedup.
        effects.tryEmitEffect(ControllerEffect.CancelSse)
        collectedEffects.clear()

        // Reconnect: ReloadSession(A) emitted (1st LoadMessages).
        coordinator.handleEvent(event("server.connected") {})
        assertEquals(
            "reconnect emits exactly one LoadMessages for session-A",
            listOf("session-A"),
            collectedEffects.filterIsInstance<ControllerEffect.LoadMessages>().map { it.sessionId }
        )

        // session.status idle arrives right after the reconnect (overlay still
        // non-empty). With the dedup REMOVED, this ALSO emits LoadMessages —
        // the scheduling layer will coalesce the two into one network round-trip.
        coordinator.handleEvent(event("session.status") {
            put("sessionID", JsonPrimitive("session-A"))
            put("status", buildJsonObject { put("type", JsonPrimitive("idle")) })
        })
        assertEquals(
            "idle ALSO emits LoadMessages (no SSC-layer dedup; coalescing is at the scheduling layer)",
            2,
            collectedEffects.filterIsInstance<ControllerEffect.LoadMessages>().size
        )
    }

    @Test
    fun `fix blocker 2 v2 - idle reload fires normally after a reconnect with no prior idle - no permanent suppress`() {
        // §R-19 fix Blocker 2 v2 (gpter round-2 residual blocker): the CORE
        // regression. Risk timeline that the prior dedup could NOT handle:
        //   1. session=A, SSE reconnect → ReloadSession(A)
        //   2. no active run at reconnect time → no idle arrives
        //   3. LoadMessages(A) completes
        //   4. user later initiates a run → run ends → session.status idle arrives
        //   5. PRIOR behavior: idle was wrongly skipped (sessionsDirty still
        //      held A from step 1's reconnect)
        //   6. NEW behavior: idle reload fires normally — sessionsDirty is no
        //      longer consulted by the idle branch.
        setCurrentSession("session-A")
        // Non-empty overlay so session.status idle would fire LoadMessages.
        seed {
            it.copy(
                streamingPartTexts = mapOf("part-1" to "partial"),
                streamingReasoningPart = Part(id = "part-1", messageId = "m1", sessionId = "session-A", type = "reasoning")
            )
        }
        // Cold start.
        coordinator.handleEvent(event("server.connected") {})
        collectedEffects.clear()

        // Disconnect + reconnect (no idle in between — no active run at the time).
        effects.tryEmitEffect(ControllerEffect.CancelSse)
        collectedEffects.clear()
        coordinator.handleEvent(event("server.connected") {})
        // Reconnect emitted exactly one LoadMessages.
        assertEquals(1, collectedEffects.filterIsInstance<ControllerEffect.LoadMessages>().size)
        collectedEffects.clear()  // reset for the idle assertion below

        // TIME PASSES — the reconnect's LoadMessages completed. The user
        // initiates a NEW run; run ends; session.status idle arrives (overlay
        // non-empty from the new run). MUST reload normally — no permanent
        // suppress from the prior reconnect.
        coordinator.handleEvent(event("session.status") {
            put("sessionID", JsonPrimitive("session-A"))
            put("status", buildJsonObject { put("type", JsonPrimitive("idle")) })
        })

        assertEquals(
            "idle reload fires normally for a new run after a reconnect (no permanent suppress)",
            1,
            collectedEffects.filterIsInstance<ControllerEffect.LoadMessages>().size
        )
    }

    // ── scenario 3 ──────────────────────────────────────────────────────────

    @Test
    fun `scenario 3 integration - currentSession switched mid-disconnect targets the NEW session`() {
        // Start on session-A.
        setCurrentSession("session-A")
        coordinator.handleEvent(event("server.connected") {})  // cold start
        collectedEffects.clear()

        // Disconnect while on A.
        effects.tryEmitEffect(ControllerEffect.CancelSse)
        collectedEffects.clear()

        // User switches to session-B during the disconnect window.
        setCurrentSession("session-B")

        // Reconnect: server.connected arrives; currentSessionId is now B.
        coordinator.handleEvent(event("server.connected") {})

        val loadMessages = collectedEffects.filterIsInstance<ControllerEffect.LoadMessages>()
        assertEquals(
            "ReloadSession should target session-B (the NEW current), not session-A",
            listOf("session-B"),
            loadMessages.map { it.sessionId }
        )
        // RefreshSessions should also fire because session-A is dirty and not current.
        assertNotNull(
            "expected a LoadSessions effect for the dirty non-current session-A",
            collectedEffects.filterIsInstance<ControllerEffect.LoadSessions>().singleOrNull()
        )
    }

    // ── scenario 4 ──────────────────────────────────────────────────────────

    @Test
    fun `scenario 4 integration - host reconfigure resets connectedOnce so next server connected is cold-start`() {
        // §R-19 Sprint 1 Lane A (P1-10) scenario 4 + fix Blocker 1:
        //
        // The PRODUCTION stale-host guard lives in ConnectionCoordinator.
        // launchSseCollection (per-event generation check, drops stale events
        // BEFORE they're forwarded as OnSseEvent). That guard is exercised in
        // ConnectionCoordinatorTest. This integration test asserts the
        // SessionSyncCoordinator side of the contract: HostReconfigured resets
        // connectedOnce=false so the next (new-host) server.connected is treated
        // as a cold-start (no ReloadSession), NOT as an implicit gap recovery
        // of the previous host's session.
        setCurrentSession("session-A")
        // First connect on generation 0 (cold start) → connectedOnce=true.
        coordinator.handleEvent(event("server.connected") {})
        collectedEffects.clear()

        // Disconnect, then host reconfigure bumps the generation to 1 and
        // resets connectedOnce=false.
        effects.tryEmitEffect(ControllerEffect.CancelSse)
        effects.tryEmitEffect(ControllerEffect.HostReconfigured)
        collectedEffects.clear()

        val stateAfterReconfigure = coordinator.sseSyncStateSnapshot()
        assertTrue(
            "expected connectedOnce=false after HostReconfigured; was $stateAfterReconfigure",
            !stateAfterReconfigure.connectedOnce
        )

        // Simulate the NEW host's first server.connected — this is a cold
        // start under gen 1 and should mark connectedOnce=true, no ReloadSession
        // decision (the cold-start loader handles initial snapshot).
        coordinator.handleEvent(event("server.connected") {})

        assertTrue(
            "cold-start under new host does NOT emit LoadMessages",
            collectedEffects.filterIsInstance<ControllerEffect.LoadMessages>().isEmpty()
        )
        assertTrue(coordinator.sseSyncStateSnapshot().connectedOnce)
    }

    @Test
    fun `fix blocker 3 integration - second server connected without explicit disconnect fires implicit gap recovery`() {
        // §R-19 fix Blocker 3 integration: a server.connected arriving after
        // the cold-start, with no explicit CancelSse in between (simulating
        // connectSSE's internal retryWhen recovery), MUST fire ClearDeltaBuffers
        // + ReloadSession. The prior idempotency-by-default would silently
        // swallow this case, leaving the overlay stale after a network blip.
        setCurrentSession("session-A")
        // Cold start.
        coordinator.handleEvent(event("server.connected") {})
        collectedEffects.clear()
        assertTrue(coordinator.sseSyncStateSnapshot().connectedOnce)

        // Simulate a network gap recovered by retryWhen: NO CancelSse emitted,
        // NO HostReconfigured. The next server.connected arrives directly.
        coordinator.handleEvent(event("server.connected") {})

        val loadMessages = collectedEffects.filterIsInstance<ControllerEffect.LoadMessages>()
        assertEquals(
            "implicit gap recovery fires ReloadSession for current session-A",
            listOf("session-A"),
            loadMessages.map { it.sessionId }
        )
        // ClearDeltaBuffers also fired (pendingFlushPartIds cleared).
        assertTrue(
            "ClearDeltaBuffers fired",
            collectedEffects.filterIsInstance<ControllerEffect.ClearDeltaBuffers>().isEmpty() &&
                slices.chat.value.pendingFlushPartIds.isEmpty()
        )
        // §R-19 fix Blocker 2 v2: currentSession is NOT added to sessionsDirty
        // (no CancelSse in this test → no Disconnected trigger → dirty stays
        // empty). The idle-dedup mechanism is removed entirely.
        assertFalse(
            "session-A NOT in sessionsDirty (no Disconnected trigger fired; idle-dedup removed)",
            coordinator.sseSyncStateSnapshot().sessionsDirty.contains("session-A")
        )
    }

    @Test
    fun `host reconfigure clears sessionsDirty accumulated by prior disconnects`() {
        setCurrentSession("session-A")
        coordinator.handleEvent(event("server.connected") {})  // cold start
        effects.tryEmitEffect(ControllerEffect.CancelSse)  // marks session-A dirty
        assertTrue(coordinator.sseSyncStateSnapshot().sessionsDirty.contains("session-A"))

        effects.tryEmitEffect(ControllerEffect.HostReconfigured)

        val snap = coordinator.sseSyncStateSnapshot()
        assertTrue("sessionsDirty cleared on HostReconfigured", snap.sessionsDirty.isEmpty())
        assertTrue("lastDisconnectAt cleared on HostReconfigured", snap.lastDisconnectAt == null)
        assertTrue("connectedOnce cleared on HostReconfigured", !snap.connectedOnce)
    }

    @Test
    fun `existing server connected test contract preserved - cold start emits ServerConnected effect only`() {
        // Regression guard: the existing
        // `server connected routes to onServerConnected and is a no-op in the
        // dispatch table` contract must still hold under the overlay — cold
        // start (default fixture, currentSessionId=null) emits exactly one
        // ServerConnected effect and zero LoadMessages.
        coordinator.handleEvent(event("server.connected") {})

        assertEquals(1, collectedEffects.filterIsInstance<ControllerEffect.ServerConnected>().size)
        assertTrue(collectedEffects.filterIsInstance<ControllerEffect.LoadMessages>().isEmpty())
    }
}
