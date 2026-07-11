package cn.vectory.ocdroid.ui.sessions

import cn.vectory.ocdroid.data.model.Session

/**
 * §round-B ③ (Phase 2 G.2 step 1 — previously declared as fake): pure
 * filter for the [SessionsScreen] SearchBar.
 *
 * Matches a session iff the (case-insensitive) [query] is blank OR any of:
 *  - the session's title (or id fallback when title is blank — matches the
 *    Session.displayName shape the list renders);
 *  - the session's directory.
 *
 * Extracted as a top-level pure fn so the search contract is unit-tested
 * (SessionsScreenKt is excluded from kover coverage as a @Composable-only
 * file). Pinned by [SessionsScreenTest].
 */
fun filterSessionsByQuery(
    sessions: List<Session>,
    query: String,
): List<Session> {
    val q = query.trim()
    if (q.isEmpty()) return sessions
    return sessions.filter { session ->
        val title = session.title.orEmpty().ifBlank { session.id }
        title.contains(q, ignoreCase = true) ||
            session.directory.contains(q, ignoreCase = true)
    }
}
