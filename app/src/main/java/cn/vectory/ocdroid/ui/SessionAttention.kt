package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.data.model.SessionStatus

/** Sessions that must not surface as unread while work is still in flight. */
internal fun effectiveBusySessionIds(
    activeSessionIds: Set<String>,
    sessionStatuses: Map<String, SessionStatus>,
): Set<String> = buildSet {
    addAll(activeSessionIds)
    sessionStatuses.forEach { (sessionId, status) ->
        if (status.isBusy || status.isRetry) add(sessionId)
    }
}

// ── §ui-badges: row-level session attention levels ──────────────────────
//
// This sealed hierarchy is the ROW-LEVEL trailing subset (badge on ListItem),
// NOT StatusSlot's full banner priority — the banner (StatusSlot.kt) remains
// independent and uses a different priority scheme.
//
// Retry IS retained as a row-level tier because retry termination is NOT
// provably bounded: the server drives a backoff loop (Session.kt:90-100
// `attempt` + `next` fields), the AuthorityReducer treats retry as non-terminal
// (terminal = "NOT busy AND NOT retry"), and SessionRetryCard keys on `next`
// for a new retry attempt. Without server-side termination proof, removing retry
// from row indicators would leave the user with zero list indication during a
// long retry loop.
//
// Priority (highest first):
//   HardError > PendingUserInput > TransientRetry > Unread > None

/**
 * Row-level session attention level — what indicator a session row's trailing
 * slot should render. Resolved by [computeSessionAttention] from per-session
 * boolean flags.
 *
 * Visual strength aligns with priority: the highest-priority active tier is
 * the only one shown (mutually exclusive at render time).
 */
internal sealed interface SessionAttentionLevel {
    /** No attention needed — render nothing. */
    data object None : SessionAttentionLevel
    /** New messages waiting — soft reminder (static dot). */
    data object Unread : SessionAttentionLevel
    /** Auto-recovery in progress (server backoff loop) — transient, no user
     *  action needed, but retained because the loop is not provably bounded. */
    data object TransientRetry : SessionAttentionLevel
    /** System BLOCKED awaiting user action (question/permission pending) —
     *  needs immediate action; breathing animation. */
    data object PendingUserInput : SessionAttentionLevel
    /** Session already failed (SET lastError) — highest static severity. */
    data object HardError : SessionAttentionLevel
}

/**
 * Pure function resolving [SessionAttentionLevel] from per-session boolean
 * flags. Priority (highest wins):
 *   1. [hasError] → [SessionAttentionLevel.HardError]
 *   2. [hasPendingUserInput] → [SessionAttentionLevel.PendingUserInput]
 *   3. [isRetry] → [SessionAttentionLevel.TransientRetry]
 *   4. [isUnread] → [SessionAttentionLevel.Unread]
 *   5. else → [SessionAttentionLevel.None]
 *
 * This is the ROW-LEVEL resolver (trailing badge), NOT the full banner
 * priority used by StatusSlot.kt — the banner has its own independent scheme.
 *
 * Pure, no Compose dependency — trivially testable.
 */
internal fun computeSessionAttention(
    hasError: Boolean,
    hasPendingUserInput: Boolean,
    isRetry: Boolean,
    isUnread: Boolean,
): SessionAttentionLevel = when {
    hasError -> SessionAttentionLevel.HardError
    hasPendingUserInput -> SessionAttentionLevel.PendingUserInput
    isRetry -> SessionAttentionLevel.TransientRetry
    isUnread -> SessionAttentionLevel.Unread
    else -> SessionAttentionLevel.None
}
