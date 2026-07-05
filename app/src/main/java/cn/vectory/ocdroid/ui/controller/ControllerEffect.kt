package cn.vectory.ocdroid.ui.controller

import cn.vectory.ocdroid.data.model.SSEEvent

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
    /** Cancels the in-flight SSE feed (and drops delta buffers). */
    data object CancelSse : ControllerEffect()
    /** Gap-aware catch-up probe + tail reload. */
    data class CatchUpAfterDisconnect(val sessionId: String) : ControllerEffect()

    // ── SessionSwitcher ──
    data class LoadMessages(val sessionId: String, val resetLimit: Boolean) : ControllerEffect()
    data class LoadChildSessions(val sessionId: String) : ControllerEffect()
    data object LoadSessionStatus : ControllerEffect()
    data object LoadPendingQuestions : ControllerEffect()
    /** Drop all pending delta buffers in SessionSyncCoordinator. */
    data object ClearDeltaBuffers : ControllerEffect()

    // ── HostProfileController ──
    /** Cancel SSE feed BEFORE repository reconfigure. */
    data object CancelSseForReconfigure : ControllerEffect()
    /** Start SSE feed after successful connect. */
    data object StartSse : ControllerEffect()
    /** Host profile switched + settled → reload per-host state. */
    data object HostProfileSwitched : ControllerEffect()
    /** Force reconnect with cold-start retries (used by tunnel/reset paths). */
    data object ColdStartReconnect : ControllerEffect()
    /** Reset all local data + reconnect (full reset path). */
    data object ResetLocalDataAndResync : ControllerEffect()
    /** Drop the per-session message-window cache (SessionSwitcher owns it). */
    data object ClearSessionWindowCache : ControllerEffect()

    // ── ConnectionCoordinator ──
    /** A host/profile switch → reset foreground catch-up state machine. */
    data object HostReconfigured : ControllerEffect()
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
     */
    data class OnSseEvent(val event: SSEEvent) : ControllerEffect()

    // ── SessionSyncCoordinator ──
    /** server.connected frame → route to catch-up controller. */
    data object ServerConnected : ControllerEffect()
    /** Full session-list refresh. */
    data object RefreshSessions : ControllerEffect()
}
