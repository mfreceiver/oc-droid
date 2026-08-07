package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.data.repository.SessionRepository
import cn.vectory.ocdroid.di.UiApplicationScope
import cn.vectory.ocdroid.util.runSuspendCatching
import cn.vectory.ocdroid.ui.controller.SessionSwitcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * §Wave2.1-split-l2: leaf orchestrator for session-opening operations.
 * Resolves session identity from store+repository and drives
 * [SessionSwitcher.switchTo].
 *
 * Dependencies: store, repository, appScope, sessionSwitcher (~40 LOC).
 * No orchestrator depends on this one (leaf in the dep graph).
 */
@Singleton
class SessionOpener @Inject constructor(
    private val store: SharedStateStore,
    private val repository: SessionRepository,
    @UiApplicationScope private val appScope: CoroutineScope,
    private val sessionSwitcher: SessionSwitcher,
) {

    /** nav → session-list → chat. Used by the notification deep-link path. */
    fun openSessionFromDeepLink(sessionId: String) {
        appScope.launch {
            if (store.sessionListFlow.value.sessions.none { it.id == sessionId }) {
                val fetched = runSuspendCatching { repository.getSession(sessionId).getOrNull() }.getOrNull()
                if (fetched != null) {
                    store.dispatch(AppAction.SessionUpserted(fetched))
                }
            }
            sessionSwitcher.switchTo(sessionId)
        }
    }

    fun selectSessionForEffect(sessionId: String) {
        sessionSwitcher.switchTo(sessionId)
    }
}
