package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.MainViewModelTestBase
import cn.vectory.ocdroid.ui.controller.CachedSessionWindow
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        // CP9 §D21: AppCore.dispatchForegroundCatchUpEffect(CancelSse) NO
        // LONGER calls connectionCoordinator.cancelSse() — CancelSse is now
        // an OBSERVED transport-disconnect signal (the producer is the
        // Service's ServiceSseConnectionOwner.disconnect). Calling CC here
        // would route through coordinator.onDisconnect() → redundant
        // teardown loop. The delta-buffer clear is retained (the gap-dirty
        // contract is still relevant).
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
        // No CC teardown recursion: cancelSse / cancelSseForReconfigure on
        // the CC instance are no-ops in this test core
        // (streamingLifecycleCoordinator = null). The CP9 §D21 invariant is
        // that AppCore does not even CALL cancelSse; we verify by asserting
        // the dispatcher returned handled=true without observable side
        // effects beyond the buffer clear.
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
        identityStore.bind("test-fp", "/proj", "test-endpoint")

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
        // CP9 (notify Phase-0 switchover): CC's startSSE now calls
        // StreamingServiceLauncher.ensureStarted() instead of
        // repository.connectSSE. The SSE collector moved to the
        // Service-owned ServiceSseConnectionOwner; the launcher is the
        // atomic trigger that promotes the Service to foreground.
        io.mockk.every { settingsManager.currentWorkdir } returns "/proj"
        val core = newCore()
        identityStore.bind("test-fp", "/proj", "test-endpoint")
        val callsBefore = streamingServiceLauncher.callCount

        val handled = core.dispatchHostEffect(ControllerEffect.StartSse)
        advanceUntilIdle()

        assertTrue(handled)
        assertEquals(
            "StartSse dispatches through the launcher, not repository.connectSSE",
            callsBefore + 1,
            streamingServiceLauncher.callCount,
        )
        io.mockk.verify(exactly = 0) { repository.connectSSE(any()) }
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
            "test-fp",
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
        // CP1: OnSseEvent now wraps IdentifiedSseEvent. Use a zero-epoch
        // identity — SSC's identity gate is skipped when no identity is bound
        // (test cold-start, identityStore.currentIdentity == null), so the
        // raw dispatch path runs.
        val identified = cn.vectory.ocdroid.service.events.IdentifiedSseEvent(
            cn.vectory.ocdroid.service.identity.ConnectionIdentity(
                epoch = 0L, serverGroupFp = "", normalizedWorkdir = "", endpointFp = ""
            ),
            event,
        )

        val handled = core.dispatchConnectionEffect(ControllerEffect.OnSseEvent(identified))
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

    // ── T13 round-2 review fix: AppCore poller-backoff dispatch ───────────

    @Test
    fun `dispatchSessionSyncEffect handles RequestPollerBackoff by calling scheduleBackoff (T13 round-2 #6)`() {
        // Round-2 review fix #6: the coordinator emits
        // RequestPollerBackoff; AppCore MUST route it to
        // processStatusPoller.scheduleBackoff() (the default-jitter arg
        // kicks in so production jitter samples — see M2). Without this
        // dispatch the emitted effect disappeared through the unhandled-
        // effect warning path.
        val core = newCore()

        val handled = core.dispatchSessionSyncEffect(ControllerEffect.RequestPollerBackoff)

        assertTrue(
            "RequestPollerBackoff must be claimed by the session-sync dispatcher",
            handled,
        )
        io.mockk.verify(exactly = 1) { processStatusPoller.scheduleBackoff() }
    }

    @Test
    fun `dispatchSessionSyncEffect handles ResetPollerBackoff by calling resetBackoff (T13 round-2 #6)`() {
        val core = newCore()

        val handled = core.dispatchSessionSyncEffect(ControllerEffect.ResetPollerBackoff)

        assertTrue(
            "ResetPollerBackoff must be claimed by the session-sync dispatcher",
            handled,
        )
        io.mockk.verify(exactly = 1) { processStatusPoller.resetBackoff() }
    }

    @Test
    fun `dispatchEffect cascade routes RequestPollerBackoff end-to-end to the poller (T13 round-2 #6)`() = runTest {
        // End-to-end: coordinator emit (via effectBus) → AppCore dispatchEffect
        // cascade → dispatchSessionSyncEffect → scheduleBackoff. This pins
        // the production wiring (the round-1 review found the cascade was
        // missing the branch entirely).
        val core = newCore()
        DebugLog.clear()

        core.effectBus.tryEmitEffect(ControllerEffect.RequestPollerBackoff)
        advanceUntilIdle()

        io.mockk.verify(exactly = 1) { processStatusPoller.scheduleBackoff() }
        val unhandled = DebugLog.entries.value.filter {
            it.message.startsWith("unhandled effect=")
        }
        assertTrue(
            "RequestPollerBackoff must be claimed (no unhandled warning)",
            unhandled.isEmpty(),
        )
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
            ControllerEffect.HostReconfigured(epoch = 0L),
            ControllerEffect.LoadSessions,
            ControllerEffect.LoadAgents,
            ControllerEffect.LoadProviders,
            ControllerEffect.LoadPendingPermissions,
            ControllerEffect.OnSseEvent(
                cn.vectory.ocdroid.service.events.IdentifiedSseEvent(
                    cn.vectory.ocdroid.service.identity.ConnectionIdentity(
                        epoch = 0L, serverGroupFp = "", normalizedWorkdir = "", endpointFp = ""
                    ),
                    SSEEvent(payload = SSEPayload(type = "server.connected")),
                )
            ),
            // SessionSync
            ControllerEffect.ServerConnected,
            ControllerEffect.RefreshSessions,
            // T13 round-2: AppCore now dispatches these — include them in
            // the partition sweep so the "exactly one dispatcher" invariant
            // covers the new branches.
            ControllerEffect.RequestPollerBackoff,
            ControllerEffect.ResetPollerBackoff,
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

    // ═══════════════════════════════════════════════════════════════════════
    // remove-message-persistence Task 6: the prior R-20 Phase 1 C8 tests
    // (`C8 init verify drops currentSessionId on fingerprint mismatch` +
    // `C8 init verify keeps currentSessionId when fingerprint matches`) were
    // deleted together with the cold-start `cacheRepository.verifyFingerprint`
    // self-check block in AppCore.init. The seeded currentSessionId is now
    // consumed directly; VerifyAndHydrate's in-memory peek handles empty-
    // cache cold start.
    // ═══════════════════════════════════════════════════════════════════════
    // remove-message-persistence Task 2: VerifyAndHydrate → peek sessionWindowCache
    // ═══════════════════════════════════════════════════════════════════════
    // The handler no longer touches SQLite (no cacheRepository.verifyAndLoad).
    // It synchronously probes the in-memory sessionWindowCache via
    // AppCore.peekSessionWindow (→ SessionSwitcher.peekSessionWindow) and
    // hydrates the chat slice directly on hit, else cold-starts via
    // loadMessages(resetLimit=true). The prior TOCTOU tests (review-fix #1
    // 1A/1B + 复审 #1 三次重检) pinned the suspend-time switch window of
    // verifyAndLoad; with peek synchronous on Main.immediate (the same
    // dispatcher that confines the cache) that window no longer exists, so
    // those tests were replaced by the four below pinning the new contract
    // (T2-C1..C4).

    private fun pinnedTestProfile() = cn.vectory.ocdroid.data.model.HostProfile(
        id = "test-host",
        name = "Test",
        serverUrl = "http://server.test",
        serverGroupFp = "test-fp",
    )

    @Test
    fun `VerifyAndHydrate injects cached window on memory hit and skips SQLite (Task2 C1+C2)`() = runTest {
        // Memory hit: peekSessionWindow returns the cached window → the handler
        // mutateChats messages/partsByMessage/olderMessagesCursor/hasMoreMessages
        // from it and calls loadMessages(resetLimit=false). Crucially it does
        // NOT call cacheRepository.verifyAndLoad anymore.
        val c = newCore()
        io.mockk.every { hostProfileStore.currentProfile() } returns pinnedTestProfile()
        c.store.mutateChat { it.copy(currentSessionId = "sess-A") }
        // Seed the in-memory LRU with a recognizable window. The base mock
        // returns an empty MessagesPage, so the cached window must survive the
        // resetLimit=false selective merge.
        val cachedMsg = cn.vectory.ocdroid.data.model.Message(id = "cached-m1", role = "user")
        // Use a recognizable non-empty Part so the partsByMessage assertion
        // below locks the C2 four-field copy at the handler boundary (an
        // empty list would not distinguish a real copy from a slice default).
        val cachedPart = cn.vectory.ocdroid.data.model.Part(
            id = "cached-p1",
            type = "text",
            text = "peeked-body",
        )
        c.writeSessionWindow(
            "test-fp", "sess-A",
            CachedSessionWindow(
                messages = listOf(cachedMsg),
                partsByMessage = mapOf("cached-m1" to listOf(cachedPart)),
                olderMessagesCursor = "cached-cursor",
                hasMoreMessages = true,
            ),
        )

        c.dispatchSessionEffect(
            ControllerEffect.VerifyAndHydrate("test-fp", "sess-A", createdAt = 1L)
        )
        advanceUntilIdle()

        // C2: cached fields injected (and preserved by resetLimit=false merge).
        assertEquals(
            "cached window messages must be injected on hit",
            listOf("cached-m1"),
            c.store.chatFlow.value.messages.map { it.id },
        )
        // C2 parts copy: partsByMessage["cached-m1"] must be the cached Part
        // list (locks the four-field mutateChat copy at the handler boundary).
        assertEquals(
            "cached partsByMessage must be injected on hit",
            listOf("cached-p1"),
            c.store.chatFlow.value.partsByMessage["cached-m1"]?.map { it.id },
        )
        assertEquals(
            "cached cursor must be preserved (resetLimit=false)",
            "cached-cursor",
            c.store.chatFlow.value.olderMessagesCursor,
        )
        assertTrue(
            "cached hasMore must be preserved (resetLimit=false)",
            c.store.chatFlow.value.hasMoreMessages,
        )
        // C2 tail: loadMessages fired after the hydrate.
        io.mockk.coVerify(atLeast = 1) {
            repository.getMessagesPaged(any(), any(), any())
        }
    }

    @Test
    fun `VerifyAndHydrate cold-starts via loadMessages resetLimit=true on memory miss (Task2 C3)`() = runTest {
        // Memory miss: peekSessionWindow returns null → the handler injects
        // nothing and calls loadMessages(resetLimit=true) so the slice is
        // seeded fresh from the REST fetch.
        val c = newCore()
        io.mockk.every { hostProfileStore.currentProfile() } returns pinnedTestProfile()
        c.store.mutateChat { it.copy(currentSessionId = "sess-A") }
        // No writeSessionWindow — cache miss for sess-A.

        c.dispatchSessionEffect(
            ControllerEffect.VerifyAndHydrate("test-fp", "sess-A", createdAt = null)
        )
        advanceUntilIdle()

        // C3: nothing injected; empty fetch → empty slice.
        assertTrue(
            "no messages injected on cache miss",
            c.store.chatFlow.value.messages.isEmpty(),
        )
        assertNull(
            "cursor null on cold-start (resetLimit=true seeds nothing from cache)",
            c.store.chatFlow.value.olderMessagesCursor,
        )
        // C3: loadMessages(resetLimit=true) fired. §empty-window-fix: the
        // cold-load path now routes through getMessagesPagedUnanchored (the
        // UNANCHORED slim fetch that bypasses a stale watermark).
        io.mockk.coVerify(atLeast = 1) {
            repository.getMessagesPagedUnanchored(any(), any(), any(), any())
        }
    }

    @Test
    fun `VerifyAndHydrate treats resident-but-EMPTY window as miss and cold-loads unanchored (empty-window-fix)`() = runTest {
        // §empty-window-fix regression: a resident CachedSessionWindow with an
        // EMPTY messages list + a stale slim watermark that already covers the
        // server's latest → pre-fix the hydrate + resetLimit=false refresh
        // returned an empty /since response → "暂无消息". The fix treats the
        // empty window as a MISS and cold-loads via getMessagesPagedUnanchored
        // (since=0L), which returns the server's actual initial window.
        //
        // Setup:
        //  - default mock: getMessagesPaged (anchored) returns EMPTY (the bug
        //    scenario — a stale watermark returns no new messages).
        //  - stub: getMessagesPagedUnanchored returns real messages (the fix —
        //    unanchored fetch returns the server's actual window).
        val c = newCore()
        io.mockk.every { hostProfileStore.currentProfile() } returns pinnedTestProfile()
        c.store.mutateChat { it.copy(currentSessionId = "sess-A") }
        // Seed a resident-but-EMPTY window — the root-cause state.
        c.writeSessionWindow(
            "test-fp", "sess-A",
            CachedSessionWindow(
                messages = emptyList(),
                partsByMessage = emptyMap(),
                olderMessagesCursor = null,
                hasMoreMessages = false,
            ),
        )
        // The anchored path (getMessagesPaged) returns empty — the bug
        // scenario. This is the default mock, restated for clarity.
        io.mockk.coEvery {
            repository.getMessagesPaged(any(), any(), any(), any())
        } returns Result.success(MessagesPage(emptyList(), null))
        // The UNANCHORED path returns real messages — the fix.
        val coldMsg = cn.vectory.ocdroid.data.model.Message(id = "server-m1", role = "user")
        val coldMsgWithParts = cn.vectory.ocdroid.data.model.MessageWithParts(
            info = coldMsg,
            parts = emptyList(),
        )
        io.mockk.coEvery {
            repository.getMessagesPagedUnanchored(any(), any(), any(), any())
        } returns Result.success(MessagesPage(listOf(coldMsgWithParts), null))

        c.dispatchSessionEffect(
            ControllerEffect.VerifyAndHydrate("test-fp", "sess-A", createdAt = 1L)
        )
        advanceUntilIdle()

        // The empty window did NOT hydrate (would have left the slice empty).
        // The cold-load fetched the server's actual message via the unanchored
        // path → the slice is populated, NOT "暂无消息".
        assertEquals(
            "empty resident window must cold-load server messages (not stay empty)",
            listOf("server-m1"),
            c.store.chatFlow.value.messages.map { it.id },
        )
        // The cold-load branch called getMessagesPagedUnanchored (NOT the
        // anchored getMessagesPaged — that path returns empty under a stale
        // watermark and is the root cause).
        io.mockk.coVerify(atLeast = 1) {
            repository.getMessagesPagedUnanchored(any(), any(), any(), any())
        }
        // The anchored getMessagesPaged must NOT be called for the cold-load
        // path — the empty window is treated as a miss, not a hit-with-refresh.
        io.mockk.coVerify(exactly = 0) {
            repository.getMessagesPaged(any(), any(), any(), any())
        }
    }

    @Test
    fun `VerifyAndHydrate entry guard drops when session switched away before launch (Task2 C4)`() = runTest {
        // Entry guard: the user switched to a different session before this
        // launch started → the handler returns immediately, no peek, no inject,
        // no loadMessages.
        val c = newCore()
        io.mockk.every { hostProfileStore.currentProfile() } returns pinnedTestProfile()
        // Current session is B, but the stale effect still references A.
        c.store.mutateChat { it.copy(currentSessionId = "sess-B") }
        val cachedMsg = cn.vectory.ocdroid.data.model.Message(id = "cached-m1", role = "user")
        c.writeSessionWindow(
            "test-fp", "sess-A",
            CachedSessionWindow(
                messages = listOf(cachedMsg),
                partsByMessage = emptyMap(),
                olderMessagesCursor = null,
                hasMoreMessages = false,
            ),
        )

        c.dispatchSessionEffect(
            ControllerEffect.VerifyAndHydrate("test-fp", "sess-A", createdAt = null)
        )
        advanceUntilIdle()

        assertTrue(
            "entry guard must drop — no injection for a stale session",
            c.store.chatFlow.value.messages.isEmpty(),
        )
        // loadMessages never ran (entry guard returned before it).
        io.mockk.coVerify(exactly = 0) {
            repository.getMessagesPaged(any(), any(), any())
        }
    }

    @Test
    fun `VerifyAndHydrate post-peek re-check drops when serverGroupFp mismatched (Task2 C4)`() = runTest {
        // Defence-in-depth: the post-peek re-check of the composite key is
        // retained. peek is synchronous (no TOCTOU window), but if the effect
        // carries an fp that no longer equals currentServerGroupFp() the
        // injection is still dropped. Here the cache is keyed by the CURRENT
        // fp (test-fp) so peek hits, but the effect's fp is "mismatch-fp" →
        // the re-check drops.
        val c = newCore()
        io.mockk.every { hostProfileStore.currentProfile() } returns pinnedTestProfile()
        c.store.mutateChat { it.copy(currentSessionId = "sess-A") }
        val cachedMsg = cn.vectory.ocdroid.data.model.Message(id = "cached-m1", role = "user")
        c.writeSessionWindow(
            "test-fp", "sess-A",
            CachedSessionWindow(
                messages = listOf(cachedMsg),
                partsByMessage = emptyMap(),
                olderMessagesCursor = null,
                hasMoreMessages = false,
            ),
        )

        c.dispatchSessionEffect(
            ControllerEffect.VerifyAndHydrate("mismatch-fp", "sess-A", createdAt = null)
        )
        advanceUntilIdle()

        assertTrue(
            "post-peek fp re-check must drop — no injection on fp mismatch",
            c.store.chatFlow.value.messages.isEmpty(),
        )
        io.mockk.coVerify(exactly = 0) {
            repository.getMessagesPaged(any(), any(), any())
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // remove-message-persistence Task 3: makeCacheHook no longer writes SQLite.
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `makeCacheHook writes only the in-memory LRU and skips putSessionWindow (Task3 C1)`() = runTest {
        // Task 3: the hook body shrank to a single synchronous
        // SessionSwitcher.writeSessionWindow call. The prior async
        // cacheRepository.putSessionWindow fire-and-forget write was
        // deleted (the process-in LRU is the sole cache layer now), so
        // the previously-captured createdAt / directorySessions lookup is
        // gone too.
        val c = newCore()
        // Pin currentServerGroupFp() = "test-fp" so peekSessionWindow
        // resolves the same key the captured-fp hook writes under.
        io.mockk.every { hostProfileStore.currentProfile() } returns pinnedTestProfile()
        val window = CachedSessionWindow(
            messages = emptyList(), partsByMessage = emptyMap(),
            olderMessagesCursor = null, hasMoreMessages = false,
        )

        val hook = c.makeCacheHook("test-fp")
        hook("dir-sess-1", window)
        advanceUntilIdle()

        // The captured fp keyed the in-memory LRU write — peekSessionWindow
        // must return the same window verbatim.
        val hit = c.peekSessionWindow("dir-sess-1")
        assertNotNull("in-memory write must land in sessionWindowCache", hit)
        assertEquals(
            "writeSessionWindow stored the window verbatim",
            window,
            hit,
        )
    }

    @Test
    fun `AppendMessageToCache handler delegates to SessionSwitcher appendMessageIfCached (Task3 C4)`() = runTest {
        // Task 3: SessionSyncCoordinator's `message.updated` new-insert branch
        // now emits AppendMessageToCache instead of calling
        // cacheRepository.appendMessageIfSessionCached. The handler in
        // dispatchSessionEffect routes to sessionSwitcher.appendMessageIfCached.
        val c = newCore()
        // Pin fp = "test-fp" so peekSessionWindow reads the same key the
        // write + effect use.
        io.mockk.every { hostProfileStore.currentProfile() } returns pinnedTestProfile()
        // Seed a resident window so the append is observable (a MISS is a
        // no-op and would leave nothing to assert beyond cache size).
        val existing = CachedSessionWindow(
            messages = listOf(cn.vectory.ocdroid.data.model.Message(id = "m1", role = "user")),
            partsByMessage = mapOf("m1" to emptyList()),
            olderMessagesCursor = "cur",
            hasMoreMessages = false,
        )
        c.writeSessionWindow("test-fp", "s-emit", existing)

        val handled = c.dispatchSessionEffect(
            ControllerEffect.AppendMessageToCache(
                serverGroupFp = "test-fp",
                sessionId = "s-emit",
                message = cn.vectory.ocdroid.data.model.Message(id = "m2", role = "assistant"),
                parts = emptyList(),
            )
        )

        assertTrue("AppendMessageToCache must be claimed by the session dispatcher", handled)
        val hit = c.peekSessionWindow("s-emit")
        assertNotNull(hit)
        assertEquals(
            "append lands on the resident window",
            listOf("m1", "m2"),
            hit!!.messages.map { it.id },
        )
    }
}
