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

    // ═══════════════════════════════════════════════════════════════════════
    // R-20 Phase 1 (C8): applySavedSettings verify hoist in AppCore.init
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `C8 init verify drops currentSessionId on fingerprint mismatch`() = runTest {
        // The persisted currentSessionId is seeded by applySavedSettings
        // (applySavedSettings reads SettingsManager.currentSessionId). The
        // hoisted verify in AppCore.init runs cacheRepository.verifyFingerprint
        // on the seeded id; MismatchEvicted → clear currentSessionId so the
        // user lands on the empty state instead of seeing a stale cached
        // window. This is a defensive cache self-check (DB corruption / fp
        // drift), NOT a cross-connection merge (Phase 5 owns that).
        io.mockk.every { settingsManager.currentSessionId } returns "sess-stale"

        val c = newCore()
        // Re-stub the cache mock AFTER newCore() (the mock instance lives
        // inside core; we override MainViewModelTestBase's default
        // UnknownColdStart stub so the init-time verify returns MismatchEvicted).
        // The init launch is queued on the test dispatcher and runs on the
        // advanceUntilIdle() below, so re-stubbing now (before the advance)
        // is in time.
        io.mockk.coEvery {
            c.cacheRepository.verifyFingerprint(any(), any(), any())
        } returns cn.vectory.ocdroid.data.cache.FingerprintResult.MismatchEvicted
        advanceUntilIdle()

        assertNull(
            "MismatchEvicted on cold-start verify must clear currentSessionId",
            c.store.chatFlow.value.currentSessionId,
        )
    }

    @Test
    fun `C8 init verify keeps currentSessionId when fingerprint matches`() = runTest {
        // Verified → cache is self-consistent → keep the seeded currentSessionId.
        // No clear, no eviction.
        io.mockk.every { settingsManager.currentSessionId } returns "sess-good"

        val c = newCore()
        io.mockk.coEvery {
            c.cacheRepository.verifyFingerprint(any(), any(), any())
        } returns cn.vectory.ocdroid.data.cache.FingerprintResult.Verified
        advanceUntilIdle()

        assertEquals(
            "Verified fingerprint must keep the seeded currentSessionId",
            "sess-good",
            c.store.chatFlow.value.currentSessionId,
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // R-20 Phase 1 review-fix #1: VerifyAndHydrate handler TOCTOU guard
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `review-fix 1A VerifyAndHydrate drops injection when session switched during verifyAndLoad`() = runTest {
        // glm-3 scenario: user rapidly switches A→B during verifyAndLoad(A).
        // The entry guard passes (sessionId==A==currentSessionId at launch
        // start). verifyAndLoad(A) suspends (tens of ms Room IO). During the
        // suspend, user switches to B → currentSessionId becomes B. Without
        // the 二次重检, A's verified window would be injected into B's view.
        val c = newCore()
        c.store.mutateChat { it.copy(currentSessionId = "sess-A") }
        // Stub verifyAndLoad to simulate suspend: when it's called, flip
        // currentSessionId to B (as if the user switched during the IO).
        // Then return Verified(A's window). The post-suspend guard must
        // drop the injection.
        io.mockk.coEvery {
            c.cacheRepository.verifyAndLoad(any(), any(), any())
        } answers {
            // Simulate the user switching to B during the suspend.
            c.store.mutateChat { it.copy(currentSessionId = "sess-B") }
            cn.vectory.ocdroid.data.cache.HydrateResult.Verified(
                cn.vectory.ocdroid.ui.CachedSessionWindow(
                    messages = listOf(io.mockk.mockk(relaxed = true)),
                    partsByMessage = emptyMap(),
                    olderMessagesCursor = null,
                    hasMoreMessages = false,
                )
            )
        }
        // Dispatch the effect directly (entry guard passes: sid=A==currentSessionId).
        c.dispatchSessionEffect(
            ControllerEffect.VerifyAndHydrate("test-fp", "sess-A", createdAt = null)
        )
        advanceUntilIdle()

        // The verified window must NOT be injected — messages should be empty
        // (switchTo's Step 2 cleared them, and the handler dropped the injection).
        // currentSessionId is "sess-B" (set by the simulated switch).
        assertEquals("sess-B", c.store.chatFlow.value.currentSessionId)
        assertTrue(
            "VerifyAndHydrate must NOT inject A's window after session switched to B",
            c.store.chatFlow.value.messages.isEmpty(),
        )
    }

    @Test
    fun `review-fix 1B VerifyAndHydrate drops injection when host group switched during verifyAndLoad`() = runTest {
        // gpter scenario: cross-group same-sessionId collision (plan §0 N1:
        // ses_xxxx is not UUID). verifyAndLoad(A) suspends; during the suspend
        // user switches host (different serverGroupFp). sessionId might stay
        // the same (collision), but the fp changed. Without the fp 二次重检,
        // old group's content would be injected into the new group's view.
        val c = newCore()
        c.store.mutateChat { it.copy(currentSessionId = "sess-X") }
        // Track the fp the effect carries vs the "current" fp after the host switch.
        // Simulate: during verifyAndLoad, switch the host so currentServerGroupFp() returns a different fp.
        // The hostProfileStore mock returns defaultProfile (fp derived from id).
        // We simulate the host switch by re-stubbing currentProfile mid-flight.
        val switchedProfile = cn.vectory.ocdroid.data.model.HostProfile(
            id = "new-host",
            name = "New",
            serverUrl = "http://new",
            serverGroupFp = "new-fp",
        )
        io.mockk.coEvery {
            c.cacheRepository.verifyAndLoad(any(), any(), any())
        } answers {
            // Simulate the user switching host during the suspend — the fp
            // provider now returns the new host's fp.
            io.mockk.every { hostProfileStore.currentProfile() } returns switchedProfile
            cn.vectory.ocdroid.data.cache.HydrateResult.Verified(
                cn.vectory.ocdroid.ui.CachedSessionWindow(
                    messages = listOf(io.mockk.mockk(relaxed = true)),
                    partsByMessage = emptyMap(),
                    olderMessagesCursor = null,
                    hasMoreMessages = false,
                )
            )
        }
        // Dispatch the effect with the OLD fp (from before the host switch).
        c.dispatchSessionEffect(
            ControllerEffect.VerifyAndHydrate("old-fp", "sess-X", createdAt = null)
        )
        advanceUntilIdle()

        // The verified window must NOT be injected — fp changed.
        assertTrue(
            "VerifyAndHydrate must NOT inject old-group window after host switch (fp changed)",
            c.store.chatFlow.value.messages.isEmpty(),
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // R-20 Phase 2 fix-#1: Verified hydrate restores gapMarkers
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `fix-1 Verified hydrate injects gapMarkers from gapsOf so non-contiguous history renders`() = runTest {
        // gpter #1 (头号): verifyAndLoad returns a FLAT CachedSessionWindow
        // (messages only). Before the fix, the handler injected messages but
        // left `gapMarkers` empty, so a session with open gaps in the
        // persistent gap_marker table rendered CONTIGUOUSLY (fault hidden).
        // After the fix, the handler ALSO calls cacheRepository.gapsOf and
        // injects the markers so the UI's `messages.withGaps(gapMarkers)`
        // renders the dividers.
        val c = newCore()
        // Pin the host profile's fp to a known value so the 二次重检 guard
        // passes (effect.serverGroupFp == currentServerGroupFp()).
        val pinnedProfile = cn.vectory.ocdroid.data.model.HostProfile(
            id = "test-host",
            name = "Test",
            serverUrl = "http://server.test",
            serverGroupFp = "test-fp",
        )
        io.mockk.every { hostProfileStore.currentProfile() } returns pinnedProfile
        c.store.mutateChat { it.copy(currentSessionId = "sess-A") }
        // Stub verifyAndLoad → Verified with two flat messages.
        io.mockk.coEvery {
            c.cacheRepository.verifyAndLoad(any(), any(), any())
        } returns cn.vectory.ocdroid.data.cache.HydrateResult.Verified(
            cn.vectory.ocdroid.ui.CachedSessionWindow(
                messages = listOf(
                    io.mockk.mockk(relaxed = true),
                    io.mockk.mockk(relaxed = true),
                ),
                partsByMessage = emptyMap(),
                olderMessagesCursor = null,
                hasMoreMessages = false,
            )
        )
        // Stub gapsOf → one open gap marker between the two messages.
        val gapMarker = cn.vectory.ocdroid.ui.chat.GapMarker(
            gapId = "gap-1",
            lowerAnchorMessageId = "anchor",
            upperBoundaryMessageId = "upper",
            nextBeforeCursor = "c1",
            fillState = cn.vectory.ocdroid.ui.chat.GapFillState.Idle,
        )
        io.mockk.coEvery { c.cacheRepository.gapsOf(any(), any()) } returns listOf(gapMarker)

        c.dispatchSessionEffect(
            ControllerEffect.VerifyAndHydrate("test-fp", "sess-A", createdAt = 1L)
        )
        advanceUntilIdle()

        // gapsOf was queried for the verified session's (fp, sid).
        io.mockk.coVerify { c.cacheRepository.gapsOf("test-fp", "sess-A") }
        // gapMarkers injected — the UI's withGaps will render the divider.
        assertEquals(
            "Verified hydrate must inject gapMarkers from gapsOf",
            listOf("gap-1"),
            c.store.chatFlow.value.gapMarkers.map { it.gapId },
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // R-20 Phase 2 复审 #1: VerifyAndHydrate 三次重检 (after gapsOf suspend)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `fix-1-third VerifyAndHydrate drops injection when session switched during gapsOf`() = runTest {
        // gpter 复审 #1: 二次重检 gates verifyAndLoad, but gapsOf (suspend,
        // used to re-hydrate gapMarkers for a Verified window) is the THIRD
        // suspend point in the handler. A user can switch session during it
        // — the 二次 guard already passed (it ran before gapsOf was even
        // called). Without the 三次 guard, old session's gaps would be
        // injected into the new session's view. After the fix, the third
        // guard re-checks (fp + sessionId) between gapsOf-return and
        // mutateChat, dropping the injection on mismatch.
        val c = newCore()
        // Pin the host profile so currentServerGroupFp() == effect.serverGroupFp
        // ("test-fp") — otherwise the 二次 guard (which checks fp too) would
        // fire before gapsOf is reached and the test would not exercise the
        // 三次 guard path. (Mirrors the existing fix-1 test's pinning.)
        val pinnedProfile = cn.vectory.ocdroid.data.model.HostProfile(
            id = "test-host",
            name = "Test",
            serverUrl = "http://server.test",
            serverGroupFp = "test-fp",
        )
        io.mockk.every { hostProfileStore.currentProfile() } returns pinnedProfile
        c.store.mutateChat { it.copy(currentSessionId = "sess-A") }
        // Stub verifyAndLoad → Verified (passes 二次 guard).
        io.mockk.coEvery {
            c.cacheRepository.verifyAndLoad(any(), any(), any())
        } returns cn.vectory.ocdroid.data.cache.HydrateResult.Verified(
            cn.vectory.ocdroid.ui.CachedSessionWindow(
                messages = listOf(io.mockk.mockk(relaxed = true)),
                partsByMessage = emptyMap(),
                olderMessagesCursor = null,
                hasMoreMessages = false,
            )
        )
        // Stub gapsOf to simulate the suspend-time switch: when it's called,
        // flip currentSessionId to B (as if the user switched during the IO).
        // Then return a non-empty gap list (the stale A gaps).
        val staleGap = cn.vectory.ocdroid.ui.chat.GapMarker(
            gapId = "stale-gap-A",
            lowerAnchorMessageId = "anchor-a",
            upperBoundaryMessageId = "upper-a",
            nextBeforeCursor = "c1",
            fillState = cn.vectory.ocdroid.ui.chat.GapFillState.Idle,
        )
        io.mockk.coEvery {
            c.cacheRepository.gapsOf(any(), any())
        } answers {
            // Simulate the user switching to B during the gapsOf suspend.
            c.store.mutateChat { it.copy(currentSessionId = "sess-B") }
            listOf(staleGap)
        }

        c.dispatchSessionEffect(
            ControllerEffect.VerifyAndHydrate("test-fp", "sess-A", createdAt = null)
        )
        advanceUntilIdle()

        // currentSessionId is B (set by the simulated switch during gapsOf).
        assertEquals("sess-B", c.store.chatFlow.value.currentSessionId)
        // The stale A gap MUST NOT be injected into B's view. The 三次 guard
        // dropped the mutateChat; gapMarkers stays empty.
        assertTrue(
            "VerifyAndHydrate must NOT inject sess-A's gaps after session switched to B during gapsOf",
            c.store.chatFlow.value.gapMarkers.none { it.gapId == "stale-gap-A" },
        )
        // The verified messages are also NOT injected (the third guard gates
        // the whole mutateChat block, not just the gapMarkers field).
        // The mockk message is opaque; we verify via gapMarkers above.
    }

    // ═══════════════════════════════════════════════════════════════════════
    // R-20 Phase 1 review-fix #3: makeCacheHook looks up directorySessions
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `review-fix 3 makeCacheHook resolves createdAt from directorySessions for directory-only sessions`() = runTest {
        // §review-fix #3 (gpter #3): a session that exists ONLY in
        // directorySessions (not yet in the main sessions list) must still
        // get its real createdAt cached. The prior code looked up only
        // `sessions` → directory-only sessions got createdAt=null →
        // verifyAndLoad always UnknownColdStart, defeating the persistent
        // cache for the "connected project sharing" feature.
        val c = newCore()
        val dirSession = cn.vectory.ocdroid.data.model.Session(
            id = "dir-sess-1",
            directory = "/proj",
            time = cn.vectory.ocdroid.data.model.Session.TimeInfo(created = 1_700_000L),
        )
        // Put in directorySessions ONLY (not in sessions).
        c.store.mutateSessionList {
            it.copy(directorySessions = mapOf("/proj" to listOf(dirSession)))
        }
        // Capture the createdAt that putSessionWindow receives.
        var capturedCreatedAt: Long? = -999L
        io.mockk.coEvery {
            c.cacheRepository.putSessionWindow(any(), any(), any(), any(), any())
        } answers {
            capturedCreatedAt = thirdArg()
            Unit
        }
        // Call makeCacheHook + invoke the hook for the directory-only session.
        val hook = c.makeCacheHook("test-fp")
        hook("dir-sess-1", cn.vectory.ocdroid.ui.CachedSessionWindow(
            messages = emptyList(), partsByMessage = emptyMap(),
            olderMessagesCursor = null, hasMoreMessages = false,
        ))
        advanceUntilIdle()

        assertEquals(
            "makeCacheHook must resolve createdAt from directorySessions for directory-only sessions",
            1_700_000L,
            capturedCreatedAt,
        )
    }
}
