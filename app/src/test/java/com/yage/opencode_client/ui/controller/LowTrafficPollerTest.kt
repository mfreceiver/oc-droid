package com.yage.opencode_client.ui.controller

import com.yage.opencode_client.data.model.Message
import com.yage.opencode_client.data.model.PermissionRequest
import com.yage.opencode_client.data.model.QuestionRequest
import com.yage.opencode_client.data.model.Session
import com.yage.opencode_client.data.model.SessionStatus
import com.yage.opencode_client.data.repository.OpenCodeRepository
import com.yage.opencode_client.di.AppLifecycleMonitor
import com.yage.opencode_client.ui.AppState
import com.yage.opencode_client.ui.ChatState
import com.yage.opencode_client.ui.ComposerState
import com.yage.opencode_client.ui.ConnectionState
import com.yage.opencode_client.ui.FileState
import com.yage.opencode_client.ui.HostState
import com.yage.opencode_client.ui.SessionListState
import com.yage.opencode_client.ui.SettingsState
import com.yage.opencode_client.ui.SliceFlows
import com.yage.opencode_client.ui.TrafficState
import com.yage.opencode_client.ui.UnreadState
import com.yage.opencode_client.ui.syncSlicesFromAppState
import com.yage.opencode_client.util.SettingsManager
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * M1 (docs/省流模式设计.md §1): independent unit test for [LowTrafficPoller].
 *
 * Zero reflection — the state machine is driven through its public/internal API
 * ([start]/[stop]/[onForegroundChanged]/[onSessionSwitched]/[onMessageSent] +
 * the internal [LowTrafficPoller.doTick]) and asserted via the
 * [RecordingCallbacks] spy + direct state reads. An injected `clock` makes the
 * content-probe wall-time branch deterministic; the tick counter drives the
 * every-3 / every-6 cadences. Heavy deps (OpenCodeRepository / SettingsManager)
 * are mockk stubs; AppState + SliceFlows are real so the poller's writes are
 * observable. Follows the [ForegroundCatchUpControllerTest] /
 * [ConnectionCoordinatorTest] pattern.
 *
 * The 12 cases below mirror the v4 §1 state-machine scenarios (idle counting,
 * three-layer signals, retry/recovery, network backoff, idle backoff,
 * sendMessage local-busy, foreground catchUp boundary).
 */
@Suppress("DEPRECATION")
class LowTrafficPollerTest {

    private lateinit var state: MutableStateFlow<AppState>
    private lateinit var slices: SliceFlows
    private lateinit var repository: OpenCodeRepository
    private lateinit var settingsManager: SettingsManager
    private lateinit var callbacks: RecordingCallbacks
    private lateinit var foregroundFlow: MutableStateFlow<Boolean>
    private var nowMs: Long = 0L

    private fun makePoller(scope: TestScope): LowTrafficPoller =
        LowTrafficPoller(
            scope = scope,
            state = state,
            slices = slices,
            repository = repository,
            settingsManager = settingsManager,
            appLifecycleMonitor = stubMonitor(),
            callbacks = callbacks,
            clock = { nowMs }
        )

    @Before
    fun setUp() {
        state = MutableStateFlow(
            AppState(
                currentSessionId = "s1",
                // local newest by time.created = "A"
                messages = listOf(Message(id = "A", role = "user", time = Message.TimeInfo(created = 100L)))
            )
        )
        slices = SliceFlows(
            connection = MutableStateFlow(ConnectionState()),
            traffic = MutableStateFlow(TrafficState()),
            composer = MutableStateFlow(ComposerState()),
            file = MutableStateFlow(FileState()),
            settings = MutableStateFlow(SettingsState()),
            chat = MutableStateFlow(ChatState()),
            sessionList = MutableStateFlow(SessionListState()),
            unread = MutableStateFlow(UnreadState()),
            host = MutableStateFlow(HostState())
        )
        // §R-17 M5.1: updateAndSync rebuilds AppState from the slices, so the
        // slices must mirror the seed state or the first tick's write would
        // wipe messages (aggregateFromSlices reads the empty chat slice).
        // Mirrors the production MainViewModel init where slices track _state.
        syncSlicesFromAppState(state.value, slices)
        repository = mockk(relaxed = true)
        settingsManager = mockk(relaxed = true)
        callbacks = RecordingCallbacks()
        callbacks.currentSessionId = "s1"
        foregroundFlow = MutableStateFlow(true)
        nowMs = 0L
    }

    // ── 1. idle 无变化 → 计数器累加（推进 6 tick content probe） ────────────

    @Test
    fun `idle with unchanged content probe accumulates counter and does not catchUp`() {
        val poller = makePoller(TestScope())
        stubIdle(statusOf("s1", "idle"))
        stubSessionUpdated(100L)
        stubProbe("A") // matches local newest
        stubPendingEmpty()
        poller.start()

        repeat(6) { runTick(poller) }

        assertEquals("no catchUp when probe matches anchor", 0, callbacks.triggerCatchUpCalls)
        assertTrue(
            "unchanged counter must accumulate (got ${poller.unchangedCounterForTest()})",
            poller.unchangedCounterForTest() >= 1
        )
    }

    // ── 2. idle + time.updated 变 → triggerCatchUp ─────────────────────────

    @Test
    fun `idle with time_dot_updated change triggers catchUp`() {
        val poller = makePoller(TestScope())
        stubIdle(statusOf("s1", "idle"))
        // tick 3 (first time probe) records updated=100; tick 6 sees updated=200 → trigger.
        coEvery { repository.getSession("s1") } returnsMany listOf(
            Result.success(sessionWithUpdated(100L)),
            Result.success(sessionWithUpdated(200L))
        )
        stubProbe("A")
        stubPendingEmpty()
        poller.start()

        repeat(6) { runTick(poller) } // time probe at tick 3 records, tick 6 fires

        assertTrue(
            "time.updated change must trigger catchUp (got ${callbacks.triggerCatchUpCalls})",
            callbacks.triggerCatchUpCalls >= 1
        )
        assertEquals("s1", callbacks.triggerCatchUpIds.last())
    }

    // ── 3. idle + content probe 命中（probe id 不同）→ triggerCatchUp ───────

    @Test
    fun `idle content probe hit triggers catchUp`() {
        val poller = makePoller(TestScope())
        stubIdle(statusOf("s1", "idle"))
        stubSessionUpdated(100L) // constant → time probe never fires
        stubProbe("Z") // differs from local anchor "A" → authoritative hit
        stubPendingEmpty()
        poller.start()

        repeat(6) { runTick(poller) } // content probe fires at tick 6

        assertEquals("content probe hit → catchUp", 1, callbacks.triggerCatchUpCalls)
        assertEquals("s1", callbacks.triggerCatchUpIds.single())
    }

    // ── 4. busy→idle 跳变 → triggerCatchUp ─────────────────────────────────

    @Test
    fun `busy to idle transition triggers catchUp`() {
        val poller = makePoller(TestScope())
        stubPendingEmpty()
        stubProbe("A")
        stubSessionUpdated(100L)
        poller.start()

        // tick 1: busy
        coEvery { repository.getSessionStatus() } returns Result.success(statusOf("s1", "busy"))
        runTick(poller)
        assertEquals("busy alone does not catchUp", 0, callbacks.triggerCatchUpCalls)

        // tick 2: idle (active→idle jump)
        coEvery { repository.getSessionStatus() } returns Result.success(statusOf("s1", "idle"))
        runTick(poller)

        assertEquals("busy→idle jump → catchUp", 1, callbacks.triggerCatchUpCalls)
        assertEquals("s1", callbacks.triggerCatchUpIds.single())
    }

    // ── 5. retry ≥3 tick → onRetryError ────────────────────────────────────

    @Test
    fun `retry persisted for 3 ticks fires onRetryError`() {
        val poller = makePoller(TestScope())
        coEvery { repository.getSessionStatus() } returns Result.success(statusOf("s1", "retry"))
        stubPendingEmpty()
        poller.start()

        repeat(3) { runTick(poller) }

        assertEquals(1, callbacks.onRetryErrorCalls)
        assertEquals("s1", callbacks.onRetryErrorIds.single())
    }

    // ── 6. busy/retry 不累加休眠计数器 ─────────────────────────────────────

    @Test
    fun `active state does not accumulate sleep counter`() {
        val poller = makePoller(TestScope())
        coEvery { repository.getSessionStatus() } returns Result.success(statusOf("s1", "busy"))
        stubPendingEmpty()
        stubProbe("A")
        poller.start()

        repeat(6) { runTick(poller) } // all busy → never reaches content probe branch

        assertEquals("busy never accumulates sleep counter", 0, poller.unchangedCounterForTest())
        assertEquals("busy never triggers stale/stale hint", 0, callbacks.onStaleHintCalls)
    }

    // ── 7. content probe 不变达 MAX_UNCHANGED → onStaleHint ────────────────

    @Test
    fun `unchanged content probe up to MAX_UNCHANGED fires onStaleHint`() {
        val poller = makePoller(TestScope())
        stubIdle(statusOf("s1", "idle"))
        stubSessionUpdated(100L)
        stubProbe("A") // always matches anchor
        stubPendingEmpty()
        poller.start()

        // content probe runs at tick 6,12,18,24,30 → 5 unchanged → onStaleHint.
        repeat(LowTrafficPoller.MAX_UNCHANGED * LowTrafficPoller.CONTENT_PROBE_INTERVAL_TICKS) {
            runTick(poller)
        }

        assertEquals("onStaleHint at MAX_UNCHANGED probes", 1, callbacks.onStaleHintCalls)
    }

    // ── 8. status 连续失败≥3 → onConnectionError + 退避 ─────────────────────

    @Test
    fun `status failure 3 times fires onConnectionError and backs off`() {
        val poller = makePoller(TestScope())
        coEvery { repository.getSessionStatus() } returns Result.failure(java.io.IOException("net"))
        poller.start()

        repeat(3) { runTick(poller) }

        assertEquals("onConnectionError at 3 consecutive failures", 1, callbacks.onConnectionErrorCalls)
        assertEquals(
            "network backoff 5s→10s after error tier",
            LowTrafficPoller.BASE_TICK_MS * 2,
            poller.currentTickMsForTest()
        )
    }

    // ── 9. 单次恢复 → onConnectionRecovered ────────────────────────────────

    @Test
    fun `single status success after error tier fires onConnectionRecovered`() {
        val poller = makePoller(TestScope())
        stubPendingEmpty()
        stubProbe("A")
        stubSessionUpdated(100L)
        poller.start()

        // 3 failures → error tier
        coEvery { repository.getSessionStatus() } returns Result.failure(java.io.IOException("net"))
        repeat(3) { runTick(poller) }
        assertEquals(1, callbacks.onConnectionErrorCalls)

        // 1 success → recovered
        coEvery { repository.getSessionStatus() } returns Result.success(statusOf("s1", "idle"))
        runTick(poller)

        assertEquals("recovered on first success after error", 1, callbacks.onConnectionRecoveredCalls)
        assertEquals(
            "tick reset to base on recovery",
            LowTrafficPoller.BASE_TICK_MS,
            poller.currentTickMsForTest()
        )
    }

    // ── 10. 6 轮 idle → tick 退避到 30s ─────────────────────────────────────

    @Test
    fun `6 idle ticks without reload engages idle backoff to 30s`() {
        val poller = makePoller(TestScope())
        stubIdle(statusOf("s1", "idle"))
        stubSessionUpdated(100L)
        stubProbe("A") // unchanged → no reload → backoff engages
        stubPendingEmpty()
        poller.start()

        repeat(LowTrafficPoller.IDLE_BACKOFF_TICKS) { runTick(poller) }

        assertEquals(
            "idle backoff grows tick to BACKOFF_TICK_MS",
            LowTrafficPoller.BACKOFF_TICK_MS,
            poller.currentTickMsForTest()
        )
    }

    // ── 11. onMessageSent → 本地标 busy + tick 5s ───────────────────────────

    @Test
    fun `onMessageSent marks session busy locally and keeps tick at base`() {
        val poller = makePoller(TestScope())
        stubIdle(statusOf("s1", "idle"))
        stubSessionUpdated(100L)
        stubProbe("A")
        stubPendingEmpty()
        poller.start()

        // Engage backoff first, then prove onMessageSent resets it.
        repeat(LowTrafficPoller.IDLE_BACKOFF_TICKS) { runTick(poller) }
        assertEquals(LowTrafficPoller.BACKOFF_TICK_MS, poller.currentTickMsForTest())

        poller.onMessageSent("s1")

        assertEquals(
            "local busy marker written",
            "busy",
            state.value.sessionStatuses["s1"]?.type
        )
        assertEquals(
            "tick reset to base after send",
            LowTrafficPoller.BASE_TICK_MS,
            poller.currentTickMsForTest()
        )
    }

    // ── 12. 回前台 → 立即 triggerCatchUp ────────────────────────────────────

    @Test
    fun `foreground return fires immediate catchUp`() {
        val poller = makePoller(TestScope())
        stubIdle(statusOf("s1", "idle"))
        stubSessionUpdated(100L)
        stubProbe("A")
        stubPendingEmpty()
        poller.start()

        // Consume the baseline emission (mirrors ForegroundCatchUpControllerTest).
        poller.onForegroundChanged(true)
        callbacks.reset()

        poller.onForegroundChanged(false) // background → pause
        assertEquals("background does not catchUp", 0, callbacks.triggerCatchUpCalls)

        poller.onForegroundChanged(true) // foreground return → immediate catchUp
        assertEquals("foreground return → catchUp", 1, callbacks.triggerCatchUpCalls)
        assertEquals("s1", callbacks.triggerCatchUpIds.single())
    }

    // ── 13. currentSessionId==null 安全跳过 (评审 H.1) ──────────────────────

    @Test
    fun `doTick is a safe no-op when currentSessionId is null`() {
        val poller = makePoller(TestScope())
        callbacks.currentSessionId = null
        stubIdle(statusOf("s1", "idle"))
        stubPendingEmpty()
        poller.start()

        // No crash, no callback, no status write — sessionId guard returns before any I/O.
        runTick(poller)

        assertEquals("null sessionId never triggers catchUp", 0, callbacks.triggerCatchUpCalls)
    }

    // ── 14. probeLatestMessageId 返回 null/失败 不计数不崩溃 (评审 H.2) ──────

    @Test
    fun `probeLatestMessageId failure does not crash or increment unchanged counter`() {
        val poller = makePoller(TestScope())
        stubIdle(statusOf("s1", "idle"))
        stubSessionUpdated(100L)
        coEvery { repository.probeLatestMessageId("s1") } returns Result.failure(java.io.IOException("probe net"))
        stubPendingEmpty()
        poller.start()

        repeat(6) { runTick(poller) } // content probe at tick 6 fails

        assertEquals("failed probe does not increment unchanged counter", 0, poller.unchangedCounterForTest())
        assertEquals("no catchUp on probe failure", 0, callbacks.triggerCatchUpCalls)
        assertEquals("no stale hint on probe failure", 0, callbacks.onStaleHintCalls)
    }

    // ── 15. retry→idle 跳变触发 catchUp (评审 H.3) ──────────────────────────

    @Test
    fun `retry to idle transition triggers catchUp`() {
        val poller = makePoller(TestScope())
        stubPendingEmpty()
        stubProbe("A")
        stubSessionUpdated(100L)
        poller.start()

        // tick 1: retry (active per §1.2 — retry = run in progress)
        coEvery { repository.getSessionStatus() } returns Result.success(statusOf("s1", "retry"))
        runTick(poller)
        assertEquals("retry alone does not catchUp", 0, callbacks.triggerCatchUpCalls)

        // tick 2: idle (active→idle jump — retry counts as active)
        coEvery { repository.getSessionStatus() } returns Result.success(statusOf("s1", "idle"))
        runTick(poller)

        assertEquals("retry→idle jump → catchUp", 1, callbacks.triggerCatchUpCalls)
        assertEquals("s1", callbacks.triggerCatchUpIds.single())
    }

    // ── 16. onMessageSent 后本地 busy 在下一 tick 不被 server idle 覆盖 (评审 H.4 / §A) ─

    @Test
    fun `onMessageSent local busy survives next tick server idle (validates A)`() {
        val poller = makePoller(TestScope())
        stubPendingEmpty()
        stubProbe("A")
        stubSessionUpdated(100L)
        poller.start()

        // tick 1: busy (lastStatus ← busy)
        coEvery { repository.getSessionStatus() } returns Result.success(statusOf("s1", "busy"))
        runTick(poller)

        // user sends → local busy badge + localBusyUntil[s1] window set
        poller.onMessageSent("s1")
        assertEquals("onMessageSent writes local busy", "busy", state.value.sessionStatuses["s1"]?.type)

        // tick 2: server returns idle. §A must preserve local busy in uiStatuses
        // (rawStatuses still idle → state machine still fires active→idle catchUp).
        callbacks.reset()
        coEvery { repository.getSessionStatus() } returns Result.success(statusOf("s1", "idle"))
        runTick(poller)

        assertEquals(
            "local busy badge preserved despite server idle (A)",
            "busy",
            state.value.sessionStatuses["s1"]?.type
        )
        assertEquals(
            "state machine still detects active→idle via rawStatuses",
            1,
            callbacks.triggerCatchUpCalls
        )
    }

    // ── 17. status map 缺失当前 session 时 lastStatus 保留 (评审 H.5 / §C) ───

    @Test
    fun `status map missing current session preserves lastStatus (validates C)`() {
        val poller = makePoller(TestScope())
        stubPendingEmpty()
        stubProbe("A")
        stubSessionUpdated(100L)
        poller.start()

        // tick 1: busy (lastStatus ← busy)
        coEvery { repository.getSessionStatus() } returns Result.success(statusOf("s1", "busy"))
        runTick(poller)

        // tick 2: status map empty (current session missing) — §C must NOT clear lastStatus
        callbacks.reset()
        coEvery { repository.getSessionStatus() } returns Result.success(emptyMap())
        runTick(poller)
        assertEquals("missing-session tick does not catchUp", 0, callbacks.triggerCatchUpCalls)

        // tick 3: idle — lastStatus preserved as busy → wasActive=true → active→idle fires
        coEvery { repository.getSessionStatus() } returns Result.success(statusOf("s1", "idle"))
        runTick(poller)

        assertEquals(
            "lastStatus preserved across missing-session tick → active→idle fires (C)",
            1,
            callbacks.triggerCatchUpCalls
        )
        assertEquals("s1", callbacks.triggerCatchUpIds.single())
    }

    // ── 18. isLoadingMessages=true 仍写 status，跳过 probe/计数器 (评审 §E) ────

    @Test
    fun `isLoadingMessages true still writes status badge but skips state machine (validates E)`() {
        val poller = makePoller(TestScope())
        stubIdle(statusOf("s1", "idle"))
        stubSessionUpdated(100L)
        stubProbe("Z") // differs from anchor A → would normally trigger catchUp
        stubPendingEmpty()
        poller.start()

        // Simulate an in-flight load: isLoadingMessages=true.
        state.value = state.value.copy(isLoadingMessages = true)

        runTick(poller)

        // §E: status map IS written even during isLoading (badge stays in sync).
        assertEquals(
            "status map written during isLoading (E)",
            "idle",
            state.value.sessionStatuses["s1"]?.type
        )
        // §E: probe / state machine skipped → no catchUp, no counter advancement.
        assertEquals("no catchUp during isLoading", 0, callbacks.triggerCatchUpCalls)
        assertEquals(
            "tickCounter frozen during isLoading (no probe cadence advance)",
            0,
            poller.unchangedCounterForTest()
        )
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /** Runs one tick synchronously by invoking the internal [LowTrafficPoller.doTick].
     *  Uses [runBlocking] (not `runTest`) so the standalone [TestScope]'s pending
     *  loop job / init-subscription collector are never auto-advanced — the test
     *  drives [LowTrafficPoller.doTick] directly and deterministically. Mirrors
     *  the [ForegroundCatchUpControllerTest] standalone-TestScope pattern. */
    private fun runTick(poller: LowTrafficPoller) {
        runBlocking { poller.doTick() }
    }

    private fun statusOf(sessionId: String, type: String): Map<String, SessionStatus> =
        mapOf(sessionId to SessionStatus(type = type))

    private fun sessionWithUpdated(updated: Long): Session =
        Session(id = "s1", directory = "/x", time = Session.TimeInfo(updated = updated))

    private fun stubIdle(statuses: Map<String, SessionStatus>) {
        coEvery { repository.getSessionStatus() } returns Result.success(statuses)
    }

    private fun stubSessionUpdated(updated: Long) {
        coEvery { repository.getSession("s1") } returns Result.success(sessionWithUpdated(updated))
    }

    private fun stubProbe(newestId: String?) {
        coEvery { repository.probeLatestMessageId("s1") } returns Result.success(newestId)
    }

    private fun stubPendingEmpty() {
        coEvery { repository.getPendingPermissions() } returns Result.success(emptyList<PermissionRequest>())
        coEvery { repository.getPendingQuestions() } returns Result.success(emptyList<QuestionRequest>())
    }

    private fun stubMonitor(): AppLifecycleMonitor = mockk(relaxed = true) {
        io.mockk.every { isInForeground } returns foregroundFlow
    }

    /** Records every callback invocation so tests can assert on side effects. */
    private class RecordingCallbacks : LowTrafficPollerCallbacks {
        var currentSessionId: String? = null
        var triggerCatchUpCalls = 0
        var onStaleHintCalls = 0
        var onRetryErrorCalls = 0
        var onConnectionErrorCalls = 0
        var onConnectionRecoveredCalls = 0
        var onPendingPermissionsCalls = 0
        var onPendingQuestionsCalls = 0
        val triggerCatchUpIds = mutableListOf<String>()
        val onRetryErrorIds = mutableListOf<String>()

        fun reset() {
            triggerCatchUpCalls = 0
            onStaleHintCalls = 0
            onRetryErrorCalls = 0
            onConnectionErrorCalls = 0
            onConnectionRecoveredCalls = 0
            onPendingPermissionsCalls = 0
            onPendingQuestionsCalls = 0
            triggerCatchUpIds.clear()
            onRetryErrorIds.clear()
        }

        override fun currentSessionId(): String? = currentSessionId
        override fun triggerCatchUp(sessionId: String) {
            triggerCatchUpCalls++
            triggerCatchUpIds.add(sessionId)
        }
        override fun onStaleHint() { onStaleHintCalls++ }
        override fun onRetryError(sessionId: String) {
            onRetryErrorCalls++
            onRetryErrorIds.add(sessionId)
        }
        override fun onConnectionError() { onConnectionErrorCalls++ }
        override fun onConnectionRecovered() { onConnectionRecoveredCalls++ }
        override fun onPendingPermissionsLoaded(list: List<PermissionRequest>) {
            onPendingPermissionsCalls++
        }
        override fun onPendingQuestionsLoaded(list: List<QuestionRequest>) {
            onPendingQuestionsCalls++
        }
    }
}
