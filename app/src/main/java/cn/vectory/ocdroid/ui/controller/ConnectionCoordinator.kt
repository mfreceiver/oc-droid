package cn.vectory.ocdroid.ui.controller

import cn.vectory.ocdroid.data.api.CommandInfo
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.data.repository.ServerCompatProfile
import cn.vectory.ocdroid.data.repository.http.TofuDecision
import cn.vectory.ocdroid.service.bootstrap.ConnectionBootstrapCoordinator
import cn.vectory.ocdroid.service.StreamingServiceLauncher
import cn.vectory.ocdroid.service.OwnershipStartResult
import cn.vectory.ocdroid.service.TeardownReason
import cn.vectory.ocdroid.service.DegradedBootstrapTerminator
import cn.vectory.ocdroid.service.streaming.BootstrapRetryPolicy
import cn.vectory.ocdroid.service.streaming.ConnectionBootstrapEngine
import cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
import cn.vectory.ocdroid.service.lifecycle.StreamingLifecycleCoordinator
import cn.vectory.ocdroid.di.AppLifecycleMonitor
import cn.vectory.ocdroid.ui.ConnectionPhase
import cn.vectory.ocdroid.ui.ConnectionState
import cn.vectory.ocdroid.ui.MainViewModelTimings
import cn.vectory.ocdroid.ui.SharedEffectBus
import cn.vectory.ocdroid.ui.SliceFlows
import cn.vectory.ocdroid.ui.launchLoadProviders
import cn.vectory.ocdroid.ui.reportNonFatalIssue
import cn.vectory.ocdroid.util.DebugLog
import cn.vectory.ocdroid.util.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * R-16 M4 → R-17 batch3b: owns the server connection lifecycle — health-check
 * probe with exponential-backoff retry, the 30s health-check throttle,
 * initial-data load orchestration on a healthy connect.
 *
 * **L4c (Wave ζ): the health-probe concern has been EXTRACTED into
 * [ConnectionHealthProbe]** ([healthProbe]). The multi-state connect flow
 * (`testConnection` / `testConnectionWithEngine` / `coldStartReconnect`),
 * the TOFU delegation (`tofu` / `hasPendingTofuDecision` /
 * `promoteDegradedTofuIfNeeded`), and the foreground-monitor `init` hook now
 * live there verbatim. This coordinator keeps thin public delegates
 * ([testConnection] / [coldStartReconnect] / [resolveTofuTrust]) so every
 * existing call site (ConnectionViewModel / AppCore / ChatViewModel / tests)
 * resolves unchanged. The probe calls back into [loadInitialData] /
 * [startSSE] (both public, both with external callers — they could not move).
 * Extraction is behavior-preserving: identical state-machine transitions,
 * TOFU call order, SSE lifecycle timing, and `onSettled` exactly-once
 * contract.
 *
 * **CP9 switchover**: the SSE feed ownership (sseJob + launchSseCollection)
 * has been DELETED from this coordinator and moved into the Service-owned
 * [cn.vectory.ocdroid.service.streaming.ServiceSseConnectionOwner]. The
 * thin [startSSE] delegate is preserved (ConnectionViewModel /
 * ControllerEffect.StartSse / tests expose it; deleting adds rollback churn)
 * — it now calls [streamingServiceLauncher].ensureStarted() so a successful
 * foreground health probe synchronously requests the Service before
 * reporting success. The no-zero-time-gap guarantee (FGS spec §1) is
 * preserved: there is NO terminal connected-without-SSE path.
 *
 * `cancelSse` / `cancelSseForReconfigure` remain as lifecycle-teardown
 * delegates (still exposed for direct VM/process cleanup callers), but they
 * no longer touch a job — they route through
 * [StreamingLifecycleCoordinator.onDisconnect] which the Service observes
 * (the coordinator emits StopSse → owner.disconnect). Cluster 11 (duplication
 * backlog): the two are now deduped through [cancelSseInternal].
 *
 * **Migration (batch 3b)**: the [ConnectionCoordinatorCallbacks] interface
 * was eliminated. Most of its methods either (a) had all dependencies already
 * available on this coordinator (scope/repository/settingsManager/slices) and
 * were inlined (loadAgents / loadProviders / loadPendingQuestions /
 * loadPendingPermissions — rule A), or (b) reached sibling controllers and
 * now emit [ControllerEffect]s on [effects]: loadSessions →
 * [ControllerEffect.LoadSessions], onSseEvent →
 * [ControllerEffect.OnSseEvent] (the SessionSyncCoordinator is constructed
 * AFTER this coordinator in AppCore so it cannot be a constructor param),
 * onHostReconfigured → [ControllerEffect.HostReconfigured]. configureRepositoryForCurrentProfile
 * was vestigial (no callers in this coordinator) — dropped entirely. The
 * previously-injected [cn.vectory.ocdroid.ui.EventEmitter] is replaced by
 * [effects] — UiEvents now ride [SharedEffectBus.uiEvents].
 *
 * **Moved from the orchestrator (and still here unless noted):**
 *  - `lastHealthCheckTime` field — the throttle anchor now lives in
 *    [ConnectionHealthProbe].
 *  - `testConnection(force, retries)` — now a delegate to [healthProbe].
 *  - `coldStartReconnect()` — now a delegate to [healthProbe].
 *  - `loadInitialData()` — sessions/agents/providers/questions/commands + the
 *    directory-sessions re-fetch for the restored workdir.
 *  - `loadCommands()` + `localCommands()` + `mergeCommands()` — slash-command
 *    merge (server list + client-side /clear /compact /undo /redo).
 *  - `startSSE()` — thin delegate to [streamingServiceLauncher]; its
 *    TOFU-frozen guard now reads [ConnectionHealthProbe.hasPendingTofuDecision].
 *  - `cancelSse()` / `cancelSseForReconfigure()` — coordinator teardown
 *    delegates, deduped via [cancelSseInternal].
 *
 * The 30s throttle clock is injectable ([clock], forwarded to [healthProbe])
 * so the cooldown is deterministically testable without wall-clock latency.
 *
 * §R-17 batch2 step e final: all state writes go through the per-slice
 * `MutableStateFlow.update` helpers (`writeConnection` here, plus the other
 * slices from the [SliceFlows] bundle as needed).
 *
 * RFC reference: R-16 §A / §M4. Zero behaviour change.
 */
@Suppress("DEPRECATION")
class ConnectionCoordinator(
    private val scope: CoroutineScope,
    private val slices: SliceFlows,
    private val repository: OpenCodeRepository,
    private val settingsManager: SettingsManager,
    private val effects: SharedEffectBus,
    // ③ ServerCompat: populated from the health probe so future shim migrations
    // can read version-derived capability flags instead of guessing a version.
    private val serverCompatProfile: ServerCompatProfile,
    /**
     * R-20 Phase 3: provider for the current host's serverGroupFp. Same
     * `@Named("currentServerGroupFp")` provider every other controller uses
     * (ControllerModule.provideCurrentServerGroupFp) — single source of truth
     * so a profile switch races the same fp read as everyone else.
     */
    private val currentServerGroupFp: () -> String = { "" },
    // Injected clock so the 30s health-check throttle is deterministically
    // testable without depending on wall-clock latency. Defaults to
    // System::currentTimeMillis in production (preserves the exact pre-extraction
    // behaviour — the original `testConnection` called System.currentTimeMillis()).
    // Forwarded to [healthProbe].
    private val clock: () -> Long = { System.currentTimeMillis() },
    /**
     * CP1 (notify Phase-0): the single connection-identity store. Replaces
     * the private [directoryFetchGeneration] AtomicLong. Guards the
     * directory-fetch fan-out in [loadInitialData]. FGS spec §2 «关键约束»:
     * no second private generation.
     *
     * `null` for legacy/test construction that doesn't wire the store — the
     * coordinator falls back to unconditional forwarding (no identity gate).
     */
    private val identityStore: ConnectionIdentityStore? = null,
    /**
     * CP2 (notify Phase-0): the application-level shared TOFU bootstrap
     * coordinator. CC DELEGATES its TOFU state here (FGS spec §10 — TOFU
     * state is extracted so the SessionStreamingService shares the
     * same single source and the bootstrap cannot fork into two TLS/SSE
     * state machines). CC's public TOFU surface ([resolveTofuTrust] + the
     * freeze guards on testConnection/coldStartReconnect/startSSE) is
     * preserved verbatim — callers (ConnectionViewModel) see no change.
     *
     * L4c: forwarded to [healthProbe], which now owns the `tofu` delegate +
     * the TOFU capture/decision flow. `null` for legacy/test construction
     * that doesn't exercise the TOFU path — the probe constructs a private
     * fallback so the guards work even without Hilt wiring (mirrors the
     * pre-extraction private fields).
     */
    private val bootstrapCoordinator: ConnectionBootstrapCoordinator? = null,
    /**
     * CP9 (notify Phase-0 switchover): the trigger that promotes the live
     * SSE connection ownership into [cn.vectory.ocdroid.service.SessionStreamingService].
     * CC's [startSSE] delegate now calls [StreamingServiceLauncher.ensureStarted]
     * instead of `repository.connectSSE(...)`; the Service runs the §5
     * bootstrap and the coordinator's decision matrix drives StartSse /
     * StopSse into the new owner.
     *
     * `null` for legacy/test construction — CC falls back to a no-op so
     * tests that drive health probes without the launcher keep compiling.
     */
    private val streamingServiceLauncher: StreamingServiceLauncher? = null,
    /**
     * CP9 (notify Phase-0 switchover): the lifecycle coordinator that
     * drives the L1/L2/L3 state machine inside the Service. CC's
     * [cancelSse] / [cancelSseForReconfigure] delegates now call
     * [StreamingLifecycleCoordinator.onDisconnect] (the §4.1 disconnect
     * entry → L3 teardown); the Service observes the teardown commands and
     * disconnects its [cn.vectory.ocdroid.service.streaming.ServiceSseConnectionOwner].
     *
     * `null` for legacy/test construction — CC falls back to the existing
     * HostReconfigured effect emission so tests that drive
     * [cancelSseForReconfigure] directly keep asserting on the epoch +
     * effect.
     */
    private val streamingLifecycleCoordinator: StreamingLifecycleCoordinator? = null,
    private val connectionBootstrapEngine: ConnectionBootstrapEngine? = null,
    private val bootstrapRetryPolicy: BootstrapRetryPolicy = BootstrapRetryPolicy(),
    private val appLifecycleMonitor: AppLifecycleMonitor? = null,
    private val degradedBootstrapTerminator: DegradedBootstrapTerminator? = null,
    /**
     * §Stage-D2 §5.8/§5.9: the token-stream coordinator. CC hooks
     * [TokenStreamCoordinator.close] on [cancelSse] (background / ViewModel
     * onCleared) and [cancelSseForReconfigure] (host / profile switch), and
     * [TokenStreamCoordinator.resetDegraded] after a successful health probe
     * that re-confirms `features.tokenStream == true` (re-arms after a
     * transient sidecar admission-cap state).
     *
     * `null` for legacy/test construction — CC falls back to a no-op so tests
     * that don't exercise the token-stream path keep compiling.
     */
    private val tokenStreamCoordinator: cn.vectory.ocdroid.ui.controller.sse.TokenStreamCoordinator? = null,
    /**
     * §resolver-single-source-of-truth (RESOLVER lane ②): the authority for the
     * effective connection URL. Forwarded to [healthProbe] so its legacy
     * testConnection path (identity endpointFp bind + TOFU host:port) resolves
     * the URL the SAME way the engine path + token-stream factory do — the
     * resolver is the single source of truth, NOT settingsManager.serverUrl.
     * `null` for legacy/test construction (the probe then explicit-fails on a
     * null resolve instead of falling back to stale settings).
     */
    private val effectiveConnectionConfigResolver: cn.vectory.ocdroid.service.streaming.EffectiveConnectionConfigResolver? = null,
) {
    /**
     * L4c (Wave ζ): the health-probe concern (testConnection /
     * testConnectionWithEngine / coldStartReconnect / TOFU delegation /
     * degraded-TOFU foreground promotion) now lives in [ConnectionHealthProbe].
     * This coordinator keeps thin public delegates so every existing call
     * site (ConnectionViewModel / AppCore / ChatViewModel / tests) resolves
     * unchanged. The probe calls back into [loadInitialData] / [startSSE]
     * (both stay here — public, external callers). Constructed eagerly: its
     * `init` block launches the foreground-monitor coroutine on [scope]
     * (deferred — never runs during CC construction), preserving the
     * pre-extraction wiring timing verbatim.
     */
    private val healthProbe = ConnectionHealthProbe(
        scope = scope,
        slices = slices,
        repository = repository,
        settingsManager = settingsManager,
        effects = effects,
        serverCompatProfile = serverCompatProfile,
        currentServerGroupFp = currentServerGroupFp,
        clock = clock,
        identityStore = identityStore,
        bootstrapCoordinator = bootstrapCoordinator,
        connectionBootstrapEngine = connectionBootstrapEngine,
        bootstrapRetryPolicy = bootstrapRetryPolicy,
        streamingServiceLauncher = streamingServiceLauncher,
        degradedBootstrapTerminator = degradedBootstrapTerminator,
        appLifecycleMonitor = appLifecycleMonitor,
        loadInitialData = ::loadInitialData,
        startSSE = ::startSSE,
        effectiveConnectionConfigResolver = effectiveConnectionConfigResolver,
    )

    /**
     * §streaming-state-sync-diag (DEBUG-only): expose the current lifecycle
     * layer (L1 / L2Active / L2Idle / L3) as a string for send-time
     * diagnostics. Null when the coordinator is absent (legacy/test). The
     * layer tells us whether SSE is live at send time (L1/L2Active = SSE on;
     * L2Idle/L3 = SSE off, poller only). Temporary diagnostic surface.
     */
    val diagLayer: String? get() = streamingLifecycleCoordinator?.layer?.value?.toString()

    // ── State sync helpers (mirror orchestrator.writeConnection) ──

    /**
     * §R-17 M5.1→batch2: writes the connection slice only (slice is the
     * authoritative read path). The deprecated AppState mirror write +
     * `Dispatchers.Main.immediate` Looper check were removed in R-17 batch2
     * sub-step d (Fixer C) — call sites already run on the main dispatcher
     * (viewModelScope default), and `MutableStateFlow.update` is main-thread-
     * safe by VM contract.
     *
     * L4c: still used by [startSSE] (the CP9 `ensureStarted` adapter writes
     * Disconnected on a refused/stale ownership result). The probe owns its
     * own copy for the testConnection / testConnectionWithEngine flows.
     */
    private fun writeConnection(transform: (ConnectionState) -> ConnectionState) {
        slices.mutateConnection(transform)
    }

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Health-check probe with optional exponential-backoff retry.
     *
     * L4c: thin delegate to [ConnectionHealthProbe.testConnection]. The full
     * multi-state flow (connect → cert capture → TOFU decision → health check
     * → result) lives there. Signature + result semantics preserved verbatim
     * — see [ConnectionHealthProbe.testConnection] for the authoritative
     * contract. Throttle / TOFU-freeze / onSettled-exactly-once behavior is
     * unchanged.
     */
    fun testConnection(force: Boolean = false, retries: Int = 0, onSettled: ((Boolean) -> Unit)? = null) {
        healthProbe.testConnection(force = force, retries = retries, onSettled = onSettled)
    }

    /**
     * Cold-start entry point: force a connection check with up to 3 retries.
     *
     * L4c: thin delegate to [ConnectionHealthProbe.coldStartReconnect]. The
     * TOFU-frozen guard + `testConnection(force=true, retries=3)` semantics
     * are preserved verbatim. Callers (MainActivity cold-start LaunchedEffect,
     * [resetLocalDataAndResync], SessionsScreen onRefresh via
     * `ConnectionViewModel.coldStartReconnect()`) see no change.
     */
    fun coldStartReconnect() {
        healthProbe.coldStartReconnect()
    }

    /**
     * Loads initial data after a healthy connect: sessions, agents, providers,
     * pending questions, slash commands, and the directory-scoped sessions for
     * EVERY known workdir (the persisted `recentWorkdirs` set + currentWorkdir)
     * so each connected project's sessions reappear after restart —
     * directorySessions is in-memory and otherwise empty until the user
     * re-connects each project. Restoring only currentWorkdir lost every other
     * project whose sessions fell outside the global getSessions(limit) first
     * page.
     *
     * §batch 3b: the loaders that crossed into sibling controllers
     * (loadSessions / onSseEvent-style) used to be callbacks on
     * [ConnectionCoordinatorCallbacks]; they now emit [ControllerEffect]s on
     * [effects]. The agents/providers/questions/permissions loaders also reach
     * cross-domain state (settings / sessionList slices) plus the in-process
     * SettingsManager, and the orchestrator already owns their full
     * implementations — emit them as effects so we don't duplicate the bodies
     * here. Only `loadCommands` (pure connection-domain: server-published
     * slash commands merged with client-side /clear /compact /undo /redo,
     * written to the settings slice) is inlined.
     */
    fun loadInitialData() {
        // §Stage-D2 §5.9: re-arm token-stream capability after a successful
        // health probe. updateSlimapi already ran inside checkHealth/checkHealthFor,
        // settling slimapiTokenStreamEnabled. If the feature is on, clear any
        // stale degrade state for the current session so the token stream can
        // be opened (a transient cap-8 admission state may have cleared).
        if (serverCompatProfile.slimapiTokenStreamEnabled) {
            tokenStreamCoordinator?.let { tsc ->
                slices.chat.value.currentSessionId?.let { sid -> tsc.resetDegraded(sid) }
            }
        }
        // Cross-domain fan-out: orchestrator owns these implementations.
        // §R18 Phase 3 Wave 1 (P1-3 C 类): loadInitialData 五连发顺序敏感 → 保持同步 tryEmitEffect (scope.launch 包裹会破坏顺序)。
        effects.tryEmitEffect(ControllerEffect.LoadSessions)
        effects.tryEmitEffect(ControllerEffect.LoadAgents)
        effects.tryEmitEffect(ControllerEffect.LoadProviders)
        effects.tryEmitEffect(ControllerEffect.LoadPendingQuestions)
        effects.tryEmitEffect(ControllerEffect.LoadPendingPermissions)
        // Same-domain inline: slash commands merged with client-side commands.
        loadCommands()
        // CP1 (notify Phase-0): capture the current identity so directory
        // fetches that return AFTER a host reconfigure
        // (cancelSseForReconfigure → identityStore.beginReconfigure bumps the
        // epoch AND nulls currentIdentity) are dropped — their sessions belong
        // to the previous host. FGS spec §2: the SAME epoch guards both the
        // SSE collector AND the directory fan-out (no private second
        // generation — the removed `directoryFetchGeneration` is now the
        // identityStore's epoch).
        val fetchIdentity = identityStore?.currentIdentity?.value
        // Re-fetch directory-scoped sessions for EVERY known workdir (the
        // persisted recentWorkdirs set + currentWorkdir) so each connected
        // project's sessions reappear after restart. directorySessions is
        // in-memory and otherwise empty until the user re-connects each one;
        // restoring only currentWorkdir lost every other project whose
        // sessions fell outside the global getSessions(limit) first page (the
        // "one of my frequent projects randomly disappeared" bug).
        //
        // §R-19 #2 catch-up contract: this fan-out is what makes the single-SSE
        // model safe for multi-workdir — it populates directorySessions.keys
        // for every recent workdir, which
        // SessionSyncCoordinator.loadPendingQuestionsAllWorkdirs then reads to
        // poll pending questions/permissions for BACKGROUND workdirs (the SSE
        // only feeds currentWorkdir). Skipping this fan-out would silently
        // drop pending questions for any workdir that isn't currently active.
        // R-20 Phase 5: recentWorkdirs is now per-serverGroupFp (was a single
        // global key). The migration in applySavedSettings (cold start) copies
        // the legacy global list to the current fp's slot, so this read sees
        // the right list for the active host. Same-group switches share the
        // list (correct — two entry points to the same server share project
        // memory); 异组 switches get their own list.
        val currentFp = currentServerGroupFp()
        val restoreWorkdirs = (
            settingsManager.getRecentWorkdirs(currentFp) + listOfNotNull(settingsManager.currentWorkdir)
        ).distinct().filter { it.isNotBlank() }
        if (restoreWorkdirs.isNotEmpty()) {
            restoreWorkdirs.forEach { workdir ->
                scope.launch {
                    repository.getSessionsForDirectory(workdir)
                        .onSuccess { sessions ->
                            // Drop stale-host results: a host/profile switch between
                            // dispatch and return would otherwise write the previous
                            // host's sessions into the new host's directorySessions.
                            // CP1: identityStore.isCurrent checks epoch + fp fields.
                            if (fetchIdentity != null && identityStore != null &&
                                !identityStore.isCurrent(fetchIdentity)
                            ) return@launch
                            appendDirectorySessions(workdir, sessions)
                        }
                        .onFailure { error ->
                            // Best-effort restore (mirrors createSessionInWorkdir):
                            // a failed workdir simply stays absent from
                            // directorySessions; the global getSessions list and a
                            // user-initiated refreshDirectorySessions are the
                            // fallbacks. Log for diagnosability without surfacing
                            // a user-facing error.
                            if (fetchIdentity == null || identityStore == null ||
                                identityStore.isCurrent(fetchIdentity)
                            ) {
                                reportNonFatalIssue(TAG, "directory restore failed for $workdir", error)
                            }
                        }
                }
            }
        } else {
            // §Q4-strict-sync (#10 self-heal): when recentWorkdirs is empty
            // (e.g. right after clearAllLocalData / a fresh install), the
            // fan-out above has nothing to iterate. Fall back to a global
            // getSessions probe whose response carries each session's
            // `directory` field — infer the workdir set from it, register
            // each via addRecentWorkdir (so subsequent loads restore them),
            // and fan-out getSessionsForDirectory per workdir. This lets the
            // client self-heal back to the full session set purely from
            // server data after a local-data wipe. Best-effort: failures are
            // swallowed (the global LoadSessions effect above still seeds the
            // top-level sessions list).
            //
            // The entire body is wrapped in try-catch because this scope is
            // NOT a SupervisorJob — an uncaught exception would cancel sibling
            // coroutines (e.g. startSSE's collector). The getSessions relaxed-
            // mock fallback in some test cores throws (see
            // ConnectionCoordinatorTest setUp comment); the try-catch ensures
            // the self-heal is truly best-effort and never tears down the scope.
            scope.launch {
                try {
                    repository.getSessions(MainViewModelTimings.sessionFullLoadLimit)
                        .onSuccess { sessions ->
                            if (fetchIdentity != null && identityStore != null &&
                                !identityStore.isCurrent(fetchIdentity)
                            ) return@launch
                            val fp = currentServerGroupFp()
                            if (fp.isBlank()) return@launch
                            val knownNorm = settingsManager
                                .getRecentWorkdirs(fp)
                                .map { cn.vectory.ocdroid.util.WorkdirPaths.normalize(it) }
                                .toSet()
                            sessions
                                .mapNotNull { it.directory.takeIf { d -> d.isNotBlank() } }
                                .map { cn.vectory.ocdroid.util.WorkdirPaths.normalize(it) to it }
                                .distinctBy { it.first }
                                .filter { (norm, _) -> norm.isNotEmpty() && norm !in knownNorm }
                                .forEach { (_, rawWorkdir) ->
                                    settingsManager.addRecentWorkdir(fp, rawWorkdir)
                                    scope.launch {
                                        try {
                                            repository.getSessionsForDirectory(rawWorkdir)
                                                .onSuccess { dirSessions ->
                                                    if (fetchIdentity != null && identityStore != null &&
                                                        !identityStore.isCurrent(fetchIdentity)
                                                    ) return@launch
                                                    appendDirectorySessions(rawWorkdir, dirSessions)
                                                }
                                                .onFailure { /* best-effort self-heal */ }
                                        } catch (e: Exception) {
                                            // best-effort self-heal — swallow
                                        }
                                    }
                                }
                        }
                        .onFailure { /* best-effort — LoadSessions effect handles the error path */ }
                } catch (e: Exception) {
                    // best-effort self-heal — swallow (scope is non-supervisor)
                }
            }
        }
    }

    /**
     * Guarded single-workdir refresh: fetches directory-scoped sessions for
     * [workdir] and writes them via [appendDirectorySessions]. Mirrors
     * [loadInitialData]'s host-identity guard verbatim — the current identity
     * is captured before the launch, and on return the result is dropped (or
     * the failure logged) unless [ConnectionIdentityStore.isCurrent] still
     * matches, so a mid-flight host/profile switch cannot write the previous
     * host's sessions into the new host's directorySessions.
     *
     * §fix-connect-prefetch (9.5 gate, decision 1b): used by SessionViewModel
     * for both the connect-prefetch path (SessionsScreen directory-picker
     * `onSelect`, immediately after `settingsVM.connectWorkdir`) and the
     * project-row expand path (HomeWorkdirRow onToggleExpand). Pre-fix the
     * SessionViewModel version wrote directorySessions unconditionally — a
     * pre-existing race the connect prefetch would widen.
     */
    fun refreshDirectorySessions(workdir: String) {
        val wd = workdir.trim()
        if (wd.isBlank()) return
        val fetchIdentity = identityStore?.currentIdentity?.value
        scope.launch {
            repository.getSessionsForDirectory(wd)
                .onSuccess { sessions ->
                    if (fetchIdentity != null && identityStore != null &&
                        !identityStore.isCurrent(fetchIdentity)
                    ) return@launch
                    appendDirectorySessions(wd, sessions)
                }
                .onFailure { error ->
                    if (fetchIdentity == null || identityStore == null ||
                        identityStore.isCurrent(fetchIdentity)
                    ) {
                        reportNonFatalIssue(TAG, "refreshDirectorySessions failed for $wd", error)
                    }
                }
        }
    }

    /**
     * Appends a workdir's directory-scoped sessions using a REAL compare-and-set
     * ([MutableStateFlow.update]) on the sessionList slice, so the concurrent
     * fan-out in [loadInitialData] cannot lose entries. This deliberately does
     * NOT rely on the `Dispatchers.Main.immediate` single-thread serialization
     * that the legacy `updateState`/`updateAndSync` path depended on
     * (§R-17 M5.1: that path was a non-atomic read-modify-write safe only
     * because call sites were main-threaded and suspension-free). The fan-out
     * here makes the CAS explicit.
     *
     * §R-17 batch2 (Fixer C): the deprecated AppState mirror write was removed;
     * the sessionList slice is the authoritative read path.
     */
    @Suppress("DEPRECATION")
    private fun appendDirectorySessions(workdir: String, sessions: List<Session>) {
        slices.mutateSessionList { slice ->
            slice.copy(directorySessions = slice.directorySessions + (workdir to sessions))
        }
    }

    /**
     * Best-effort fetch of the server-published slash commands. Merges the
     * server list with a small set of client-side commands (/clear, /compact,
     * /undo, /redo) so the composer's `/`-autocomplete surfaces both. Failures
     * (older servers without GET /command, transient network errors) are
     * swallowed: only the client-side commands remain available.
     */
    private fun loadCommands() {
        scope.launch {
            repository.getCommands()
                .onSuccess { serverCommands ->
                    slices.mutateSettings {
                        it.copy(availableCommands = mergeCommands(localCommands(), serverCommands))
                    }
                }
                .onFailure { error ->
                    reportNonFatalIssue(TAG, "Failed to load commands", error)
                    slices.mutateSettings {
                        it.copy(availableCommands = localCommands())
                    }
                }
        }
    }

    private fun localCommands(): List<CommandInfo> = listOf(
        CommandInfo(name = "clear", description = "Start a new session"),
        CommandInfo(name = "compact", description = "Compact conversation history"),
        CommandInfo(name = "undo", description = "Undo the last change"),
        CommandInfo(name = "redo", description = "Redo the last undone change")
    )

    private fun mergeCommands(
        local: List<CommandInfo>,
        server: List<CommandInfo>
    ): List<CommandInfo> {
        // Server takes precedence on duplicates (its descriptions/hints are
        // authoritative); local commands are appended only when the server did
        // not also expose the same name.
        val serverNames = server.mapTo(mutableSetOf()) { it.name.lowercase(Locale.getDefault()) }
        val localOnly = local.filter { it.name.lowercase(Locale.getDefault()) !in serverNames }
        return server + localOnly
    }

    // ── SSE lifecycle ───────────────────────────────────────────────────────

    /**
     * CP9 switchover: the SSE feed collector has been moved into the
     * Service-owned [cn.vectory.ocdroid.service.streaming.ServiceSseConnectionOwner].
     * This method is preserved as a thin compatibility delegate (VMs,
     * [ControllerEffect.StartSse], and tests expose it; deleting adds
     * rollback churn). It MUST NEVER call `repository.connectSSE` — the
     * atomic capture belongs to the command identity (StartSse), not to a
     * re-read of `SettingsManager.currentWorkdir`.
     *
     * The shared TOFU-frozen guard is preserved verbatim — while a TOFU trust
     * dialog is pending the launcher must NOT be invoked (the resulting
     * Service bootstrap would try the same unpinned TLS handshake and fail
     * the same way; the user's [resolveTofuTrust] unfreezes the retry loop
     * which re-calls startSSE). L4c: the guard now reads
     * [ConnectionHealthProbe.hasPendingTofuDecision] / [pendingTofuHostPort]
     * from [healthProbe] (the single TOFU-state owner post-extraction);
     * behavior is identical.
     *
     * §no-zero-time-gap (FGS spec §1): the Service start is asynchronous,
     * but the start REQUEST is issued synchronously here BEFORE
     * `onSettled(true)` returns in [testConnection] (via the probe's
     * [startSSE] callback). The Service's §5 bootstrap then leads to
     * `StartSse` (the only legal L3→running entry); there is NO terminal
     * connected-without-SSE path.
     */
    fun startSSE() {
        // §tofu R2: FROZEN while a TOFU trust dialog is pending — the SSE
        // feed would try the same unpinned TLS handshake and fail the same
        // way (the pin isn't written until the user decides). Wait for
        // [resolveTofuTrust]; the connect retry loop calls startSSE itself
        // once the pin is in place.
        // CP2 / L4c: TOFU state delegated to [healthProbe]
        // (ConnectionHealthProbe owns the tofu delegate post-extraction).
        if (healthProbe.hasPendingTofuDecision()) {
            DebugLog.i(TAG, "startSSE: frozen — TOFU trust pending for ${healthProbe.pendingTofuHostPort()}")
            return
        }
        val identity = identityStore?.currentIdentity?.value ?: return
        DebugLog.i("SSE", "startSSE → launcher.ensureStarted(identity=${identity.epoch})")
        scope.launch {
            val result = streamingServiceLauncher?.ensureStarted(identity)
            if (result !is OwnershipStartResult.Ready || result.identity != identity) {
                writeConnection {
                    it.copy(
                        isConnected = false,
                        isConnecting = false,
                        connectionPhase = ConnectionPhase.Disconnected,
                    )
                }
            }
        }
    }

    /**
     * §tofu R2: applies the user's TOFU trust decision for the pending
     * endpoint. Called by the UI (via [cn.vectory.ocdroid.ui.ConnectionViewModel])
     * when the user taps Accept once / Trust / Cancel in [cn.vectory.ocdroid.ui.settings.TofuTrustDialog].
     * Completes the deferred the [testConnection] retry loop is awaiting; the
     * loop then writes the pin (Accept/Trust) and re-probes, or settles false
     * (Cancel). No-op when no TOFU prompt is pending.
     *
     * CP2 (notify Phase-0): delegates to [ConnectionBootstrapCoordinator.
     * resolveTofuTrust] (FGS spec §10). L4c: forwards to [healthProbe], which
     * owns the `tofu` delegate. CC's public surface is unchanged —
     * ConnectionViewModel / external callers see the same signature + behavior.
     */
    fun resolveTofuTrust(decision: TofuDecision) {
        healthProbe.resolveTofuTrust(decision)
    }

    /**
     * CP9 §B11: cancels the in-flight SSE feed (foreground ON_STOP /
     * ViewModel onCleared / process teardown). No longer touches a job —
     * routes through [StreamingLifecycleCoordinator.onDisconnect] which the
     * Service observes (the coordinator emits StopSse →
     * [cn.vectory.ocdroid.service.streaming.ServiceSseConnectionOwner].disconnect).
     * Does NOT reset the catch-up state machine — the foreground return path
     * re-arms it.
     *
     * Remains for direct VM / process cleanup callers. The
     * `streamingLifecycleCoordinator` is null in legacy/test construction
     * that doesn't wire it (pre-CP9 build) — the call is a no-op there.
     */
    fun cancelSse() {
        cancelSseInternal(TeardownReason.Disconnect)
    }

    /**
     * §Stage D (gpter 阻塞 #1) + CP9 §B12: tear down any in-flight SSE feed
     * BEFORE the repository is reconfigured for a host / profile switch.
     * CP9: routes through [StreamingLifecycleCoordinator.onDisconnect] (the
     * §4.1 disconnect entry → L3 teardown); the Service observes the
     * teardown commands and disconnects its owner. Without this, the SSE job
     * bound to the PREVIOUS host keeps delivering events into AppState while
     * the new host's health probe is still in flight — those stale events
     * would pollute the freshly-cleared state for the new profile.
     *
     * D3 keeps this as a legacy effect adapter only. It deliberately does not
     * bump epoch or publish HostReconfigured: ConnectionReconfigureBarrier is
     * the sole transaction/epoch owner and performs the repository rebuild
     * only after this lifecycle teardown has joined.
     */
    fun cancelSseForReconfigure() {
        DebugLog.i("SSE", "cancelSse (reconfigure)")
        cancelSseInternal(TeardownReason.Reconfigure)
    }

    /**
     * Cluster 11 (duplication backlog): the shared body of [cancelSse] /
     * [cancelSseForReconfigure]. Closes the token stream for the current
     * session, then tears down the streaming lifecycle with [reason]. The
     * `DebugLog` in [cancelSseForReconfigure] stays at its call site (BEFORE
     * this helper) so the pre-dedup log timing is preserved verbatim;
     * [cancelSse] logs nothing (as before).
     *
     * L4c placement decision: this helper STAYS on the coordinator (it is
     * general teardown, NOT probe-owned — the probe never calls
     * cancelSse / cancelSseForReconfigure; their callers are AppCore's
     * reconfigure barrier + teardown, ConnectionViewModel delegates, tests).
     */
    private fun cancelSseInternal(reason: TeardownReason) {
        // §Stage-D2: close the token stream for the current session (background /
        // ViewModel onCleared / process teardown OR host/profile switch). The
        // coordinator's close(sid) cancels the lifecycle job + clears
        // coordinator-internal state.
        tokenStreamCoordinator?.let { tsc ->
            slices.chat.value.currentSessionId?.let { sid -> tsc.close(sid) }
        }
        scope.launch {
            streamingLifecycleCoordinator?.teardownAndAwait(reason)
        }
    }

    companion object {
        private const val TAG = "ConnectionCoordinator"
    }
}
