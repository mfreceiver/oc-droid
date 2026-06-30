package com.yage.opencode_client.ui.controller

import com.yage.opencode_client.data.model.Session
import com.yage.opencode_client.ui.CachedSessionWindow

/**
 * R-16 M2: callbacks the SessionSwitcher invokes back into MainViewModel for
 * cross-domain side effects during session switching. Defined as an interface
 * rather than direct injection so the controller never holds a reference to
 * MainViewModel — following the [ForegroundCatchUpCallbacks] pattern from M1
 * (R-16 §7.3 circular-dependency avoidance).
 *
 * The core `selectSession` logic (~130 lines, 8 sub-steps with deep coupling
 * to `_state`, `SliceFlows`, `SettingsManager`, `OpenCodeRepository`, and the
 * per-session message window cache) remains in MainViewModel for now — see
 * the rationale in the constructor KDoc.
 */
interface SessionSwitcherCallbacks {
    /** Persists the draft text for [oldSessionId] via SettingsManager. */
    fun saveDraft(sessionId: String, text: String)

    /** Restores the draft text for [sessionId] from SettingsManager. */
    fun getDraft(sessionId: String): String

    /** Persists [openIds] + [currentId] to SettingsManager.openSessionIds /
     *  SettingsManager.currentSessionId. */
    fun persistOpenSessionIds(openIds: List<String>, currentId: String?)

    /** Persists the session-metadata cache via [com.yage.opencode_client.ui.persistSessionCache]. */
    fun persistSessionCache(
        sessions: List<Session>,
        openIds: List<String>,
        currentId: String?,
        currentWorkdir: String?
    )

    /** Upserts [session] into the global session list. */
    fun upsertSession(session: Session)

    /** Syncs the repository's workdir context to [directory]. */
    fun syncCurrentDirectory(directory: String?)

    /** Refreshes the directory-scoped sessions for [workdir]. */
    fun refreshDirectorySessions(workdir: String)

    /** Loads child (sub-agent) sessions for [sessionId]. */
    fun loadChildSessions(sessionId: String)

    /** Loads messages for [sessionId] (cold or cache-miss). */
    fun loadMessages(sessionId: String, resetLimit: Boolean)

    /** Loads the current session-status map. */
    fun loadSessionStatus()

    /** Returns the current AppState's currentSessionId (for guards). */
    fun currentSessionId(): String?
}

/**
 * R-16 M2: partial extraction of the session-switching state machine.
 *
 * **Status: partial**. The [selectSession] method in MainViewModel is a
 * tightly-coupled ~130-line flow with 8 interleaved steps that each touch
 * multiple slices (`_state`, `_composerFlow`, `_sessionListFlow`, `_unreadFlow`,
 * `_chatFlow`), the `sessionWindowCache` LRU, `SettingsManager` prefs
 * persistence, `OpenCodeRepository` calls, and the R-17 `SliceFlows` sync
 * infrastructure. Extracting the whole flow now would require:
 *  1. ~15 callback methods to cover every state mutation + repository call
 *  2. Breaking a single synchronous function into an async choreography
 *     spread across the controller and its callbacks
 *  3. Risk of subtle behavioural drift in the unread re-marking state machine
 *     (`tempClearedUnread` / `previousWasBusyAndCleared`)
 *
 * The conservative decision (per the task spec's risk allowance):
 *  - THIS file defines the [SessionSwitcherCallbacks] contract so future
 *    extraction has a clear interface boundary.
 *  - The [SessionSwitcher] class is a minimal facade that MainViewModel CAN
 *    delegate to, but currently all core work stays in [MainViewModel.selectSession].
 *  - A follow-up milestone (R-16 M2b) can complete the extraction once the
 *    `ComposerController` wiring has stabilised and the orchestration
 *    risks are better understood.
 *
 * RFC reference: §E / §M2.
 */
internal class SessionSwitcher(
    private val callbacks: SessionSwitcherCallbacks
) {
    /**
     * Minimal guard: returns the current session ID (delegates to callbacks).
     * Used by [ForegroundCatchUpCallbacks.currentSessionId] call chain when the
     * foreground controller needs to target a catch-up probe.
     */
    fun currentSessionId(): String? = callbacks.currentSessionId()
}
