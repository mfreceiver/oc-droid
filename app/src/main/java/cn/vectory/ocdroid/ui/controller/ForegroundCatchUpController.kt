package cn.vectory.ocdroid.ui.controller

import cn.vectory.ocdroid.data.model.QuestionRequest
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.di.AppLifecycleMonitor
import cn.vectory.ocdroid.ui.SessionListState
import cn.vectory.ocdroid.ui.SharedEffectBus
import cn.vectory.ocdroid.ui.SharedStateStore
import cn.vectory.ocdroid.util.DebugLog
import cn.vectory.ocdroid.util.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * R-16 M1 → R-17 batch3b: owns the foreground/background three-tier catch-up
 * state machine.
 *
 * **Migration (batch 3b)**: the [ForegroundCatchUpCallbacks] interface was
 * eliminated. The 4 cross-domain signals (forceReconnect /
 * globalColdStartRefresh / cancelSse / catchUpAfterDisconnect) now emit
 * [ControllerEffect]s on [effects] (rule B). The 3 same-domain operations
 * (setStaleNotice / clearDraft / currentSessionId) now run inline against the
 * injected [chatFlow] / [composerFlow] / [settingsManager] (rule A — they
 * only touch state the controller can reach directly).
 *
 * Tiers (bucketed by how long the app was backgrounded, [backgroundedAtMs]):
 *  - `<15s`   → throttle (suppress SSE reconnect catch-up; rely on the live feed)
 *  - `15s–5min` → let the SSE reconnect's `server.connected` drive a gap aware
 *    catch-up (single entry point — no double-trigger)
 *  - `>5min`  → global cold-start reload of the current session + stale banner;
 *    suppress the reconnect catch-up (cold-start already loaded)
 *
 * State machine fields (previously @Volatile on MainViewModel) live HERE now;
 * the orchestrator no longer touches them directly. The controller subscribes
 * to [AppLifecycleMonitor.isInForeground] in its own init (so the subscription
 * lifecycle follows the controller, which follows the orchestrator).
 *
 * RFC R-16 §C / §M1. Zero behaviour change vs the pre-extraction logic.
 */
internal class ForegroundCatchUpController(
    private val appLifecycleMonitor: AppLifecycleMonitor,
    private val scope: CoroutineScope,
    private val store: SharedStateStore,
    private val settingsManager: SettingsManager,
    private val effects: SharedEffectBus,
    // Injected clock so the three-tier thresholds (<15s / 15s–5min / >5min) are
    // deterministically testable without depending on wall-clock latency in
    // the unit test harness. Defaults to System::currentTimeMillis in production.
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    /**
     * Guards the very first [AppLifecycleMonitor.isInForeground] emission.
     * StateFlow always delivers its current value to a new collector, so
     * without this flag the catch-up would fire spuriously on ViewModel
     * construction. The first emission is treated as the baseline "current
     * state"; only subsequent transitions act.
     */
    @Volatile private var hasObservedForegroundState: Boolean = false

    /** Timestamp (epoch ms) of the most recent message load (throttle anchor). */
    @Volatile private var lastLoadAtMs: Long = 0L

    /**
     * Tracks whether the SSE feed has connected AT LEAST ONCE in this process.
     * The catch-up trigger in [onServerConnected] runs on every reconnect
     * EXCEPT the very first (cold start has no local history to diff). Fires
     * for BOTH reconnect kinds: foreground startSSE reconnects AND in-flow
     * `retryWhen` reconnects inside SSEClient.connect. Reset to false on host
     * reconfigure (new server = fresh cold start) via [onHostReconfigured].
     */
    @Volatile private var sseHasConnectedOnce: Boolean = false

    /** Epoch ms of the last ON_STOP (enter background). Buckets the foreground return. */
    @Volatile private var backgroundedAtMs: Long = 0L

    /**
     * Set by [onForegroundChanged] to suppress the catch-up that the SSE
     * reconnect's `server.connected` would otherwise fire. Consumed (reset to
     * false) in [onServerConnected] after the first connect.
     *   - `<15s` tier  → suppress (rely on the live SSE feed)
     *   - `15s–5min`   → DON'T suppress (let server.connected drive the catch-up)
     *   - `>5min` tier → suppress (globalColdStartRefresh already loaded)
     */
    @Volatile private var suppressNextConnectCatchUp: Boolean = false

    init {
        // §15.2: foreground/background hook (onEach+launchIn, NOT a suspend
        // `collect`, since init is synchronous and cannot block).
        appLifecycleMonitor.isInForeground
            .onEach { onForegroundChanged(it) }
            .launchIn(scope)
    }

    /**
     * §15.2 / R-A: process foreground/background transitions.
     *
     * On **enter foreground** (ON_START): force a connection check, then — at
     * most once per [FOREGROUND_RELOAD_MIN_INTERVAL_MS] — wipe stale streaming
     * buffers and reload the current session (the wipe+reload are coupled).
     *
     * On **enter background** (ON_STOP): cancel the SSE feed (R-A: saves data,
     * avoids half-open sockets).
     */
    fun onForegroundChanged(inForeground: Boolean) {
        // The first emission is the current state, not a transition — skip so
        // the ViewModel's very first subscribe does not spuriously reload.
        if (!hasObservedForegroundState) {
            hasObservedForegroundState = true
            return
        }
        if (inForeground) {
            // §Phase1E (glm-1 🟠-1): clear any residual suppress flag FIRST. If
            // a prior foreground cycle set it but the SSE reconnect's
            // server.connected never arrived, the flag would linger and
            // suppress the NEXT cycle's catch-up — permanently hiding messages.
            suppressNextConnectCatchUp = false
            // §R18 Phase 3 Wave 1 (P1-3 B 类): 单发非 suspend → tryEmitEffect。
            effects.tryEmitEffect(ControllerEffect.ForceReconnect)
            val now = clock()
            val bgGapMs = if (backgroundedAtMs > 0L) now - backgroundedAtMs else Long.MAX_VALUE
            when {
                bgGapMs < FOREGROUND_RELOAD_MIN_INTERVAL_MS -> {
                    // Throttled: suppress the SSE reconnect's catch-up too.
                    suppressNextConnectCatchUp = true
                }
                bgGapMs <= LONG_ABSENCE_THRESHOLD_MS -> {
                    // Medium absence: let server.connected drive the catch-up
                    // (single entry point). Just stamp the load timestamp.
                    lastLoadAtMs = now
                }
                else -> {
                    // Long absence: cold-start the current session + stale banner.
                    // Suppress the reconnect catch-up — cold-start already loaded.
                    lastLoadAtMs = now
                    suppressNextConnectCatchUp = true
                    currentSessionId()?.let {
                        // §R18 Phase 3 Wave 1 (P1-3 B 类): 单发非 suspend → tryEmitEffect。
                        effects.tryEmitEffect(ControllerEffect.GlobalColdStartRefresh(it))
                    }
                    setStaleNotice()
                }
            }
        } else {
            // Discard any in-progress draft before tearing down SSE so a
            // backgrounded draft does not leak into the next foreground cycle.
            clearDraft()
            backgroundedAtMs = clock()
            DebugLog.i("SSE", "cancelSse (background)")
            // §R18 Phase 3 Wave 1 (P1-3 B 类): 单发非 suspend → tryEmitEffect。
            effects.tryEmitEffect(ControllerEffect.CancelSse)
        }
    }

    /**
     * §Phase1E: invoked from the SSE event handler on every `server.connected`
     * frame. Catch-up runs on every connect EXCEPT the very first process-time
     * connect (cold start). [suppressNextConnectCatchUp] (set by the foreground
     * tiers) skips the probe for `<15s` (throttled) and `>5min` (cold-start
     * already loaded). Consumed once so only a genuine network-drop reconnect
     * or the 15s–5min tier catches up.
     */
    fun onServerConnected() {
        val suppress = suppressNextConnectCatchUp
        suppressNextConnectCatchUp = false
        val doCatchUp = sseHasConnectedOnce && !suppress
        DebugLog.i("SSE", "server.connected: sseHasConnectedOnce=$sseHasConnectedOnce suppress=$suppress → ${if (doCatchUp) "catch-up" else "skip"}")
        if (doCatchUp) {
            currentSessionId()?.let {
                // §R18 Phase 3 Wave 1 (P1-3 B 类): 单发非 suspend → tryEmitEffect。
                effects.tryEmitEffect(ControllerEffect.CatchUpAfterDisconnect(it))
            }
        }
        sseHasConnectedOnce = true
    }

    /**
     * §Phase1E: a host/profile switch is a fresh server — treat the next
     * connect as a cold start (skip catch-up; the reconfigure path loads
     * sessions/messages itself). Called from the host-switch / reset paths.
     */
    fun onHostReconfigured() {
        sseHasConnectedOnce = false
        suppressNextConnectCatchUp = false
    }

    /**
     * Inline (rule A) — writes the staleNotice banner directly to [chatFlow].
     * Was a [ForegroundCatchUpCallbacks] method; eliminated in batch 3b.
     */
    private fun setStaleNotice() {
        store.mutateChat { it.copy(staleNotice = true) }
    }

    /**
     * Inline (rule A) — drops an in-progress draft before tearing down SSE so
     * a backgrounded draft does not leak into the next foreground cycle.
     * Mirrors [ComposerController.clearDraftIfActive]. Eliminated in batch 3b.
     */
    private fun clearDraft() {
        if (store.composerFlow.value.draftWorkdir == null || store.chatFlow.value.currentSessionId != null) return
        settingsManager.currentWorkdir = null
        store.mutateComposer {
            it.copy(draftWorkdir = null, inputText = "", imageAttachments = emptyList())
        }
    }

    /**
     * Inline (rule A) — reads currentSessionId directly from [SharedStateStore.chatFlow].
     * Eliminated in batch 3b.
     */
    private fun currentSessionId(): String? = store.chatFlow.value.currentSessionId

    // ── §R18 Phase 3 Wave 1 (P1-9): multi-workdir pending questions fan-out ──

    /**
     * §P1-9: catch-up pending questions across EVERY known workdir (the in-
     * memory `directorySessions` keys + `currentWorkdir`), not just the single
     * [SettingsManager.currentWorkdir]. Without this, questions arriving for
     * a background workdir (one the user previously connected to but isn't
     * viewing right now) are lost: the AppCore dispatch handler for
     * `LoadPendingQuestions` reads only `currentWorkdir`, so a `question.asked`
     * SSE event for any other workdir is fetched-then-immediately-overwritten
     * by the next currentWorkdir poll.
     *
     * This controller does NOT own [cn.vectory.ocdroid.ui.SliceFlows] (the
     * batch 3b migration gave it only the [SharedStateStore.chatFlow] +
     * [SharedStateStore.composerFlow] it writes), so the workdir set is
     * supplied by the caller (typically AppCore, which has both). Each
     * workdir's `getPendingQuestions(dir)` is launched in [scope]; results
     * are merged by id into the sessionList slice via
     * [SharedStateStore.mutateSessionList] (`byGet` wins, pre-existing fills
     * gaps — same merge semantics as `launchLoadPendingQuestions`).
     *
     * §scope-note: AppCore (out of this wave's write scope) needs to call
     * this from its catch-up paths to wire production. The method is
     * exercised directly by [ForegroundCatchUpControllerTest].
     */
    fun catchUpPendingQuestionsAllWorkdirs(
        repository: OpenCodeRepository,
        workdirs: List<String>,
        tag: String = "ForegroundCatchUp",
    ) {
        if (workdirs.isEmpty()) return
        workdirs.forEach { dir ->
            scope.launch {
                repository.getPendingQuestions(dir)
                    .onSuccess { questions ->
                        mergePendingQuestionsById(store::mutateSessionList, questions)
                    }
                    .onFailure { error ->
                        DebugLog.w(tag, "catchUp getPendingQuestions failed for $dir: ${error.message}")
                    }
            }
        }
    }

    companion object {
        /** Foreground reload throttle window: at most one reload per 15s. */
        const val FOREGROUND_RELOAD_MIN_INTERVAL_MS = 15_000L

        /** Beyond this absence the foreground return becomes a global cold-start. */
        const val LONG_ABSENCE_THRESHOLD_MS = 5 * 60 * 1000L
    }
}

// ── §R18 Phase 3 Wave 1 (P1-9): multi-workdir pending-questions merge helper ──

/**
 * Merges [incoming] into the sessionList slice's `pendingQuestions` by id,
 * with the freshly-fetched [incoming] winning (byGet semantics — mirrors the
 * pre-fan-out `launchLoadPendingQuestions` merge so the catch-up fan-out's
 * per-directory results compose without dropping prior SSE-delivered
 * questions that the server's get endpoint doesn't echo back).
 *
 * §R18 Phase 4 (P0-9): the write funnels through [mutateSessionList] (a
 * per-slice mutate-function reference from [SharedStateStore] /
 * [cn.vectory.ocdroid.ui.SliceFlows]). Pure CAS — safe for the concurrent
 * per-workdir fan-out (each launch's onSuccess may land in any order).
 */
internal fun mergePendingQuestionsById(
    mutateSessionList: ((SessionListState) -> SessionListState) -> Unit,
    incoming: List<QuestionRequest>,
) {
    if (incoming.isEmpty()) return
    mutateSessionList { currentState ->
        val byGet = incoming.associateBy { it.id }
        val existing = currentState.pendingQuestions.associateBy { it.id }
        val merged = (byGet + existing.filterKeys { it !in byGet }).values.toList()
        currentState.copy(pendingQuestions = merged)
    }
}
