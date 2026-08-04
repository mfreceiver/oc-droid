package cn.vectory.ocdroid.ui.chat

/**
 * Deterministic state machine that decides whether [ChatScaffold] should call
 * [cn.vectory.ocdroid.ui.ChatViewModel.reconcilePendingQuestions].
 *
 * Two independent trigger paths:
 *   1. **Session change** ([onSessionChange]): called from
 *      [androidx.compose.runtime.LaunchedEffect] when [chromeSessionId]
 *      changes. Returns `(shouldReconcile, nextState)` directly so the caller
 *      can invoke [cn.vectory.ocdroid.ui.ChatViewModel.reconcilePendingQuestions]
 *      immediately — does NOT wait for the next ON_RESUME. Reconciles iff the
 *      new session ID differs from [lastReconciledSessionId].
 *   2. **Genuine pause→resume** ([onPause] / [onResume]): called from
 *      [androidx.lifecycle.compose.LifecycleEventEffect]. [onResume]
 *      reconciles iff [wasPaused] is `true` (set by a prior [onPause]).
 *
 * `wasPaused` starts as `false` so the first-composition catch-up ON_RESUME
 * (lifecycle observer emit) is a no-op when [onSessionChange] already handled
 * the first composition.
 *
 * @see QuestionReconcileTriggerTest for the full coverage matrix.
 */
internal data class ReconcileTriggerState(
    /** Session ID that was last reconciled by [onSessionChange]. `null` = never. */
    val lastReconciledSessionId: String? = null,
    /**
     * `true` when a genuine [Lifecycle.Event.ON_PAUSE] has occurred since the
     * last [onResume]. Initialised `false` so the first ON_RESUME catch-up
     * does NOT double-reconcile when [onSessionChange] already handled it.
     */
    val wasPaused: Boolean = false,
) {
    /** Call when [Lifecycle.Event.ON_PAUSE] fires. */
    fun onPause(): ReconcileTriggerState = copy(wasPaused = true)

    /**
     * Call when [chromeSessionId] changes or the composable first enters
     * composition.
     *
     * @return `(shouldReconcile, nextState)` — `shouldReconcile` is `true`
     *         iff [newSessionId] differs from [lastReconciledSessionId].
     *         [wasPaused] is preserved (pause→resume is handled independently
     *         by [onResume]).
     */
    fun onSessionChange(newSessionId: String): Pair<Boolean, ReconcileTriggerState> {
        val shouldReconcile = lastReconciledSessionId != newSessionId
        return shouldReconcile to ReconcileTriggerState(
            lastReconciledSessionId = newSessionId,
            wasPaused = this.wasPaused,
        )
    }

    /**
     * Call when [Lifecycle.Event.ON_RESUME] fires.
     *
     * @return `(shouldReconcile, nextState)` — `shouldReconcile` is `true`
     *         iff [wasPaused] is `true` (a genuine ON_PAUSE preceded this
     *         resume). The next state has `wasPaused = false`.
     *         [lastReconciledSessionId] is preserved — session changes are
     *         managed by [onSessionChange].
     */
    fun onResume(currentSessionId: String?): Pair<Boolean, ReconcileTriggerState> {
        if (currentSessionId == null) return false to this
        val shouldReconcile = wasPaused
        return shouldReconcile to copy(wasPaused = false)
    }
}
