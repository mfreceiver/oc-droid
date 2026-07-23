package cn.vectory.ocdroid.di

internal fun shouldPostIdleNotification(
    isInForeground: Boolean,
    key: String,
    notifiedKeys: Set<String>,
): Boolean = !isInForeground && key !in notifiedKeys

internal fun pruneIdleNotificationSnapshot(
    notifiedKeys: MutableSet<String>,
    activeKeys: Set<String>,
) {
    notifiedKeys.retainAll(activeKeys)
}
