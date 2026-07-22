package cn.vectory.ocdroid.ui.controller

/**
 * T1d R3: pure decision for whether to refresh a session's children
 * (sub-agents) given local/remote version + server-generation pairs.
 * No wire/network integration — pure only (slimapi Batch4 wires later).
 */
sealed interface ChildrenRefreshDecision {
    /** Remote version is strictly newer than local within the same generation. */
    data object Refresh : ChildrenRefreshDecision

    /** Remote version is equal-or-older within the same generation — skip fetch. */
    data object Skip : ChildrenRefreshDecision

    /**
     * Server generation changed (host switch / server restart) — versions are
     * incomparable across generations; Y-gateway fallback refresh.
     */
    data object RefreshCrossGeneration : ChildrenRefreshDecision

    /**
     * Version or generation info is null/missing — safe fallback refresh
     * (cannot make a version-based decision).
     */
    data object RefreshNullFallback : ChildrenRefreshDecision
}

/**
 * Decide children refresh from local/remote version + server-generation pairs.
 *
 * Policy (locked by [T1dChildrenVersionDecisionTest]):
 * 1. Any of the four args null → [ChildrenRefreshDecision.RefreshNullFallback]
 * 2. Else if generations differ → [ChildrenRefreshDecision.RefreshCrossGeneration]
 *    (never compare version magnitude across gens)
 * 3. Else same gen: remote > local → Refresh; remote <= local → Skip
 */
fun decideChildrenRefresh(
    localVersion: Long?,
    remoteVersion: Long?,
    localServerGeneration: Long?,
    remoteServerGeneration: Long?,
): ChildrenRefreshDecision {
    if (localVersion == null ||
        remoteVersion == null ||
        localServerGeneration == null ||
        remoteServerGeneration == null
    ) {
        return ChildrenRefreshDecision.RefreshNullFallback
    }
    if (localServerGeneration != remoteServerGeneration) {
        return ChildrenRefreshDecision.RefreshCrossGeneration
    }
    return if (remoteVersion > localVersion) {
        ChildrenRefreshDecision.Refresh
    } else {
        ChildrenRefreshDecision.Skip
    }
}
