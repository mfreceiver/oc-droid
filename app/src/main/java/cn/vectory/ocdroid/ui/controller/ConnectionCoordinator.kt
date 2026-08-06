package cn.vectory.ocdroid.ui.controller

import cn.vectory.ocdroid.data.model.CommandInfo
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.data.repository.ServerCompatProfile
import cn.vectory.ocdroid.service.DegradedBootstrapTerminator
import cn.vectory.ocdroid.service.StreamingOwnershipGate
import cn.vectory.ocdroid.service.streaming.BootstrapRetryPolicy
import cn.vectory.ocdroid.service.streaming.ConnectionBootstrapEngine
import cn.vectory.ocdroid.service.streaming.ServiceSseConnectionOwner
import cn.vectory.ocdroid.service.streaming.SourceActivation
import cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
import cn.vectory.ocdroid.di.AppLifecycleMonitor
import cn.vectory.ocdroid.ui.ConnectionPhase
import cn.vectory.ocdroid.ui.ConnectionState
import cn.vectory.ocdroid.ui.AppAction
import cn.vectory.ocdroid.ui.MainViewModelTimings
import cn.vectory.ocdroid.ui.SharedEffectBus
import cn.vectory.ocdroid.ui.SliceFlows
import cn.vectory.ocdroid.ui.launchLoadProviders
import cn.vectory.ocdroid.ui.reportNonFatalIssue
import cn.vectory.ocdroid.util.DebugLog
import cn.vectory.ocdroid.util.SettingsManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * R-16 M4 → R-17 batch3b: owns the server connection lifecycle — health-check
 * probe with exponential-backoff retry, the 30s health-check throttle,
 * initial-data load orchestration on a healthy connect.
 *
 * **L4c (Wave ζ): the health-probe concern has been EXTRACTED into
 * [ConnectionHealthProbe]** ([healthProbe]). The multi-state connect flow
 * (`testConnection` / `testConnectionWithEngine` / `coldStartReconnect`)
 * and the foreground-monitor `init` hook now live there verbatim. This
 * coordinator keeps thin public delegates
 * ([testConnection] / [coldStartReconnect]) so every existing call site
 * (ConnectionViewModel / AppCore / ChatViewModel / tests) resolves
 * unchanged. The probe calls back into [loadInitialData] /
 * [startSSE] (both public, both with external callers — they could not move).
 * Extraction is behavior-preserving: identical state-machine transitions,
 * SSE lifecycle timing, and `onSettled` exactly-once contract.
 *
 * §review-blocker-#4 (L7 TOFU removal): the pre-L7 TOFU trust-decision
 * machinery (`hasPendingTofuDecision` / `resolveTofuTrust` /
 * `pendingTofuHostPort` / `promoteDegradedTofuIfNeeded`) was DELETED in L7.
 * The historical extraction notes below are kept for archaeology but the
 * TOFU-specific symbols they referenced no longer exist; trust is now
 * per-server via [cn.vectory.ocdroid.data.repository.http.SslConfig] (SystemDefault /
 * MutualTLS / TrustAll), resolved purely from the active HostProfile at
 * configure time.
 *
 * **CP9 switchover**: the SSE feed ownership (sseJob + launchSseCollection)
 * has been DELETED from this coordinator and moved into the Service-owned
 * [cn.vectory.ocdroid.service.streaming.ServiceSseConnectionOwner]. The
 * thin [startSSE] delegate is preserved (ConnectionViewModel /
 * ControllerEffect.StartSse / tests expose it; deleting adds rollback churn)
     * — it now calls sseOwner.connect() so a successful
 * foreground health probe synchronously requests the Service before
 * reporting success. The no-zero-time-gap guarantee (FGS spec §1) is
 * preserved: there is NO terminal connected-without-SSE path.
 *
 * `cancelSse` / `cancelSseForReconfigure` remain as lifecycle-teardown
 * delegates (still exposed for direct VM/process cleanup callers). They
 * route through [sseOwner.disconnect] + [ownershipGate.disconnectAndRelease].
 * Cluster 11 (duplication backlog): the two are now deduped through [cancelSseInternal].
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
     *  - `startSSE()` — thin delegate to sseOwner.connect(); the L7 TOFU
     *    removal eliminated the pre-L7 TOFU-frozen guard (the symbols it
     *    consulted, `hasPendingTofuDecision` / `pendingTofuHostPort`, no
     *    longer exist). See [UnexpectedTransportDropHandler] kdoc for the
     *    current teardown linearization contract.
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
     * `@Named("currentProfileId")` provider every other controller uses
     * (ControllerModule.provideCurrentServerGroupFp) — single source of truth
     * so a profile switch races the same fp read as everyone else.
     */
    private val currentProfileId: () -> String = { "" },
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
     * L1 FGS commit 1: the new SSE owner host. Replaces
     * [streamingServiceLauncher] as the call target for [startSSE] (the old
     * param stays unused for now — removed in Commit 2).
     */
    private val sseOwner: ServiceSseConnectionOwner,
    /**
     * L1 FGS commit 1: the streaming ownership gate. Used in conjunction with
     * [sseOwner.disconnect] for the fg/bg source switch and teardowns.
     */
    private val ownershipGate: StreamingOwnershipGate,
    private val connectionBootstrapEngine: ConnectionBootstrapEngine? = null,
    private val bootstrapRetryPolicy: BootstrapRetryPolicy = BootstrapRetryPolicy(),
    private val appLifecycleMonitor: AppLifecycleMonitor? = null,
    private val degradedBootstrapTerminator: DegradedBootstrapTerminator? = null,
    /**
     * §Stage-D2 §5.8/§5.9: the token-stream coordinator. CC hooks
     * [TokenStreamCoordinator.close] on [cancelSse] (background / ViewModel
     * onCleared) and [cancelSseForReconfigure] (host / profile switch).
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
    /**
     * lite-v2 D-barrier-fixup + lane A ABA fix: stores the no-source teardown
     * Job from cancelSseForReconfigure so coldStartReconnect can await it before
     * probing.
     *
     * **Job chaining (thread-safe)**: each new `cancelSseForReconfigure` joins
     * the previous job first (`prev?.join()`) — serializes teardowns transitively.
     * The read-modify-write of this field is protected by [reconfigureLock]
     * (`synchronized`) so concurrent callers from multiple threads cannot both
     * read `null` and launch parallel teardowns (the @Volatile-only race).
     *
     * **Identity-guarded loop join**: `coldStartReconnect` enters a while loop
     * that repeatedly reads this field under [reconfigureLock], joins the job,
     * then loops to check for new jobs that were registered during the join.
     * The atomic read-and-clear inside the lock eliminates the TOCTOU between
     * the identity check and the field nullification that a concurrent
     * `cancelSseForReconfigure` could otherwise exploit.
     *
     * Locking note: both [cancelSseForReconfigure] and [coldStartReconnect] use
     * the same [reconfigureLock]. [synchronized] is safe here because the
     * critical sections are brief (field read/write + `scope.async` which is
     * non-blocking and returns immediately). On `Dispatchers.Main.immediate`
     * (production dispatcher) the coroutine body from `scope.async` is
     * scheduled on the main queue — it does NOT run inside the lock.
     *
     * **Result-aware barrier (rev-gpt R2 fix):** [pendingReconfigureTeardown] is
     * typed as [Deferred]&lt;[Result]&lt;[Unit]&gt;&gt; so the caller can distinguish
     * successful teardown from exceptional teardown. Each body step (token-stream
     * close / lifecycle teardown) catches non-[CancellationException] errors
     * independently — a close failure NEVER skips the lifecycle teardown. The
     * deferred completes with [Result.success] iff both steps succeed.
     * [coldStartReconnect] joins the tail: on success it clears the field and
     * probes; on failure it clears the field, logs a diagnostic, and does NOT
     * probe (no bootstrap on a corrupted teardown). A newer successful full
     * teardown (chained via prev?.await()) recovers the barrier.
     */
    private val reconfigureLock = Any()
    @Volatile
    private var pendingReconfigureTeardown: Deferred<Result<Unit>>? = null

    /**
     * §需求13 rev-7 #1 / rev-8 #1+#2: single-flight guard for the
     * LoadProviders emit in [loadInitialData]. Closes the check-then-act
     * race where multiple concurrent loadInitialData() calls at cold start
     * (MainActivity.coldStartReconnect + ON_RESUME + health-probe recovery
     * all firing before the first /config/providers fetch completes) each
     * saw `providers == null` and each emitted LoadProviders → duplicate
     * parallel fetches. The [compareAndSet] in loadInitialData arms the gate
     * exactly once per successful fetch (the gate arms via CAS on the first providers==null observation; it disarms ONLY on real fetch failure so the next loadInitialData can retry — see rev-8 #2 below).
     *
     * rev-8 #1: the gate is NOT re-armed at the top of [coldStartReconnect]
     * — that method is a SHARED entry point (MainActivity cold-start,
     * SessionsScreen force-refresh, resetLocalDataAndResync), so resetting it
     * there re-opened the race during a normal cold start while the first
     * fetch was still in flight (the original rev-7 bug). See the
     * PROCESS-LIFETIME comment at the top of [coldStartReconnect].
     *
     * rev-8 #2 (council #2 fix): on REAL fetch failure the gate is DISARMED
     * via [resetProvidersFirstFetchGate] (called by launchLoadProviders'
     * onFailure callback) so the next loadInitialData / ON_RESUME auto-retries
     * — weak-network cold-start recovery. After a hard local reset (rare,
     * destructive; HostProfileController.resetLocalDataAndResync nulls
     * providers) the auto-fetch stays suppressed and the user taps the Model
     * management refresh IconButton once (that path emits LoadProviders
     * DIRECTLY, bypassing this gate).
     */
    private val providersFirstFetchArmed = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * §需求13 rev-8 #2 (council #2 fix): disarms the single-flight gate so the
     * next [loadInitialData] auto-retries the providers fetch. Called by
     * [launchLoadProviders]' onFailure callback (wired in AppCore) when
     * getProvidersOrFailure returns a REAL failure (network/HTTP/parse) — the
     * weak-network cold-start recovery path. Idempotent + thread-safe (AtomicBoolean).
     */
    internal fun resetProvidersFirstFetchGate() {
        providersFirstFetchArmed.set(false)
    }

    /**
     * @VisibleForTesting: seam fired inside [reconfigureLock] just before
     * the handoff-probe invocation. Tests use this to deterministically
     * trigger a REAL second thread to call [cancelSseForReconfigure] while
     * the lock is held — proving the concurrent call is blocked until the
     * probe completes and the lock is released. This is the ONLY way to
     * reproduce the lock-contention window without fragile sleeps or
     * private-field reflection.
     *
     * Unlike the previous [onBeforeHandoffRecheck] callback, this seam does
     * NOT itself call [cancelSseForReconfigure] (that would be reentrant
     * synchronized and would NOT test real thread competition).
     */
    internal var onHandoffProbeAboutToRun: (() -> Unit)? = null

    /**
     * @VisibleForTesting: fired at the probe coroutine's FIRST instruction
     * (after [scope.launch] enqueues it), BEFORE the clear→probe recheck. This
     * is AFTER coldStartReconnect has released [reconfigureLock] — exactly the
     * window the old inside-lock handoff seam ([onHandoffProbeAboutToRun])
     * could not capture. Tests park the probe here to deterministically
     * register a teardown via [cancelSseForReconfigure] and prove the recheck
     * joins it. Forwarded to the probe via its constructor hook.
     */
    internal var onProbeCoroutineStarted: (() -> Unit)? = null

    private val healthProbe = ConnectionHealthProbe(
        scope = scope,
        slices = slices,
        repository = repository,
        settingsManager = settingsManager,
        effects = effects,
        serverCompatProfile = serverCompatProfile,
        currentProfileId = currentProfileId,
        clock = clock,
        identityStore = identityStore,
        connectionBootstrapEngine = connectionBootstrapEngine,
        bootstrapRetryPolicy = bootstrapRetryPolicy,
        connectSseAndAwait = ::connectSseAndAwait,
        degradedBootstrapTerminator = degradedBootstrapTerminator,
        appLifecycleMonitor = appLifecycleMonitor,
        loadInitialData = ::loadInitialData,
        startSSE = ::startSSE,
        effectiveConnectionConfigResolver = effectiveConnectionConfigResolver,
        awaitPendingReconfigureTeardown = ::awaitPendingReconfigureTeardown,
        onProbeCoroutineStartedHook = { onProbeCoroutineStarted?.invoke() },
    )

    /**
     * rev-ogpt B (Disconnected 周期重探): resident supervisor that self-heals
     * the connection banner's REST_OUTAGE dead-lock after transient network
     * blips. Only constructed when BOTH [appLifecycleMonitor] AND
     * [identityStore] are non-null (production wiring). The legacy test fixture
     * leaves both null → this field is null → no reprobe logic runs → existing
     * tests are untouched (zero-regression gate).
     */
    private val reprobeController: ConnectionReprobeController? =
        if (appLifecycleMonitor != null && identityStore != null) {
            ConnectionReprobeController(
                scope = scope,
                connectionFlow = slices.connection,
                isInForeground = appLifecycleMonitor!!.isInForeground,
                currentEpoch = { identityStore!!.currentEpoch() },
                probe = { onSettled ->
                    healthProbe.testConnection(force = true, retries = 0, onSettled = onSettled)
                },
            ).also { it.start() }
        } else null

    /**
     * fg/bg source switch. When the app goes to background, disconnect the SSE
     * transport (the foreground-return path re-probes and re-connects via the
     * health probe). Launched on the init scope — never runs during construction.
     *
     * Phase 1 (后台驻留移除): the [ProcessStatusPoller.ensureRunning]
     * background-polling start that USED to fire here was removed — background
     * is now completely silent (0 polling). The SSE disconnect stays so a
     * backgrounded app does not hold an open socket. Foreground return re-arms
     * SSE via the health probe's foreground monitor.
     */
    init {
        val monitor = appLifecycleMonitor
        if (monitor != null) {
            scope.launch {
                // StateFlow has operator fusion — distinctUntilChanged is
                // already built-in. Use drop(1) to skip the initial value.
                monitor.isInForeground
                    .drop(1)
                    .collect { inForeground ->
                        if (!inForeground) {
                            sseOwner.disconnect(markGap = true)
                            ownershipGate.disconnectAndRelease(markGap = true)
                        }
                        // On →foreground: no-op — probe's foreground monitor
                        // re-probes and re-connects via startSSE.
                    }
            }
        }
    }

    /**
     * L1 FGS commit 3: streaming lifecycle coordinator deleted.
     * diagLayer is no longer available — the FGS layer diagnostic is
     * permanently removed. Returns null.
     */
    val diagLayer: String? get() = null

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
     * lite-v2 D-barrier-fixup: if a no-source teardown (from
     * [cancelSseForReconfigure]) is pending, awaits it before probing
     * (serializes teardown→bootstrap, equivalent to the old barrier).
     * Otherwise directly delegates to [healthProbe].
     *
     * L4c: thin delegate to [ConnectionHealthProbe.coldStartReconnect]. The
     * TOFU-frozen guard + `testConnection(force=true, retries=3)` semantics
     * are preserved verbatim. Callers (MainActivity cold-start LaunchedEffect,
     * [resetLocalDataAndResync], SessionsScreen onRefresh via
     * `ConnectionViewModel.coldStartReconnect()`) see no change.
     */
    fun coldStartReconnect() {
        // §需求13 rev-8 #1: NO re-arm here. coldStartReconnect() is a SHARED
        // entry point (MainActivity cold-start LaunchedEffect,
        // SessionsScreen force-refresh, resetLocalDataAndResync) — resetting
        // providersFirstFetchArmed here would re-open the single-flight gate
        // during a normal cold start / force-refresh while the first fetch is
        // still in flight → duplicate LoadProviders (rev-7's original bug).
        // The gate is now PROCESS-LIFETIME for the auto path: armed exactly
        // once via the CAS in loadInitialData; reset ONLY by
        // [resetProvidersFirstFetchGate] on real fetch failure (rev-8 #2
        // recovery) — never reset by coldStartReconnect itself. After a hard
        // local reset (rare, destructive) the auto-fetch stays suppressed and
        // the user taps the Model management refresh IconButton once — that
        // path (ComposerViewModel/HostViewModel.refreshProviders) emits
        // LoadProviders DIRECTLY, bypassing this gate, so it always works.
        scope.launch {
            // Result-aware barrier loop: joins the pending teardown [Deferred]
            // and checks its [Result]:
            //
            //   - **null** (no pending teardown): break to handoff re-check.
            //   - **Result.success**: identity-guarded clear, break to handoff
            //     re-check (or loop back if new teardown arrived during window).
            //   - **Result.failure**: identity-guarded clear, log diagnostic,
            //     return WITHOUT probing (no bootstrap on a corrupted teardown).
            //     A subsequent cancelSseForReconfigure → coldStart cycle (or an
            //     external reconnect trigger) recovers.
            //   - **identity mismatch** (concurrent cancelSseForReconfigure
            //     fired during await): loop back to read and await the new
            //     deferred.
            //
            // Non-[CancellationException] is never thrown from await() because
            // every teardown body wraps its steps in try-catch and completes
            // the deferred with [Result]. [CancellationException] propagates.
            //
            // ## Atomic handoff (Item ① fix)
            //
            // The handoff (final pending==null check + probe invocation) is
            // performed INSIDE [reconfigureLock]. A concurrent
            // [cancelSseForReconfigure] that arrives:
            //   (a) before the check → caught by the != null branch → loops back.
            //   (b) during the probe → blocked by [synchronized] until the
            //       probe returns (probe is non-suspend and short). No race
            //       window exists.
            outer@ while (true) {
                var shouldProbe = true
                inner@ while (true) {
                    val d: Deferred<Result<Unit>>?
                    synchronized(reconfigureLock) {
                        d = pendingReconfigureTeardown
                    }
                    if (d == null) break@inner
                    val result = d.await()
                    synchronized(reconfigureLock) {
                        if (pendingReconfigureTeardown === d) {
                            result
                                .onSuccess { pendingReconfigureTeardown = null }
                                .onFailure { e ->
                                    pendingReconfigureTeardown = null
                                    DebugLog.w(
                                        TAG,
                                        "coldStartReconnect: teardown failed (${e.message}), skipping probe",
                                    )
                                    shouldProbe = false
                                }
                            break@inner
                        }
                        // identity mismatch → loop back to @inner
                    }
                }

                if (!shouldProbe) return@launch

                // Atomic handoff: re-check AND probe under reconfigureLock.
                synchronized(reconfigureLock) {
                    if (pendingReconfigureTeardown != null) {
                        // A concurrent cancel registered during the join
                        // window — loop back to join it.
                        continue@outer
                    }
                    // @VisibleForTesting: test seam fires inside the lock,
                    // just before the probe. A concurrent thread calling
                    // cancelSseForReconfigure is blocked by this very
                    // synchronized block — cannot register until the probe
                    // returns and the lock is released.
                    onHandoffProbeAboutToRun?.invoke()
                    healthProbe.coldStartReconnect()
                    return@launch
                }
            }
        }
    }

    /**
     * lite-v2 D-fixup-r4 (Item ① close): re-check + join any teardown
     * registered in the clear→probe window. Called by the probe coroutine
     * ([ConnectionHealthProbe.testConnection]) at its FIRST instruction,
     * BEFORE checkHealth/SSE start.
     *
     * **Why this exists**: [coldStartReconnect] performs the handoff inside
     * [reconfigureLock], but `healthProbe.coldStartReconnect()` →
     * `testConnection()` → `scope.launch{}` returns ASYNCHRONOUSLY — the lock
     * is released before the probe coroutine runs. A concurrent
     * [cancelSseForReconfigure] arriving in that window registers a teardown
     * the probe would otherwise bypass (clear→probe race). This method closes
     * that window: the probe coroutine, once dispatched, re-acquires the lock,
     * re-checks [pendingReconfigureTeardown], and joins any teardown that
     * appeared.
     *
     * Mirrors [coldStartReconnect]'s identity-guarded join loop. Returns true
     * if the probe may proceed (no pending teardown, or it succeeded); false
     * if a pending teardown FAILED — the probe should abort, mirroring
     * coldStart's "skip probe on corrupted teardown". The field is consumed
     * (nulled) in both cases so a subsequent coldStart sees a clean slate.
     */
    private suspend fun awaitPendingReconfigureTeardown(): Boolean {
        while (true) {
            val d: Deferred<Result<Unit>>?
            synchronized(reconfigureLock) { d = pendingReconfigureTeardown }
            if (d == null) return true
            val result = d.await()
            synchronized(reconfigureLock) {
                if (pendingReconfigureTeardown === d) {
                    pendingReconfigureTeardown = null
                    return result.isSuccess.also { ok ->
                        if (!ok) DebugLog.w(TAG, "probe-entry recheck: pending teardown failed, aborting probe")
                    }
                }
            }
            // identity mismatch → a newer teardown arrived during await; loop
        }
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
        // Cross-domain fan-out: orchestrator owns these implementations.
        // §R18 Phase 3 Wave 1 (P1-3 C 类): loadInitialData 五连发顺序敏感 → 保持同步 tryEmitEffect (scope.launch 包裹会破坏顺序)。
        effects.tryEmitEffect(ControllerEffect.LoadSessions)
        effects.tryEmitEffect(ControllerEffect.LoadAgents)
        // §需求13: do NOT proactively fetch the model catalog on every
        // soft-refresh / health-probe recovery / ON_RESUME. Only fetch on the
        // TRUE first launch (providers == null) so the Model management
        // section isn't empty before the user ever taps the manual refresh
        // icon. Subsequent refreshes are user-driven via the Model management
        // refresh IconButton. All 4 proactive auto-paths (ON_RESUME, sessions
        // refresh button, ServerStatus force-refresh chain, health-probe
        // recovery) route through this fan-out, so gating here blocks them
        // all without per-button edits. `slices` is this controller's private
        // SliceFlows field (same accessor as the nearby `slices.chat.value`
        // reads + `slices.settings.value` projections).
        //
        // §需求13 rev-7 #1 / rev-8 #1+#2: atomic single-flight —
        // [providersFirstFetchArmed].compareAndSet closes the check-then-act race
        // where multiple concurrent loadInitialData() calls at cold start ALL saw
        // providers==null and each emitted LoadProviders. CAS arms the gate exactly
        // once per successful fetch (disarmed on real failure for retry — see
        // rev-8 #2). rev-8 #1: the gate is NOT re-armed at the top
        // of [coldStartReconnect] (shared entry point — see the PROCESS-LIFETIME
        // comment there). rev-8 #2: on real fetch failure the gate is DISARMED via
        // [resetProvidersFirstFetchGate] (launchLoadProviders onFailure callback)
        // so the next loadInitialData / ON_RESUME auto-retries (weak-network
        // recovery). A normal cross-host switch does NOT null providers (the
        // reducer only clears availableCommands — see reduceHostStatePurged) so the
        // gate stays armed and no re-fetch fires on switch (correct).
        //
        // rev-8 #2b (rev-gpt finding): the `!isLoadingProviders` guard closes the
        // window where a failure-disarmed gate + an in-flight manual refresh
        // (refreshProviders emits LoadProviders DIRECTLY, bypassing this CAS)
        // would let a concurrent loadInitialData emit a SECOND LoadProviders →
        // duplicate parallel /config/providers fetches. The flag is set
        // synchronously by launchLoadProviders, so once a fetch is in flight
        // the auto path skips re-emit and lets the in-flight request resolve it.
        if (slices.settings.value.providers == null &&
            !slices.settings.value.isLoadingProviders &&
            providersFirstFetchArmed.compareAndSet(false, true)) {
            effects.tryEmitEffect(ControllerEffect.LoadProviders)
        }
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
        val currentFp = currentProfileId()
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
                            if (fetchIdentity != null &&
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
                            if (fetchIdentity == null ||
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
                            if (fetchIdentity != null &&
                                !identityStore.isCurrent(fetchIdentity)
                            ) return@launch
                            val fp = currentProfileId()
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
                                                    if (fetchIdentity != null &&
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
                    if (fetchIdentity != null &&
                        !identityStore.isCurrent(fetchIdentity)
                    ) return@launch
                    appendDirectorySessions(wd, sessions)
                }
                .onFailure { error ->
                    if (fetchIdentity == null ||
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

    /**
     * L1 FGS commit 3: internal seam for [ConnectionHealthProbe] to
     * call the owner directly. Returns [SourceActivation]. sseOwner is
     * non-nullable (wired by ControllerModule).
     */
    internal suspend fun connectSseAndAwait(
        identity: cn.vectory.ocdroid.service.identity.ConnectionIdentity,
    ): SourceActivation {
        return sseOwner.connect(identity)
    }

    // ── SSE lifecycle ───────────────────────────────────────────────────────

    /**
     * L1 FGS commit 2: thin delegate that calls sseOwner.connect() directly
     * (the old Service launcher path was removed in Commit 2).
     * Preserved as a compatibility delegate (VMs, [ControllerEffect.StartSse],
     * and tests expose it; deleting adds rollback churn).
     *
     * §review-blocker-#4 (L7 TOFU removal): the pre-L7 "TOFU-frozen guard"
     * and the `resolveTofuTrust` / `hasPendingTofuDecision` /
     * `pendingTofuHostPort` symbols it consulted were DELETED in L7. TLS
     * trust is now resolved purely from the active HostProfile's
     * [cn.vectory.ocdroid.data.repository.http.SslConfig] at configure time
     * (SystemDefault / MutualTLS / TrustAll); there is no runtime trust
     * dialog to freeze on. See [UnexpectedTransportDropHandler] kdoc for the
     * current teardown linearization contract.
     */
    fun startSSE() {
        val identity = identityStore?.currentIdentity?.value ?: return
        DebugLog.i("SSE", "startSSE → sseOwner.connect(identity=${identity.epoch})")
        scope.launch {
            // L1 FGS commit 3: sseOwner.connect is the sole path (launcher + 
            // coordinator removed). Non-nullable in production.
            val activation = sseOwner.connect(identity)
            // §sse-zombie-fix (v3 Bug B / Kimi N1): the await below may
            // resolve long after the user switched host. Re-check identity
            // currency BEFORE writing.
            if (identityStore?.isCurrent(identity) == false) {
                DebugLog.i("SSE", "startSSE: identity no longer current — drop stale result")
                return@launch
            }
            when (activation) {
                is SourceActivation.Ready -> {
                    DebugLog.i("SSE", "startSSE: sseOwner.connect returned Ready")
                }
                is SourceActivation.Rejected.StaleIdentity -> {
                    DebugLog.i("SSE", "startSSE: sseOwner.connect returned StaleIdentity — drop")
                }
                is SourceActivation.Rejected.TofuPending -> {
                    DebugLog.i("SSE", "startSSE: sseOwner.connect returned TofuPending — no-op")
                }
                is SourceActivation.Rejected.Superseded -> {
                    DebugLog.i("SSE", "startSSE: sseOwner.connect returned Superseded — no-op")
                }
                is SourceActivation.Rejected.TransportTimeout,
                is SourceActivation.Rejected.Exhausted -> {
                    writeConnection {
                        it.copy(
                            isConnected = true,
                            isConnecting = false,
                            connectionPhase = ConnectionPhase.SseBootstrapFailed,
                            authFailureReason = null,
                        )
                    }
                }
            }
        }
    }

    /**
     * L1 FGS commit 3: cancels the in-flight SSE feed (foreground ON_STOP /
     * ViewModel onCleared / process teardown). Uses the generic Disconnect
     * path with markGap=true so the catch-up state machine records the gap
     * and [ForegroundCatchUpController] refreshes on reconnect — NOT the
     * no-source teardown used by [cancelSseForReconfigure].
     *
     * Routes through [sseOwner.disconnect] + [ownershipGate.disconnectAndRelease].
     * Does NOT reset the catch-up state machine — the foreground return path
     * re-arms it.
     */
    fun cancelSse() {
        cancelSseInternal()
    }

    /**
     * lite-v2 D-barrier-fixup: no-source teardown (markGap=false — no gap is
     * marked because a reconfigure immediately re-connects). Stores the
     * pending teardown Job so [coldStartReconnect] can await it before probing
     * (serializes teardown→bootstrap, equivalent to the old
     * [ConnectionReconfigureBarrier] serialization).
     *
     * [cancelSse] (generic Disconnect path) is UNCHANGED — it still uses
     * markGap=true so the catch-up controller records the gap.
     */
    fun cancelSseForReconfigure() {
        DebugLog.i("SSE", "cancelSse (reconfigure, no-source)")
        // Capture sid before the lock; close will run inside the chained
        // Deferred body so there is no window between side effect start and
        // registration — coldStartReconnect always sees a non-null pending
        // teardown and joins it before probing.
        val currentSid = slices.chat.value.currentSessionId
        // Atomic create-and-register: the LAZY Deferred body
        // (prev?.await() → token close → lifecycle teardown → Result<Unit>)
        // does NOT start until [deferred.start()] is called outside the lock,
        // so the assignment is visible before any teardown side effect begins.
        //
        // Result-aware barrier: each step (close / lifecycle) independently
        // catches non-[CancellationException] exceptions — a close failure
        // NEVER skips the lifecycle teardown. The deferred completes with
        // [Result.success] iff both steps succeed.
        val deferred = synchronized(reconfigureLock) {
            val prev = pendingReconfigureTeardown
            val newDef = scope.async(start = CoroutineStart.LAZY) {
                // Serialization only: await the previous teardown (if any)
                // before starting this one. The prev's result does NOT gate
                // this node's execution — every node always runs its full
                // close + lifecycle.
                prev?.await()
                var allOk = true

                // Step 1: token-stream close — errors are captured, lifecycle
                // continues regardless. CancellationException propagates.
                try {
                    tokenStreamCoordinator?.let { tsc ->
                        currentSid?.let { sid -> tsc.close(sid) }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DebugLog.e(TAG, "cancelSseForReconfigure: close failed", e)
                    allOk = false
                }

                // Step 2: lifecycle teardown — errors are captured.
                // CancellationException propagates.
                // L1 FGS commit 3: sseOwner.disconnect + ownershipGate.disconnectAndRelease
                // is the sole path (coordinator fallback deleted).
                try {
                    sseOwner.disconnect(markGap = false)
                    ownershipGate.disconnectAndRelease(markGap = false)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DebugLog.e(TAG, "cancelSseForReconfigure: teardown failed", e)
                    allOk = false
                }

                if (allOk) Result.success(Unit) else Result.failure(TeardownException("one or more steps failed"))
            }
            pendingReconfigureTeardown = newDef
            newDef
        }
        // Start outside the lock — the field is already set so concurrent
        // coldStartReconnect / cancelSseForReconfigure see the Deferred
        // immediately.
        deferred.start()
    }

    /**
     * Generic teardown shared body used by [cancelSse] only. Closes the token
     * stream for the current session, then tears down via
     * [sseOwner.disconnect] + [ownershipGate.disconnectAndRelease] with
     * markGap=true so the catch-up controller records the gap.
     */
    private fun cancelSseInternal() {
        // §Stage-D2: close the token stream for the current session (background /
        // ViewModel onCleared / process teardown OR host/profile switch).
        tokenStreamCoordinator?.let { tsc ->
            slices.chat.value.currentSessionId?.let { sid -> tsc.close(sid) }
        }
        // L1 FGS commit 3: sseOwner.disconnect + ownershipGate.disconnectAndRelease
        // is the sole path — coordinator fallback deleted.
        scope.launch {
            sseOwner.disconnect(markGap = true)
            ownershipGate.disconnectAndRelease(markGap = true)
        }
    }

    internal class TeardownException(message: String, cause: Throwable? = null) : Exception(message, cause)

    companion object {
        private const val TAG = "ConnectionCoordinator"
    }
}
