package cn.vectory.ocdroid.di

import android.app.Activity
import android.app.Application
import android.util.Log
import cn.vectory.ocdroid.util.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Singleton
import javax.inject.Inject

/**
 * Process-foreground awareness.
 *
 * Phase 1 (后台驻留移除): the background notification poller, system
 * notification publishing, dedup store, and unread-soak probe were DELETED.
 * This monitor now owns ONLY the foreground/background detection seam —
 * translating started-activity count transitions into a Boolean
 * [isInForeground] [StateFlow] that drives SSE disconnect-on-background /
 * reconnect-on-foreground, plus a best-effort draft flush on background.
 *
 * Background is now completely silent: 0 polling / 0 notifications / 0
 * network. Cold-start recovery + foreground catch-up cover freshness.
 *
 * Implementation note: we deliberately use
 * [Application.registerActivityLifecycleCallbacks] rather than
 * `ProcessLifecycleOwner` so we can stay within the §15 write-domain (no
 * build.gradle change to add `lifecycle-process`). The semantics match:
 * started-activity count 0→1 emits ON_START (foreground), 1→0 emits ON_STOP
 * (background), matching `ProcessLifecycleOwner`'s binding semantics for the
 * common single-Activity app shape.
 */
@Singleton
class AppLifecycleMonitor @Inject constructor(
    private val application: Application,
    @UiApplicationScope private val uiScope: CoroutineScope,
    private val settingsManager: SettingsManager,
) {
    /**
     * §4.3 foreground truth-source.
     *
     * **Default `false`** (CP8): a sticky-rebuilt process with no started
     * Activity is **background**, NOT foreground. The previous `true` default
     * caused the §4.3 bug — a Service-only sticky rebuild (the OS restarted
     * the process after death without bringing any Activity back up)
     * misreported foreground, which would have routed the §5 bootstrap
     * through the L1 (foreground) decision branch instead of the L2
     * (background) one and tripped a wrong-state teardown on the subsequent
     * onActivityStarted→0 transition.
     *
     * The `onActivityStarted` 0→1 transition below still flips this to `true`
     * on the first real Activity start, so the foreground UX is unchanged;
     * only the "no Activity yet" window is now correctly treated as background.
     */
    private val _isInForeground = MutableStateFlow(false)
    val isInForeground: StateFlow<Boolean> = _isInForeground.asStateFlow()

    @Volatile private var activityStartedCount = 0

    /**
     * D1 (gate #2, §4.3): pending background-confirmation job. A 1→0
     * `onActivityStopped` transition does NOT flip `_isInForeground=false`
     * synchronously — it launches this confirmation job, which waits
     * [BACKGROUND_CONFIRMATION_MS] (matching AndroidX
     * `ProcessLifecycleOwner`'s 700ms) and re-checks that the started-count
     * is still 0 before actually emitting background. The 0→1
     * `onActivityStarted` transition cancels this job, so an in-flight
     * configuration change (rotation: 1→0→1 inside 700ms) does NOT wrongly
     * drive the §4.3 foreground→background→foreground cycle (which would
     * have flipped L1→L3/L2 on stale data).
     */
    private var backgroundConfirmationJob: Job? = null

    init {
        // registerActivityLifecycleCallbacks fires on the main thread; we
        // count started/stopped transitions and translate to a Boolean
        // foreground StateFlow.
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                // D1 (gate #2): a 0→1 transition cancels any pending
                // background-confirmation job (rotation 1→0→1 inside 700ms
                // must NOT emit background). Done BEFORE incrementing so a
                // racing in-flight confirmation recheck observes count >= 1.
                backgroundConfirmationJob?.cancel()
                backgroundConfirmationJob = null
                activityStartedCount++
                if (activityStartedCount == 1 && !_isInForeground.value) {
                    _isInForeground.value = true
                    onEnterForeground()
                }
            }
            override fun onActivityStopped(activity: Activity) {
                activityStartedCount = (activityStartedCount - 1).coerceAtLeast(0)
                // D1 (gate #2, §4.3): do NOT flip foreground synchronously at
                // count 0. A rotation produces a transient 1→0→1 cycle and
                // the synchronous flip would wrongly drive L1→L3/L2 on the
                // §4.3 foreground signal. Match AndroidX
                // ProcessLifecycleOwner's 700ms confirmation: cancel any
                // prior pending job, launch a new one, and only actually
                // emit background if the count is STILL 0 after the delay.
                if (activityStartedCount == 0 && _isInForeground.value) {
                    backgroundConfirmationJob?.cancel()
                    backgroundConfirmationJob = uiScope.launch {
                        delay(BACKGROUND_CONFIRMATION_MS)
                        // Lifecycle callbacks and this delayed recheck share
                        // Main.immediate, so a start cannot race a stale
                        // Default-dispatcher confirmation into a false flip.
                        if (activityStartedCount == 0 && _isInForeground.value) {
                            _isInForeground.value = false
                            onEnterBackground()
                        }
                    }
                }
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /**
     * Called from the ActivityLifecycleCallbacks when the started-activity
     * count goes 0→1 (process enters foreground). Idempotent.
     *
     * Phase 1 (后台驻留移除): the background poller cancel + shade-hygiene
     * notification cancel that USED to live here were deleted with the rest
     * of the notification subsystem. Foreground return re-arms SSE via the
     * health probe; no explicit work is needed here.
     */
    private fun onEnterForeground() {
        // No-op: foreground uses in-app surfaces; SSE reconnect is driven by
        // the health probe's foreground monitor.
    }

    /**
     * Called from the ActivityLifecycleCallbacks when the started-activity
     * count goes 1→0 (process enters background). Idempotent.
     */
    private fun onEnterBackground() {
        // §C1: flush any pending debounced draft write so the user's unsent
        // text survives the app going to background / process reclaim. The
        // debounce (500ms) may have a pending mutation at the moment of
        // backgrounding; flushing here makes it durable. Best-effort: ESP
        // .apply() schedules async disk IO; this covers the common case
        // (process alive but backgrounded) — process death is out of scope
        // per the C1 constraints.
        runCatching { settingsManager.flushDraftText() }
            .onFailure { Log.w(TAG, "Failed to flush pending draft on background", it) }
    }

    companion object {
        private const val TAG = "AppLifecycleMonitor"

        /**
         * D1 (gate #2, §4.3): delay between the started-activity count
         * reaching 0 and the actual `_isInForeground=false` flip. Matches
         * AndroidX `ProcessLifecycleOwner`'s established 700ms window — a
         * rotation's transient 1→0→1 cycle completes well inside this, so
         * the §4.3 foreground signal stays stable across configuration
         * changes. Do NOT use a different value without coordinating with
         * the lifecycle-state tests (the L1→L3/L2 transitions keyed off
         * this signal depend on this exact delay for correctness).
         */
        const val BACKGROUND_CONFIRMATION_MS = 700L
    }
}
