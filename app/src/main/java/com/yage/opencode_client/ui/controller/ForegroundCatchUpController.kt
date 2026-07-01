package com.yage.opencode_client.ui.controller

import com.yage.opencode_client.di.AppLifecycleMonitor
import com.yage.opencode_client.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Callbacks the controller invokes back into MainViewModel (or a future
 * Coordinator) to drive side effects. Defined as an interface rather than a
 * bundle of lambdas so the contract is explicit and the controller never holds
 * a reference to MainViewModel itself — avoiding the circular dependency
 * flagged in R-16 §7.3 (Controller ← MainViewModel that owns it).
 *
 * Each method maps 1:1 to an existing MainViewModel private helper; the
 * implementations are wired in MainViewModel's property initialiser.
 */
interface ForegroundCatchUpCallbacks {
    /** Forces a health-check reconnect bypassing the 30s throttle (ON_START). */
    fun forceReconnect()

    /** Global cold-start reload of [currentId] (clears cache + message state). */
    fun globalColdStartRefresh(currentId: String)

    /** Sets the staleNotice banner (>5min absence tier). */
    fun setStaleNotice()

    /** Discards any in-progress draft before tearing down SSE. */
    fun clearDraft()

    /** Cancels the in-flight SSE feed (ON_STOP). */
    fun cancelSse()

    /** Gap-aware catch-up probe + tail reload (15s–5min tier / network-drop reconnect). */
    fun catchUpAfterDisconnect(sessionId: String)

    /** Current chat session id, or null (draft mode / freshly-closed last tab). */
    fun currentSessionId(): String?
}

/**
 * R-16 M1: owns the foreground/background three-tier catch-up state machine.
 *
 * Tiers (bucketed by how long the app was backgrounded, [backgroundedAtMs]):
 *  - `<15s`   → throttle (suppress SSE reconnect catch-up; rely on the live feed)
 *  - `15s–5min` → let the SSE reconnect's `server.connected` drive a gap-aware
 *    catch-up (single entry point — no double-trigger)
 *  - `>5min`  → global cold-start reload of the current session + stale banner;
 *    suppress the reconnect catch-up (cold-start already loaded)
 *
 * State machine fields (previously @Volatile on MainViewModel) live HERE now;
 * MainViewModel no longer touches them directly. The controller subscribes to
 * [AppLifecycleMonitor.isInForeground] in its own init (so the subscription
 * lifecycle follows the controller, which follows the ViewModel).
 *
 * RFC R-16 §C / §M1. Zero behaviour change vs the pre-extraction logic.
 */
internal class ForegroundCatchUpController(
    private val appLifecycleMonitor: AppLifecycleMonitor,
    private val scope: CoroutineScope,
    private val callbacks: ForegroundCatchUpCallbacks,
    // Injected clock so the three-tier thresholds (<15s / 15s–5min / >5min) are
    // deterministically testable without depending on wall-clock latency in
    // the unit test harness. Defaults to System::currentTimeMillis in production.
    private val clock: () -> Long = { System.currentTimeMillis() },
    /**
     * R2 (docs/省流模式设计.md §5.1):省流守卫。Returns true when省流模式 is on —
     * in that case this controller (SSE-mode foreground catch-up) cedes to the
     * [LowTrafficPoller], which owns foreground sync when SSE is disabled. The
     * guard is a `() -> Boolean` lambda rather than a [SettingsManager]
     * reference so (a) the controller stays decoupled from SettingsManager
     * (R-16 §7.3) and (b) existing tests keep compiling unchanged (default
     * `{ false }` → SSE-mode behaviour preserved). MainViewModel passes
     * `{ settingsManager.lowTrafficMode }` in production.
     */
    private val isLowTrafficMode: () -> Boolean = { false }
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
        // R2 (docs/省流模式设计.md §5.1): 省流守卫. 省流模式下 SSE 关闭 → 无
        // server.connected 事件、cancelSse/forceReconnect 回调天然 no-op →
        // 此 controller 无活可干；前台同步由 LowTrafficPoller 接管（订阅同一个
        // isInForeground，回前台时立即 catchUp flow）。直接 return，避免省流
        // 模式下误触发 globalColdStartRefresh / setStaleNotice 等副作用。
        if (isLowTrafficMode()) {
            return
        }
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
            callbacks.forceReconnect()
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
                    callbacks.currentSessionId()?.let { callbacks.globalColdStartRefresh(it) }
                    callbacks.setStaleNotice()
                }
            }
        } else {
            // Discard any in-progress draft before tearing down SSE so a
            // backgrounded draft does not leak into the next foreground cycle.
            callbacks.clearDraft()
            backgroundedAtMs = clock()
            DebugLog.i("SSE", "cancelSse (background)")
            callbacks.cancelSse()
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
        // §B (glm 重要): 防御性省流守卫——与 onForegroundChanged 对称。省流模式下
        // SSE 未启 (M3)，理论上不会触发本回调；但万一上游误连或事件残留，直接 return
        // 避免在省流模式下重复 catchUp (LowTrafficPoller 已独占前台同步)。
        if (isLowTrafficMode()) {
            return
        }
        val suppress = suppressNextConnectCatchUp
        suppressNextConnectCatchUp = false
        val doCatchUp = sseHasConnectedOnce && !suppress
        DebugLog.i("SSE", "server.connected: sseHasConnectedOnce=$sseHasConnectedOnce suppress=$suppress → ${if (doCatchUp) "catch-up" else "skip"}")
        if (doCatchUp) {
            callbacks.currentSessionId()?.let { callbacks.catchUpAfterDisconnect(it) }
        }
        sseHasConnectedOnce = true
    }

    /**
     * §Phase1E: a host/profile switch is a fresh server — treat the next
     * connect as a cold start (skip catch-up; the reconfigure path loads
     * sessions/messages itself). Called from MainViewModel's host-switch /
     * reset paths.
     */
    fun onHostReconfigured() {
        sseHasConnectedOnce = false
        suppressNextConnectCatchUp = false
    }

    companion object {
        /** Foreground reload throttle window: at most one reload per 15s. */
        const val FOREGROUND_RELOAD_MIN_INTERVAL_MS = 15_000L

        /** Beyond this absence the foreground return becomes a global cold-start. */
        const val LONG_ABSENCE_THRESHOLD_MS = 5 * 60 * 1000L
    }
}
