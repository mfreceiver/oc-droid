package cn.vectory.ocdroid.ui.controller

import cn.vectory.ocdroid.data.repository.http.TofuDecision

import android.util.Log
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.RecordingStreamingServiceLauncher
import cn.vectory.ocdroid.data.api.CommandInfo
import cn.vectory.ocdroid.data.model.HealthResponse
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.ui.ChatState
import cn.vectory.ocdroid.ui.ComposerState
import cn.vectory.ocdroid.ui.ConnectionPhase
import cn.vectory.ocdroid.ui.ConnectionState
import cn.vectory.ocdroid.ui.SharedEffectBus
import cn.vectory.ocdroid.ui.FileState
import cn.vectory.ocdroid.ui.HostState
import cn.vectory.ocdroid.ui.SessionListState
import cn.vectory.ocdroid.ui.SettingsState
import cn.vectory.ocdroid.ui.SliceFlows
import cn.vectory.ocdroid.ui.TrafficState
import cn.vectory.ocdroid.ui.UiEvent
import cn.vectory.ocdroid.ui.UnreadState
import cn.vectory.ocdroid.util.SettingsManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * R-16 M4: independent unit test for [ConnectionCoordinator].
 *
 * Zero reflection — the coordinator is driven entirely through its public API
 * (testConnection / coldStartReconnect / loadInitialData / startSSE / cancelSse /
 * cancelSseForReconfigure) and asserted via the
 * [RecordingConnectionCoordinatorCallbacks] spy + direct StateFlow reads. The
 * 30s health-check throttle is deterministically driven via the injected
 * [clock]. Heavy service dependencies (OpenCodeRepository / SettingsManager) are
 * mockk stubs; AppState + SliceFlows + connectionFlow + settingsFlow are real so
 * the coordinator's writes are observable. Follows the
 * [HostProfileControllerTest] / [ForegroundCatchUpControllerTest] pattern.
 *
 * These cases are the behaviour-equivalence proof that the connect state machine
 * (30s throttle, exponential-backoff retry, healthy/unhealthy/failure branches,
 * initial-data fan-out, SSE lifecycle) is byte-for-byte preserved from the
 * pre-extraction MainViewModel.testConnection.
 */
@Suppress("DEPRECATION")
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ConnectionCoordinatorTest {

    // §R18 Phase 4 (P0-9): the controller takes the [SliceFlows] bundle only
    // (no separate connectionFlow / settingsFlow). Keep read-only StateFlow
    // views of those two slices so the existing assertions keep their
    // `.value.X` shape.
    private lateinit var connectionFlow: kotlinx.coroutines.flow.StateFlow<ConnectionState>
    private lateinit var settingsFlow: kotlinx.coroutines.flow.StateFlow<SettingsState>
    private lateinit var slices: SliceFlows
    private lateinit var repository: OpenCodeRepository
    private lateinit var settingsManager: SettingsManager
    private lateinit var effects: SharedEffectBus
    private lateinit var collectedEffects: MutableList<ControllerEffect>
    private lateinit var scope: TestScope
    private var now: Long = 0L
    private lateinit var coordinator: ConnectionCoordinator
    /** CP1 (notify Phase-0): the single connection-identity store. */
    private lateinit var identityStore: cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
    /** CP2 (notify Phase-0): the shared TOFU bootstrap coordinator. */
    private lateinit var bootstrapCoordinator: cn.vectory.ocdroid.service.bootstrap.ConnectionBootstrapCoordinator
    /**
     * CP9 (notify Phase-0 switchover): the recording launcher. Tests assert
     * on its callCount instead of `repository.connectSSE` invocations after
     * the switchover (CC's startSSE now calls the launcher).
     */
    private lateinit var launcher: RecordingStreamingServiceLauncher
    /** §R-17 batch2 / §batch 3b: captures UiEvents emitted on effects.uiEvents. */
    private val recordedEvents = mutableListOf<UiEvent>()

    @Before
    fun setUp() {
        // launchSseCollection's catch block calls Log.e; loadPendingQuestions /
        // loadPendingPermissions inline paths now call Log.w on failure.
        mockkStatic(Log::class)
        io.mockk.every { Log.w(any<String>(), any<String>()) } returns 0
        val stateStore = cn.vectory.ocdroid.ui.SharedStateStore()
        slices = stateStore.slices
        connectionFlow = stateStore.connectionFlow
        settingsFlow = stateStore.settingsFlow
        repository = mockk(relaxed = true)
        settingsManager = mockk(relaxed = true)
        effects = SharedEffectBus()
        collectedEffects = mutableListOf()
        scope = TestScope(UnconfinedTestDispatcher())
        recordedEvents.clear()
        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { effects.effectsConsumed.toList(collectedEffects) }
        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { effects.uiEventsConsumed.toList(recordedEvents) }
        // Baseline clock well past the 30s throttle window from epoch 0 so the
        // first probe always proceeds (mirrors production's wall-clock ms).
        now = 100_000L
        identityStore = cn.vectory.ocdroid.service.identity.ConnectionIdentityStore()
        bootstrapCoordinator = cn.vectory.ocdroid.service.bootstrap.ConnectionBootstrapCoordinator()
        launcher = RecordingStreamingServiceLauncher()
        coordinator = ConnectionCoordinator(
            scope = scope,
            slices = slices,
            repository = repository,
            settingsManager = settingsManager,
            effects = effects,
            serverCompatProfile = cn.vectory.ocdroid.data.repository.ServerCompatProfile(),
            clock = { now },
            identityStore = identityStore,
            // CP2: delegate TOFU state to the shared coordinator so the
            // delegation test can assert on bootstrapCoordinator.tofuState.
            bootstrapCoordinator = bootstrapCoordinator,
            // CP9: CC's startSSE now calls the launcher (atomic ownership
            // switch). cancelSse / cancelSseForReconfigure route through
            // streamingLifecycleCoordinator (null here — they are no-ops in
            // this test core; the cancelSseForReconfigure test asserts on
            // the HostReconfigured epoch bump which still fires).
            streamingServiceLauncher = launcher,
            streamingLifecycleCoordinator = null,
            // RESOLVER lane ②: resolver deliberately NOT wired on the shared
            // fixture — the legacy testConnection path then uses the gated
            // fallback (settingsManager.serverUrl), keeping every existing
            // success/failure-path test byte-identical. Tests that exercise the
            // resolver path construct their own coordinator with a resolver.
        )
        // Defaults: no directory fetch on loadInitialData (so the directory
        // coroutine — whose relaxed Result<List<Session>> mock would otherwise
        // throw and cancel the scope's non-supervisor Job, tearing down sibling
        // coroutines like startSSE's SSE collector) is not launched. Tests that
        // exercise the directory path re-stub both explicitly.
        every { settingsManager.currentWorkdir } returns null
        coEvery { repository.getSessionsForDirectory(any()) } returns Result.success(emptyList())
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun runPending() {
        scope.testScheduler.advanceUntilIdle()
    }

    // ── testConnection: 30s health-check throttle ──────────────────────────

    @Test
    fun `testConnection skips a second probe within the 30s throttle window`() {
        coEvery { repository.checkHealth() } returns Result.success(HealthResponse(healthy = false, version = "1.0"))

        coordinator.testConnection()
        coordinator.testConnection() // same clock tick → within 30s
        runPending()

        coVerify(exactly = 1) { repository.checkHealth() }
    }

    @Test
    fun `testConnection force bypasses the 30s throttle`() {
        coEvery { repository.checkHealth() } returns Result.success(HealthResponse(healthy = false, version = "1.0"))

        coordinator.testConnection()
        coordinator.testConnection(force = true) // bypass
        runPending()

        coVerify(exactly = 2) { repository.checkHealth() }
    }

    @Test
    fun `testConnection outside the 30s window probes again`() {
        coEvery { repository.checkHealth() } returns Result.success(HealthResponse(healthy = false, version = "1.0"))

        coordinator.testConnection()
        now += 30_001 // past the window
        coordinator.testConnection()
        runPending()

        coVerify(exactly = 2) { repository.checkHealth() }
    }

    @Test
    fun `testConnection no longer reconfigures the repository (callers pre-configure)`() {
        coEvery { repository.checkHealth() } returns Result.success(HealthResponse(healthy = true, version = "1.0"))

        coordinator.testConnection()
        runPending()

        // configureRepositoryForCurrentProfile must NOT fire from testConnection —
        // its reconfigure chain (cancelSseForReconfigure -> onHostReconfigured)
        // reset ForegroundCatchUpController.sseHasConnectedOnce and swallowed the
        // 15s-5min foreground gap catch-up. Callers configure the repo themselves.
        // §batch 3b: the method was removed entirely; assert no repository
        // reconfigure happened.
        verify(exactly = 0) { repository.configure(any(), any(), any(), any()) }
    }

    // ── testConnection: healthy / unhealthy / failure branches ──────────────

    @Test
    fun `testConnection on healthy sets connected phase and fans out initial data plus SSE`() {
        coEvery { repository.checkHealth() } returns Result.success(HealthResponse(healthy = true, version = "9.9"))
        coEvery { repository.getCommands() } returns Result.success(emptyList())
        // §Q4-strict-sync (#10 self-heal): loadInitialData's self-heal branch
        // probes getSessions when recentWorkdirs is empty (the default here:
        // currentWorkdir=null + getRecentWorkdirs→empty). Explicitly stub it
        // so the relaxed mock does not throw and cancel the non-supervisor
        // scope (which would prevent startSSE / launcher.ensureStarted).
        coEvery { repository.getSessions(any()) } returns Result.success(emptyList())

        coordinator.testConnection()
        runPending()

        assertTrue(connectionFlow.value.isConnected)
        assertFalse(connectionFlow.value.isConnecting)
        assertEquals(ConnectionPhase.Connected, connectionFlow.value.connectionPhase)
        assertEquals("9.9", connectionFlow.value.serverVersion)
        // Mirrors on AppState too.
        assertTrue(slices.connection.value.isConnected)
        assertEquals("9.9", slices.connection.value.serverVersion)
        // §batch 3b: every loader fans out via [ControllerEffect]s.
        assertEquals(1, collectedEffects.filterIsInstance<ControllerEffect.LoadSessions>().size)
        assertEquals(1, collectedEffects.filterIsInstance<ControllerEffect.LoadAgents>().size)
        assertEquals(1, collectedEffects.filterIsInstance<ControllerEffect.LoadProviders>().size)
        assertEquals(1, collectedEffects.filterIsInstance<ControllerEffect.LoadPendingQuestions>().size)
        assertEquals(1, collectedEffects.filterIsInstance<ControllerEffect.LoadPendingPermissions>().size)
        coVerify { repository.getCommands() }
        // CP9 §F27: SSE is now started via the launcher (atomic ownership
        // switch). The launcher is invoked EXACTLY ONCE on a healthy
        // connect; repository.connectSSE is invoked ZERO times (CC no
        // longer owns the collector).
        assertEquals(
            "launcher.ensureStarted invoked exactly once on healthy connect",
            1,
            launcher.callCount,
        )
        verify(exactly = 0) { repository.connectSSE(any()) }
    }

    @Test
    fun `testConnection unhealthy surfaces server version when present but stays connecting-retrying`() {
        // healthy=false, one shot (retries=0): the version is surfaced, then the
        // failure branch marks disconnected.
        coEvery { repository.checkHealth() } returns Result.success(HealthResponse(healthy = false, version = "1.2.3"))

        coordinator.testConnection()
        runPending()

        assertEquals("1.2.3", connectionFlow.value.serverVersion)
        assertFalse(connectionFlow.value.isConnected)
        assertFalse(connectionFlow.value.isConnecting)
        assertEquals(ConnectionPhase.Disconnected, connectionFlow.value.connectionPhase)
    }

    @Test
    fun `testConnection on failure surfaces the error message and disconnected phase`() {
        coEvery { repository.checkHealth() } returns Result.failure(IOException("network down"))

        coordinator.testConnection()
        runPending()

        assertFalse(connectionFlow.value.isConnected)
        assertEquals(ConnectionPhase.Disconnected, connectionFlow.value.connectionPhase)
        val errorEvent = recordedEvents.filterIsInstance<UiEvent.Error>().single()
        // §R18 Phase 2-G: UiEvent.Error now carries @StringRes resId + args.
        // Connection failure → R.string.error_connection_failed with the
        // resolved exception message as the single arg.
        assertEquals(R.string.error_connection_failed, errorEvent.resId)
        assertTrue(errorEvent.args.single().toString().contains("network down"))
    }

    @Test
    fun `testConnection unhealthy with retries exhausts the backoff loop then disconnects`() {
        coEvery { repository.checkHealth() } returns Result.success(HealthResponse(healthy = false, version = "1.0"))

        coordinator.testConnection(force = true, retries = 3) // 4 attempts total
        runPending()

        coVerify(exactly = 4) { repository.checkHealth() }
        assertEquals(ConnectionPhase.Disconnected, connectionFlow.value.connectionPhase)
        assertFalse(connectionFlow.value.isConnected)
    }

    @Test
    fun `testConnection on healthy clears the prior error`() {
        // §R-17 batch2: error is now a one-shot UiEvent — there's no persistent
        // error field to clear. This test stays as a regression guard: a healthy
        // connect must NOT emit a fresh UiEvent.Error (it used to clear the
        // legacy `state.error` field here).
        coEvery { repository.checkHealth() } returns Result.success(HealthResponse(healthy = true, version = "1.0"))
        coEvery { repository.getCommands() } returns Result.success(emptyList())

        coordinator.testConnection()
        runPending()

        assertTrue("healthy connect emits no error event", recordedEvents.filterIsInstance<UiEvent.Error>().isEmpty())
    }

    // ── RESOLVER lane ②: identity endpointFp + null explicit-fail ──────────

    @Test
    fun `testConnection binds identity endpointFp from the resolver URL, not settingsManager`() {
        // §resolver-single-source-of-truth: when the resolver is WIRED, the
        // legacy testConnection success path must bind the resolver's URL as
        // endpointFp (NOT settingsManager.serverUrl). This is the identity-
        // moves-with-url invariant: if identity stayed on settingsManager, a
        // profile/host switch would leave the epoch/identity guards keyed to
        // the OLD url. The shared fixture keeps the resolver absent (gated
        // fallback → settingsManager), so this test builds a coordinator WITH
        // a resolver (mirrors the d3/d4 engine-test pattern).
        coEvery { repository.checkHealth() } returns Result.success(HealthResponse(healthy = true, version = "1.0"))
        coEvery { repository.getCommands() } returns Result.success(emptyList())
        coEvery { repository.getSessions(any()) } returns Result.success(emptyList())
        val resolver = mockk<cn.vectory.ocdroid.service.streaming.EffectiveConnectionConfigResolver>()
        every { resolver.resolve() } returns cn.vectory.ocdroid.service.streaming.EffectiveConnectionConfig(
            source = cn.vectory.ocdroid.service.streaming.EffectiveConnectionSource.Manual,
            profileId = null,
            serverGroupFp = "test-fp",
            url = "http://resolver-authority:1234",
            username = null,
            password = null,
            workdir = "",
            tunnelPasswordId = null,
            tunnelPassword = null,
            clientCertId = null,
            mtlsEnabled = false,
            slim = false,
        )
        val cc = ConnectionCoordinator(
            scope, slices, repository, settingsManager, effects,
            cn.vectory.ocdroid.data.repository.ServerCompatProfile(),
            identityStore = identityStore,
            bootstrapCoordinator = bootstrapCoordinator,
            streamingServiceLauncher = launcher,
            effectiveConnectionConfigResolver = resolver,
        )

        cc.testConnection()
        runPending()

        assertTrue(connectionFlow.value.isConnected)
        assertEquals(
            "identity endpointFp must be the resolver URL (not settingsManager.serverUrl)",
            "http://resolver-authority:1234",
            identityStore.currentIdentity.value?.endpointFp,
        )
    }

    @Test
    fun `testConnection defers and never writes Connected when resolver returns no effective connection`() {
        // §resolver null = EXPLICIT FAIL (resolver WIRED): a null resolve() on
        // the success path means "no valid active endpoint" (host mid-switch).
        // The probe must NOT fall back to a stale settingsManager.serverUrl
        // (that resurrects the token-stream storm). It defers — settles false,
        // writes no Connected — and the in-flight reconfigure re-drives it.
        coEvery { repository.checkHealth() } returns Result.success(HealthResponse(healthy = true, version = "1.0"))
        val resolver = mockk<cn.vectory.ocdroid.service.streaming.EffectiveConnectionConfigResolver>()
        every { resolver.resolve() } returns null
        val cc = ConnectionCoordinator(
            scope, slices, repository, settingsManager, effects,
            cn.vectory.ocdroid.data.repository.ServerCompatProfile(),
            identityStore = identityStore,
            bootstrapCoordinator = bootstrapCoordinator,
            streamingServiceLauncher = launcher,
            effectiveConnectionConfigResolver = resolver,
        )

        var settled: Boolean? = null
        cc.testConnection(onSettled = { settled = it })
        runPending()

        assertEquals("onSettled must fire false (probe superseded)", false, settled)
        assertFalse("must NOT write Connected on a null resolve", connectionFlow.value.isConnected)
        assertFalse(
            "connectionPhase must not reach Connected",
            connectionFlow.value.connectionPhase is ConnectionPhase.Connected,
        )
    }

    @Test
    fun `testConnection aborts commit when a reconfigure advances the generation during checkHealth`() {
        // §toctou-resolver-snapshot (bgpt phase-gate): a host/profile switch that
        // bumps ConnectionIdentityStore's generation WHILE checkHealth is
        // suspended must NOT let the probe commit Connected or bind identity
        // from the (now-obsolete) resolver snapshot. The probe captures the
        // generation before checkHealth; if it advanced by the time the success
        // path runs, the probe defers (settle false, no Connected, no bind) — the
        // new generation's probe re-runs under the new URL. This is the
        // single-snapshot + barrier-guard fix for the resolve→commit→bind TOCTOU.
        //
        // The interleave is simulated by making checkHealth's answers block call
        // beginReconfigure() (the synchronous generation bump every real
        // HostProfileController reconfigure path performs before repository.configure).
        val resolver = mockk<cn.vectory.ocdroid.service.streaming.EffectiveConnectionConfigResolver>()
        every { resolver.resolve() } returns cn.vectory.ocdroid.service.streaming.EffectiveConnectionConfig(
            source = cn.vectory.ocdroid.service.streaming.EffectiveConnectionSource.Manual,
            profileId = null,
            serverGroupFp = "test-fp",
            url = "http://obsolete-host:1234",
            username = null,
            password = null,
            workdir = "",
            tunnelPasswordId = null,
            tunnelPassword = null,
            clientCertId = null,
            mtlsEnabled = false,
            slim = false,
        )
        coEvery { repository.checkHealth() } answers {
            // Host switch fires while checkHealth is suspended — bumps the
            // generation the probe captured before the call.
            identityStore.beginReconfigure()
            Result.success(HealthResponse(healthy = true, version = "1.0"))
        }
        coEvery { repository.getCommands() } returns Result.success(emptyList())
        coEvery { repository.getSessions(any()) } returns Result.success(emptyList())
        val cc = ConnectionCoordinator(
            scope, slices, repository, settingsManager, effects,
            cn.vectory.ocdroid.data.repository.ServerCompatProfile(),
            identityStore = identityStore,
            bootstrapCoordinator = bootstrapCoordinator,
            streamingServiceLauncher = launcher,
            effectiveConnectionConfigResolver = resolver,
        )

        var settled: Boolean? = null
        cc.testConnection(onSettled = { settled = it })
        runPending()

        assertEquals("onSettled must fire false (probe superseded by reconfigure)", false, settled)
        assertFalse(
            "must NOT commit Connected when the generation advanced during checkHealth",
            connectionFlow.value.isConnected,
        )
        assertFalse(
            "connectionPhase must not reach Connected",
            connectionFlow.value.connectionPhase is ConnectionPhase.Connected,
        )
        // The obsolete resolver URL must NOT have leaked into the identity.
        // beginReconfigure nulls the old identity; the aborted probe never re-binds,
        // so currentIdentity stays null (endpointFp != the obsolete URL).
        assertFalse(
            "obsolete resolver URL must not be bound as endpointFp; actual=${identityStore.currentIdentity.value?.endpointFp}",
            "http://obsolete-host:1234" == identityStore.currentIdentity.value?.endpointFp,
        )
    }

    @Test
    fun `testConnection rejects the bind when a reconfigure fires between resolve and bind (epoch-CAS gate)`() {
        // §toctou-resolver-snapshot DEFINITIVE fix (bgpt phase-gate): the prior
        // non-atomic epoch-guard could still let a reconfigure slip between the
        // guard and the subsequent bind (the window bgpt held the lane on). The
        // fix moves the epoch-CHECK into bindIfCurrent itself, under one
        // synchronized critical section with the identity commit. This test
        // exercises the POST-check window: the reconfigure (epoch bump) is
        // injected AFTER resolve returns the obsolete snapshot but BEFORE the
        // bindIfCurrent call — so it proves the CAS rejects a stale-epoch bind
        // even when the snapshot was taken against the current (pre-switch) host.
        //
        // We model the interleave by making resolve()'s answers block call
        // beginReconfigure() (resolve is the last step before bindIfCurrent on
        // the main dispatcher, so a host switch that lands at that instant bumps
        // the generation the probe captured before checkHealth).
        val resolver = mockk<cn.vectory.ocdroid.service.streaming.EffectiveConnectionConfigResolver>()
        coEvery { repository.checkHealth() } returns Result.success(HealthResponse(healthy = true, version = "1.0"))
        coEvery { repository.getCommands() } returns Result.success(emptyList())
        coEvery { repository.getSessions(any()) } returns Result.success(emptyList())
        every { resolver.resolve() } answers {
            // Host switch lands AFTER resolve captured the obsolete URL but
            // BEFORE bindIfCurrent — bumps the generation.
            identityStore.beginReconfigure()
            cn.vectory.ocdroid.service.streaming.EffectiveConnectionConfig(
                source = cn.vectory.ocdroid.service.streaming.EffectiveConnectionSource.Manual,
                profileId = null,
                serverGroupFp = "test-fp",
                url = "http://stale-snapshot:9999",
                username = null,
                password = null,
                workdir = "",
                tunnelPasswordId = null,
                tunnelPassword = null,
                clientCertId = null,
                mtlsEnabled = false,
                slim = false,
            )
        }
        val cc = ConnectionCoordinator(
            scope, slices, repository, settingsManager, effects,
            cn.vectory.ocdroid.data.repository.ServerCompatProfile(),
            identityStore = identityStore,
            bootstrapCoordinator = bootstrapCoordinator,
            streamingServiceLauncher = launcher,
            effectiveConnectionConfigResolver = resolver,
        )

        var settled: Boolean? = null
        cc.testConnection(onSettled = { settled = it })
        runPending()

        // The epoch-CAS bind MUST be rejected (probeEpoch E0 != currentEpoch E1),
        // so: no Connected committed, no stale URL persisted.
        assertEquals("onSettled must fire false (CAS rejected the superseded bind)", false, settled)
        assertFalse(
            "must NOT commit Connected when the CAS rejected the bind",
            connectionFlow.value.isConnected,
        )
        assertFalse(
            "connectionPhase must not reach Connected",
            connectionFlow.value.connectionPhase is ConnectionPhase.Connected,
        )
        assertFalse(
            "stale resolver URL must not be bound as endpointFp; actual=${identityStore.currentIdentity.value?.endpointFp}",
            "http://stale-snapshot:9999" == identityStore.currentIdentity.value?.endpointFp,
        )
    }

    // ── R-20 Phase 3: daily cache sweep hook on healthy connect ─────────────
    // remove-message-persistence Task 5: the three sweep-hook tests that used
    // to live here (fires-on-healthy / null-no-crash / swallows-failures) were
    // removed together with the CacheMaintenanceCoordinator — the hook itself
    // is gone, so there is nothing left to pin.

    // ── coldStartReconnect ─────────────────────────────────────────────────

    @Test
    fun `coldStartReconnect forces a probe with 3 retries`() {
        coEvery { repository.checkHealth() } returns Result.success(HealthResponse(healthy = false, version = "1.0"))

        coordinator.coldStartReconnect()
        runPending()

        // force=true bypasses throttle; retries=3 → 4 attempts.
        coVerify(exactly = 4) { repository.checkHealth() }
    }

    // ── loadInitialData ────────────────────────────────────────────────────

    @Test
    fun `loadInitialData fans out to every loader and fetches slash commands`() {
        coEvery { repository.getCommands() } returns Result.success(emptyList())
        every { settingsManager.currentWorkdir } returns null

        coordinator.loadInitialData()
        runPending()

        // §batch 3b: every loader is now a [ControllerEffect]; loadCommands
        // stays inline.
        assertEquals(1, collectedEffects.filterIsInstance<ControllerEffect.LoadSessions>().size)
        assertEquals(1, collectedEffects.filterIsInstance<ControllerEffect.LoadAgents>().size)
        assertEquals(1, collectedEffects.filterIsInstance<ControllerEffect.LoadProviders>().size)
        assertEquals(1, collectedEffects.filterIsInstance<ControllerEffect.LoadPendingQuestions>().size)
        assertEquals(1, collectedEffects.filterIsInstance<ControllerEffect.LoadPendingPermissions>().size)
        coVerify { repository.getCommands() }
    }

    @Test
    fun `loadInitialData merges server commands with the client-side local commands`() {
        coEvery { repository.getCommands() } returns Result.success(
            listOf(CommandInfo(name = "init", description = "server init"))
        )
        every { settingsManager.currentWorkdir } returns null

        coordinator.loadInitialData()
        runPending()

        val names = settingsFlow.value.availableCommands.map { it.name }.toSet()
        // Server command present.
        assertTrue("init" in names)
        // Client-side local commands appended (server did not publish them).
        assertTrue("clear" in names)
        assertTrue("compact" in names)
        assertTrue("undo" in names)
        assertTrue("redo" in names)
        // Mirrored onto AppState.
        assertEquals(settingsFlow.value.availableCommands, slices.settings.value.availableCommands)
    }

    @Test
    fun `loadCommands falls back to local commands when the server fetch fails`() {
        coEvery { repository.getCommands() } returns Result.failure(IOException("no /command endpoint"))
        every { settingsManager.currentWorkdir } returns null

        coordinator.loadInitialData()
        runPending()

        val names = settingsFlow.value.availableCommands.map { it.name }.toSet()
        assertEquals(setOf("clear", "compact", "undo", "redo"), names)
    }

    @Test
    fun `mergeCommands gives server commands precedence over local commands with the same name`() {
        coEvery { repository.getCommands() } returns Result.success(
            listOf(CommandInfo(name = "clear", description = "server-authoritative"))
        )
        every { settingsManager.currentWorkdir } returns null

        coordinator.loadInitialData()
        runPending()

        val clear = settingsFlow.value.availableCommands.single { it.name == "clear" }
        assertEquals("server-authoritative", clear.description)
        // Only one /clear entry (no duplicate from the local list).
        assertEquals(1, settingsFlow.value.availableCommands.count { it.name == "clear" })
    }

    @Test
    fun `loadInitialData refreshes directorySessions for the restored workdir`() {
        every { settingsManager.currentWorkdir } returns "/persisted/proj"
        coEvery { repository.getCommands() } returns Result.success(emptyList())
        val dirSessions = listOf(Session(id = "s-dir", directory = "/persisted/proj"))
        coEvery { repository.getSessionsForDirectory("/persisted/proj") } returns Result.success(dirSessions)

        coordinator.loadInitialData()
        runPending()

        assertEquals(dirSessions, slices.sessionList.value.directorySessions["/persisted/proj"])
    }

    @Test
    fun `loadInitialData refreshes directorySessions for every recent workdir`() {
        // §recent-workdirs: cold start restores ALL connected projects, not
        // just currentWorkdir — otherwise a non-current workdir whose sessions
        // fall outside the getSessions(limit) first page vanishes after restart.
        every { settingsManager.currentWorkdir } returns "/current"
        every { settingsManager.getRecentWorkdirs(any()) } returns listOf("/current", "/other")
        coEvery { repository.getCommands() } returns Result.success(emptyList())
        val currentSessions = listOf(Session(id = "s-cur", directory = "/current"))
        val otherSessions = listOf(Session(id = "s-oth", directory = "/other"))
        coEvery { repository.getSessionsForDirectory("/current") } returns Result.success(currentSessions)
        coEvery { repository.getSessionsForDirectory("/other") } returns Result.success(otherSessions)

        coordinator.loadInitialData()
        runPending()

        assertEquals(currentSessions, slices.sessionList.value.directorySessions["/current"])
        assertEquals(otherSessions, slices.sessionList.value.directorySessions["/other"])
        // currentWorkdir 是 /current（也在 recentWorkdirs 内）；distinct 去重后
        // 只拉两次（/current + /other），不会对 /current 重复拉取。
        coVerify(exactly = 1) { repository.getSessionsForDirectory("/current") }
        coVerify(exactly = 1) { repository.getSessionsForDirectory("/other") }
    }

    @Test
    fun `loadInitialData skips the directory fetch when no workdir is restored`() {
        every { settingsManager.currentWorkdir } returns null
        every { settingsManager.getRecentWorkdirs(any()) } returns emptyList()
        coEvery { repository.getCommands() } returns Result.success(emptyList())
        // §Q4-strict-sync (#10 self-heal): when recentWorkdirs is empty, the
        // self-heal path probes getSessions to infer workdirs. Explicitly return
        // empty so no directories are inferred → getSessionsForDirectory is
        // never called (the test's core assertion).
        coEvery { repository.getSessions(any()) } returns Result.success(emptyList())

        coordinator.loadInitialData()
        runPending()

        coVerify(exactly = 0) { repository.getSessionsForDirectory(any()) }
    }

    // ── §generation-guard: cross-host in-flight pollution ─────────────────

    @Test
    fun `loadInitialData drops directory fetches that return after a reconfigure`() {
        // §generation-guard: a host switch (cancelSseForReconfigure) between
        // dispatch and return must not let the PREVIOUS host's sessions
        // pollute the NEW host's directorySessions. The fan-out here (up to 8
        // launches) amplifies the in-flight window, so the generation check
        // gates every onSuccess write.
        // CP1: bind an identity first so loadInitialData has a non-null
        // capture; cancelSseForReconfigure → beginReconfigure invalidates it.
        identityStore.bind("test-fp", "/proj", "test-endpoint")
        every { settingsManager.currentWorkdir } returns "/proj"
        every { settingsManager.getRecentWorkdirs(any()) } returns listOf("/proj")
        coEvery { repository.getCommands() } returns Result.success(emptyList())
        // Suspend the fetch so we can interleave a reconfigure before it returns.
        val pending = CompletableDeferred<Result<List<Session>>>()
        coEvery { repository.getSessionsForDirectory("/proj") } coAnswers { pending.await() }

        coordinator.loadInitialData()
        runPending() // launch now suspended on pending.await()

        // The D3 barrier is now the sole epoch owner; simulate its first step.
        identityStore.beginReconfigure()

        // The stale-host result arrives AFTER the reconfigure.
        pending.complete(Result.success(listOf(Session(id = "stale", directory = "/proj"))))
        runPending()

        // Stale result dropped: /proj must NOT appear in directorySessions.
        assertFalse(
            "stale-host directory result must be dropped after reconfigure",
            slices.sessionList.value.directorySessions.containsKey("/proj")
        )
    }

    @Test
    fun `loadInitialData keeps directory fetches that return without a reconfigure`() {
        // Positive control for the generation guard: identical setup but NO
        // reconfigure interleaved → the result IS written.
        every { settingsManager.currentWorkdir } returns "/proj"
        every { settingsManager.getRecentWorkdirs(any()) } returns listOf("/proj")
        coEvery { repository.getCommands() } returns Result.success(emptyList())
        val sessions = listOf(Session(id = "s1", directory = "/proj"))
        coEvery { repository.getSessionsForDirectory("/proj") } returns Result.success(sessions)

        coordinator.loadInitialData()
        runPending()

        assertEquals(sessions, slices.sessionList.value.directorySessions["/proj"])
    }

    // ── §R-19 fix Blocker 1 / SSE lifecycle: REMOVED in CP9 ─────────────────
    //
    // CP9 (notify Phase-0 switchover): the CC-owned collector tests moved to
    // ServiceSseConnectionOwnerTest. CC no longer owns sseJob / launchSse-
    // Collection; startSSE delegates to StreamingServiceLauncher, and the
    // stale-identity drop / event-level failure / collection-level failure /
    // prior-job replacement / cancel-stops-forwarding / workdir retarget /
    // capture-time identity / liveness recovery behaviors are now property
    // of the Service-owned collector.

    // ── SSE lifecycle (cancelSseForReconfigure still lives here) ───────────

    @Test
    fun `cancelSseForReconfigure does not duplicate barrier epoch or effect`() {
        val epochBefore = identityStore.currentEpoch()
        coordinator.cancelSseForReconfigure()
        runPending()
        assertEquals(epochBefore, identityStore.currentEpoch())
        assertTrue(collectedEffects.none { it is ControllerEffect.HostReconfigured })
    }

    @Test
    fun `cancelSseForReconfigure is neutral with no active feed`() {
        coordinator.cancelSseForReconfigure()
        runPending()
        assertTrue(collectedEffects.none { it is ControllerEffect.HostReconfigured })
    }

    // ── loadInitialData (§best-effort: directory onFailure is non-fatal) ───

    @Test
    fun `loadInitialData directory fetch failure logs non-fatal issue without surfacing error or writing stale data`() {
        // §best-effort restore: a failed workdir simply stays absent from
        // directorySessions — the global getSessions list + user-initiated
        // refreshDirectorySessions are the fallbacks. reportNonFatalIssue
        // (Log.w) fires for diagnosability WITHOUT a user-facing error toast.
        // This covers the previously-uncovered onFailure branch (lines 661-664).
        every { settingsManager.currentWorkdir } returns "/proj"
        every { settingsManager.getRecentWorkdirs(any()) } returns listOf("/proj")
        coEvery { repository.getCommands() } returns Result.success(emptyList())
        coEvery { repository.getSessionsForDirectory("/proj") } returns
            Result.failure(IOException("dir fetch boom"))

        coordinator.loadInitialData()
        runPending()

        // Failed workdir stays absent from directorySessions.
        assertFalse(
            "failed directory must not appear in directorySessions",
            slices.sessionList.value.directorySessions.containsKey("/proj")
        )
        // Non-fatal: no user-facing error toast for a directory fetch failure.
        assertTrue(
            "no UiEvent.Error for directory fetch failure",
            recordedEvents.filterIsInstance<UiEvent.Error>().isEmpty()
        )
        // Other initial-data loaders still fanned out.
        assertEquals(1, collectedEffects.filterIsInstance<ControllerEffect.LoadSessions>().size)
    }

    @Test
    fun `loadInitialData directory fetch failure after reconfigure is silently dropped`() {
        // §generation-guard × onFailure: a directory fetch that fails AFTER a
        // host switch must not even log — the error belongs to the stale host.
        // CP1: bind an identity first so the guard is active.
        identityStore.bind("test-fp", "/proj", "test-endpoint")
        every { settingsManager.currentWorkdir } returns "/proj"
        every { settingsManager.getRecentWorkdirs(any()) } returns listOf("/proj")
        coEvery { repository.getCommands() } returns Result.success(emptyList())
        val pending = CompletableDeferred<Result<List<Session>>>()
        coEvery { repository.getSessionsForDirectory("/proj") } coAnswers { pending.await() }

        coordinator.loadInitialData()
        runPending() // launch suspended on pending.await()

        // Host switch bumps generation.
        coordinator.cancelSseForReconfigure()

        // Stale-host failure arrives AFTER the reconfigure.
        pending.complete(Result.failure(IOException("stale-host dir boom")))
        runPending()

        // Silently dropped: no directorySessions entry, no error toast.
        assertFalse(slices.sessionList.value.directorySessions.containsKey("/proj"))
        assertTrue(recordedEvents.filterIsInstance<UiEvent.Error>().isEmpty())
    }

    // ── cancelSse (no-op coverage; CC no longer owns a job) ────────────────

    @Test
    fun `cancelSse is a safe no-op without a streaming lifecycle coordinator`() {
        // CP9: cancelSse now routes through streamingLifecycleCoordinator?.onDisconnect().
        // In this test core coordinator is null — the call MUST be a no-op
        // (null-safe delegate). The repository MUST NOT be touched.
        coordinator.cancelSse()
        verify(exactly = 0) { repository.connectSSE(any()) }
        // The launcher is also not touched (cancelSse does not start anything).
        assertEquals(0, launcher.callCount)
    }

    // ── §R-19 #2 single-SSE product decision: ownership moved to Service ───
    //
    // CP9: the SSE-bound-to-currentWorkdir tests + the workdir-retarget +
    // reconnect-consistency tests moved to ServiceSseConnectionOwnerTest
    // (the workdir is now derived from the StartSse command's identity, not
    // from a fresh settingsManager.currentWorkdir read at collection start).
    // The directory-fan-out catch-up precondition (single-SSE on B restores
    // A's directory) is still exercised via the loadInitialData tests below.

    @Test
    fun `single SSE catch-up fan-out precondition - loadInitialData restores every recent workdir`() {
        // §catch-up precondition: SSE is bound to currentWorkdir B (CP9: the
        // Service binds the identity's workdir), but A is a BACKGROUND workdir
        // (in recentWorkdirs but not currentWorkdir). The single-SSE model
        // relies on loadInitialData's fan-out restoring directorySessions[/A]
        // so SessionSyncCoordinator.loadPendingQuestionsAllWorkdirs (which
        // reads directorySessions.keys) can still poll A's pending questions.
        every { settingsManager.currentWorkdir } returns "/B"
        every { settingsManager.getRecentWorkdirs(any()) } returns listOf("/A", "/B")
        coEvery { repository.getCommands() } returns Result.success(emptyList())
        val aSessions = listOf(Session(id = "s-a", directory = "/A"))
        val bSessions = listOf(Session(id = "s-b", directory = "/B"))
        coEvery { repository.getSessionsForDirectory("/A") } returns Result.success(aSessions)
        coEvery { repository.getSessionsForDirectory("/B") } returns Result.success(bSessions)

        coordinator.loadInitialData()
        runPending()

        assertEquals(aSessions, slices.sessionList.value.directorySessions["/A"])
        assertEquals(bSessions, slices.sessionList.value.directorySessions["/B"])
    }

    // ── CP1 (notify Phase-0): identity-guarded directory-fetch ─────────────

    /**
     * CP1 spec test: stale directory-fetch response (old epoch) is dropped via
     * the same epoch check. Explicitly asserts the identityStore-based guard
     * (the SSE-side stale-identity drop moved to ServiceSseConnectionOwnerTest).
     */
    @Test
    fun `CP1 stale directory-fetch response is dropped via identity epoch check`() {
        identityStore.bind("test-fp", "/proj", "test-endpoint")
        every { settingsManager.currentWorkdir } returns "/proj"
        every { settingsManager.getRecentWorkdirs(any()) } returns listOf("/proj")
        coEvery { repository.getCommands() } returns Result.success(emptyList())
        val pending = CompletableDeferred<Result<List<Session>>>()
        coEvery { repository.getSessionsForDirectory("/proj") } coAnswers { pending.await() }

        coordinator.loadInitialData()
        runPending()

        // beginReconfigure invalidates the identity → epoch bump.
        identityStore.beginReconfigure()

        // Stale-host result arrives.
        pending.complete(Result.success(listOf(Session(id = "stale", directory = "/proj"))))
        runPending()

        assertFalse(
            "stale-identity directory result dropped",
            slices.sessionList.value.directorySessions.containsKey("/proj"),
        )
    }

    // ── CP2 (notify Phase-0): CC delegates TOFU state to bootstrap coordinator ──

    /**
     * CP2 spec test: when CC enters the TOFU-pending path via its public
     * [ConnectionCoordinator.resolveTofuTrust] surface, the shared
     * [ConnectionBootstrapCoordinator.tofuState] reflects the transition.
     * This proves the delegation — CC has NO private TOFU state of its own
     * (the old `pendingTofuHostPort` / `pendingTofuDecision` fields are gone).
     *
     * We exercise the delegation directly: setPendingTofu through the shared
     * coordinator (which is what CC does internally on the SSL/cert-failure
     * path), then resolveTofuTrust through CC's public wrapper, and observe
     * the state on the shared coordinator.
     */
    @Test
    fun `CP2 CC delegates TOFU state to bootstrap coordinator`() {
        // Initially Idle.
        assertEquals(
            cn.vectory.ocdroid.service.bootstrap.TofuState.Idle,
            bootstrapCoordinator.tofuState.value,
        )

        // CC's internal TOFU path sets pending via the shared coordinator.
        bootstrapCoordinator.setPendingTofu("example.com:443")
        bootstrapCoordinator.setTofuDecision(
            kotlinx.coroutines.CompletableDeferred<cn.vectory.ocdroid.data.repository.http.TofuDecision>()
        )
        assertEquals(
            cn.vectory.ocdroid.service.bootstrap.TofuState.TrustPending("example.com:443"),
            bootstrapCoordinator.tofuState.value,
        )

        // CC's public resolveTofuTrust delegates to the shared coordinator.
        coordinator.resolveTofuTrust(
            cn.vectory.ocdroid.data.repository.http.TofuDecision.Cancel
        )
        // resolveTofuTrust completes the deferred but does NOT clear the
        // pending state (mirrors CC's original semantics — the retry loop's
        // finally block calls clearPendingTofu). The state is still
        // TrustPending until clearPendingTofu runs.
        assertEquals(
            "resolveTofuTrust does not clear tofuState (the retry loop finally does)",
            cn.vectory.ocdroid.service.bootstrap.TofuState.TrustPending("example.com:443"),
            bootstrapCoordinator.tofuState.value,
        )

        // The retry loop's finally block clears the state.
        bootstrapCoordinator.clearPendingTofu()
        identityStore.bind("test-fp", "/proj", "test-endpoint")
        assertEquals(
            cn.vectory.ocdroid.service.bootstrap.TofuState.Idle,
            bootstrapCoordinator.tofuState.value,
        )
    }

    /**
     * CP2 / CP9 spec test: the freeze semantics are preserved through
     * delegation — while TOFU is pending, testConnection / coldStartReconnect
     * / startSSE short-circuit (the guards read through
     * `tofu.pendingTofuHostPort()` which returns non-null for TrustPending).
     * CP9: startSSE now delegates to the launcher; the freeze assertion is
     * "launcher NOT invoked while frozen" + "launcher invoked once after clear".
     */
    @Test
    fun `CP2 freeze semantics preserved - startSSE short-circuits while TOFU pending`() {
        // Enter TOFU-pending via the shared coordinator (CC's internal path).
        bootstrapCoordinator.setPendingTofu("example.com:443")

        // startSSE must FROZEN — no launcher call.
        coordinator.startSSE()
        runPending()

        assertEquals(
            "launcher NOT invoked while TOFU pending",
            0,
            launcher.callCount,
        )

        // Resolve + clear → unfreezes.
        bootstrapCoordinator.clearPendingTofu()
        identityStore.bind("test-fp", "/proj", "test-endpoint")

        coordinator.startSSE()
        runPending()

        assertEquals(
            "launcher invoked once after TOFU cleared",
            1,
            launcher.callCount,
        )
    }

    /**
     * CP2 / CP9 spec test: CC has NO private TOFU fields (grep-verifiable:
     * the old `pendingTofuHostPort` / `pendingTofuDecision` private vars are
     * gone). This test exercises the public surface end-to-end to prove no
     * duplicate state — the shared coordinator is the single source.
     */
    @Test
    fun `CP2 CC has no private TOFU state - single source in bootstrap coordinator`() {
        // If CC held a PRIVATE copy of the TOFU state (the old fields), this
        // test would fail: setting state via the shared coordinator would
        // not be visible to CC's guards, and vice versa. The fact that CC's
        // startSSE guard reads the shared coordinator's state proves no
        // private duplicate exists.
        bootstrapCoordinator.setPendingTofu("frozen.example.com:443")
        assertEquals(
            "frozen.example.com:443",
            bootstrapCoordinator.pendingTofuHostPort(),
        )

        // CC's internal guard (via delegation) sees the same state.
        coordinator.startSSE() // should freeze — no launcher call.
        runPending()
        assertEquals(0, launcher.callCount)

        // Clearing via the shared coordinator unfreezes CC.
        bootstrapCoordinator.clearPendingTofu()
        identityStore.bind("test-fp", "/proj", "test-endpoint")
        coordinator.startSSE()
        runPending()
        assertEquals(1, launcher.callCount)
    }

    @Test
    fun `D3 refused ownership settles false once and never publishes Connected`() {
        val engine = mockk<cn.vectory.ocdroid.service.streaming.ConnectionBootstrapEngine>()
        val identity = identityStore.bind("group", "/work", "endpoint")
        coEvery { engine.bootstrap() } returns
            cn.vectory.ocdroid.service.streaming.ConnectionBootstrapOutcome.Success(
                identity,
                HealthResponse(true, "3.0"),
            )
        launcher.nextResult = false
        val d3 = ConnectionCoordinator(
            scope = scope,
            slices = slices,
            repository = repository,
            settingsManager = settingsManager,
            effects = effects,
            serverCompatProfile = cn.vectory.ocdroid.data.repository.ServerCompatProfile(),
            clock = { now },
            identityStore = identityStore,
            bootstrapCoordinator = bootstrapCoordinator,
            streamingServiceLauncher = launcher,
            connectionBootstrapEngine = engine,
        )
        val settled = mutableListOf<Boolean>()

        d3.testConnection(force = true, onSettled = settled::add)
        runPending()

        assertEquals(listOf(false), settled)
        assertFalse(connectionFlow.value.isConnected)
        assertFalse(connectionFlow.value.connectionPhase is ConnectionPhase.Connected)
    }

    @Test
    fun `D3 exact accepted identity is the only healthy settlement`() {
        val engine = mockk<cn.vectory.ocdroid.service.streaming.ConnectionBootstrapEngine>()
        val identity = identityStore.bind("group", "/work", "endpoint")
        coEvery { engine.bootstrap() } returns
            cn.vectory.ocdroid.service.streaming.ConnectionBootstrapOutcome.Success(
                identity,
                HealthResponse(true, "3.0"),
            )
        val d3 = ConnectionCoordinator(
            scope = scope,
            slices = slices,
            repository = repository,
            settingsManager = settingsManager,
            effects = effects,
            serverCompatProfile = cn.vectory.ocdroid.data.repository.ServerCompatProfile(),
            clock = { now },
            identityStore = identityStore,
            bootstrapCoordinator = bootstrapCoordinator,
            streamingServiceLauncher = launcher,
            connectionBootstrapEngine = engine,
        )
        val settled = mutableListOf<Boolean>()

        d3.testConnection(force = true, onSettled = settled::add)
        runPending()

        assertEquals(listOf(true), settled)
        assertTrue(connectionFlow.value.isConnected)
        assertEquals(listOf(identity), launcher.requestedIdentities.takeLast(1))
    }

    @Test
    fun `B2 foreground promotes degraded capture once and Accept reruns engine to ownership Ready`() {
        val foreground = kotlinx.coroutines.flow.MutableStateFlow(false)
        val monitor = mockk<cn.vectory.ocdroid.di.AppLifecycleMonitor>(relaxed = true)
        every { monitor.isInForeground } returns foreground
        val engine = mockk<cn.vectory.ocdroid.service.streaming.ConnectionBootstrapEngine>()
        val identity = identityStore.bind("group", "/work", "endpoint")
        coEvery { engine.bootstrap() } returns
            cn.vectory.ocdroid.service.streaming.ConnectionBootstrapOutcome.Success(
                identity,
                HealthResponse(true, "4.0"),
            )
        val capture = mockk<OpenCodeRepository.TofuCaptureResult>()
        every { capture.hostPort } returns "server:443"
        bootstrapCoordinator.setPendingTofu("server:443")
        bootstrapCoordinator.setPendingCapture(capture)
        bootstrapCoordinator.markDegradedNeedsActivity()
        val d4 = ConnectionCoordinator(
            scope, slices, repository, settingsManager, effects,
            cn.vectory.ocdroid.data.repository.ServerCompatProfile(),
            identityStore = identityStore,
            bootstrapCoordinator = bootstrapCoordinator,
            streamingServiceLauncher = launcher,
            connectionBootstrapEngine = engine,
            appLifecycleMonitor = monitor,
        )
        runPending()

        foreground.value = true
        runPending()
        assertTrue(bootstrapCoordinator.tofuState.value is cn.vectory.ocdroid.service.bootstrap.TofuState.TrustPending)
        assertEquals(capture, connectionFlow.value.pendingTofuCapture)
        assertEquals(ConnectionPhase.AwaitingTofuTrust, connectionFlow.value.connectionPhase)

        d4.resolveTofuTrust(TofuDecision.AcceptOnce("spki"))
        runPending()

        verify(exactly = 1) { repository.applyTofuDecision("server:443", TofuDecision.AcceptOnce("spki")) }
        coVerify(exactly = 1) { engine.bootstrap() }
        assertEquals(ConnectionPhase.Connected, connectionFlow.value.connectionPhase)
        assertEquals(1, launcher.callCount)
    }

    @Test
    fun `B2 duplicate foreground emits one challenge and Cancel clears degraded without retry`() {
        val foreground = kotlinx.coroutines.flow.MutableStateFlow(false)
        val monitor = mockk<cn.vectory.ocdroid.di.AppLifecycleMonitor>(relaxed = true)
        every { monitor.isInForeground } returns foreground
        val engine = mockk<cn.vectory.ocdroid.service.streaming.ConnectionBootstrapEngine>(relaxed = true)
        val terminator = mockk<cn.vectory.ocdroid.service.DegradedBootstrapTerminator>(relaxed = true)
        val capture = mockk<OpenCodeRepository.TofuCaptureResult>()
        every { capture.hostPort } returns "server:443"
        bootstrapCoordinator.setPendingTofu("server:443")
        bootstrapCoordinator.setPendingCapture(capture)
        bootstrapCoordinator.markDegradedNeedsActivity()
        val d4 = ConnectionCoordinator(
            scope, slices, repository, settingsManager, effects,
            cn.vectory.ocdroid.data.repository.ServerCompatProfile(),
            identityStore = identityStore,
            bootstrapCoordinator = bootstrapCoordinator,
            streamingServiceLauncher = launcher,
            connectionBootstrapEngine = engine,
            appLifecycleMonitor = monitor,
            degradedBootstrapTerminator = terminator,
        )
        runPending()

        foreground.value = true
        foreground.value = true
        runPending()
        d4.resolveTofuTrust(TofuDecision.Cancel)
        runPending()

        assertEquals(cn.vectory.ocdroid.service.bootstrap.TofuState.Idle, bootstrapCoordinator.tofuState.value)
        assertEquals(null, connectionFlow.value.pendingTofuCapture)
        assertEquals(ConnectionPhase.Disconnected, connectionFlow.value.connectionPhase)
        coVerify(exactly = 0) { engine.bootstrap() }
        verify(exactly = 1) { terminator.terminate() }
    }

    // ── RecordingConnectionCoordinatorCallbacks (removed in batch 3b) ──────
    // The handwritten spy was replaced by direct filtering on
    // [collectedEffects] (plus the [forwardedSseEvents] helper for SSE event
    // assertions) and repository coVerify for the inlined loaders. See the
    // class kdoc at the top for the new pattern.
}
