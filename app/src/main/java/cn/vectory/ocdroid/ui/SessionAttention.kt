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
