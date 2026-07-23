package cn.vectory.ocdroid.di

import cn.vectory.ocdroid.ui.controller.IdleUnreadAlert
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Shared idle-notification publish critical section, extracted from
 * [AppLifecycleMonitor.handleIdleAlert] and
 * [cn.vectory.ocdroid.service.streaming.SseNotificationBridge.handleSessionStatus].
 *
 * The caller is responsible for choosing the dispatcher for the foreground
 * gate (see T5-re-review I2-R):
 *  - ALM wraps this call in `withContext(Dispatchers.Main.immediate)` so the
 *    gate reads [AppLifecycleMonitor._isInForeground] on Main.
 *  - SSE bridge calls this directly (already on Main).
 *
 * ## Protocol (runs under [mutex])
 * 1. [claim][NotificationDedup.claim] — atomically reserve the key.
 * 2. [isInForeground] gate — suppress if user is active.
 * 3. [notifyIdle][SessionNotifier.notifyIdle] — post the temporary shade.
 * 4. On success: [complete][NotificationDedup.complete] (token-gated) +
 *    [onPersist] (durable mirror).
 * 5. [release][NotificationDedup.release] in `finally` — always frees the
 *    slot unless already completed.
 *
 * The caller keeps the pre-lock foreground early-out (an unclaimed fast-path
 * return) outside the helper — this function starts with the [mutex] acquire.
 */
internal suspend fun publishIdleNotification(
    dedup: NotificationDedup,
    notifier: SessionNotifier,
    mutex: Mutex,
    alert: IdleUnreadAlert,
    isInForeground: () -> Boolean,
    onPersist: (String) -> Unit,
    onSuppressed: ((String) -> Unit)? = null,
    onPosted: ((key: String, completed: Boolean) -> Unit)? = null,
    onFailed: ((String) -> Unit)? = null,
    onClaimLost: ((String) -> Unit)? = null,
) {
    mutex.withLock {
        val token = dedup.claim(alert.key) ?: run {
            onClaimLost?.invoke(alert.key)
            return@withLock
        }
        try {
            if (isInForeground()) {
                onSuppressed?.invoke(alert.key)
                return@withLock
            }
            val posted = notifier.notifyIdle(alert.rootId, alert.title, alert.key)
            if (posted) {
                val completed = dedup.complete(alert.key, token)
                onPersist(alert.key)
                onPosted?.invoke(alert.key, completed)
            } else {
                onFailed?.invoke(alert.key)
            }
        } finally {
            dedup.release(alert.key, token)
        }
    }
}
