package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.service.identity.ConnectionIdentityStore

/**
 * L3 (blocker #6): atomic reconfigure barrier — bumps the identity generation
 * AND detaches the skeleton reload scheduler's stale states under a single
 * call, so no caller can invoke [ConnectionIdentityStore.beginReconfigure]
 * without also detaching the scheduler. MUST be called BEFORE
 * `repository.configure()` so the epoch bump precedes any client rebuild.
 *
 * Returns the new epoch so the caller can pass it downstream (e.g. emit
 * `HostReconfigured(newEpoch)`).
 */
internal fun beginReconfigureBarrier(
    identityStore: ConnectionIdentityStore,
    scheduler: SkeletonReloadCoordinator,
): Long {
    val newEpoch = identityStore.beginReconfigure()
    scheduler.detachGeneration(newEpoch)
    return newEpoch
}
