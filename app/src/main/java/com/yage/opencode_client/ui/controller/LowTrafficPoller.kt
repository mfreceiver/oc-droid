package com.yage.opencode_client.ui.controller

import com.yage.opencode_client.data.model.PermissionRequest
import com.yage.opencode_client.data.model.QuestionRequest
import com.yage.opencode_client.data.model.SessionStatus
import com.yage.opencode_client.data.repository.OpenCodeRepository
import com.yage.opencode_client.di.AppLifecycleMonitor
import com.yage.opencode_client.ui.AppState
import com.yage.opencode_client.ui.SliceFlows
import com.yage.opencode_client.ui.updateAndSync
import com.yage.opencode_client.util.DebugLog
import com.yage.opencode_client.util.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * M1 (docs/省流模式设计.md §1): callbacks the [LowTrafficPoller] invokes back
 * into MainViewModel for the side effects a poll tick can trigger.
 *
 * Defined as an interface rather than a bundle of lambdas / a direct
 * MainViewModel reference so the poller stays decoupled from its owner
 * (mirrors [ForegroundCatchUpCallbacks] / [SessionSyncCoordinatorCallbacks] —
 * R-16 §7.3 circular-dependency avoidance). Each method maps 1:1 to an existing
 * MainViewModel helper; MainViewModel implements the interface in its property
 * initialiser block.
 */
interface LowTrafficPollerCallbacks {
    /** Currently-open chat session id, or null (draft mode / no tab). */
    fun currentSessionId(): String?

    /**
     * Authoritative catch-up entry point — forwards to the existing
     * `launchCatchUp` wrapper (`MainViewModel.catchUpAfterDisconnectOrForeground`,
     * `MainViewModelSessionActions.kt:738`). M2 already parameterised the
     * sentinel limit to 4; the poller does NOT re-implement reload logic.
     * Sources: active→idle jump / time.updated change / content probe hit /
     * foreground return / session switch.
     */
    fun triggerCatchUp(sessionId: String)

    /**
     * Top banner "省流模式下可能不是最新，点击检查" — fires when the unchanged
     * counter reaches [LowTrafficPoller.MAX_UNCHANGED]. Reuses the existing
     * staleNotice mechanism (§1.5: non-modal hint,文案不暗示"无变化"可靠).
     */
    fun onStaleHint()

    /** `retry` status persisted ≥ [LowTrafficPoller.RETRY_HINT_TICKS] ticks (gpter 致命#3). */
    fun onRetryError(sessionId: String)

    /** status poll failed ≥ [LowTrafficPoller.MAX_STATUS_FAILURES] consecutive times (B4). */
    fun onConnectionError()

    /** A status poll succeeded again after [onConnectionError] had fired. */
    fun onConnectionRecovered()

    /** `GET /permission` result (省流前台补足, dser 🔴-3) → state.pendingPermissions. */
    fun onPendingPermissionsLoaded(list: List<PermissionRequest>)

    /** `GET /question` result (省流前台补足, dser 🔴-3) → state.pendingQuestions. */
    fun onPendingQuestionsLoaded(list: List<QuestionRequest>)
}

/**
 * M1 (docs/省流模式设计.md §1): the省流模式 polling state machine.
 *
 * SSE is disabled in省流模式 (M3), so this poller is the sole driver of current-
 * session sync. Per tick (5s base) it issues `GET /session/status` +
 * `GET /permission` + `GET /question`, then folds the current session's status
 * through the三层信号 state machine:
 *
 *  - **status (主驱动)** — `busy`/`retry` are active; `active→idle` jump triggers
 *    catchUp. `retry` ≥ 3 ticks surfaces a "运行出错" hint (gpter 致命#3).
 *  - **time.updated (辅)** — every 3 idle ticks, probe `GET /session/{id}` and
 *    compare `time.updated`. Unreliable alone (MessageUpdated/PartUpdated don't
 *    bump it), so a no-change here does NOT accumulate the sleep counter.
 *  - **content probe (权威兜底)** — every 6 ticks (30s, decoupled from backoff)
 *    `probeLatestMessageId` vs local `anchorNewestId` (max-by time.created).
 *    Hit → catchUp; unchanged → increment sleep counter → [MAX_UNCHANGED] stale hint.
 *
 * Failure handling (B4): a failed status poll leaves lastStatus / counters
 * untouched; ≥ 3 consecutive failures → [onConnectionError] + tick backoff
 * (5s→10s→20s→30s cap). A single success → [onConnectionRecovered] + reset.
 *
 * Idle backoff (§1.6): 6 consecutive idle ticks without a reload → tick grows
 * to 30s. The content probe interval stays ≤ 30s wall-time regardless (decoupled
 * from the tick length) so backoff can't stretch probing to 90s.
 *
 * Lifecycle: foreground-only. `start()`/`stop()` are driven by
 * [SettingsManager.lowTrafficMode] via MainViewModel.onLowTrafficModeChanged
 * (M0). Entering background pauses the loop (省电); returning to foreground
 * resets the tick + fires an immediate catchUp (回前台是 catchUp 边界, gpter 致命#2).
 *
 * Constructor-injected scope / clock / tick mirror [ForegroundCatchUpController]
 * so the state machine is deterministically testable without wall-clock latency.
 */
@Suppress("DEPRECATION")
internal class LowTrafficPoller(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<AppState>,
    private val slices: SliceFlows,
    private val repository: OpenCodeRepository,
    @Suppress("UNUSED_PARAMETER") private val settingsManager: SettingsManager,
    private val appLifecycleMonitor: AppLifecycleMonitor,
    private val callbacks: LowTrafficPollerCallbacks,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val tickMs: Long = BASE_TICK_MS,
) {
    init {
        // §15.2 / ForegroundCatchUpController:106 — onEach+launchIn (init is
        // synchronous; a suspend `collect` would block construction). The
        // subscription lifecycle follows the poller, which follows the ViewModel.
        appLifecycleMonitor.isInForeground
            .onEach { onForegroundChanged(it) }
            .launchIn(scope)
    }

    // ── lifecycle ──────────────────────────────────────────────────────────

    @Volatile private var active: Boolean = false

    /**
     * Guards the very first [AppLifecycleMonitor.isInForeground] emission
     * (StateFlow always replays its current value to a new collector). Without
     * this flag the foreground-return catchUp would fire spuriously on
     * construction. Mirrors [ForegroundCatchUpController.hasObservedForegroundState].
     */
    @Volatile private var hasObservedForegroundState: Boolean = false

    private var loopJob: Job? = null

    /** Idempotent — a repeated start while active is a no-op. */
    fun start() {
        if (active) return
        active = true
        DebugLog.i(TAG, "start: low-traffic poller activated (tick=${tickMs}ms)")
        // Only run the loop while the process is foregrounded; if the user
        // toggled省流 while backgrounded, onForegroundChanged (next transition)
        // will resume. The init subscription already consumed the baseline
        // emission, so no foreground re-emit arrives from start() itself.
        if (appLifecycleMonitor.isInForeground.value) {
            launchLoop()
        }
    }

    /** Cancels the loop and wipes all per-session state. Idempotent. */
    fun stop() {
        if (!active) return
        active = false
        loopJob?.cancel()
        loopJob = null
        resetCounters()
        DebugLog.i(TAG, "stop: low-traffic poller deactivated")
    }

    // ── external events ────────────────────────────────────────────────────

    /**
     * §1 / §2: foreground transitions. Background = pause loop (省电). Foreground
     * return = reset tick (kill idle + network backoff) + relaunch loop +
     * immediate catchUp (回前台是 catchUp 边界, gpter 致命#2).
     */
    fun onForegroundChanged(inForeground: Boolean) {
        if (!hasObservedForegroundState) {
            hasObservedForegroundState = true
            return
        }
        if (!active) return
        if (inForeground) {
            DebugLog.i(TAG, "foreground return → reset tick + immediate catchUp")
            consecutiveStatusFailures = 0
            connectionErrorNotified = false
            networkBackoffTickMs = tickMs
            resetIdleBackoff()
            launchLoop()
            callbacks.currentSessionId()?.let { triggerCatchUpInternal(it) }
        } else {
            DebugLog.i(TAG, "background → pause loop")
            loopJob?.cancel()
            loopJob = null
        }
    }

    /**
     * §1.7 (G): switching tabs freezes the outgoing session and immediately
     * catches up the new one. Reset every counter + tick to 5s + fire catchUp.
     */
    fun onSessionSwitched(sessionId: String) {
        if (!active) return
        DebugLog.i(TAG, "session switched → $sessionId (reset + catchUp)")
        resetCounters()
        triggerCatchUpInternal(sessionId)
        resetTickAndRelaunch()
    }

    /**
     * §1.9 (gpter 可选#3): locally mark the session busy so the next status
     * poll can observe active→idle authoritatively. Reset tick to 5s (kill
     * backoff) + reset idle/retry counters. Does NOT fire catchUp (the
     * active→idle transition will).
     *
     * §A (glm): also stamp [localBusyUntil] for [LOCAL_BUSY_WINDOW_MS] so the
     * next doTick's full-map overwrite of `sessionStatuses` doesn't clobber
     * this local busy badge with the server's stale idle (UI 闪回).
     */
    fun onMessageSent(sessionId: String) {
        if (!active) return
        DebugLog.i(TAG, "message sent → local busy + reset tick")
        state.updateAndSync(slices) {
            it.copy(sessionStatuses = it.sessionStatuses + (sessionId to SessionStatus(type = "busy")))
        }
        lastStatus = SessionStatus(type = "busy")
        // §A: keep the local busy badge alive across the next status overwrite
        // (server may still report idle until its own state machine catches up).
        localBusyUntil[sessionId] = clock() + LOCAL_BUSY_WINDOW_MS
        consecutiveUnchanged = 0
        consecutiveIdleWithoutReload = 0
        consecutiveRetryTicks = 0
        idleBackoffActive = false
        networkBackoffTickMs = tickMs
        resetTickAndRelaunch()
    }

    // ── the tick loop ──────────────────────────────────────────────────────

    private fun launchLoop() {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch {
            while (isActive) {
                delay(effectiveTickMs())
                doTick()
            }
        }
    }

    private fun resetTickAndRelaunch() {
        loopJob?.cancel()
        launchLoop()
    }

    /**
     * Effective tick interval: the idle backoff (5s→30s) and the network
     * failure ladder (5s→10s→20s→30s) are independent — take the max so the
     * more aggressive constraint wins.
     */
    private fun effectiveTickMs(): Long {
        val base = if (idleBackoffActive) BACKOFF_TICK_MS else tickMs
        return maxOf(base, networkBackoffTickMs)
    }

    /**
     * One poll tick. Public-visible (internal) so unit tests can drive the
     * state machine deterministically without virtual-time gymnastics — the
     * `while(isActive){delay;doTick}` wrapper is trivial; the logic lives here.
     */
    internal suspend fun doTick() {
        if (!active) return
        val sessionId = callbacks.currentSessionId() ?: return
        // §E (kimo 重要): isLoadingMessages=true 时整 tick return 会让 badge 失同步
        // (doTickForSession 的 sessionStatuses 全量写入是 badge 唯一驱动)。改为：
        // 仍跑 status/permission/question 写入，只跳过 probe / 计数器推进 / catchUp
        // 状态机 (skipStateMachine)。tickCounter 也冻结——它是 probe 节拍来源。
        val skipStateMachine = state.value.isLoadingMessages
        if (!skipStateMachine) tickCounter++
        doTickForSession(sessionId, skipStateMachine = skipStateMachine)
    }

    private suspend fun doTickForSession(sessionId: String, skipStateMachine: Boolean = false) {
        // ── ① status poll (B4: failure must not change lastStatus / counters) ──
        val statusResult = repository.getSessionStatus()
        if (statusResult.isFailure) {
            // §E: isLoading 期间不动失败计数器 (loading 本身就是"正在工作"信号)。
            if (skipStateMachine) return
            consecutiveStatusFailures++
            DebugLog.w(TAG, "status poll failed (#$consecutiveStatusFailures)")
            if (consecutiveStatusFailures == MAX_STATUS_FAILURES) {
                connectionErrorNotified = true
                callbacks.onConnectionError()
            }
            if (consecutiveStatusFailures >= MAX_STATUS_FAILURES) {
                stepNetworkBackoff()
            }
            return
        }
        // Recovery: a single success after the error tier → recovered + reset.
        // §E: 仅在状态机活跃时清——isLoading 期间不干扰连接健康计数器。
        if (!skipStateMachine) {
            if (connectionErrorNotified) {
                connectionErrorNotified = false
                callbacks.onConnectionRecovered()
            }
            consecutiveStatusFailures = 0
            networkBackoffTickMs = tickMs
        }

        val rawStatuses = statusResult.getOrDefault(emptyMap())
        // §A (glm 重要): 每 tick 用 server status 整 map 覆盖会把 onMessageSent 写
        // 的本地 busy 标记冲掉 (server 可能仍报 idle)，UI 闪回 idle。对在
        // [localBusyUntil] 窗口内的 session，server 报 idle/missing 时保留 busy。
        // 注意：状态机逻辑用 rawStatuses (server 真实态) 做 active→idle 检测，
        // 绝不被本地标记干扰——本地 busy 只影响 UI badge。
        val gcNow = clock()
        localBusyUntil.entries.removeAll { it.value <= gcNow }
        val uiStatuses = if (localBusyUntil.isEmpty()) {
            rawStatuses
        } else {
            rawStatuses.mapValues { (id, s) ->
                if (id in localBusyUntil && !s.isBusy && !s.isRetry) {
                    SessionStatus(type = "busy")
                } else {
                    s
                }
            }
        }
        // §1.7: write the full status map every tick — non-current tabs get a
        // lightweight busy/idle badge refresh without any per-tab message load.
        state.updateAndSync(slices) { it.copy(sessionStatuses = uiStatuses) }

        // ── ② ③ permission / question (省流前台补足, dser 🔴-3) ──
        // Best-effort: a failure here is swallowed (the status poll above is
        // the authoritative connection-health signal). The next tick retries.
        // §E: 这两个写入在 isLoading 期间也跑 (pendingPermissions/Questions 是
        // 前台补足信号，与 message load 互不干扰)。
        repository.getPendingPermissions()
            .onSuccess { callbacks.onPendingPermissionsLoaded(it) }
        repository.getPendingQuestions()
            .onSuccess { callbacks.onPendingQuestionsLoaded(it) }

        // §E: isLoading 期间到此为止——不推进 probe / 计数器 / catchUp 状态机。
        if (skipStateMachine) return

        val currentStatus = rawStatuses[sessionId]
        if (currentStatus == null) {
            // §C (glm+gpter 重要): 当前 session 不在 status map (draft / unknown id)
            // → 跳过本轮。**不动 lastStatus** —— 保留上一态用于后续 active→idle 跳变
            // 检测 (旧实现误清 lastStatus=null 导致下一态丢失 wasActive 信号)。
            return
        }

        val isNowActive = currentStatus.isBusy || currentStatus.isRetry
        val wasActive = lastStatus?.isBusy == true || lastStatus?.isRetry == true

        if (isNowActive) {
            // §1.2: retry is also active (gpter 致命#3: retry = run in progress).
            if (currentStatus.isRetry) {
                consecutiveRetryTicks++
                if (consecutiveRetryTicks == RETRY_HINT_TICKS) {
                    callbacks.onRetryError(sessionId)
                }
            } else {
                consecutiveRetryTicks = 0
            }
            // §1.5: active state neither accumulates nor resets the sleep
            // counter (hold). The active→idle reload path is the authoritative
            // reset; a content-probe hit after settling idle also resets.
            lastStatus = currentStatus
            return
        }

        // active→idle jump → catchUp (主驱动, §1.1).
        if (wasActive) {
            consecutiveRetryTicks = 0
            lastStatus = currentStatus
            triggerCatchUpInternal(sessionId)
            return
        }

        // ── steady idle ──
        // §1.6: every idle tick counts toward the 6-tick idle-backoff threshold.
        consecutiveIdleWithoutReload++
        if (consecutiveIdleWithoutReload >= IDLE_BACKOFF_TICKS && !idleBackoffActive) {
            idleBackoffActive = true
            DebugLog.i(TAG, "idle backoff engaged → tick=${BACKOFF_TICK_MS}ms")
        }

        val now = clock()

        // [layer 2] time.updated probe — every 3 ticks (§1.1 辅信号).
        if (tickCounter % TIME_PROBE_INTERVAL_TICKS == 0) {
            val serverUpdated = repository.getSession(sessionId).getOrNull()?.time?.updated
            if (serverUpdated != null) {
                val previous = lastTimeUpdated
                lastTimeUpdated = serverUpdated
                if (previous != null && serverUpdated != previous) {
                    // time.updated moved → catchUp. (No increment: this IS the
                    // change signal; the content probe is the兜底, not this.)
                    lastStatus = currentStatus
                    triggerCatchUpInternal(sessionId)
                    return
                }
                // Else unchanged → do NOT increment the sleep counter
                // (time.updated is unreliable on its own; the content probe
                // owns the权威变化信号).
            }
        }

        // [layer 3] content probe (权威兜底) — every 6 ticks OR ≥30s wall-time
        // (decoupled from the idle backoff tick so backoff can't stretch the
        // probe interval to 90s — gpter 可选#1).
        if (shouldRunContentProbe(now)) {
            lastContentProbeAtMs = now
            val probeId = repository.probeLatestMessageId(sessionId).getOrNull()
            // anchorNewestId = newest by time.created (NOT messages.last() —
            // the list is oldest-first but order-independence is safer, ora-2).
            val anchorNewestId = state.value.messages
                .maxByOrNull { it.time?.created ?: -1L }?.id
            if (probeId != null && anchorNewestId != null && probeId != anchorNewestId) {
                // §1.1/§3: authoritative hit → catchUp + reset counter.
                lastStatus = currentStatus
                triggerCatchUpInternal(sessionId)
                return
            }
            if (probeId != null && anchorNewestId != null && probeId == anchorNewestId) {
                consecutiveUnchanged++
                if (consecutiveUnchanged == MAX_UNCHANGED) {
                    callbacks.onStaleHint()
                }
            }
            // probe failure (probeId null) → skip increment this tick; the
            // status poll above is the authoritative health signal.
        }

        lastStatus = currentStatus
    }

    private fun shouldRunContentProbe(now: Long): Boolean {
        val byTick = tickCounter % CONTENT_PROBE_INTERVAL_TICKS == 0
        val byTime = lastContentProbeAtMs > 0L &&
            now - lastContentProbeAtMs >= CONTENT_PROBE_INTERVAL_MS
        return byTick || byTime
    }

    private fun stepNetworkBackoff() {
        networkBackoffTickMs = when (networkBackoffTickMs) {
            tickMs -> NETWORK_BACKOFF_LADDER[0]
            NETWORK_BACKOFF_LADDER[0] -> NETWORK_BACKOFF_LADDER[1]
            else -> NETWORK_BACKOFF_LADDER[2]
        }
        DebugLog.i(TAG, "network backoff → tick=${networkBackoffTickMs}ms")
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /** Centralised catchUp trigger: every internal callsite resets the idle
     *  backoff + sleep counter first (a reload is the authoritative "we're
     *  current again" signal), then forwards to the callback. */
    private fun triggerCatchUpInternal(sessionId: String) {
        consecutiveUnchanged = 0
        consecutiveIdleWithoutReload = 0
        idleBackoffActive = false
        callbacks.triggerCatchUp(sessionId)
    }

    private fun resetIdleBackoff() {
        idleBackoffActive = false
        consecutiveIdleWithoutReload = 0
    }

    private fun resetCounters() {
        tickCounter = 0
        lastStatus = null
        lastTimeUpdated = null
        lastContentProbeAtMs = 0L
        consecutiveUnchanged = 0
        consecutiveIdleWithoutReload = 0
        consecutiveRetryTicks = 0
        consecutiveStatusFailures = 0
        connectionErrorNotified = false
        idleBackoffActive = false
        networkBackoffTickMs = tickMs
    }

    // ── state machine fields (all @Volatile: written from the loop / public
    //    API on the injected scope, read back on the same scope; @Volatile
    //    guarantees the next tick sees the latest write) ───────────────────

    @Volatile private var tickCounter: Int = 0
    @Volatile private var lastStatus: SessionStatus? = null
    @Volatile private var lastTimeUpdated: Long? = null
    @Volatile private var lastContentProbeAtMs: Long = 0L
    @Volatile private var consecutiveUnchanged: Int = 0
    @Volatile private var consecutiveIdleWithoutReload: Int = 0
    @Volatile private var consecutiveRetryTicks: Int = 0
    @Volatile private var consecutiveStatusFailures: Int = 0
    @Volatile private var connectionErrorNotified: Boolean = false
    @Volatile private var idleBackoffActive: Boolean = false
    @Volatile private var networkBackoffTickMs: Long = tickMs

    /**
     * §A (glm 重要): per-session "本地 busy 截止时间戳"。`onMessageSent` 写入
     * `now + LOCAL_BUSY_WINDOW_MS`；doTick 写 sessionStatuses 时对窗口内且 server
     * 报 idle 的 session 保留 busy (UI 不闪回)。条目在 doTick 中按时间戳 GC。
     *
     * 所有读写都在注入 scope (单一 dispatcher) 上，与其它状态机字段一致——
     * 内容访问无竞争；引用本身永不重新赋值，故无需 @Volatile。
     */
    private val localBusyUntil: MutableMap<String, Long> = mutableMapOf()

    // ── test hooks (internal; the loop wrapper + backoff ladders are trivial
    //    to assert via these readouts instead of virtual-time acrobatics) ───

    /** Current effective tick interval — asserts the idle/network backoff state. */
    internal fun currentTickMsForTest(): Long = effectiveTickMs()

    /** Number of consecutive unchanged content probes (drives [onStaleHint]). */
    internal fun unchangedCounterForTest(): Int = consecutiveUnchanged

    companion object {
        private const val TAG = "LowTrafficPoller"

        /** §7: base poll interval. */
        const val BASE_TICK_MS = 5_000L

        /** §1.6: idle-backoff tick interval (after 6 idle ticks w/o reload). */
        const val BACKOFF_TICK_MS = 30_000L

        /** §7: time.updated probe frequency (idle-only, every 3 ticks = 15s). */
        const val TIME_PROBE_INTERVAL_TICKS = 3

        /** §7: content probe frequency in ticks (every 6 = 30s at base tick). */
        const val CONTENT_PROBE_INTERVAL_TICKS = 6

        /** §1.6: content probe wall-clock cap (decoupled from the backoff tick). */
        const val CONTENT_PROBE_INTERVAL_MS = 30_000L

        /** §7: unchanged-probe count that triggers the stale hint (≈150s). */
        const val MAX_UNCHANGED = 5

        /** §1.6: idle ticks w/o a reload before the tick grows to [BACKOFF_TICK_MS]. */
        const val IDLE_BACKOFF_TICKS = 6

        /** §1.1: consecutive status failures before [onConnectionError] + backoff. */
        const val MAX_STATUS_FAILURES = 3

        /** §1.2: consecutive retry ticks before [onRetryError] (gpter 致命#3). */
        const val RETRY_HINT_TICKS = 3

        /** §1.6: network-failure tick ladder (5s→10s→20s→30s cap). */
        private val NETWORK_BACKOFF_LADDER = longArrayOf(10_000L, 20_000L, 30_000L)

        /**
         * §A (glm 重要): onMessageSent 后本地 busy 标记在 sessionStatuses 整 map
         * 覆盖中保留的窗口。10s 足够 server 状态机跟上 (典型 busy→idle 几秒内)；
         * 超时后 GC，让 server 真实态接管 badge。
         */
        const val LOCAL_BUSY_WINDOW_MS = 10_000L
    }
}
