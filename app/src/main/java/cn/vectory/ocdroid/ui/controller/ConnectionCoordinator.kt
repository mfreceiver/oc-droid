package cn.vectory.ocdroid.ui.controller

import android.util.Log
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.api.CommandInfo
import cn.vectory.ocdroid.data.cache.CacheMaintenanceCoordinator
import cn.vectory.ocdroid.data.model.SSEEvent
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.data.repository.ServerCompatProfile
import cn.vectory.ocdroid.data.repository.http.TofuDecision
import cn.vectory.ocdroid.data.repository.http.hostPortFromUrl
import cn.vectory.ocdroid.ui.ConnectionPhase
import cn.vectory.ocdroid.ui.ConnectionState
import cn.vectory.ocdroid.ui.SharedEffectBus
import cn.vectory.ocdroid.ui.SliceFlows
import cn.vectory.ocdroid.ui.UiEvent
import cn.vectory.ocdroid.ui.errorMessageOrFallback
import cn.vectory.ocdroid.ui.launchLoadProviders
import cn.vectory.ocdroid.ui.reportNonFatalIssue
import cn.vectory.ocdroid.util.DebugLog
import cn.vectory.ocdroid.util.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.net.ssl.SSLHandshakeException
import java.security.cert.CertificateException
import java.util.Locale

/**
 * R-16 M4 → R-17 batch3b: owns the server connection lifecycle — health-check
 * probe with exponential-backoff retry, the 30s health-check throttle,
 * initial-data load orchestration on a healthy connect, and the SSE feed's
 * start/stop.
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
 * **Moved from the orchestrator:**
 *  - `sseJob` + `lastHealthCheckTime` fields — the SSE feed Job and the
 *    throttle anchor now live here.
 *  - `testConnection(force, retries)` — the full connect state machine.
 *  - `coldStartReconnect()` — `testConnection(force=true, retries=3)`.
 *  - `loadInitialData()` — sessions/agents/providers/questions/commands + the
 *    directory-sessions re-fetch for the restored workdir.
 *  - `loadCommands()` + `localCommands()` + `mergeCommands()` — slash-command
 *    merge (server list + client-side /clear /compact /undo /redo).
 *  - `startSSE()` — starts the SSE collection coroutine.
 *  - `cancelSse()` / `cancelSseForReconfigure()` — tear down the in-flight
 *    feed (reconfigure also resets the catch-up state machine via effect).
 *
 * The 30s throttle clock is injectable ([clock]) so the cooldown is
 * deterministically testable without wall-clock latency.
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
     * R-20 Phase 3 (plan §3): daily cache sweep coordinator. Null in tests
     * that do not exercise the sweep path (legacy pre-Phase 3 construction).
     * On a healthy connect, [testConnection] fires-and-forgets
     * [CacheMaintenanceCoordinator.dailySweepIfNeeded] against the current
     * host's serverGroupFp so the LRU/age/orphan sweep stays current without
     * a separate background job.
     */
    private val cacheMaintenanceCoordinator: CacheMaintenanceCoordinator? = null,
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
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private var sseJob: Job? = null
    private var lastHealthCheckTime = 0L

    /**
     * §tofu R2: pending-trust state — non-null hostPort means a TOFU trust
     * dialog is showing for this endpoint and the [testConnection] retry loop
     * is SUSPENDED on [pendingTofuDecision].await(). Three guards read this:
     *  1. The retry loop itself (no retries burn while waiting).
     *  2. [coldStartReconnect] / [startSSE] early-return while frozen so a
     *     tunnel/reset path or foreground catch-up cannot race the decision.
     *  3. [resolveTofuTrust] is the ONLY writer (completes the deferred +
     *     clears both fields + applies the decision to the pin store).
     */
    @Volatile
    private var pendingTofuHostPort: String? = null
    private var pendingTofuDecision: kotlinx.coroutines.CompletableDeferred<TofuDecision>? = null

    /**
     * §generation-guard: bumped on every [cancelSseForReconfigure] (host/profile
     * switch or manual reconfigure). Two consumers:
     *
     *  1. [loadInitialData] captures this value and discards directory-fetch
     *     results that return AFTER a reconfigure — their sessions belong to
     *     the previous host and would otherwise pollute the new host's
     *     directorySessions. The fan-out in [loadInitialData] (up to one
     *     launch per recent workdir) amplifies this in-flight window, hence
     *     the explicit guard.
     *
     *  2. §R-19 Sprint 1 Lane A (P1-10 fix Blocker 1): [launchSseCollection]
     *     captures this value at coroutine start ([sseGenAtStart]) and
     *     re-checks per collected event. A mismatch means the host was
     *     reconfigured away mid-collection; further events from THIS job
     *     belong to the previous host and are dropped at the forwarding layer
     *     (never become [ControllerEffect.OnSseEvent]s). This is the
     *     production-grade stale-host guard that closes the race the
     *     SessionSyncCoordinator's own trigger-stamped generation could not.
     *
     * Reads/writes are on the main thread (reconfigure + loadInitialData +
     * launchSseCollection's collect lambda all run there); AtomicLong is
     * belt-and-suspenders.
     */
    private val directoryFetchGeneration = java.util.concurrent.atomic.AtomicLong(0L)

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
        scope.launch {
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
                    val healthResult = repository.checkHealth()
                    if (healthResult.isSuccess) {
                        val health = healthResult.getOrNull()
                        if (health != null && health.healthy) {
                            // ③ populate the compat profile from the freshly-probed
                            // version before any consumer (initial-data loaders, SSE)
                            // runs, so capability flags are settled for this connect.
                            serverCompatProfile.update(health.version)
                            writeConnection {
                                it.copy(
                                    isConnected = true,
                                    serverVersion = health.version,
                                    isConnecting = false,
                                    connectionPhase = ConnectionPhase.Connected
                                )
                            }
                            loadInitialData()
                            startSSE()
                            // R-20 Phase 3 (plan §3): fire-and-forget the daily
                            // cache sweep on a healthy connect. The coordinator
                            // does its own 24h dedup (SettingsManager.
                            // lastSweepEpoch_<fp>), so a reconnect within the
                            // same day short-circuits inside the coordinator
                            // without re-enumerating. Null in tests that don't
                            // wire it (pre-Phase 3 construction).
                            cacheMaintenanceCoordinator?.let { coordinator ->
                                val fp = currentServerGroupFp()
                                scope.launch {
                                    runCatching { coordinator.dailySweepIfNeeded(fp) }
                                        .onFailure { DebugLog.w("ConnectionCoordinator", "daily sweep failed for fp=$fp: ${it.message}") }
                                }
                            }
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
                    // §tofu R2: SSL/cert error against an endpoint with NO pin
                    // yet → capture the leaf cert and prompt the user. This
                    // replaces the legacy "allowInsecure=true → trust-all"
                    // downgrade with SSH-style trust-on-first-use: the user
                    // sees the actual leaf (subject / issuer / expiry / SPKI)
                    // and chooses Accept once / Trust / Cancel. Security is
                    // the SPKI pin (the dialog just decides whether to write
                    // it), NOT a blanket trust-all.
                    //
                    // Guards: only prompt when
                    //   (a) the failure is SSL/cert-shaped (NOT a generic
                    //       network/HTTP error — those surface the usual way),
                    //   (b) [hostPort] is resolvable,
                    //   (c) no pin yet exists (already-trusted endpoints
                    //       never re-prompt — the pin mismatch path stays a
                    //       hard failure for security),
                    //   (d) we are not already pending a decision (avoid
                    //       stacking prompts on a re-entrant coldStart).
                    val exc = healthResult.exceptionOrNull()
                    val rootCause = generateSequence(exc) { it.cause }
                        .firstOrNull { it is SSLHandshakeException || it is CertificateException }
                    val baseUrl = settingsManager.serverUrl
                    val hostPort = hostPortFromUrl(baseUrl)
                    if (rootCause != null && hostPort != null &&
                        repository.pinnedSpkiFor(hostPort) == null &&
                        pendingTofuHostPort == null
                    ) {
                        val capture = repository.captureServerCert(baseUrl, hostPort, clientCert = null)
                        if (capture != null) {
                            // Enter pending-trust: SUSPEND the retry loop on a
                            // CompletableDeferred that [resolveTofuTrust]
                            // completes. While pending, the freeze guards in
                            // [coldStartReconnect] / [startSSE] early-return.
                            pendingTofuHostPort = hostPort
                            val deferred = kotlinx.coroutines.CompletableDeferred<TofuDecision>()
                            pendingTofuDecision = deferred
                            writeConnection {
                                it.copy(
                                    connectionPhase = ConnectionPhase.AwaitingTofuTrust,
                                    pendingTofuCapture = capture
                                )
                            }
                            val decision = deferred.await()
                            pendingTofuHostPort = null
                            pendingTofuDecision = null
                            writeConnection { it.copy(pendingTofuCapture = null) }
                            when (decision) {
                                is TofuDecision.AcceptOnce,
                                is TofuDecision.Trust -> {
                                    // Apply the pin → next loop iteration calls
                                    // checkHealth, which now resolves a
                                    // TofuPinned SSL config (SPKI match →
                                    // handshake succeeds). DON'T burn a retry
                                    // or delay — `continue` re-probes at once.
                                    repository.applyTofuDecision(hostPort, decision)
                                    writeConnection {
                                        it.copy(connectionPhase = ConnectionPhase.Connecting)
                                    }
                                    continue
                                }
                                TofuDecision.Cancel -> {
                                    // User declined — terminal failure.
                                    writeConnection {
                                        it.copy(
                                            isConnected = false,
                                            isConnecting = false,
                                            connectionPhase = ConnectionPhase.Disconnected
                                        )
                                    }
                                    settled = true
                                    onSettled?.invoke(false)
                                    return@launch
                                }
                            }
                        }
                        // capture == null (unreachable / no cert presented):
                        // fall through to the normal failure path below — the
                        // original SSLHandshakeException is surfaced verbatim.
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
                        writeConnection {
                            it.copy(
                                isConnected = false,
                                isConnecting = false,
                                connectionPhase = ConnectionPhase.Disconnected
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

    /**
     * Cold-start entry point: force a connection check with up to 3 retries
     * (exponential backoff 1s/2s/4s) so a slow-to-wake server (common when
     * the OpenCode server itself is bootstrapping) still comes up instead of
     * stranding the user on the disconnected empty state. Used exclusively
     * from MainActivity's cold-start LaunchedEffect (and resetLocalDataAndResync).
     *
     * §tofu R2: FROZEN while a TOFU trust dialog is pending — a reconnect
     * race against the in-flight decision would either burn retries or fork
     * two capture probes. The user's [resolveTofuTrust] clears the pending
     * state and the loop re-probes; cold-start then proceeds naturally.
     */
    fun coldStartReconnect() {
        if (pendingTofuHostPort != null) {
            DebugLog.i(TAG, "coldStartReconnect: frozen — TOFU trust pending for $pendingTofuHostPort")
            return
        }
        testConnection(force = true, retries = 3)
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
        effects.tryEmitEffect(ControllerEffect.LoadProviders)
        effects.tryEmitEffect(ControllerEffect.LoadPendingQuestions)
        effects.tryEmitEffect(ControllerEffect.LoadPendingPermissions)
        // Same-domain inline: slash commands merged with client-side commands.
        loadCommands()
        // §generation-guard: capture the current generation so directory fetches
        // that return AFTER a host reconfigure (cancelSseForReconfigure bumps
        // this) are dropped — their sessions belong to the previous host.
        val generation = directoryFetchGeneration.get()
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
        val restoreWorkdirs = (
            settingsManager.getRecentWorkdirs(currentServerGroupFp()) + listOfNotNull(settingsManager.currentWorkdir)
        ).distinct().filter { it.isNotBlank() }
        restoreWorkdirs.forEach { workdir ->
            scope.launch {
                repository.getSessionsForDirectory(workdir)
                    .onSuccess { sessions ->
                        // Drop stale-host results: a host/profile switch between
                        // dispatch and return would otherwise write the previous
                        // host's sessions into the new host's directorySessions.
                        if (generation != directoryFetchGeneration.get()) return@launch
                        appendDirectorySessions(workdir, sessions)
                    }
                    .onFailure { error ->
                        // Best-effort restore (mirrors createSessionInWorkdir):
                        // a failed workdir simply stays absent from
                        // directorySessions; the global getSessions list and a
                        // user-initiated refreshDirectorySessions are the
                        // fallbacks. Log for diagnosability without surfacing
                        // a user-facing error.
                        if (generation == directoryFetchGeneration.get()) {
                            reportNonFatalIssue(TAG, "directory restore failed for $workdir", error)
                        }
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
        val localOnly = local.filter { it.name.lowercase() !in serverNames }
        return server + localOnly
    }

    // ── SSE lifecycle ───────────────────────────────────────────────────────

    /**
     * Starts the SSE event collection feed. Cancels any in-flight feed first so
     * reconnects / reconfigures never leave two collectors racing. Each
     * `Result<SSEEvent>` is unpacked and forwarded to the SessionSyncCoordinator
     * via [ControllerEffect.OnSseEvent]; collection failures emit a UiEvent.Error
     * via [effects.uiEvents] so the UI can surface a "messages may be stale"
     * banner.
     *
     * Verbatim move of the `startSSE` body + the inlined `launchSseCollection`
     * free function.
     *
     * §R-19 #2 — single-SSE product decision (documented):
     * OC Droid runs **exactly one** SSE connection at a time, bound to
     * [SettingsManager.currentWorkdir] (see [launchSseCollection]). There is
     * **no per-workdir SSE multiplex**. Multi-workdir correctness is recovered
     * by catch-up instead of by parallel feeds:
     *
     *   (a) [SessionSyncCoordinator.loadPendingQuestionsAllWorkdirs] polls
     *       pending questions/permissions across EVERY workdir in
     *       `slices.sessionList.directorySessions.keys` + `currentWorkdir`,
     *       so a `question.asked` SSE event for a non-current (background)
     *       workdir is still surfaced (R-18 P1-9). The directory map those
     *       keys come from is populated by THIS coordinator's [loadInitialData]
     *       fan-out — the catch-up contract therefore depends on that fan-out
     *       running on every healthy connect.
     *   (b) [ForegroundCatchUpController] re-syncs the 15s/5min foreground
     *       gap on app return (no SSE is collected while backgrounded).
     *   (c) The session-switch path emits `LoadMessages(resetLimit=true)` to
     *       rehydrate the freshly-selected session's history, so a workdir
     *       that has never held the SSE feed still catches up on first view.
     *
     * Rationale: a multi-feed SSE redesign (one collector per
     * [SettingsManager.recentWorkdirs]) is a large change — server-side
     * routing, backpressure on N collectors, per-feed cancel/reconfigure
     * fan-out, and a per-feed generation guard — for marginal benefit because
     * the catch-up path above is already production-verified. The single-feed
     * model keeps the connection lifecycle in one place ([sseJob] +
     * [cancelSseForReconfigure]'s [directoryFetchGeneration] guard).
     *
     * **Invariant for tests**: [launchSseCollection] reads
     * `settingsManager.currentWorkdir` **fresh** at collection start; it is
     * NOT captured at construction or at first connect. A workdir switch must
     * call [startSSE] again to retarget the feed — the session-switch /
     * host-switch paths already do this. Boundary cases covered in
     * [ConnectionCoordinatorTest]: workdir retarget on switch, background-
     * workdir directory fan-out (the catch-up precondition), and reconnect
     * consistency (no currentWorkdir drift across cancel → restart).
     */
    fun startSSE() {
        // §tofu R2: FROZEN while a TOFU trust dialog is pending — the SSE
        // feed would try the same unpinned TLS handshake and fail the same
        // way (the pin isn't written until the user decides). Wait for
        // [resolveTofuTrust]; the connect retry loop calls startSSE itself
        // once the pin is in place.
        if (pendingTofuHostPort != null) {
            DebugLog.i(TAG, "startSSE: frozen — TOFU trust pending for $pendingTofuHostPort")
            return
        }
        DebugLog.i("SSE", "startSSE")
        sseJob?.cancel()
        sseJob = launchSseCollection()
    }

    /**
     * §tofu R2: applies the user's TOFU trust decision for the pending
     * endpoint. Called by the UI (via [cn.vectory.ocdroid.ui.ConnectionViewModel])
     * when the user taps Accept once / Trust / Cancel in [cn.vectory.ocdroid.ui.settings.TofuTrustDialog].
     * Completes the deferred the [testConnection] retry loop is awaiting; the
     * loop then writes the pin (Accept/Trust) and re-probes, or settles false
     * (Cancel). No-op when no TOFU prompt is pending.
     */
    fun resolveTofuTrust(decision: TofuDecision) {
        pendingTofuDecision?.complete(decision)
            ?: DebugLog.w(TAG, "resolveTofuTrust: no pending TOFU decision — ignoring $decision")
    }

    /**
     * §15.1: SSE collection coroutine. Wraps [OpenCodeRepository.connectSSE] so
     * the resulting Flow's [Result]s are unpacked and forwarded as
     * [ControllerEffect.OnSseEvent]. Failures (including the
     * SSEConnectionExhausted raised after the §15.3 retry budget is spent) emit
     * a UiEvent.Error via [effects.uiEvents] so the UI can surface a
     * "messages may be stale" banner.
     *
     * §R-19 Sprint 1 Lane A fix (gpter Blocker 1): the per-event
     * [directoryFetchGeneration] check is the production-grade stale-host
     * guard. The generation is captured at coroutine start ([sseGenAtStart])
     * and re-checked per collected event; a mismatch means a host reconfigure
     * ([cancelSseForReconfigure] bumped [directoryFetchGeneration]) landed
     * while this job was still collecting — any further events from THIS job
     * belong to the PREVIOUS host and are silently dropped (return@collect)
     * rather than forwarded as [ControllerEffect.OnSseEvent]. This closes the
     * race that the SessionSyncCoordinator's own generation guard could NOT
     * close (it stamped triggers with the CURRENT generation at consume time,
     * so a late event from the previous host arrived carrying the new
     * generation and was treated as a cold-start of the new host).
     *
     * Verbatim move of the `launchSseCollection` free function, plus the
     * per-event generation check.
     */
    private fun launchSseCollection(): Job {
        return scope.launch {
            // §R18 Phase 2-E step 1: pass the persisted workdir explicitly so
            // SSE routes to the right InstanceState. Was the global
            // currentDirectory before; behavior preserved (switchTo still
            // seeds settingsManager.currentWorkdir on session select).
            //
            // §R-19 #2: this is the ONLY SSE connection in the app — bound to
            // settingsManager.currentWorkdir (single-feed product decision;
            // see [startSSE] KDoc for the multi-workdir catch-up rationale).
            // currentWorkdir is read FRESH here on every collection start so a
            // workdir switch + new startSSE() retargets the feed (no cached
            // value to drift across reconnect).
            //
            // §R-19 P1-10 Blocker 1 fix: capture the generation at coroutine
            // start so per-event re-check can detect a host reconfigure that
            // happened mid-collection.
            val sseGenAtStart = directoryFetchGeneration.get()
            repository.connectSSE(settingsManager.currentWorkdir)
                .catch { error ->
                    Log.e("OC_ERROR", "SSE collection failed", error)
                    effects.tryEmitUiEvent(UiEvent.Error(R.string.error_sse_failed, listOf(error.message ?: "unknown error")))
                }
                .collect { result ->
                    // §R-19 P1-10 Blocker 1 fix: drop stale-host events BEFORE
                    // they're forwarded. The cancellation triggered by
                    // cancelSseForReconfigure is async; a frame already in the
                    // Flow's buffer (or mid-flight in the collector) could be
                    // delivered to this lambda before cancellation propagates.
                    // Without this check that frame would be forwarded as an
                    // OnSseEvent and pollute the new host's state (a late
                    // `server.connected` from the previous host would land in
                    // SessionSyncCoordinator.handleEvent and flip
                    // connectedOnce=true for the new host's cold-start, or
                    // worse, fire a ReloadSession of the wrong session).
                    if (sseGenAtStart != directoryFetchGeneration.get()) {
                        DebugLog.i("SSE", "drop stale-host SSE event (gen $sseGenAtStart → ${directoryFetchGeneration.get()})")
                        return@collect
                    }
                    result.onSuccess { event ->
                        // SSE liveness: a successful event (initial connect OR
                        // retryWhen recovery after a network outage) proves the
                        // server is reachable. Mirror it into ConnectionState so
                        // the server icon flips green even when recovery happened
                        // via the SSE auto-reconnect rather than a testConnection
                        // health probe (which was the only place isConnected was
                        // set true — leaving the icon red after auto-recovery).
                        writeConnection {
                            it.copy(
                                isConnected = true,
                                isConnecting = false,
                                connectionPhase = ConnectionPhase.Connected
                            )
                        }
                        // §R18 Phase 3 Wave 1 (P1-3 A 类): launchSseCollection 内 SSE collect 回调是 suspend 上下文 → suspend emitEffect。
                        effects.emitEffect(ControllerEffect.OnSseEvent(event))
                    }
                        .onFailure { error ->
                            Log.e("OC_ERROR", "SSE event failed", error)
                            effects.tryEmitUiEvent(UiEvent.Error(R.string.error_sse_failed, listOf(error.message ?: "unknown error")))
                        }
                }
        }
    }

    /**
     * Cancels the in-flight SSE feed (foreground ON_STOP / ViewModel onCleared).
     * Does NOT reset the catch-up state machine — the foreground return path
     * re-arms it.
     */
    fun cancelSse() {
        sseJob?.cancel()
        sseJob = null
    }

    /**
     * §Stage D (gpter 阻塞 #1): tear down any in-flight SSE feed BEFORE the
     * repository is reconfigured for a host / profile switch. Without this,
     * the SSE job bound to the PREVIOUS host keeps delivering events into
     * AppState while the new host's health probe is still in flight — those
     * stale events (session/status/message/permission/question) would pollute
     * the freshly-cleared state for the new profile.
     *
     * Also resets the foreground catch-up state machine via [effects] so the
     * next connect is treated as a cold start.
     */
    fun cancelSseForReconfigure() {
        DebugLog.i("SSE", "cancelSse (reconfigure)")
        sseJob?.cancel()
        sseJob = null
        // §generation-guard: a host/profile switch invalidates every in-flight
        // directory fetch from the previous host. Bump so loadInitialData's
        // late-returning onSuccess blocks drop their (stale-host) results.
        directoryFetchGeneration.incrementAndGet()
        // §Phase1E: a host/profile switch is a fresh server — treat the next
        // connect as a cold start (skip catch-up, the reconfigure path loads
        // sessions/messages itself). Routed to the catch-up controller.
        // §R18 Phase 3 Wave 1 (P1-3 B 类): 单发非 suspend → tryEmitEffect。
        effects.tryEmitEffect(ControllerEffect.HostReconfigured)
    }

    companion object {
        private const val TAG = "ConnectionCoordinator"
    }
}
