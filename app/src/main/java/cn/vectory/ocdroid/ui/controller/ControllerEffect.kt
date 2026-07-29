package cn.vectory.ocdroid.ui.controller

import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.service.events.IdentifiedSseEvent
import cn.vectory.ocdroid.service.lifecycle.SseLifecyclePolicy.SseRestFence

/**
 * R-17 batch3: cross-domain controller→VM signals. Replaces the 6 callback
 * interfaces ([ForegroundCatchUpCallbacks], [ComposerCallbacks],
 * [SessionSwitcherCallbacks], [HostProfileCallbacks],
 * [ConnectionCoordinatorCallbacks], [SessionSyncCoordinatorCallbacks]).
 *
 * Sent through [cn.vectory.ocdroid.ui.SharedEffectBus] — controllers emit via
 * `effects.tryEmit(...)`, AppCore (or, in the future 6-VM target, the
 * OrchestratorVM) collects and dispatches (calling its own methods or emitting
 * UiEvent / writing slices directly).
 *
 * Design ref: docs/tech-debt/batch3-vm-split-design.md appendix B + batch 3b
 * additions (LoadSessions / LoadAgents / LoadProviders / LoadPendingPermissions
 * / OnSseEvent / ClearSessionWindowCache) needed for the flat AppCore model
 * where sibling controllers cannot reach each other directly.
 */
sealed class ControllerEffect {
    // ── ForegroundCatchUpController ──
    /** Forces a health-check reconnect bypassing the 30s throttle. */
    data object ForceReconnect : ControllerEffect()
    /** Global cold-start reload: clear cache + message state for [sessionId]. */
    data class GlobalColdStartRefresh(val sessionId: String) : ControllerEffect()
    /**
     * Cancels the in-flight SSE feed (and drops delta buffers).
     *
     * CP9 (notify Phase-0 switchover): this is now an OBSERVED
     * transport-disconnect signal, NOT a request for CC to cancel a job. The
     * producer is [cn.vectory.ocdroid.service.streaming.ServiceSseConnectionOwner]
     * — emitted once when a live collector was actually stopped (Service
     * going away, user explicit close, reconfigure teardown, §4.1 timeout).
     * [cn.vectory.ocdroid.ui.AppCore.dispatchForegroundCatchUpEffect] no
     * longer recurses into CC.cancelSse (the loop is broken at the producer).
     * [SessionSyncCoordinator] still observes the signal: it creates a
     * `SseReconnectTrigger.Disconnected`, marks the current session dirty,
     * and stamps the disconnect time so the next `server.connected`
     * reconciles.
     */
    data object CancelSse : ControllerEffect()
    /** Gap-aware catch-up probe + tail reload. */
    data class CatchUpAfterDisconnect(val sessionId: String) : ControllerEffect()

    // ── SessionSwitcher ──
    data class LoadMessages(
        val sessionId: String,
        val resetLimit: Boolean,
        /** Captured route incarnation; 0L keeps the legacy flat path. */
        val expectedRouteInstance: Long = 0L,
    ) : ControllerEffect()
    data class LoadChildSessions(val sessionId: String) : ControllerEffect()
    data object LoadSessionStatus : ControllerEffect()
    class LoadSessionStatusWithCompletion(
        val onComplete: (successfullyApplied: Boolean) -> Unit,
    ) : ControllerEffect()
    data object LoadPendingQuestions : ControllerEffect()
    /** Drop all pending delta buffers in SessionSyncCoordinator. */
    data object ClearDeltaBuffers : ControllerEffect()
    /**
     * R-20 Phase 1: verify-and-hydrate the cached window for `(serverGroupFp,
     * sessionId)` BEFORE showing it to the user (plan §0 N2 privacy +
     * verify-before-hydrate). Dispatched by SessionSwitcher.switchTo instead
     * of the synchronous LRU seed + synchronous LoadMessages (the old path
     * hydrated the cache without fingerprint-checking it first).
     *
     * Handled by AppCore.dispatchSessionEffect (this domain) — remove-message-
     * persistence Task 2 collapsed the prior suspend
     * `cacheRepository.verifyAndLoad(...)` fingerprint check into a
     * synchronous IN-MEMORY peek via
     * [cn.vectory.ocdroid.ui.controller.SessionSwitcher.peekSessionWindow]
     * (the cache is process-local now, so verification collapses to a
     * resident-check). On a peek hit the handler copies the window into the
     * chat slice + dispatches LoadMessages(resetLimit=false); on a miss it
     * dispatches LoadMessages(resetLimit=true).
     * [createdAt] is the server-side `Session.time.created`; null = cold
     * start (no resident window to hydrate from).
     */
    data class VerifyAndHydrate(
        val serverGroupFp: String,
        val sessionId: String,
        val createdAt: Long?,
        /**
         * §chat-list-detail §7.2 B0.5-rework: the route-instance token minted by
         * [cn.vectory.ocdroid.ui.OrchestratorViewModel.navigateToChat] at
         * navigation time. Threaded UNCHANGED through the load pipeline
         * (AppCore → loadMessagesForEffect → launchLoadMessages) so the
         * ChatContentLoaded reducer CAS can reject a stale A→B→A load.
         *
         * `0L` = legacy path (emitted by [cn.vectory.ocdroid.ui.controller.SessionSwitcher.switchTo]
         * — the non-route callers). The handler treats `0L` as "no token
         * guard" and dispatches MessagesMerged (legacy bare-chat path). `> 0L`
         * = route-aware path — dispatches ChatContentLoaded(expectedRouteInstance=T).
         */
        val expectedRouteInstance: Long = 0L,
    ) : ControllerEffect()

    // ── HostProfileController ──
    /** Cancel SSE feed BEFORE repository reconfigure. */
    data object CancelSseForReconfigure : ControllerEffect()
    /** Start SSE feed after successful connect. */
    data object StartSse : ControllerEffect()
    /** Host profile switched + settled → reload per-host state. */
    data object HostProfileSwitched : ControllerEffect()
    /** Force reconnect with cold-start retries (used by reset paths). */
    data object ColdStartReconnect : ControllerEffect()
    /** Reset all local data + reconnect (full reset path). */
    data object ResetLocalDataAndResync : ControllerEffect()
    /** Drop the per-session message-window cache (SessionSwitcher owns it). */
    data object ClearSessionWindowCache : ControllerEffect()
    /**
     * remove-message-persistence Task 3: append a single SSE-delivered new
     * message to the in-memory sessionWindowCache (NOT SQLite — the
     * persistent write path was deleted in this task). Emitted by
     * [SessionSyncCoordinator]'s `message.updated` new-insert branch in
     * place of the old `cacheRepository.appendMessageIfSessionCached`
     * suspend call.
     *
     * Routed via the bus because `SessionSwitcher` is an `AppCore` member
     * (non-Hilt) while `SessionSyncCoordinator` is Hilt-provided and does
     * NOT hold a reference to it — same pattern as `ClearSessionWindowCache`
     * / `EvictSession`. Handled by [cn.vectory.ocdroid.ui.AppCore.
     * dispatchSessionEffect] → `SessionSwitcher.appendMessageIfCached`
     * (no-op when the window is not resident).
     */
    data class AppendMessageToCache(
        val serverGroupFp: String,
        val sessionId: String,
        val message: Message,
        val parts: List<Part>,
    ) : ControllerEffect()
    /**
     * T11 round-2 (oracle D1 — cache-coupled non-focus resync): write a
     * non-focus RESYNC result into the in-memory `sessionWindowCache`
     * owned by [SessionSwitcher]. Emitted by
     * [SessionSyncCoordinator.applyReconcileResult] when a RESYNC
     * reconcile produced items for a session that is NOT currently open
     * (so a later switchTo finds them without a re-fetch). Cache
     * eviction (via [EvictSession]) clears the corresponding
     * `localApplied*` watermark via [OpenCodeRepository.clearSlimLocalMessages].
     *
     * Routed via the bus because `SessionSwitcher` is an `AppCore` member
     * while `SessionSyncCoordinator` is Hilt-provided and does NOT hold a
     * reference to it — same pattern as `AppendMessageToCache` /
     * `ClearSessionWindowCache` / `EvictSession`. Handled by
     * [cn.vectory.ocdroid.ui.AppCore.dispatchSessionEffect] →
     * `SessionSwitcher.writeSessionWindow`.
     */
    data class WriteSessionWindow(
        val serverGroupFp: String,
        val sessionId: String,
        val messages: List<Message>,
        val partsByMessage: Map<String, List<Part>>,
    ) : ControllerEffect()
    /**
     * R-20 Phase 1: evict one cached session + its messages, scoped to
     * `(serverGroupFp, sessionId)`. Emitted by:
     *  - SessionMutationActions.launchSetSessionArchived (per subtree id)
     *  - SessionMutationActions.launchDeleteSession (REST onSuccess)
     *  - SessionSyncCoordinator session.updated archived branch
     *
     * Handled by AppCore.dispatchHostEffect (HostProfileController domain) —
     * it synchronously clears the in-memory window and async-evicts the
     * persistent row (plan §3 N6).
     */
    data class EvictSession(val serverGroupFp: String, val sessionId: String) : ControllerEffect()
    /**
     * R-20 Phase 1: evict a whole server-group's worth of cached sessions +
     * messages (异组 host switch / profile delete). Emitted by
     * HostProfileController.selectHostProfile when previousFp != targetFp
     * (plan §3 select 4-step).
     *
     * Naming: this is **EvictGroup** (NOT ClearGroup) — freegpt+maxer round-3
     * convergence: "clear" suggests "clear all caches", "evict" scopes it to
     * the named group (plan §3 N6 explicitly forbids ClearGroup).
     */
    data class EvictGroup(val serverGroupFp: String) : ControllerEffect()

    // ── HostProfileController (restart-required model) ──
    /** Active host connection params changed — user must restart the app to apply. */
    data object RestartRequired : ControllerEffect()

    // ── ConnectionCoordinator ──
    /**
     * Initial-data fan-out entry: load sessions list (cross-domain — needs
     * SessionSwitcher + loadMessages/loadSessionStatus callbacks that the
     * controller cannot reach directly in the flat AppCore model).
     */
    data object LoadSessions : ControllerEffect()
    /** Load agents list (cross-domain write to settings slice + SettingsManager). */
    data object LoadAgents : ControllerEffect()
    /** Load providers list (cross-domain write to settings slice). */
    data object LoadProviders : ControllerEffect()
    /** Re-fetch pending permissions (cross-domain write to sessionList slice). */
    data object LoadPendingPermissions : ControllerEffect()
    /**
     * Forward a single SSE event to SessionSyncCoordinator.handleEvent. CC
     * owns the SSE collection feed; SSC owns the fold. SSC is constructed
     * AFTER CC in AppCore, so CC cannot take it as a constructor param —
     * route via the bus instead.
     *
     * CP1 (notify Phase-0): carries an [IdentifiedSseEvent] so SSC can
     * validate the connection identity BEFORE any fold/state mutation
     * (FGS spec §1 «identity 不得在 fold 前被剥掉»). The identity was captured
     * at SSE collection start in `ConnectionCoordinator.launchSseCollection`.
     */
    data class OnSseEvent(val event: IdentifiedSseEvent) : ControllerEffect()

    // ── SessionSyncCoordinator ──
    /** server.connected frame → route to catch-up controller. */
    data object ServerConnected : ControllerEffect()
    /** Full session-list refresh. */
    data object RefreshSessions : ControllerEffect()
    /**
     * T13 — request the [cn.vectory.ocdroid.service.streaming.ProcessStatusPoller]
     * to schedule a bounded exponential + jitter backoff (max 30s) for
     * the slim on-demand fan-out's next sweep. Emitted by
     * [SessionSyncCoordinator.applySlimStatusFanOutSummary] when a slim
     * fan-out sweep returned `retryableCount > 0` (503 / transport fault
     * per §6 G2). Handled by AppCore → forwards to
     * [cn.vectory.ocdroid.service.streaming.ProcessStatusPoller.scheduleBackoff].
     */
    data object RequestPollerBackoff : ControllerEffect()

    /**
     * T13 — request the poller to RESET its slim fan-out backoff state
     * to base. Emitted by [SessionSyncCoordinator.applySlimStatusFanOutSummary]
     * when a slim fan-out sweep returned `retryableCount == 0` (success
     * — T13-C4 "success resets"). Handled by AppCore → forwards to
     * [cn.vectory.ocdroid.service.streaming.ProcessStatusPoller.resetBackoff].
     */
    data object ResetPollerBackoff : ControllerEffect()

    // ── L4 §5.2 — SSE-origin REST operations (receive-only R2 fence) ────

    /**
     * L4 §5.2: A REST operation that originated from an SSE frame (as opposed
     * to a user-initiated action). Carries a [SseRestFence] that must be
     * validated before execution. The handler ([AppCore.dispatchEffect])
     * checks [SseLifecyclePolicy.restEffectAllowed]; if denied, the operation
     * is silently dropped and its session is marked dirty.
     */
    data class ExecuteSseRest(
        val operation: SseRestOperation,
        val fence: SseRestFence,
    ) : ControllerEffect()

    /**
     * L4 §5.2: The set of SSE-origin REST operations that go through the R2
     * fence. Each corresponds to a handler-level bypass path that must be
     * gated in receive-only mode.
     */
    sealed interface SseRestOperation {
        /** server.connected → reconcile + reload. */
        data object ServerConnected : SseRestOperation
        /** Full session-list refresh (archive/restore). */
        data object RefreshSessions : SseRestOperation
        /** Fetch pending permissions after permission.* event. */
        data object LoadPendingPermissions : SseRestOperation
        /** Refresh session status list. */
        data object LoadSessionStatus : SseRestOperation
        /** Reload messages for a session (message.created event). */
        data class ReloadMessages(
            val sessionId: String,
            val resetLimit: Boolean,
            val expectedRouteInstance: Long,
        ) : SseRestOperation
        /** Catch-up after disconnect (gap recovery). */
        data class CatchUpAfterDisconnect(val sessionId: String) : SseRestOperation
    }
}
