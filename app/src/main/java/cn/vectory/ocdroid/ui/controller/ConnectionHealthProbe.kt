package cn.vectory.ocdroid.ui.controller

import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.data.repository.ServerCompatProfile
import cn.vectory.ocdroid.data.repository.http.AuthFailureReason
import cn.vectory.ocdroid.data.repository.http.classifyAuthFailure
import cn.vectory.ocdroid.data.repository.http.hostPortFromUrl
import cn.vectory.ocdroid.service.DegradedBootstrapTerminator
import cn.vectory.ocdroid.service.OwnershipStartResult
import cn.vectory.ocdroid.service.streaming.BootstrapRetryPolicy
import cn.vectory.ocdroid.service.streaming.ConnectionBootstrapEngine
import cn.vectory.ocdroid.service.streaming.ConnectionBootstrapOutcome
import cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
import cn.vectory.ocdroid.di.AppLifecycleMonitor
import cn.vectory.ocdroid.ui.ConnectionPhase
import cn.vectory.ocdroid.ui.ConnectionState
import cn.vectory.ocdroid.ui.SharedEffectBus
import cn.vectory.ocdroid.ui.SliceFlows
import cn.vectory.ocdroid.ui.UiEvent
import cn.vectory.ocdroid.ui.errorMessageOrFallback
import cn.vectory.ocdroid.util.DebugLog
import cn.vectory.ocdroid.util.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch


/**
 * L4c (Wave ζ): the connection health-probe concern extracted verbatim from
 * [ConnectionCoordinator]. Owns the multi-state connect flow — health-check
 * probe with exponential-backoff retry, the 30s health-check throttle,
 * and the engine-driven bootstrap path ([testConnectionWithEngine]).
 *
 * **Behavior-preserving extraction.** Every state-machine transition,
 * `writeConnection` ordering, SSL resolution sequence
 * ([OpenCodeRepository.captureServerCert]), SSE-test connect timing
 * (`startSSE` callback), `onSettled` exactly-once contract, and log string
 * is byte-identical to the pre-extraction coordinator. The `TAG` is
 * intentionally still `"ConnectionCoordinator"` so logcat filters/greps that
 * keyed on the old tag keep resolving.
 *
 * §review-blocker-#4 (L7 TOFU removal): the pre-L7 TOFU trust machinery
 * (`applyTofuDecision`, `promoteDegradedTofuIfNeeded`,
 * `hasPendingTofuDecision`) was DELETED in L7. Trust is resolved purely from
 * the active HostProfile's [cn.vectory.ocdroid.data.repository.http.SslConfig]
 * at configure time. The historical extraction notes that referenced those
 * symbols are updated below; `captureServerCert` is retained only as the
 * mTLS client-cert probe for the host:port authority.
 *
 * **Extraction boundary:**
 *  - Probe entry points ([testConnection] / [coldStartReconnect]) +
 *    [testConnectionWithEngine] (private) + the foreground-monitor `init`
 *    hook live HERE.
 *  - [ConnectionCoordinator] keeps thin public delegates
 *    ([ConnectionCoordinator.testConnection] /
 *    [ConnectionCoordinator.coldStartReconnect]) so all existing call sites
 *    resolve unchanged, plus the operations the probe calls back into
 *    ([ConnectionCoordinator.loadInitialData] /
 *    [ConnectionCoordinator.startSSE] — both public, both with external
 *    callers, so they could not move).
 *  - [ConnectionCoordinator.startSSE] stays on the coordinator (it is the
 *    CP9 `ensureStarted` adapter). §review-blocker-#4 (L7): the pre-L7
 *    TOFU-frozen guard (`hasPendingTofuDecision` / `pendingTofuHostPort`)
 *    was DELETED in L7 — TLS trust resolves from the HostProfile's
 *    [cn.vectory.ocdroid.data.repository.http.SslConfig] at configure time.
 *
 * **No new subpackages, no public-API change.** `internal` visibility; same
 * package `cn.vectory.ocdroid.ui.controller`.
 */
@Suppress("DEPRECATION")
internal class ConnectionHealthProbe(
    private val scope: CoroutineScope,
    private val slices: SliceFlows,
    private val repository: OpenCodeRepository,
    private val settingsManager: SettingsManager,
    private val effects: SharedEffectBus,
    private val serverCompatProfile: ServerCompatProfile,
    private val currentProfileId: () -> String,
    private val clock: () -> Long,
    private val identityStore: ConnectionIdentityStore?,
    private val connectionBootstrapEngine: ConnectionBootstrapEngine?,
    private val bootstrapRetryPolicy: BootstrapRetryPolicy,
    /**
     * L1 FGS commit 1: replaces the old [StreamingServiceLauncher] for the
     * probe's engine path. The probe calls this lambda to directly connect
     * the SSE owner instead of going through the Service launcher.
     *
     * Default `{ null }` preserves legacy/test construction.
     */
    private val connectSseAndAwait: suspend (cn.vectory.ocdroid.service.identity.ConnectionIdentity) -> cn.vectory.ocdroid.service.streaming.SourceActivation? = { _ -> null },
    private val degradedBootstrapTerminator: DegradedBootstrapTerminator?,
    private val appLifecycleMonitor: AppLifecycleMonitor?,
    // Callbacks back into [ConnectionCoordinator] for operations that remain
    // there (both are public with external callers, so they could not move).
    // Named to match the original call sites so the relocated probe bodies
    // are byte-identical to the pre-extraction coordinator.
    private val loadInitialData: () -> Unit,
    private val startSSE: () -> Unit,
    /**
     * §resolver-single-source-of-truth (RESOLVER lane ②): the authority for the
     * effective connection URL. The legacy probe used to read
     * `settingsManager.serverUrl` directly for (a) the identity `endpointFp`
     * bind on a healthy connect and (b) the TOFU host:port + captureServerCert
     * baseUrl. Both now go through `resolve()?.url` so identity + TOFU move
     * lockstep with the URL the engine/configure path used. `null` for
     * legacy/test construction that doesn't wire the resolver — the probe then
     * treats a null resolve() as an explicit fail (no stale fallback), see the
     * two call sites below.
     */
    private val effectiveConnectionConfigResolver: cn.vectory.ocdroid.service.streaming.EffectiveConnectionConfigResolver? = null,
    /**
     * lite-v2 D-fixup-r4 (Item ① close): re-check + join any teardown
     * registered in the clear→probe window. Invoked at the probe coroutine's
     * FIRST instruction (after launch, before checkHealth/SSE start). Returns
     * true if the probe may proceed (no pending teardown or it succeeded);
     * false if a pending teardown FAILED (probe should abort, mirroring
     * coldStartReconnect's skip-on-corrupted-teardown). Default `{ true }`
     * preserves legacy/test construction.
     */
    private val awaitPendingReconfigureTeardown: suspend () -> Boolean = { true },
    /**
     * @VisibleForTesting: fired at the probe coroutine's FIRST instruction,
     * BEFORE [awaitPendingReconfigureTeardown]. Tests park the probe here to
     * deterministically register a teardown in the clear→probe window (the
     * gap the old inside-lock handoff seam could not capture). Default `{}`.
     */
    private val onProbeCoroutineStartedHook: () -> Unit = {},
) {
    private var lastHealthCheckTime = 0L

    // ── State sync helpers (mirror orchestrator.writeConnection) ──

    /**
     * §R-17 M5.1→batch2: writes the connection slice only (slice is the
     * authoritative read path). The deprecated AppState mirror write +
     * `Dispatchers.Main.immediate` Looper check were removed in R-17 batch2
     * sub-step d (Fixer C) — call sites already run on the main dispatcher
     * (viewModelScope default), and `MutableStateFlow.update` is main-thread-
     * safe by VM contract.
     */
    private fun writeConnection(transform: (ConnectionState) -> ConnectionState) {
        slices.mutateConnection(transform)
    }

    /**
     * §F2: classifies a terminal probe exception into an [AuthFailureReason]
     * for the banner's AUTH_FAILURE vs REST_OUTAGE disambiguation. Extracts
     * the sidecar envelope code from the [retrofit2.HttpException]'s response
     * (if present) via [OpenCodeRepository.parseErrorCode]; for the slim path
     * (plain `Exception("HTTP 401")`) the classifier falls back to a message
     * regex. Returns null for transport IOExceptions, transient 5xx, and
     * authorization denials (shell_not_allowed).
     *
     * `parseErrorCode` reads `errorBody()?.string()` (one-shot OkHttp buffer);
     * wrapped in `runCatching` so a double-read or closed body degrades
     * gracefully to exception-only classification.
     */
    private fun classifyAuthFailureFromException(exc: Throwable?): AuthFailureReason? {
        val envCode = (exc as? retrofit2.HttpException)?.let { he ->
            he.response()?.let { resp ->
                runCatching { repository.parseErrorCode(resp) }.getOrNull()
            }
        }
        return classifyAuthFailure(exc, envCode)
    }

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Health-check probe with optional exponential-backoff retry.
     *
     * Throttle: skip when a health check ran <30s ago AND [force] is false
     * (preserves the pre-extraction guard verbatim). On a healthy response:
     * mark connected, run [loadInitialData], and [startSSE]. On failure (or
     * healthy=false past the retry budget): surface the error and mark
     * disconnected. [retries] extra attempts follow on failure with exponential
     * backoff (1s, 2s, 4s, ...); default callers pass retries=0 (one-shot),
     * only [coldStartReconnect] opts into retries.
     *
     * [onSettled] is invoked EXACTLY ONCE when the probe reaches a terminal
     * state — `true` on a healthy connect, `false` on failure / retry
     * exhaustion / ViewModel cancellation mid-backoff. Used by callers that
     * need a success/failure follow-up. Default is null (no callback) so
     * existing call sites keep compiling unchanged.
     *
     * `isActive` is checked so ViewModel cancellation aborts cleanly mid-backoff.
     */
    fun testConnection(force: Boolean = false, retries: Int = 0, onSettled: ((Boolean) -> Unit)? = null) {
        val now = clock()
        if (!force && now - lastHealthCheckTime < 30_000) {
            // Throttled: do not probe, do not invoke onSettled (no new info).
            return
        }
        lastHealthCheckTime = now
        connectionBootstrapEngine?.let { engine ->
            testConnectionWithEngine(engine, retries, onSettled)
            return
        }
        scope.launch {
            // lite-v2 D-fixup-r4 (Item ① close): fire the test seam at the
            // probe coroutine's FIRST instruction, then re-check/join any
            // teardown registered in the clear→probe window BEFORE any real
            // work. coldStartReconnect releases reconfigureLock before this
            // coroutine runs; without this recheck a teardown arriving in
            // that window would be bypassed. See
            // ConnectionCoordinator.awaitPendingReconfigureTeardown.
            onProbeCoroutineStartedHook()
            // §onSettled-exactly-once (gpt-1 🔴 / glm-1): the original post-loop
            // `onSettled?.invoke(false)` was UNREACHABLE on cancellation —
            // `delay()` / `checkHealth()` throw CancellationException when the
            // scope is cancelled, propagating out of launch and skipping the
            // post-loop line. Wrap in try/finally with a `settled` guard so
            // every exit path (success return, failure return, OR cancellation
            // mid-backoff/mid-probe) invokes onSettled exactly once. The
            // finally runs during cancellation WITHOUT swallowing the
            // CancellationException (it re-propagates after the lambda call),
            // preserving structured-concurrency teardown.
            var settled = false
            try {
                // lite-v2 D-fixup-r4 (Item ① close): clear→probe recheck. The
                // recheck returns false only if a pending teardown FAILED —
                // abort the probe (mirrors coldStartReconnect's
                // skip-on-corrupted-teardown), invoking onSettled(false) once.
                if (!awaitPendingReconfigureTeardown()) {
                    DebugLog.i(TAG, "testConnection: aborted — pending teardown failed during probe-entry recheck")
                    settled = true
                    onSettled?.invoke(false)
                    return@launch
                }
                // §R-17 batch2: error is now a one-shot UiEvent. There's no
                // persistent `error` field to clear at the start of a probe —
                // any prior failure was already consumed app-wide. Connection
                // phase/isConnecting live on connectionFlow.
                writeConnection { it.copy(isConnecting = true, connectionPhase = ConnectionPhase.Connecting) }
                // NOTE: configureRepositoryForCurrentProfile() was intentionally
                // removed here. Every caller already configures the repository
                // before invoking testConnection (cold start via applySavedSettings;
                // host-switch paths call configureRepositoryForProfile directly).
                // Re-calling it here chained cancelSseForReconfigure ->
                // onHostReconfigured, which reset ForegroundCatchUpController.
                // sseHasConnectedOnce and swallowed the 15s-5min foreground gap
                // catch-up (real bug, pre-existing).
                // Retry loop: attempt 1 is always made; up to `retries` extra
                // attempts follow on failure/unhealthy with exponential backoff
                // (1s, 2s, 4s, ...). Default callers pass retries=0 (one-shot),
                // preserving the original single-attempt semantics; only
                // coldStartReconnect() opts into retries. isActive is checked so
                // ViewModel cancellation aborts cleanly mid-backoff.
                val maxAttempts = 1 + retries.coerceAtLeast(0)
                var attempt = 0
                var backoffMs = 1000L
                while (isActive) {
                    attempt++
                    if (attempt > 1) {
                        writeConnection {
                            it.copy(connectionPhase = ConnectionPhase.ReconnectingAttempt(attempt, maxAttempts))
                        }
                    }
                    // §toctou-resolver-snapshot (RESOLVER lane ②, bgpt phase-gate):
                    // capture the connection-identity generation BEFORE the
                    // checkHealth SUSPEND point. Every host/profile reconfigure
                    // calls ConnectionIdentityStore.beginReconfigure() SYNCHRONOUSLY
                    // (before repository.configure), which bumps currentEpoch() and
                    // nulls the old identity. If the generation advances while
                    // checkHealth is suspended (a host switch interleaved), the
                    // resolved snapshot captured below is OBSOLETE and the success
                    // path MUST abort — see the guard before writeConnection{Connected}.
                    // Mirrors the engine-path isCurrent(identity) recheck in
                    // testConnectionWithEngine (~L499). null when identityStore is
                    // absent (legacy/test construction — no generation to guard).
                    val probeEpoch = identityStore?.currentEpoch()
                    val healthResult = repository.checkHealth()
                    if (healthResult.isSuccess) {
                        val health = healthResult.getOrNull()
                        if (health != null && health.healthy) {
                            // ③ populate the compat profile from the freshly-probed
                            // version before any consumer (initial-data loaders, SSE)
                            // runs, so capability flags are settled for this connect.
                            serverCompatProfile.update(health.version)
                            // §resolver-single-source-of-truth (RESOLVER lane ②):
                            // identity endpointFp MUST move WITH the url. REST/
                            // SSE reached this host via the resolver URL (the
                            // engine/configure path resolves through
                            // EffectiveConnectionConfigResolver); if identity
                            // stayed pinned to settingsManager.serverUrl, a
                            // profile/host switch would leave the epoch/identity
                            // guards keyed to the OLD url → stale directory-fetch
                            // + SSE guards misfire.
                            //
                            // null = EXPLICIT FAIL (resolver WIRED): a null
                            // resolve() means "no valid active endpoint" mid-
                            // probe. Binding a stale identity here is exactly what
                            // resurrects the token-stream-storm bug (the mirror
                            // write at HostProfileController.kt:881 exists BECAUSE
                            // direct readers bypassed the resolver), so we do NOT
                            // fall back to settingsManager.serverUrl. The probe is
                            // treated as superseded — settle false WITHOUT writing
                            // Connected — and the in-flight reconfigure re-drives
                            // it under the new identity. Mirrors the engine-path
                            // stale-identity recheck in testConnectionWithEngine
                            // (~L499).
                            //
                            // ORDERING: the check runs BEFORE the writeConnection
                            // {Connected} below so a null resolve never leaves the
                            // slice in a transient Connected-then-defer state.
                            //
                            // Legacy/test (resolver ABSENT): this whole legacy
                            // testConnection branch is only reached when
                            // connectionBootstrapEngine==null — a test/legacy
                            // condition (production ALWAYS wires the engine, which
                            // resolves through the resolver independently of this
                            // probe body). When the resolver is ALSO absent we
                            // preserve the historical settingsManager.serverUrl
                            // read so legacy constructions (incl. the shared
                            // MainViewModelTestBase harness) keep working byte-
                            // for-byte. The `?: settingsManager.serverUrl` below
                            // is therefore DEAD CODE in production (resolver is
                            // always wired → either resolvedEndpoint.url or the
                            // explicit-fail defer above).
                            val resolvedEndpoint = effectiveConnectionConfigResolver?.resolve()
                            if (effectiveConnectionConfigResolver != null && resolvedEndpoint == null) {
                                DebugLog.i(TAG, "testConnection: identity bind skipped — resolver returned no effective connection; probe superseded")
                                settled = true
                                onSettled?.invoke(false)
                                return@launch
                            }
                            // §toctou-resolver-snapshot DEFINITIVE fix (bgpt
                            // phase-gate): the epoch-CAS in
                            // ConnectionIdentityStore.bindIfCurrent is the SINGLE
                            // atomic gate between resolve and the commit. The
                            // probe captured probeEpoch before checkHealth;
                            // bindIfCurrent commits the identity ONLY IF
                            // currentEpoch() still == probeEpoch (no reconfigure
                            // superseded the snapshot) — and that epoch-CHECK +
                            // identity-COMMIT run under ONE synchronized critical
                            // section mutually exclusive with beginReconfigure,
                            // so a host switch can NEVER slip between the check
                            // and the commit. This closes the window the prior
                            // non-atomic probeEpoch-guard left open (a reconfigure
                            // between that guard and the bind could persist a
                            // stale URL).
                            //
                            // BIND-BEFORE-COMMIT: the identity is bound FIRST; the
                            // Connected slice is committed ONLY when the identity
                            // atomically committed at the captured epoch. So the
                            // connection's Connected state + identity can NEVER
                            // persist a URL inconsistent with the current epoch —
                            // no post-check window exists. If the CAS rejects
                            // (superseded), defer: settle false, NO Connected,
                            // identity NOT persisted; the new generation's probe
                            // re-runs under the new URL.
                            //
                            // Legacy/test (identityStore ABSENT): no CAS — skip
                            // the bind (the old identityStore?.bind was a no-op
                            // for a null receiver anyway) and fall through to
                            // writeConnection, preserving byte-identical legacy
                            // behaviour. The `?: settingsManager.serverUrl` arm
                            // stays dead code in production (resolver wired).
                            val identity = identityStore
                            if (identity != null && probeEpoch != null) {
                                val bound = identity.bindIfCurrent(
                                    profileId = currentProfileId(),
                                    normalizedWorkdir = settingsManager.currentWorkdir ?: "",
                                    endpointFp = resolvedEndpoint?.url ?: settingsManager.serverUrl,
                                    expectedEpoch = probeEpoch,
                                )
                                if (bound == null) {
                                    DebugLog.i(
                                        TAG,
                                        "testConnection: probe superseded — epoch-CAS bind rejected (generation advanced $probeEpoch → ${identity.currentEpoch()}); aborting commit (no Connected, identity not persisted)",
                                    )
                                    settled = true
                                    onSettled?.invoke(false)
                                    return@launch
                                }
                            }
                            // Identity atomically bound at the captured epoch (or
                            // legacy/test path with no identityStore). NOW commit
                            // Connected — loadInitialData's directory fan-out +
                            // launchSseCollection's collector read the just-bound
                            // identity (FGS spec §2 step 5: bind new collector to
                            // new identity).
                            writeConnection {
                                it.copy(
                                    isConnected = true,
                                    serverVersion = health.version,
                                    isConnecting = false,
                                    connectionPhase = ConnectionPhase.Connected,
                                    isSlimActive = serverCompatProfile.slimConnection,
                                    authFailureReason = null,
                                )
                            }
                            loadInitialData()
                            startSSE()
                            // remove-message-persistence Task 5: the daily
                            // cache sweep that used to fire-and-forget here
                            // was removed together with the maintenance
                            // coordinator (SQLite persistence layer deletion).
                            settled = true
                            onSettled?.invoke(true)
                            return@launch
                        }
                        // Healthy=false: surface the version if present but keep
                        // retrying (server may still be coming up on cold start).
                        if (health != null) {
                            serverCompatProfile.update(health.version)
                            writeConnection { it.copy(serverVersion = health.version) }
                        }
                    }
                    if (attempt >= maxAttempts || !isActive) {
                        // §R-17 batch2: error is now a one-shot UiEvent on
                        // _uiEvents (consumed app-wide). Connection fields stay
                        // on connectionFlow. Intermediate state legal (error
                        // emitted before phase flips to "disconnected" — both
                        // still describe the same failure).
                        healthResult.exceptionOrNull()?.let { e ->
                            effects.tryEmitUiEvent(UiEvent.Error(R.string.error_connection_failed, listOf(errorMessageOrFallback(e, "unknown error"))))
                        }
                        // §red-dot-trace: make the silent retry-exhaustion
                        // disconnect visible so the red indicator is traceable.
                        // The UiEvent.Error above only fires when an exception
                        // exists; a 200-OK with healthy=false reached here with
                        // NO log line (the "no exception" symptom in the task).
                        val termExc = healthResult.exceptionOrNull()
                        if (termExc != null) {
                            DebugLog.e(
                                TAG,
                                "testConnection: retry exhausted attempt=$attempt/$maxAttempts -> Disconnected",
                                termExc,
                            )
                        } else {
                            DebugLog.w(
                                TAG,
                                "testConnection: health probe: server reported healthy=false (attempt=$attempt/$maxAttempts) -> Disconnected",
                            )
                        }
                        // §F2: classify the terminal exception for AUTH_FAILURE
                        // vs REST_OUTAGE banner disambiguation (upstream
                        // 401/403 → HttpAuth; transient/transport → null).
                        val authReason = classifyAuthFailureFromException(termExc)
                        writeConnection {
                            it.copy(
                                isConnected = false,
                                isConnecting = false,
                                connectionPhase = ConnectionPhase.Disconnected,
                                authFailureReason = authReason,
                            )
                        }
                        settled = true
                        onSettled?.invoke(false)
                        return@launch
                    }
                    delay(backoffMs)
                    backoffMs *= 2
                }
                // Loop exited because isActive flipped false — terminal failure.
                settled = true
                onSettled?.invoke(false)
            } finally {
                // Cancellation path (CancellationException propagated out of
                // delay/checkHealth): the body's settled flag stayed false.
                // Invoke the callback so the caller's exactly-once contract
                // holds; the CancellationException re-propagates after this
                // finally (we do NOT catch/swallow it). refresh-path callers
                // treat false as a no-op (their lambda only acts on true).
                if (!settled) onSettled?.invoke(false)
            }
        }
    }

    private fun testConnectionWithEngine(
        engine: ConnectionBootstrapEngine,
        retries: Int,
        onSettled: ((Boolean) -> Unit)?,
    ) {
        scope.launch {
            // lite-v2 D-fixup-r4 (Item ① close): same clear→probe recheck as
            // the legacy path — the engine path IS production (ControllerModule
            // always wires connectionBootstrapEngine), so this coroutine MUST
            // also re-check/join any teardown registered in the clear→probe
            // window before engine.bootstrap().
            onProbeCoroutineStartedHook()
            var settled = false
            try {
                // lite-v2 D-fixup-r4 (Item ① close): clear→probe recheck.
                if (!awaitPendingReconfigureTeardown()) {
                    DebugLog.i(TAG, "testConnectionWithEngine: aborted — pending teardown failed during probe-entry recheck")
                    settled = true
                    onSettled?.invoke(false)
                    return@launch
                }
                writeConnection { it.copy(isConnecting = true, connectionPhase = ConnectionPhase.Connecting) }
                val delays = bootstrapRetryPolicy.delaysMs.take(retries.coerceAtLeast(0))
                var attempt = 0
                while (true) {
                    when (val outcome = engine.bootstrap()) {
                        is ConnectionBootstrapOutcome.Success -> {
                            loadInitialData()
                            // L1 FGS commit 1: connect the SSE owner directly
                            // (the launcher path was removed in Commit 2).
                            val ownership = connectSseAndAwait(outcome.identity)?.let { activation ->
                                when (activation) {
                                    is cn.vectory.ocdroid.service.streaming.SourceActivation.Ready ->
                                        cn.vectory.ocdroid.service.OwnershipStartResult.Ready(outcome.identity)
                                    else ->
                                        cn.vectory.ocdroid.service.OwnershipStartResult.Refused(
                                            cn.vectory.ocdroid.service.OwnershipRefusal.BootstrapFailed,
                                        )
                                }
                            } ?: cn.vectory.ocdroid.service.OwnershipStartResult.Refused(
                                cn.vectory.ocdroid.service.OwnershipRefusal.ServiceStopped,
                            )
                            // D5-2 (#4): identity recheck BEFORE writing Connected.
                            // A newer epoch may have started during the (possibly
                            // long) ownership wait — this stale-result branch
                            // settles false WITHOUT writing Disconnected (a
                            // newer epoch may already be connecting).
                            if (identityStore != null && !identityStore.isCurrent(outcome.identity)) {
                                settled = true
                                onSettled?.invoke(false)
                                return@launch
                            }
                            if (ownership is OwnershipStartResult.Ready &&
                                ownership.identity == outcome.identity
                            ) {
                                writeConnection {
                                    it.copy(
                                        isConnected = true,
                                        isConnecting = false,
                                        serverVersion = outcome.health.version,
                                        connectionPhase = ConnectionPhase.Connected,
                                        isSlimActive = serverCompatProfile.slimConnection,
                                        authFailureReason = null,
                                    )
                                }
                                // remove-message-persistence Task 5: the
                                // daily cache sweep that used to fire-and-forget
                                // here was removed together with the
                                // maintenance coordinator.
                                settled = true
                                onSettled?.invoke(true)
                                return@launch
                            }
                            writeConnection {
                                // §sse-zombie-fix (v3 Bug B) + §sse-zombie-fix-impl-rev1
                                // (GPT impl-review concern #1): reason-specific phase
                                // mapping. We are in the engine.bootstrap() Success
                                // branch — REST is healthy (catalog/sessions/children
                                // all 200). The phase depends on WHY the SSE transport
                                // (Stage 2) refused:
                                //  - SseDisabled (debug toggle) → SseDisabled
                                //  - BootstrapFailed (Stage-2 timeout/stale-owner) →
                                //    SseBootstrapFailed (banner: "live updates interrupted")
                                //  - ServiceStopped / PlatformRejected / other →
                                //    Disconnected (genuine service/platform failure; NOT
                                //    a mere bootstrap timeout — the banner honestly says
                                //    "server unreachable" because the service itself failed).
                                val phase = if (ownership is cn.vectory.ocdroid.service.OwnershipStartResult.Refused) {
                                    when (ownership.reason) {
                                        is cn.vectory.ocdroid.service.OwnershipRefusal.SseDisabled ->
                                            ConnectionPhase.SseDisabled
                                        is cn.vectory.ocdroid.service.OwnershipRefusal.BootstrapFailed ->
                                            ConnectionPhase.SseBootstrapFailed
                                        else ->
                                            // ServiceStopped, PlatformRejected, future
                                            // reasons — genuine failure, not bootstrap.
                                            ConnectionPhase.Disconnected
                                    }
                                } else {
                                    // Defensive: non-Refused result in this branch should
                                    // not happen (Success → ownership is Refused or
                                    // Accepted). Fall back to Disconnected.
                                    ConnectionPhase.Disconnected
                                }
                                // §degraded-connected-fix (2026-07-26): we are in the
                                // Success branch — engine.bootstrap() SUCCEEDED (REST
                                // health check passed: catalog, /sessions, /children all
                                // return 200). Only the SSE transport (Stage 2 ensureStarted)
                                // failed (Refused). Previously this wrote isConnected=false
                                // → red dot, even though REST is fully functional. The user
                                // saw "server unreachable" while messages/catalog worked
                                // fine, and force-refresh cycled through the same path
                                // (REST success → SSE fail → red again) without recovery.
                                //
                                // Fix: REST success means the server IS reachable.
                                // isConnected=true → green dot (non-breathing, since
                                // isSseConnected stays false — no live streaming, but
                                // REST polling + send still work). The phase (SseDisabled
                                // / SseBootstrapFailed / Disconnected) signals SSE status
                                // for diagnostics. SSE reconnection continues in the
                                // background via the SessionStreamingService's internal
                                // watchdog/heartbeat.
                                //
                                // §sse-zombie-fix-impl-rev1 (concern #2): REST success
                                // clears a stale authFailureReason — the server just
                                // proved it accepts our credentials, so any prior auth
                                // failure banner is obsolete.
                                it.copy(
                                    isConnected = true,
                                    isConnecting = false,
                                    connectionPhase = phase,
                                    authFailureReason = null,
                                )
                            }
                            settled = true
                            onSettled?.invoke(false)
                            return@launch
                        }
                        is ConnectionBootstrapOutcome.Failed -> {
                            if (attempt >= delays.size) {
                                effects.tryEmitUiEvent(
                                    UiEvent.Error(
                                        R.string.error_connection_failed,
                                        listOf(errorMessageOrFallback(outcome.error, "unknown error")),
                                    ),
                                )
                                // §engine-path-diag (rev-2 MINOR 3): mirror legacy
                                // testConnection's :428 diagnostic. The engine terminal-
                                // failure path emitted only a transient UiEvent.Error
                                // snackbar with NO DebugLog, so production engine-path
                                // network failures (DNS / connection drop) left zero trace
                                // in the in-app DebugLog viewer (设置→Debug).
                                DebugLog.e(
                                    TAG,
                                    "testConnectionWithEngine: retry exhausted (budget=${delays.size}) -> Disconnected",
                                    outcome.error,
                                )
                                // §F2: classify the terminal exception for
                                // AUTH_FAILURE vs REST_OUTAGE banner
                                // disambiguation (upstream 401/403 → HttpAuth).
                                val authReason = classifyAuthFailureFromException(outcome.error)
                                writeConnection {
                                    it.copy(
                                        isConnected = false,
                                        isConnecting = false,
                                        connectionPhase = ConnectionPhase.Disconnected,
                                        authFailureReason = authReason,
                                    )
                                }
                                settled = true
                                onSettled?.invoke(false)
                                return@launch
                            }
                            delay(delays[attempt++])
                        }
                    }
                }
            } finally {
                if (!settled) onSettled?.invoke(false)
            }
        }
    }

    /**
     * Cold-start entry point: force a connection check with up to 3 retries
     * (exponential backoff 1s/2s/4s) so a slow-to-wake server (common when
     * the OpenCode server itself is bootstrapping) still comes up instead of
     * stranding the user on the disconnected empty state. Callers:
     * MainActivity's cold-start LaunchedEffect, [resetLocalDataAndResync],
     * and the home server-connection popup's force-refresh (T7 F1:
     * `ConnectionViewModel.coldStartReconnect()` ←
     * `SessionsScreen.onRefresh`).
     *
     * §review-blocker-#4 (L7): the pre-L7 "FROZEN while a TOFU trust dialog
     * is pending" guard was DELETED in L7 — TLS trust resolves from the
     * HostProfile's SslConfig at configure time, so there is no in-flight
     * trust decision to freeze on. The exponential-backoff retry below
     * proceeds unconditionally on cold start.
     */
    fun coldStartReconnect() {
        testConnection(force = true, retries = 3)
    }

    companion object {
        // Intentionally kept as "ConnectionCoordinator" (not the class name) so
        // logcat filters / grep patterns that keyed on the pre-extraction tag
        // keep resolving. Behavior-preserving.
        private const val TAG = "ConnectionCoordinator"
    }
}
