package cn.vectory.ocdroid.ui.controller

import android.util.Log
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.api.CommandInfo
import cn.vectory.ocdroid.data.model.SSEEvent
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.data.repository.ServerCompatProfile
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
internal class ConnectionCoordinator(
    private val scope: CoroutineScope,
    private val slices: SliceFlows,
    private val repository: OpenCodeRepository,
    private val settingsManager: SettingsManager,
    private val effects: SharedEffectBus,
    // ③ ServerCompat: populated from the health probe so future shim migrations
    // can read version-derived capability flags instead of guessing a version.
    private val serverCompatProfile: ServerCompatProfile,
    // Injected clock so the 30s health-check throttle is deterministically
    // testable without depending on wall-clock latency. Defaults to
    // System::currentTimeMillis in production (preserves the exact pre-extraction
    // behaviour — the original `testConnection` called System.currentTimeMillis()).
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private var sseJob: Job? = null
    private var lastHealthCheckTime = 0L

    /**
     * §generation-guard: bumped on every [cancelSseForReconfigure] (host/profile
     * switch or manual reconfigure). [loadInitialData] captures this value and
     * discards directory-fetch results that return AFTER a reconfigure — their
     * sessions belong to the previous host and would otherwise pollute the new
     * host's directorySessions. The fan-out in [loadInitialData] (up to one
     * launch per recent workdir) amplifies this in-flight window, hence the
     * explicit guard. Reads/writes are on the main thread (reconfigure +
     * loadInitialData both run there); AtomicLong is belt-and-suspenders.
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
                            effects.uiEvents.tryEmit(UiEvent.Error(R.string.error_connection_failed, listOf(errorMessageOrFallback(e, "unknown error"))))
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
     */
    fun coldStartReconnect() {
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
        val restoreWorkdirs = (
            settingsManager.recentWorkdirs + listOfNotNull(settingsManager.currentWorkdir)
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
     */
    fun startSSE() {
        DebugLog.i("SSE", "startSSE")
        sseJob?.cancel()
        sseJob = launchSseCollection()
    }

    /**
     * §15.1: SSE collection coroutine. Wraps [OpenCodeRepository.connectSSE] so
     * the resulting Flow's [Result]s are unpacked and forwarded as
     * [ControllerEffect.OnSseEvent]. Failures (including the
     * SSEConnectionExhausted raised after the §15.3 retry budget is spent) emit
     * a UiEvent.Error via [effects.uiEvents] so the UI can surface a
     * "messages may be stale" banner.
     *
     * Verbatim move of the `launchSseCollection` free function.
     */
    private fun launchSseCollection(): Job {
        return scope.launch {
            // §R18 Phase 2-E step 1: pass the persisted workdir explicitly so
            // SSE routes to the right InstanceState. Was the global
            // currentDirectory before; behavior preserved (switchTo still
            // seeds settingsManager.currentWorkdir on session select).
            repository.connectSSE(settingsManager.currentWorkdir)
                .catch { error ->
                    Log.e("OC_ERROR", "SSE collection failed", error)
                    effects.uiEvents.tryEmit(UiEvent.Error(R.string.error_sse_failed, listOf(error.message ?: "unknown error")))
                }
                .collect { result ->
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
                            effects.uiEvents.tryEmit(UiEvent.Error(R.string.error_sse_failed, listOf(error.message ?: "unknown error")))
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
