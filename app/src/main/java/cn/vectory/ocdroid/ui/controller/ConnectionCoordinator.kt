package cn.vectory.ocdroid.ui.controller

import android.os.Looper
import android.util.Log
import cn.vectory.ocdroid.data.api.CommandInfo
import cn.vectory.ocdroid.data.model.SSEEvent
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.data.repository.ServerCompatProfile
import cn.vectory.ocdroid.ui.AppState
import cn.vectory.ocdroid.ui.ConnectionState
import cn.vectory.ocdroid.ui.SliceFlows
import cn.vectory.ocdroid.ui.SettingsState
import cn.vectory.ocdroid.ui.applySettingsSlice
import cn.vectory.ocdroid.ui.errorMessageOrFallback
import cn.vectory.ocdroid.ui.reportNonFatalIssue
import cn.vectory.ocdroid.ui.updateAndSync
import cn.vectory.ocdroid.util.DebugLog
import cn.vectory.ocdroid.util.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * R-16 M4: callbacks the [ConnectionCoordinator] invokes back into MainViewModel
 * (or sibling controllers) to drive the side effects of the connection lifecycle.
 *
 * Defined as an interface rather than direct injection so the coordinator never
 * holds a reference to MainViewModel — avoiding the circular dependency flagged
 * in R-16 §7.3 (Coordinator ← MainViewModel that owns it). Each method maps 1:1
 * to the private helper the pre-extraction `testConnection` / `loadInitialData`
 * / `startSSE` / `cancelSseForReconfigure` invoked inline.
 *
 * The initial-data loaders (loadSessions / loadAgents / loadProviders /
 * loadPendingQuestions) and the repository reconfigure + catch-up reset stay
 * owned by MainViewModel / their respective controllers — the coordinator only
 * requests them at the right points in the connect / reconfigure lifecycle.
 */
interface ConnectionCoordinatorCallbacks {
    /**
     * Reconfigures the repository for the CURRENT host profile (cancels SSE,
     * applies URL/creds + allowInsecure, restores persisted workdir). Routed to
     * HostProfileController.configureRepositoryForProfile(currentProfile).
     */
    fun configureRepositoryForCurrentProfile()

    /** Initial-data loaders, owned by MainViewModel / sibling controllers. */
    fun loadSessions()
    fun loadAgents()
    fun loadProviders()
    fun loadPendingQuestions()
    fun loadPendingPermissions()

    /**
     * Routes a single SSE event to [SessionSyncCoordinator.handleEvent]. The
     * SSE collection coroutine (owned by this coordinator) unpacks the
     * repository's `Result<SSEEvent>` flow and forwards each success to here.
     */
    fun onSseEvent(event: SSEEvent)

    /**
     * A host/profile switch is a fresh server — reset the foreground catch-up
     * state machine so the next `server.connected` is treated as a cold start
     * (skip catch-up; the reconfigure path loads sessions/messages itself).
     * Routed to ForegroundCatchUpController.onHostReconfigured.
     */
    fun onHostReconfigured()
}

/**
 * R-16 M4: owns the server connection lifecycle — health-check probe with
 * exponential-backoff retry, the 30s health-check throttle, initial-data load
 * orchestration on a healthy connect, and the SSE feed's start/stop.
 *
 * **Moved from MainViewModel:**
 *  - `sseJob` + `lastHealthCheckTime` fields — the SSE feed Job and the
 *    throttle anchor now live here; MainViewModel no longer touches them.
 *  - `testConnection(force, retries)` — the full connect state machine (throttle
 *    → reconfigure → health probe → retry loop → on-success loadInitialData +
 *    startSSE → on-failure error surface). Cold-start entry is `coldStartReconnect`.
 *  - `coldStartReconnect()` — `testConnection(force=true, retries=3)`.
 *  - `loadInitialData()` — sessions/agents/providers/questions/commands + the
 *    directory-sessions re-fetch for the restored workdir.
 *  - `loadCommands()` + `localCommands()` + `mergeCommands()` — slash-command
 *    merge (server list + client-side /clear /compact /undo /redo).
 *  - `startSSE()` — starts the SSE collection coroutine (the `launchSseCollection`
 *    free function is inlined here; SSE lifecycle belongs to connection mgmt).
 *  - `cancelSse()` / `cancelSseForReconfigure()` — tear down the in-flight feed
 *    (reconfigure also resets the catch-up state machine via callback).
 *
 * **Constructor params:** holds `scope` (viewModelScope), the shared `AppState`
 * flow, the connection + settings slice flows (for the slice+mirror writes), the
 * `SliceFlows` bundle, `repository`, `hostProfileStore`, `settingsManager`, and
 * the [ConnectionCoordinatorCallbacks]. Side effects that need MainViewModel
 * orchestration (repository reconfigure, initial-data loaders, SSE event
 * dispatch → SessionSyncCoordinator, catch-up reset) flow through callbacks.
 *
 * The 30s throttle clock is injectable ([clock]) so the cooldown is
 * deterministically testable without wall-clock latency — defaults to
 * `System::currentTimeMillis` in production (preserving the exact pre-extraction
 * behaviour).
 *
 * All AppState writes go through `updateState` (= `state.updateAndSync(slices)`,
 * equivalent to MainViewModel.updateState) or `writeConnection` (the connection
 * slice + mirror, byte-for-byte MainViewModel.writeConnection).
 *
 * RFC reference: R-16 §A / §M4. Zero behaviour change.
 */
@Suppress("DEPRECATION")
internal class ConnectionCoordinator(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<AppState>,
    private val connectionFlow: MutableStateFlow<ConnectionState>,
    private val settingsFlow: MutableStateFlow<SettingsState>,
    private val slices: SliceFlows,
    private val repository: OpenCodeRepository,
    private val settingsManager: SettingsManager,
    private val callbacks: ConnectionCoordinatorCallbacks,
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

    // ── State sync helpers (mirror MainViewModel.updateState / writeConnection) ──

    /**
     * Writes AppState + syncs every slice (equivalent to MainViewModel.updateState).
     * Each MutableStateFlow suppresses equal-value emissions, so only the slice
     * whose fields actually changed notifies its subscribers.
     */
    private fun updateState(transform: (AppState) -> AppState) {
        state.updateAndSync(slices, transform)
    }

    /**
     * Writes the connection slice atomically AND mirrors it onto the deprecated
     * AppState fields, so `state.value` stays consistent synchronously (RFC §4
     * strategy A — single-threaded, Main.immediate call sites; no dispatcher
     * batch reliance). Byte-for-byte copy of MainViewModel.writeConnection — the
     * connection-flow object is the SAME instance passed in by MainViewModel.
     *
     * §R-17 M5.1 (kimo 🟠#2) THREAD-SAFETY: the slice-write + AppState-mirror-write
     * is only consistent because every call site runs on `Dispatchers.Main.immediate`.
     * MUST be invoked on `Dispatchers.Main.immediate`; a background-thread call
     * would break slice↔mirror consistency. (Same constraint as
     * [MainViewModel.writeConnection] / [updateState] above.)
     */
    private fun writeConnection(transform: (ConnectionState) -> ConnectionState) {
        check(Looper.myLooper() === Looper.getMainLooper()) { "writeConnection must be called on the main thread" }
        val next = transform(connectionFlow.value)
        connectionFlow.value = next
        state.value = state.value.copy(
            isConnected = next.isConnected,
            isConnecting = next.isConnecting,
            serverVersion = next.serverVersion,
            connectionPhase = next.connectionPhase,
            tunnelActivationState = next.tunnelActivationState
        )
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
     * need a success/failure follow-up (e.g. [MainViewModel.refreshCurrentSession]
     * surfaces a "已刷新" toast when the user-initiated refresh confirms the
     * server is reachable). Default is null (no callback) so existing call
     * sites keep compiling unchanged.
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
                // §R-17 M2: error stays on _state (consumed app-wide);
                // connectionPhase/isConnecting moved to connectionFlow. Atomicity
                // (RFC §4 strategy A): the two updates run back-to-back on the same
                // dispatcher; the intermediate state (error cleared, phase not yet
                // "connecting") is legal and transient.
                updateState { it.copy(error = null) }
                writeConnection { it.copy(isConnecting = true, connectionPhase = "connecting") }
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
                            it.copy(connectionPhase = "reconnecting (attempt $attempt/$maxAttempts)")
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
                                    connectionPhase = "connected"
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
                        // §R-17 M2: error on _state; connection fields on
                        // connectionFlow. Intermediate state legal (error set
                        // before phase flips to "disconnected" — both still
                        // describe the same failure).
                        updateState {
                            it.copy(
                                error = healthResult.exceptionOrNull()?.let { e ->
                                    errorMessageOrFallback(e, "Connection failed")
                                }
                            )
                        }
                        writeConnection {
                            it.copy(
                                isConnected = false,
                                isConnecting = false,
                                connectionPhase = "disconnected"
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
     * the restored workdir (so the connected project's sessions reappear after
     * restart — directorySessions is in-memory and otherwise empty until the
     * user re-connects).
     */
    fun loadInitialData() {
        callbacks.loadSessions()
        callbacks.loadAgents()
        callbacks.loadProviders()
        callbacks.loadPendingQuestions()
        callbacks.loadPendingPermissions()
        loadCommands()
        // Re-fetch the directory-scoped sessions for the restored workdir so the
        // connected project's sessions reappear after restart (directorySessions
        // is in-memory and otherwise empty until the user re-connects).
        settingsManager.currentWorkdir?.let { workdir ->
            scope.launch {
                repository.getSessionsForDirectory(workdir)
                    .onSuccess { sessions ->
                        updateState { it.copy(directorySessions = it.directorySessions + (workdir to sessions)) }
                    }
            }
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
                    applySettingsSlice(state, settingsFlow) {
                        it.copy(availableCommands = mergeCommands(localCommands(), serverCommands))
                    }
                }
                .onFailure { error ->
                    reportNonFatalIssue(TAG, "Failed to load commands", error)
                    applySettingsSlice(state, settingsFlow) {
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
     * `Result<SSEEvent>` is unpacked and forwarded to [SessionSyncCoordinator]
     * via [ConnectionCoordinatorCallbacks.onSseEvent]; collection failures land
     * in `AppState.error` so the UI can surface a "messages may be stale" banner.
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
     * the resulting Flow's [Result]s are unpacked and forwarded to
     * [ConnectionCoordinatorCallbacks.onSseEvent]. Failures (including the
     * SSEConnectionExhausted raised after the §15.3 retry budget is spent) land
     * in `AppState.error` via the `catch` block so the UI can surface a
     * "messages may be stale" banner.
     *
     * Verbatim move of the `launchSseCollection` free function.
     */
    private fun launchSseCollection(): Job {
        return scope.launch {
            repository.connectSSE()
                .catch { error ->
                    Log.e("OC_ERROR", "SSE collection failed", error)
                    state.updateAndSync(slices) { it.copy(error = "SSE Error: ${error.message}") }
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
                                connectionPhase = "connected"
                            )
                        }
                        callbacks.onSseEvent(event)
                    }
                        .onFailure { error ->
                            Log.e("OC_ERROR", "SSE event failed", error)
                            state.updateAndSync(slices) { it.copy(error = "SSE Error: ${error.message}") }
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
     * Also resets the foreground catch-up state machine via [callbacks] so the
     * next connect is treated as a cold start.
     */
    fun cancelSseForReconfigure() {
        DebugLog.i("SSE", "cancelSse (reconfigure)")
        sseJob?.cancel()
        sseJob = null
        // §Phase1E: a host/profile switch is a fresh server — treat the next
        // connect as a cold start (skip catch-up, the reconfigure path loads
        // sessions/messages itself). Routed to the catch-up controller.
        callbacks.onHostReconfigured()
    }

    companion object {
        private const val TAG = "MainViewModel"
    }
}
