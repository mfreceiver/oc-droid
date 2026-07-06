package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.MainViewModelTestBase
import cn.vectory.ocdroid.data.model.SSEEvent
import cn.vectory.ocdroid.data.model.SSEPayload
import cn.vectory.ocdroid.data.repository.MessagesPage
import cn.vectory.ocdroid.ui.controller.ControllerEffect
import cn.vectory.ocdroid.util.DebugLog
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R-19 Sprint 2 P2-2 (S2-T1): per-domain dispatcher coverage for the
 * `dispatchEffect` split. The former 23-branch monolithic `when` in
 * [AppCore.dispatchEffect] was split into 5 internal per-domain dispatchers
 * (each returning `Boolean` handled), cascaded by short-circuit `||`. The
 * top-level router logs a [DebugLog.w] warning if no dispatcher claims the
 * effect (so a future branch added to the sealed hierarchy without a
 * matching dispatcher update is observable, not silently no-op).
 *
 * These tests:
 *  - call each internal dispatcher directly with one effect per domain and
 *    assert `handled = true` + an observable side effect (repository mock
 *    call or slice mutation);
 *  - cover every domain dispatcher's first branch + the more subtle ones
 *    (ClearDeltaBuffers, ClearSessionWindowCache) via slice state;
 *  - pin the `unhandled` contract by exercising [AppCore.assertExactlyOneHandled]
 *    directly: an unrecognized effect (the case where none of the 5
 *    dispatchers claims a future contributor-added subtype) must surface as
 *    a [DebugLog.w] entry with the canonical "unhandled effect=" prefix.
 *    (Sealed `ControllerEffect` cannot be subclassed from the test source
 *    set — different Kotlin module — so the warning path is exercised via
 *    the internal `assertExactlyOneHandled` helper with a known effect
 *    passed as the "would-be unhandled" payload. The 5-dispatcher
 *    partition test above proves the dispatchers cover every existing
 *    branch, so in production the only way to reach `handled = false` is a
 *    new subtype added without a matching dispatcher update — which is
 *    exactly the regression this guard catches.)
 *
 * Mock setup + AppCore construction are inherited from [MainViewModelTestBase];
 * repository stubs return empty success results so the launchXxx free
 * functions drive their `coVerify` targets without throwing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppCoreDispatcherTest : MainViewModelTestBase() {

    // ═══════════════════════════════════════════════════════════════════════
    // dispatchForegroundCatchUpEffect (4 branches)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `dispatchForegroundCatchUpEffect handles ForceReconnect and probes health`() = runTest {
        coEvery { repository.checkHealth() } returns Result.success(
            cn.vectory.ocdroid.data.model.HealthResponse(healthy = true, version = "1.0")
        )
        val core = newCore()

        val handled = core.dispatchForegroundCatchUpEffect(ControllerEffect.ForceReconnect)
        advanceUntilIdle()

        assertTrue("ForceReconnect must be claimed by the foreground-catch-up dispatcher", handled)
        coVerify { repository.checkHealth() }
    }

    @Test
    fun `dispatchForegroundCatchUpEffect handles CancelSse and clears delta buffers`() = runTest {
        val core = newCore()
        // Seed an observable delta-buffer entry so ClearDeltaBuffers has
        // something to drop.
        core.store.mutateChat {
            it.copy(deltaBuffer = mapOf("partial-1" to "data"))
        }

        val handled = core.dispatchForegroundCatchUpEffect(ControllerEffect.CancelSse)
        advanceUntilIdle()

        assertTrue(handled)
        assertTrue(
            "CancelSse must clear SessionSyncCoordinator delta buffers",
            core.store.chatFlow.value.deltaBuffer.isEmpty()
        )
    }

    @Test
    fun `dispatchForegroundCatchUpEffect returns false for a session-domain effect`() {
        val core = newCore()
        val handled = core.dispatchForegroundCatchUpEffect(ControllerEffect.ClearDeltaBuffers)
        assertFalse(
            "ClearDeltaBuffers belongs to the session dispatcher, not foreground-catch-up",
            handled
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // dispatchSessionEffect (5 branches)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `dispatchSessionEffect handles LoadMessages and fetches the page`() = runTest {
        coEvery { repository.getMessagesPaged(any(), any(), any()) } returns
            Result.success(MessagesPage(emptyList(), null))
        val core = newCore()

        val handled = core.dispatchSessionEffect(ControllerEffect.LoadMessages("sess-A", resetLimit = false))
        advanceUntilIdle()

        assertTrue(handled)
        coVerify { repository.getMessagesPaged("sess-A", any(), any()) }
    }

    @Test
    fun `dispatchSessionEffect handles LoadChildSessions and fetches children`() = runTest {
        coEvery { repository.getChildren(any()) } returns Result.success(emptyList())
        val core = newCore()

        val handled = core.dispatchSessionEffect(ControllerEffect.LoadChildSessions("parent-1"))
        advanceUntilIdle()

        assertTrue(handled)
        coVerify { repository.getChildren("parent-1") }
    }

    @Test
    fun `dispatchSessionEffect handles LoadSessionStatus and fetches status`() = runTest {
        coEvery { repository.getSessionStatus() } returns Result.success(emptyMap())
        val core = newCore()

        val handled = core.dispatchSessionEffect(ControllerEffect.LoadSessionStatus)
        advanceUntilIdle()

        assertTrue(handled)
        coVerify { repository.getSessionStatus() }
    }

    @Test
    fun `dispatchSessionEffect handles LoadPendingQuestions and fans out`() = runTest {
        coEvery { repository.getPendingQuestions(any()) } returns Result.success(emptyList())
        val core = newCore()
        // Seed a known workdir so the multi-workdir fan-out has at least one
        // directory to probe (settingsManager.currentWorkdir mock).
        io.mockk.every { settingsManager.currentWorkdir } returns "/proj"

        val handled = core.dispatchSessionEffect(ControllerEffect.LoadPendingQuestions)
        advanceUntilIdle()

        assertTrue(handled)
        coVerify { repository.getPendingQuestions("/proj") }
    }

    @Test
    fun `dispatchSessionEffect handles ClearDeltaBuffers and drops pending state`() = runTest {
        val core = newCore()
        core.store.mutateChat {
            it.copy(
                deltaBuffer = mapOf("a" to "x", "b" to "y"),
                pendingFlushPartIds = setOf("p1"),
            )
        }

        val handled = core.dispatchSessionEffect(ControllerEffect.ClearDeltaBuffers)
        advanceUntilIdle()

        assertTrue(handled)
        assertTrue("deltaBuffer cleared", core.store.chatFlow.value.deltaBuffer.isEmpty())
        assertTrue(
            "pendingFlushPartIds cleared",
            core.store.chatFlow.value.pendingFlushPartIds.isEmpty()
        )
    }

    @Test
    fun `dispatchSessionEffect returns false for a host-domain effect`() {
        val core = newCore()
        val handled = core.dispatchSessionEffect(ControllerEffect.StartSse)
        assertFalse("StartSse belongs to the host dispatcher, not session", handled)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // dispatchHostEffect (6 branches)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `dispatchHostEffect handles StartSse and opens the SSE feed`() = runTest {
        io.mockk.every { settingsManager.currentWorkdir } returns "/proj"
        io.mockk.every { repository.connectSSE(any()) } returns kotlinx.coroutines.flow.emptyFlow()
        val core = newCore()

        val handled = core.dispatchHostEffect(ControllerEffect.StartSse)
        advanceUntilIdle()

        assertTrue(handled)
        io.mockk.verify { repository.connectSSE("/proj") }
    }

    @Test
    fun `dispatchHostEffect handles ColdStartReconnect and probes health`() = runTest {
        coEvery { repository.checkHealth() } returns Result.success(
            cn.vectory.ocdroid.data.model.HealthResponse(healthy = true, version = "1.0")
        )
        val core = newCore()

        val handled = core.dispatchHostEffect(ControllerEffect.ColdStartReconnect)
        advanceUntilIdle()

        assertTrue(handled)
        coVerify { repository.checkHealth() }
    }

    @Test
    fun `dispatchHostEffect handles ClearSessionWindowCache and empties the cache`() = runTest {
        val core = newCore()
        // Seed the cache by writing a window, then dispatch the clear effect.
        core.writeSessionWindow(
            "sess-A",
            CachedSessionWindow(
                messages = emptyList(),
                partsByMessage = emptyMap(),
                olderMessagesCursor = null,
                hasMoreMessages = false,
            )
        )
        assertEquals("seed landed", 1, core.sessionWindowCacheSize())

        val handled = core.dispatchHostEffect(ControllerEffect.ClearSessionWindowCache)

        assertTrue(handled)
        assertEquals(0, core.sessionWindowCacheSize())
    }

    @Test
    fun `dispatchHostEffect returns false for a connection-domain effect`() {
        val core = newCore()
        val handled = core.dispatchHostEffect(ControllerEffect.LoadSessions)
        assertFalse("LoadSessions belongs to the connection dispatcher, not host", handled)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // dispatchConnectionEffect (6 branches)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `dispatchConnectionEffect handles LoadSessions and fetches the list`() = runTest {
        coEvery { repository.getSessions(any()) } returns Result.success(emptyList())
        val core = newCore()

        val handled = core.dispatchConnectionEffect(ControllerEffect.LoadSessions)
        advanceUntilIdle()

        assertTrue(handled)
        coVerify { repository.getSessions(any()) }
    }

    @Test
    fun `dispatchConnectionEffect handles LoadAgents and fetches agents`() = runTest {
        coEvery { repository.getAgents() } returns Result.success(emptyList())
        val core = newCore()

        val handled = core.dispatchConnectionEffect(ControllerEffect.LoadAgents)
        advanceUntilIdle()

        assertTrue(handled)
        coVerify { repository.getAgents() }
    }

    @Test
    fun `dispatchConnectionEffect handles LoadProviders and fetches providers`() = runTest {
        coEvery { repository.getProviders() } returns Result.success(
            cn.vectory.ocdroid.data.model.ProvidersResponse()
        )
        val core = newCore()

        val handled = core.dispatchConnectionEffect(ControllerEffect.LoadProviders)
        advanceUntilIdle()

        assertTrue(handled)
        coVerify { repository.getProviders() }
    }

    @Test
    fun `dispatchConnectionEffect handles LoadPendingPermissions and fetches permissions`() = runTest {
        coEvery { repository.getPendingPermissions() } returns Result.success(emptyList())
        val core = newCore()

        val handled = core.dispatchConnectionEffect(ControllerEffect.LoadPendingPermissions)
        advanceUntilIdle()

        assertTrue(handled)
        coVerify { repository.getPendingPermissions() }
    }

    @Test
    fun `dispatchConnectionEffect handles OnSseEvent by forwarding to SessionSyncCoordinator`() = runTest {
        val core = newCore()
        val event = SSEEvent(payload = SSEPayload(type = "server.connected"))

        val handled = core.dispatchConnectionEffect(ControllerEffect.OnSseEvent(event))
        advanceUntilIdle()

        assertTrue(handled)
        // Observable side effect: server.connected resets the streaming overlay
        // sentinel via SessionSyncCoordinator.handleEvent. Verify it does not
        // throw + the dispatcher claimed the effect.
    }

    @Test
    fun `dispatchConnectionEffect returns false for a session-sync effect`() {
        val core = newCore()
        val handled = core.dispatchConnectionEffect(ControllerEffect.ServerConnected)
        assertFalse(
            "ServerConnected belongs to the session-sync dispatcher, not connection",
            handled
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // dispatchSessionSyncEffect (2 branches)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `dispatchSessionSyncEffect handles RefreshSessions and reloads the list`() = runTest {
        coEvery { repository.getSessions(any()) } returns Result.success(emptyList())
        val core = newCore()

        val handled = core.dispatchSessionSyncEffect(ControllerEffect.RefreshSessions)
        advanceUntilIdle()

        assertTrue(handled)
        coVerify { repository.getSessions(any()) }
    }

    @Test
    fun `dispatchSessionSyncEffect handles ServerConnected`() = runTest {
        val core = newCore()

        val handled = core.dispatchSessionSyncEffect(ControllerEffect.ServerConnected)
        advanceUntilIdle()

        // ServerConnected → foregroundCatchUpController.onServerConnected (no
        // repository call by default; observable only via catch-up state).
        assertTrue(handled)
    }

    @Test
    fun `dispatchSessionSyncEffect returns false for a foreground-catch-up effect`() {
        val core = newCore()
        val handled = core.dispatchSessionSyncEffect(ControllerEffect.ForceReconnect)
        assertFalse(
            "ForceReconnect belongs to the foreground-catch-up dispatcher, not session-sync",
            handled
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Top-level dispatchEffect cascade: short-circuit + unhandled-warning
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `each known effect is claimed by exactly one domain dispatcher`() {
        // Sanity sweep: every branch in the sealed hierarchy must be handled
        // by exactly one of the 5 dispatchers. Pins the R-19 P2-2 invariant
        // ("at most one handled") at the dispatcher-set level.
        val core = newCore()
        val representatives: List<ControllerEffect> = listOf(
            // ForegroundCatchUp
            ControllerEffect.ForceReconnect,
            ControllerEffect.GlobalColdStartRefresh("sess-A"),
            ControllerEffect.CancelSse,
            ControllerEffect.CatchUpAfterDisconnect("sess-A"),
            // Session
            ControllerEffect.LoadMessages("sess-A", resetLimit = false),
            ControllerEffect.LoadChildSessions("parent-1"),
            ControllerEffect.LoadSessionStatus,
            ControllerEffect.LoadPendingQuestions,
            ControllerEffect.ClearDeltaBuffers,
            // Host
            ControllerEffect.CancelSseForReconfigure,
            ControllerEffect.StartSse,
            ControllerEffect.HostProfileSwitched,
            ControllerEffect.ColdStartReconnect,
            ControllerEffect.ResetLocalDataAndResync,
            ControllerEffect.ClearSessionWindowCache,
            // Connection
            ControllerEffect.HostReconfigured,
            ControllerEffect.LoadSessions,
            ControllerEffect.LoadAgents,
            ControllerEffect.LoadProviders,
            ControllerEffect.LoadPendingPermissions,
            ControllerEffect.OnSseEvent(SSEEvent(payload = SSEPayload(type = "server.connected"))),
            // SessionSync
            ControllerEffect.ServerConnected,
            ControllerEffect.RefreshSessions,
        )

        representatives.forEach { effect ->
            val claimants = listOf(
                core.dispatchForegroundCatchUpEffect(effect),
                core.dispatchSessionEffect(effect),
                core.dispatchHostEffect(effect),
                core.dispatchConnectionEffect(effect),
                core.dispatchSessionSyncEffect(effect),
            ).count { it }
            assertEquals(
                "effect $effect must be claimed by exactly ONE dispatcher (got $claimants)",
                1,
                claimants
            )
        }
    }

    @Test
    fun `assertExactlyOneHandled logs DebugLog_w when no dispatcher claimed the effect`() {
        // R-19 P2-2 contract: if a future contributor adds a new
        // ControllerEffect subtype without registering it in any domain
        // dispatcher, the cascade in dispatchEffect lands here with
        // `handled = false`. The miss MUST be observable (in-app debug log
        // viewer) rather than silently swallowed. We exercise the helper
        // directly because sealed ControllerEffect cannot be subclassed from
        // the test source set (different Kotlin module) — but the helper's
        // contract is independent of which effect triggered it: any effect
        // + `handled = false` → exactly one "unhandled effect=" warning.
        val core = newCore()
        DebugLog.clear()

        core.assertExactlyOneHandled(ControllerEffect.ClearDeltaBuffers, handled = false)

        val unhandledEntries = DebugLog.entries.value.filter {
            it.tag == "AppCore" &&
                it.level == DebugLog.Level.WARN &&
                it.message.startsWith("unhandled effect=")
        }
        assertEquals(1, unhandledEntries.size)
        assertTrue(
            "warning payload must include the effect for diagnosis",
            unhandledEntries.single().message.contains("ClearDeltaBuffers")
        )
    }

    @Test
    fun `assertExactlyOneHandled is silent when the effect was handled`() {
        // Complement of the warning test: `handled = true` MUST NOT log.
        // Pins the "exactly one" half of the invariant — a successful claim
        // is the happy path, not a warning.
        val core = newCore()
        DebugLog.clear()

        core.assertExactlyOneHandled(ControllerEffect.ForceReconnect, handled = true)

        val unhandledEntries = DebugLog.entries.value.filter {
            it.message.startsWith("unhandled effect=")
        }
        assertTrue("handled effect must not log a warning", unhandledEntries.isEmpty())
    }

    @Test
    fun `dispatchEffect cascade routes a known effect without logging a warning`() = runTest {
        // End-to-end: drive a real effect through the production wiring
        // (effectBus → init collector → dispatchEffect → cascade →
        // assertExactlyOneHandled). A known effect must be claimed by its
        // dispatcher, so NO "unhandled effect=" warning lands. This pins the
        // happy-path cascade alongside the assertExactlyOneHandled direct
        // tests above.
        coEvery { repository.checkHealth() } returns Result.success(
            cn.vectory.ocdroid.data.model.HealthResponse(healthy = true, version = "1.0")
        )
        val core = newCore()
        DebugLog.clear()

        core.effectBus.tryEmitEffect(ControllerEffect.ForceReconnect)
        advanceUntilIdle()

        val unhandledEntries = DebugLog.entries.value.filter {
            it.message.startsWith("unhandled effect=")
        }
        assertTrue(
            "known effect must be claimed (no unhandled warning)",
            unhandledEntries.isEmpty()
        )
        coVerify { repository.checkHealth() }
    }

    @Test
    fun `each dispatcher returns false for an effect outside its domain`() {
        // Per-dispatcher isolation pin: feed each dispatcher an effect that
        // belongs to a DIFFERENT domain and assert `false`. A future
        // contributor accidentally duplicating a branch in two dispatchers
        // (which would break the `||` short-circuit invariant — both would
        // log "handled") is caught by the partition test above; this test
        // catches the converse (a dispatcher claiming an effect it should
        // not).
        val core = newCore()
        // ForceReconnect is a foreground-catch-up effect; every other
        // dispatcher must reject it.
        assertFalse(core.dispatchSessionEffect(ControllerEffect.ForceReconnect))
        assertFalse(core.dispatchHostEffect(ControllerEffect.ForceReconnect))
        assertFalse(core.dispatchConnectionEffect(ControllerEffect.ForceReconnect))
        assertFalse(core.dispatchSessionSyncEffect(ControllerEffect.ForceReconnect))
        // StartSse is a host effect.
        assertFalse(core.dispatchForegroundCatchUpEffect(ControllerEffect.StartSse))
        assertFalse(core.dispatchSessionEffect(ControllerEffect.StartSse))
        assertFalse(core.dispatchConnectionEffect(ControllerEffect.StartSse))
        assertFalse(core.dispatchSessionSyncEffect(ControllerEffect.StartSse))
    }
}
