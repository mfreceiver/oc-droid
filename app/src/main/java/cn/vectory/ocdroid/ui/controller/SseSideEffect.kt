package cn.vectory.ocdroid.ui.controller

/**
 * §R-19 Sprint 3 P2-4: the formal side-effect vocabulary for the SSE reducer.
 *
 * Each `applyXxx` pure transform returns `Pair<State, List<SseSideEffect>>`.
 * The dispatcher ([SessionSyncCoordinator.dispatchSseEvent]) collects the
 * lists from one or more applyXxx calls + its own cross-slice decisions, then
 * routes the combined list through [SessionSyncCoordinator.applySseSideEffects]
 * — the single point that translates each effect into the matching
 * [ControllerEffect] / [cn.vectory.ocdroid.ui.UiEvent] / log call.
 *
 * **Design boundary**: only CROSS-DOMAIN signals that ride the
 * [cn.vectory.ocdroid.ui.SharedEffectBus] are formalized as
 * [SseSideEffect]s. Coordinator-internal operations that don't cross the
 * bus boundary stay inline in the dispatcher:
 *  - `scheduleDeltaFlush` / `clearDeltaBuffers` (Job lifecycle + same-slice
 *    coalesce buffer mutation — imperative, not a bus signal).
 *  - `markSessionUnread` (cross-slice unread mutation —
 *    routed directly via `slices.mutateUnread`).
 *
 * This keeps the sealed hierarchy small (4 cases) and focused on the
 * effect-bus concerns that P2-4 formalizes.
 */
sealed class SseSideEffect {

    /**
     * Reload [sessionId]'s message window. Maps to
     * `ControllerEffect.LoadMessages(sessionId, resetLimit)` via
     * `effects.tryEmitEffect`.
     */
    data class ReloadMessages(
        val sessionId: String,
        val resetLimit: Boolean
    ) : SseSideEffect()

    /**
     * Refresh pending permissions. Maps to
     * `ControllerEffect.LoadPendingPermissions` via `effects.tryEmitEffect`.
     */
    data object LoadPendingPermissions : SseSideEffect()

    /**
     * Surface a server-side session error to the user. Maps to
     * `UiEvent.Error(resId, args)` via `effects.tryEmitUiEvent`. [name] is
     * the server's error name (may be null → unnamed variant); [rawMsg] is
     * the human-readable message extracted from the payload.
     */
    data class SessionError(
        val name: String?,
        val rawMsg: String
    ) : SseSideEffect()

    /**
     * Log a non-fatal issue (parse failure, invalid payload, etc.). Maps to
     * `reportNonFatalIssue(tag, message)` which forwards to `Log.w`. NOT a
     * bus-level effect — included here so that parse-failure logging goes
     * through the same `applySseSideEffects` routing as bus effects, keeping
     * the dispatcher branches effect-linear (no scattered `if (x == null)
     * reportNonFatal(...)` calls).
     */
    data class ReportNonFatal(
        val message: String
    ) : SseSideEffect()
}
