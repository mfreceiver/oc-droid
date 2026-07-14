package cn.vectory.ocdroid.ui.controller

import cn.vectory.ocdroid.data.model.QuestionRequest
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.ui.UnreadState
import cn.vectory.ocdroid.data.model.SessionStatus

/** Merge the three session stores into a deduped id→Session map. */
internal fun allSessionsById(
    sessions: List<Session>,
    directorySessions: Map<String, List<Session>>,
    childSessions: Map<String, List<Session>>,
): Map<String, Session> = buildMap {
    sessions.forEach { putIfAbsent(it.id, it) }
    directorySessions.values.flatten().forEach { putIfAbsent(it.id, it) }
    childSessions.values.flatten().forEach { putIfAbsent(it.id, it) }
}

/** Walk parentId up to the root (parentId==null). null if unknown or cycle. */
internal fun rootIdOf(sessionId: String, sessionsById: Map<String, Session>): String? {
    var cur = sessionId
    val seen = HashSet<String>()
    while (true) {
        if (!seen.add(cur)) return null
        val s = sessionsById[cur] ?: return null
        val p = s.parentId
        if (p == null) return cur
        cur = p
    }
}

/** root + all descendants. */
internal fun treeIds(rootId: String, sessionsById: Map<String, Session>): Set<String> {
    val childrenByParent = sessionsById.values.groupBy { it.parentId }
    val out = LinkedHashSet<String>()
    fun collect(id: String) {
        if (!out.add(id)) return
        childrenByParent[id]?.forEach { collect(it.id) }
    }
    collect(rootId)
    return out
}

/** Three-source subtree helper (= treeIds over allSessionsById). */
internal fun subtreeIds(
    rootId: String,
    sessions: List<Session>,
    directorySessions: Map<String, List<Session>>,
    childSessions: Map<String, List<Session>>,
): Set<String> = treeIds(rootId, allSessionsById(sessions, directorySessions, childSessions))

/**
 * `/session/status` is authoritative but omits idle entries. Convert omitted
 * nodes from a proven authoritative tree to explicit idle; IDs outside that
 * tree remain absent/unknown and therefore fail closed in the unread evaluator.
 */
internal fun normalizeAuthoritativeStatusSnapshot(
    snapshot: Map<String, SessionStatus>,
    authoritativeNodeIds: Set<String>,
): Map<String, SessionStatus> = buildMap {
    putAll(snapshot)
    authoritativeNodeIds.forEach { putIfAbsent(it, SessionStatus(type = "idle")) }
}

/**
 * §task6: project each pending question's session up to its ROOT id. Used by
 * the chat tab strip / picker / Sessions card so a sub-agent's pending
 * question surfaces on its root (the only surface the user actually sees).
 * Questions whose [QuestionRequest.sessionId] cannot be resolved to a root
 * (unknown id / parentId cycle) are dropped — there is no root to mark.
 */
internal fun questionRootIds(
    pendingQuestions: List<QuestionRequest>,
    sessionsById: Map<String, Session>,
): Set<String> = pendingQuestions.mapNotNull { rootIdOf(it.sessionId, sessionsById) }.toSet()

/**
 * §task6: the queue of pending questions whose owning session lives anywhere
 * inside the [rootId] tree (root itself + any descendant). The returned
 * [QuestionRequest]s preserve their ORIGINAL sessionId / id — the answer
 * endpoint takes the question's real ids, never the root's. The queue order
 * matches [pendingQuestions] (insertion order).
 */
internal fun questionsInTree(
    rootId: String,
    pendingQuestions: List<QuestionRequest>,
    sessionsById: Map<String, Session>,
): List<QuestionRequest> {
    val tree = treeIds(rootId, sessionsById)
    return pendingQuestions.filter { it.sessionId in tree }
}

/**
 * §task5-ghost (final-review fix 2): drop [QuestionRequest]s whose session is
 * marked archived in the caller's [sessionsById]. Unknown session ids are
 * CONSERVATIVELY KEPT — the session may not yet be loaded (cold start /
 * mid-fetch), so the question stays visible until the session materialises
 * and proves it is archived. Used by [SessionSyncCoordinator
 * .loadPendingQuestionsAllWorkdirs]'s authoritative reconcile to prevent an
 * archived session's question from ghosting back when the server still
 * returns it in its pending-questions response.
 *
 * §task5-ghost-r2 (final-fix round 2): the archive check walks the WHOLE
 * parentId chain — a question is dropped if its session OR ANY known ancestor
 * is archived. Closes the root-only-SSE-archive ghost path: the server may
 * archive only the root and leave child questions in its
 * pending-questions response; the Fix-1 reducer cleared them locally, but
 * without the ancestor walk this filter would only inspect the (still
 * unarchived) child and let the question back in. Unknown session / parentId
 * cycle / chain reaching a root with no archived node — all conservatively
 * KEEP the question (only a known archived session anywhere on the chain
 * drops it).
 */
internal fun filterArchivedSessionQuestions(
    questions: List<QuestionRequest>,
    sessionsById: Map<String, Session>,
): List<QuestionRequest> {
    val archivedIds = sessionsById.values.filter { it.isArchived }.map { it.id }.toSet()
    return questions.filter { q -> !chainHasArchived(q.sessionId, sessionsById, archivedIds) }
}

/**
 * §task5-ghost-r2: walk [startId]'s parentId chain (self included); return
 * true iff any node on the chain is in [archivedIds]. Cycle-guarded; an
 * unknown session or a chain that reaches a root (parentId==null) without
 * hitting an archived node returns false (conservative — let the caller
 * keep the question).
 */
private fun chainHasArchived(
    startId: String,
    sessionsById: Map<String, Session>,
    archivedIds: Set<String>,
): Boolean {
    var cur = startId
    val seen = HashSet<String>()
    while (true) {
        if (!seen.add(cur)) return false
        if (cur in archivedIds) return true
        val s = sessionsById[cur] ?: return false
        val p = s.parentId ?: return false
        cur = p
    }
}

/**
 * §task5-lifecycle: drop the given [ids] from [UnreadState.unreadSessions] AND
 * [UnreadState.lastViewedTime]. Used by the archive / delete / disconnectWorkdir
 * lifecycle paths so an unread badge cannot survive a session that no longer
 * shows up in the UI. Pure — callers funnel the result through mutateUnread.
 *
 * §unread-soak: also clears [UnreadState.idleSince] for the pruned ids so a
 * pending soak does not survive the lifecycle event (an archived / deleted
 * root must never later cross the soak threshold and re-populate
 * [UnreadState.unreadSessions]).
 */
internal fun UnreadState.removeSessions(ids: Set<String>): UnreadState = copy(
    unreadSessions = unreadSessions - ids,
    lastViewedTime = lastViewedTime.filterKeys { it !in ids },
    idleSince = idleSince.filterKeys { it !in ids },
)
