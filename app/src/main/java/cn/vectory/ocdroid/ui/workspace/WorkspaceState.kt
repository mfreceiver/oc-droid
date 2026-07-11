package cn.vectory.ocdroid.ui.workspace

import cn.vectory.ocdroid.data.model.FileDiff

/**
 * Ephemeral Workspace selection. It is deliberately scoped to one host.
 *
 * §phase2-isolation: the [workdir] field carries the workdir identity the
 * state was constructed against. [matches] now does STRICT identity
 * comparison (`workdir == activeWorkdir`) instead of the previous "non-blank
 * workdir" check — a stale diff from workdir A must NOT render under workdir
 * B even if the host + session happen to coincide.
 */
data class WorkspaceState(
    val hostId: String? = null,
    val sessionId: String? = null,
    val workdir: String? = null,
    val selectedFile: String? = null,
) {
    fun forHost(activeHostId: String?): WorkspaceState =
        if (hostId == activeHostId) this else WorkspaceState(hostId = activeHostId)

    /**
     * §phase2-isolation: the diff scope is visible ONLY when host, session,
     * AND workdir identity all match. The previous `!workdir.isNullOrBlank()`
     * check accepted ANY non-empty workdir — a stale diff snapshot for
     * workdir A would render under workdir B as long as host+session matched
     * (a cross-workdir data leak). Strict identity (`workdir == activeWorkdir`)
     * closes the leak: null/blank workdir never matches (fail-closed).
     */
    fun matches(activeHostId: String?, activeSessionId: String?, activeWorkdir: String?): Boolean =
        hostId == activeHostId &&
            sessionId == activeSessionId &&
            workdir == activeWorkdir
}

/**
 * §B1-fix: the pure state-resolution pipeline used by [WorkspaceScaffold].
 *
 * Mirrors EXACTLY what the composable does on each (host, session, workdir)
 * change: build the state from the persisted SavedStateHandle slots, run it
 * through [WorkspaceState.forHost] (which clears EVERYTHING when the host
 * differs — including first-visit when savedHost is null), then restore BOTH
 * sessionId AND workdir via copy. Extracted so the combination behaviour
 * (the §B1 ship-breaker lived here) is unit-testable without a Compose host.
 *
 * Cross-host isolation is preserved: a REAL host switch (savedHost non-null
 * and != activeHost) still clears selection via forHost; the workdir field is
 * always the route/session-derived live value, used for display scoping + the
 * matches() identity check.
 */
internal fun resolveWorkspaceState(
    savedHost: String?,
    savedSession: String?,
    savedFile: String?,
    workdir: String?,
    activeHost: String?,
    activeSession: String?,
): WorkspaceState = WorkspaceState(
    hostId = savedHost,
    sessionId = savedSession,
    workdir = workdir,
    selectedFile = savedFile,
).forHost(activeHost).copy(sessionId = activeSession, workdir = workdir)

/**
 * §B1-fix: the combined visibleDiffs decision used by [WorkspaceScaffold].
 *
 * Returns the session diffs for [activeSession] ONLY when the resolved state
 * matches (host, session, AND workdir identity); otherwise empty (fail-closed
 * — a stale snapshot must never render). The resolution + match are piped
 * through [resolveWorkspaceState] + [WorkspaceState.matches] so the test pins
 * the exact combination that was broken (workdir restored after forHost).
 */
internal fun computeVisibleDiffs(
    savedHost: String?,
    savedSession: String?,
    savedFile: String?,
    workdir: String?,
    activeHost: String?,
    activeSession: String?,
    sessionDiffs: Map<String, List<FileDiff>>,
): List<FileDiff> {
    val state = resolveWorkspaceState(
        savedHost = savedHost,
        savedSession = savedSession,
        savedFile = savedFile,
        workdir = workdir,
        activeHost = activeHost,
        activeSession = activeSession,
    )
    return if (state.matches(activeHost, activeSession, workdir)) {
        sessionDiffs[activeSession].orEmpty()
    } else emptyList()
}
