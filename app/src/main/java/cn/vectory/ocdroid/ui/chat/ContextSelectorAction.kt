package cn.vectory.ocdroid.ui.chat

import cn.vectory.ocdroid.util.WorkdirPaths

/**
 * §round-B ② (scheme D.5): pure decision for the ContextSelectorSheet's
 * workdir-select callback. Replaces the previous "first session in
 * directory → selectSession, else createSessionInWorkdir" semantics
 * (oracle: that broke session-scoping — it re-selected an arbitrary
 * sibling session in the same directory, losing the user's current
 * conversation context).
 *
 * Scheme D.5 contract:
 *  - If the current session is ALREADY scoped to the target workdir →
 *    [WorkdirAction.PRESERVE_CURRENT] (no-op; keep the user exactly where
 *    they are).
 *  - Otherwise → [WorkdirAction.MATERIALIZE_DRAFT] (the caller invokes
 *    `SessionViewModel.createSessionInWorkdir(target)` to materialize a
 *    fresh scoped draft in that workdir).
 *
 * Extracted as a top-level pure fn so the rule is unit-testable; the
 * ContextSelectorSheet call site in ChatScaffold consumes the result and
 * forwards the matching SessionViewModel call.
 *
 * §B1-fix⑤ (三评委共识): the comparison is now NORMALIZED via
 * [WorkdirPaths.normalize] so that semantically-equal paths differing only
 * by surrounding slashes/whitespace (`/proj` vs `/proj/` vs `proj`) are
 * recognised as the SAME workdir → PRESERVE_CURRENT. The previous exact-
 * equality check mis-judged them as different → spurious MATERIALIZE_DRAFT
 * → an unwanted draft session was created.
 */
enum class WorkdirAction {
    /** The current session is already in the target workdir — keep it. */
    PRESERVE_CURRENT,
    /** Open a fresh draft scoped to the target workdir. */
    MATERIALIZE_DRAFT,
}

fun resolveWorkdirSelection(
    currentSessionDirectory: String?,
    targetWorkdir: String,
): WorkdirAction {
    if (currentSessionDirectory == null) return WorkdirAction.MATERIALIZE_DRAFT
    val current = WorkdirPaths.normalize(currentSessionDirectory)
    val target = WorkdirPaths.normalize(targetWorkdir)
    return if (current == target && current.isNotEmpty()) WorkdirAction.PRESERVE_CURRENT
    else WorkdirAction.MATERIALIZE_DRAFT
}
