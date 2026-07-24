package cn.vectory.ocdroid.di

/**
 * §defect-A-1B: pure decision for the background freshness probe. True when the
 * server's newest message id is absent locally and every staleness/foreground
 * fence is clear, so the poll should emit a REST catch-up.
 *
 * Extracted as a top-level pure function so the decision is unit-testable
 * without the Android-heavy [AppLifecycleMonitor] construction — the probe site
 * in [AppLifecycleMonitor.pollPendingItems] feeds the already-collected inputs
 * (server's latest id, the locally-known id set, the load flag, the foreground
 * flag, and whether the lifecycle generation moved during the in-flight probe)
 * into this helper and only emits a [ControllerEffect.CatchUpAfterDisconnect]
 * when it returns true. Keeping the helper pure means every branch is covered
 * by a plain JVM test with no Robolectric/MockWebServer harness.
 */
internal fun shouldTriggerBackgroundCatchUp(
    serverLatestId: String?,
    knownLocalIds: Set<String>,
    isLoadingMessages: Boolean,
    isForeground: Boolean,
    generationChanged: Boolean,
): Boolean =
    !serverLatestId.isNullOrEmpty() &&
        serverLatestId !in knownLocalIds &&
        !isLoadingMessages &&
        !isForeground &&
        !generationChanged
