package cn.vectory.ocdroid.ui.controller

import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SessionStatus

/**
 * §unread-soak: result of [evaluateUnread]. Pure value — the caller
 * ([UnreadSoakController] sweep or the T3b background poller) commits
 * both halves to the unread slice.
 *
 *  - [newIdleSince]: the FULLY recomputed rootId → epoch-ms soak-stamp map.
 *    The caller writes this wholesale. Busy/reset/orphan entries vanish;
 *    completed-cycle stamps remain as edge memory until the next busy state.
 *  - [rootsToMarkUnread]: roots that crossed the ≥[soakMs] threshold this tick
 *    AND were not viewed since going idle. The caller routes each through
 *    [applyMarkSessionUnread] (the existing low-level marker).
 */
data class UnreadSoakResult(
    val newIdleSince: Map<String, Long>,
    val rootsToMarkUnread: Set<String>,
    /** Root → idle-cycle start used for stable background notification keys. */
    val markedIdleSince: Map<String, Long>,
    /** Roots whose current all-idle cycle has been observed/consumed. */
    val rootsToStampViewed: Set<String>,
)

/**
 * §unread-soak: default soak window. A root must remain all-idle for ≥10s
 * before being marked unread. Tuned to absorb the instant busy→idle flutters
 * (sub-agent dispatch, retry storms, cross-client sync) that previously fired
 * spurious unread badges the instant the root went idle.
 */
const val UNREAD_SOAK_MS: Long = 10_000L

/**
 * §unread-soak (LOCKED design): the PURE deterministic evaluator that
 * replaces the old instant busy→idle marking. A root becomes unread ONLY when
 * (a) the root AND ALL its descendants (via the session tree) are idle,
 * (b) that all-idle state has persisted ≥[soakMs], (c) the root is not the
 * currently-open session, AND (d) the user has not viewed the root since it
 * went idle.
 *
 * Per ROOT session (parentId==null, !isArchived), iterating the union of
 * [sessions] + [directorySessions] (deduped by id):
 *  - subtree = [subtreeIds] over the three session stores (root + all
 *    descendants). allIdle = subtree.all { sessionStatuses[it]?.isIdle == true }
 *    (unknown status ⇒ NOT idle — a descendant whose status we have not yet
 *    observed blocks the soak).
 *  - If the current session resolves to this root: never mark and emit a
 *    viewed stamp for the cycle (opening a child counts as viewing its root).
 *  - Else if !allIdle: root or a descendant is busy ⇒ reset the soak (clear).
 *  - Else (allIdle, not current):
 *    - idleSince[root] == null ⇒ START the soak (newIdleSince[root] = [now]).
 *    - idleSince[root] != null AND (now - stamp) ≥ [soakMs] ⇒ soak COMPLETE:
 *      if lastViewedTime[root] < stamp (not viewed since idle) ⇒ add to
 *      [rootsToMarkUnread] and [rootsToStampViewed]. The cycle stamp remains
 *      until busy so continuously-idle evaluations cannot mark twice.
 *    - Otherwise still soaking — stamp unchanged.
 *
 * Pure + deterministic: same inputs ⇒ same outputs. The ONLY time input is
 * [now] (the caller's clock); no internal time side-effects. Reusable by both
 * the foreground sweep ([UnreadSoakController]) and the T3b background
 * poller lane.
 */
fun evaluateUnread(
    sessions: List<Session>,
    sessionStatuses: Map<String, SessionStatus>,
    childSessions: Map<String, List<Session>>,
    directorySessions: Map<String, List<Session>>,
    currentSessionId: String?,
    lastViewedTime: Map<String, Long>,
    idleSince: Map<String, Long>,
    now: Long,
    soakMs: Long = UNREAD_SOAK_MS,
    completeRootIds: Set<String>,
): UnreadSoakResult {
    // Build the live ROOT set: parentId==null, !isArchived, deduped by id.
    // Order-stable (LinkedHashMap) so deterministic iteration aids reproducible
    // tests + logs.
    val roots = LinkedHashMap<String, Session>()
    for (s in sessions) {
        if (s.parentId == null && !s.isArchived && s.id !in roots) roots[s.id] = s
    }
    for (list in directorySessions.values) {
        for (s in list) {
            if (s.parentId == null && !s.isArchived && s.id !in roots) roots[s.id] = s
        }
    }

    // Mutate a copy of the incoming idleSince map; entries for live roots are
    // touched below. Entries for roots no longer present (archived / deleted /
    // not yet loaded) are pruned at the end so the map cannot grow unbounded
    // across the process lifetime.
    val mutableIdleSince = idleSince.toMutableMap()
    val rootsToMark = LinkedHashSet<String>()
    val markedIdleSince = LinkedHashMap<String, Long>()
    val rootsToStampViewed = LinkedHashSet<String>()
    val sessionsById = allSessionsById(sessions, directorySessions, childSessions)
    val currentRootId = currentSessionId?.let { rootIdOf(it, sessionsById) }

    for ((rootId, _) in roots) {
        val isCurrent = rootId == currentRootId
        val subtree = subtreeIds(rootId, sessions, directorySessions, childSessions)
        val allIdle = rootId in completeRootIds &&
            subtree.all { sessionStatuses[it]?.isIdle == true }

        when {
            // soak reset: root or any descendant busy / unknown → reset.
            !allIdle -> mutableIdleSince.remove(rootId)
            else -> {
                // Keep the cycle stamp until a busy/unknown transition. This is
                // the edge memory that prevents a continuously-idle root from
                // starting a fresh soak every tick after completion.
                val stamp = mutableIdleSince[rootId] ?: now.also {
                    // Rising edge of the all-idle state — start the soak.
                    mutableIdleSince[rootId] = it
                }
                if (isCurrent) {
                    // Viewing either the root or one of its descendants counts
                    // as viewing the root for this cycle.
                    rootsToStampViewed.add(rootId)
                } else if (now - stamp >= soakMs) {
                    // Soak threshold crossed.
                    val viewed = lastViewedTime[rootId]
                    // (d) not viewed since idle ⇒ mark. Viewed-null (never
                    // opened) is treated as not-viewed so a fresh root still
                    // badges once it settles.
                    if (viewed == null || viewed < stamp) {
                        rootsToMark.add(rootId)
                        markedIdleSince[rootId] = stamp
                        // Consume this cycle without discarding its edge memory.
                        // The viewed stamp suppresses repeat marks until busy
                        // clears idleSince and a new rising edge occurs.
                        rootsToStampViewed.add(rootId)
                    }
                }
                // else: still soaking, stamp unchanged.
            }
        }
    }

    // Prune stamps for roots no longer in the live root set (archived / deleted
    // / not loaded). Keeps the map bounded; the live-root iteration above
    // already touched every present root's entry.
    val pruned = mutableIdleSince.filterKeys { it in roots }
    return UnreadSoakResult(
        newIdleSince = pruned,
        rootsToMarkUnread = rootsToMark,
        markedIdleSince = markedIdleSince,
        rootsToStampViewed = rootsToStampViewed,
    )
}
