package cn.vectory.ocdroid.di

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import cn.vectory.ocdroid.MainActivity
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.PermissionRequest
import cn.vectory.ocdroid.data.model.QuestionRequest
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.util.DebugLog
import cn.vectory.ocdroid.util.SettingsManager
import cn.vectory.ocdroid.util.runSuspendCatching
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Qualifier
import javax.inject.Singleton
import javax.inject.Inject

/**
 * Hilt qualifier for the application-wide [CoroutineScope] tied to the
 * Application process (vs the Activity-scoped `viewModelScope`). The
 * background notification poller (§18) runs on this scope so it survives
 * Activity destruction while the process is alive. Best-effort (D1): when
 * the OS reclaims the process the scope is cancelled along with it.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/**
 * Hilt module that provides the application-wide [CoroutineScope] used by
 * [AppLifecycleMonitor] for best-effort background polling (§18, D1).
 */
@Module
@InstallIn(SingletonComponent::class)
object ApplicationScopeModule {
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

/**
 * Process-foreground awareness + best-effort background notification poller.
 *
 * This is the seam between §15 (SSE/lifecycle) and §18 (notifications) per
 * R-A: ON_STOP disconnects SSE (saves data, avoids half-open sockets), while
 * a separate 30 s poller on [ApplicationScope] keeps firing as long as the
 * process is alive. Notifications are thus decoupled from SSE — no service,
 * no foreground notification, zero channel noise while in foreground.
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
    @ApplicationScope private val appScope: CoroutineScope,
    private val repository: OpenCodeRepository,
    // §R18 Phase 2-E step 1: needed to pass the explicit directory header to
    // getPendingQuestions for the background poll (was injected from the
    // global currentDirectory before; that fallback is being phased out).
    private val settingsManager: SettingsManager
) {
    /**
     * §4.3 foreground truth-source.
     *
     * **Default `false`** (CP8): a sticky-rebuilt process with no started
     * Activity is **background**, NOT foreground. The previous `true` default
     * caused the §4.3 bug — a Service-only sticky rebuild (the OS restarted
     * `SessionStreamingService` after process death without bringing any
     * Activity back up) misreported foreground, which would have routed the
     * §5 bootstrap through the L1 (foreground) decision branch instead of
     * the L2 (background) one and tripped a wrong-state teardown on the
     * subsequent onActivityStarted→0 transition.
     *
     * The `onActivityStarted` 0→1 transition below still flips this to `true`
     * on the first real Activity start, so the foreground UX is unchanged;
     * only the "no Activity yet" window is now correctly treated as background.
     */
    private val _isInForeground = MutableStateFlow(false)
    val isInForeground: StateFlow<Boolean> = _isInForeground.asStateFlow()

    /**
     * "Already-notified" snapshot of permission/question IDs. Per §18.1 / N4:
     * **grow-only across ON_STOP/ON_START** so a pending item that the user
     * dismissed does not re-notify on every backgrounding. Cleared only on
     * process death (acceptable UX trade-off vs. the alternative of looping
     * notifications). Key shape: `"perm:<id>"` / `"q:<id>"`.
     */
    private val notificationSnapshot: MutableSet<String> = HashSet()

    /** Currently-running background poller job; null while in foreground. */
    private var pollJob: Job? = null

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

    /** Last error message surfaced via [onAppError]; tracked so we don't loop. */
    private var lastNotifiedError: String? = null

    private val notificationManagerCompat = NotificationManagerCompat.from(application)

    @Volatile private var activityStartedCount = 0

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
                    backgroundConfirmationJob = appScope.launch {
                        delay(BACKGROUND_CONFIRMATION_MS)
                        // Re-check under the same dispatcher (appScope =
                        // Dispatchers.Default). A racing onActivityStarted
                        // would have cancelled this job before this line;
                        // the explicit count re-check is belt-and-suspenders.
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
     * Hook for [cn.vectory.ocdroid.ui.MainViewModel] to push its
     * `state.error` changes. Per §18.1: when the error changes AND we are in
     * background, fire an `ocdroid.errors` notification. Errors are **not**
     * snapshot-deduplicated (the spec wants errors to be repeatable).
     */
    fun onAppError(error: String?) {
        if (error == null) return
        if (error == lastNotifiedError) return
        lastNotifiedError = error
        if (!_isInForeground.value) {
            notifyError(error)
        }
    }

    /**
     * Called from the ActivityLifecycleCallbacks when the started-activity
     * count goes 0→1 (process enters foreground). Idempotent.
     */
    private fun onEnterForeground() {
        // Nothing to do for notifications: foreground uses in-app cards.
        // The background poller (if running) is cancelled here to be safe
        // against double-emissions (inverse of [onEnterBackground]).
        pollJob?.cancel()
        pollJob = null
    }

    /**
     * Called from the ActivityLifecycleCallbacks when the started-activity
     * count goes 1→0 (process enters background). Idempotent.
     *
     * The [notificationSnapshot] is intentionally **preserved** (§18.1 / N4)
     * — clearing it would cause every pending item to re-notify on each
     * subsequent backgrounding.
     */
    private fun onEnterBackground() {
        startBackgroundPolling()
    }

    private fun startBackgroundPolling() {
        pollJob?.cancel()
        pollJob = appScope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                if (!_isInForeground.value) {
                    pollPendingItems()
                }
            }
        }
    }

    private suspend fun pollPendingItems() {
        // Permissions
        // R-14: runSuspendCatching rethrows CancellationException so the
        // background poller's viewModelScope/appScope cancellation still
        // propagates cleanly (runCatching would swallow it and keep the
        // cancelled poller looping).
        runSuspendCatching { repository.getPendingPermissions().getOrDefault(emptyList()) }
            .onSuccess { permissions -> permissions.forEach { handlePendingPermission(it) } }
            .onFailure { Log.w(TAG, "Background poll getPendingPermissions failed", it) }
        // Questions
        // §R18 Phase 2-E step 1: explicit directory header (was injected from
        // the global currentDirectory before).
        runSuspendCatching {
            repository.getPendingQuestions(settingsManager.currentWorkdir).getOrDefault(emptyList())
        }
            .onSuccess { questions ->
                // §Phase1a instrumentation (Issue 1): workdir queried + count returned.
                DebugLog.d("Question", "pollPendingQuestions workdir=${settingsManager.currentWorkdir ?: "null"} count=${questions.size}")
                questions.forEach { handlePendingQuestion(it) }
            }
            .onFailure { Log.w(TAG, "Background poll getPendingQuestions failed", it) }
    }

    private fun handlePendingPermission(permission: PermissionRequest) {
        val key = "perm:${permission.id}"
        // §Stage D (gpter 重要 #4): run ALL eligibility checks (foreground,
        // already-notified, permission) BEFORE touching the snapshot, and only
        // record the key after a real notify attempt. Previously the add
        // happened first, so a pending item seen while backgrounded with
        // notifications disabled was permanently marked "already notified" —
        // the user enabling the permission later could never receive it. This
        // preserves "don't re-notify the same item" semantics while allowing
        // deferred delivery once permission is granted.
        if (_isInForeground.value) return // foreground uses in-app cards
        if (key in notificationSnapshot) return // already notified
        val notified = notifyDecision(
            sessionId = permission.sessionId,
            title = NOTIF_PERMISSION_TITLE,
            body = permission.permission ?: NOTIF_DECISION_BODY,
            notificationId = key.hashCode()
        )
        if (notified) notificationSnapshot.add(key)
    }

    private fun handlePendingQuestion(question: QuestionRequest) {
        val key = "q:${question.id}"
        // §Stage D (gpter 重要 #4): see handlePendingPermission — snapshot add
        // is deferred until after a real notify so permission-denied items
        // remain eligible for future delivery.
        if (_isForeground()) return
        if (key in notificationSnapshot) return
        val headline = question.questions.firstOrNull()?.header ?: NOTIF_DECISION_BODY
        val notified = notifyDecision(
            sessionId = question.sessionId,
            title = NOTIF_QUESTION_TITLE,
            body = headline,
            notificationId = key.hashCode()
        )
        if (notified) notificationSnapshot.add(key)
    }

    /**
     * Returns `true` when a notification was actually posted (permission
     * granted + [notificationManagerCompat.notify] invoked); `false` when
     * suppressed by a missing/denied permission. Callers use the result to
     * decide whether to record the item in [notificationSnapshot].
     */
    // §lint: MissingPermission is satisfied by the hasNotificationPermission()
    // guard above (NotificationManagerCompat.areNotificationsEnabled), which
    // lint's dataflow can't track through the helper. The runtime check is
    // stricter than POST_NOTIFICATIONS (also honors per-channel settings).
    @Suppress("MissingPermission")
    private fun notifyDecision(sessionId: String, title: String, body: String, notificationId: Int): Boolean {
        if (!hasNotificationPermission()) return false
        val notification = NotificationCompat.Builder(application, CHANNEL_DECISIONS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(buildContentIntent(sessionId, notificationId))
            .build()
        // §notify-fix: a thrown notify() (channel missing / transient
        // SecurityException) must report failure so the caller does NOT
        // record the item in notificationSnapshot — otherwise it would be
        // permanently suppressed and never re-notified once the condition
        // recovers. isSuccess is false on any thrown exception.
        val posted = runCatching { notificationManagerCompat.notify(notificationId, notification) }.isSuccess
        return posted
    }

    // §lint: see notifyDecision — hasNotificationPermission() above is the guard.
    @Suppress("MissingPermission")
    private fun notifyError(error: String) {
        if (!hasNotificationPermission()) return
        val notification = NotificationCompat.Builder(application, CHANNEL_ERRORS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(NOTIF_ERROR_TITLE)
            .setContentText(error)
            .setStyle(NotificationCompat.BigTextStyle().bigText(error))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        // Use a fixed ID so the latest error replaces the previous one.
        runCatching { notificationManagerCompat.notify(ERROR_NOTIFICATION_ID, notification) }
    }

    private fun buildContentIntent(sessionId: String, requestCode: Int): PendingIntent {
        val intent = Intent(application, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            action = Intent.ACTION_VIEW
            putExtra(MainActivity.EXTRA_SESSION_ID, sessionId)
        }
        return PendingIntent.getActivity(
            application,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun hasNotificationPermission(): Boolean {
        // POST_NOTIFICATIONS is runtime on API 33+; granted-bypass on older.
        return NotificationManagerCompat.from(application).areNotificationsEnabled()
    }

    // Avoids the StateFlow getter indirection in tight loops.
    private fun _isForeground(): Boolean = _isInForeground.value

    companion object {
        private const val TAG = "AppLifecycleMonitor"

        const val CHANNEL_DECISIONS = "ocdroid.decisions"
        const val CHANNEL_ERRORS = "ocdroid.errors"

        /**
         * Ongoing session-status channel id (FGS spec §7 channel matrix /
         * dev-design P1.4). IMPORTANCE_LOW. Used by
         * [cn.vectory.ocdroid.service.SessionStreamingService] for the
         * ongoing FGS notification.
         *
         * **Single-source rule**: this is the one canonical home for the
         * channel id. The Service references `AppLifecycleMonitor.CHANNEL_SESSION_STATUS`
         * rather than keeping its own copy. (Pre-CP8 the Service held its
         * own const as a placeholder; CP8 moves the source here because
         * `createChannels` — which uses the id — lives in this companion.)
         */
        const val CHANNEL_SESSION_STATUS = "ocdroid.session_status"

        /** Background polling interval per §18.1 (R-A, D1). */
        private const val POLL_INTERVAL_MS = 30_000L

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

        /** Fixed notification ID for the latest app error. */
        private const val ERROR_NOTIFICATION_ID = 4242

        /**
         * Creates the notification channels required by §18.1 + §7. Wrapped
         * in try/catch and only invoked on API 26+ (NotificationChannel was
         * added in O). Channels are idempotent — re-creating with the same
         * ID is a no-op. Called from [cn.vectory.ocdroid.OpenCodeApp.onCreate].
         *
         * CP8: adds [CHANNEL_SESSION_STATUS] (FGS spec §7 IMPORTANCE_LOW)
         * alongside the existing [CHANNEL_DECISIONS] / [CHANNEL_ERRORS].
         */
        fun createChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            runCatching {
                val manager = context.getSystemService(NotificationManager::class.java) ?: return@runCatching
                val decisions = NotificationChannel(
                    CHANNEL_DECISIONS,
                    CHANNEL_DECISIONS_NAME,
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = CHANNEL_DECISIONS_DESC
                }
                val errors = NotificationChannel(
                    CHANNEL_ERRORS,
                    CHANNEL_ERRORS_NAME,
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = CHANNEL_ERRORS_DESC
                }
                val sessionStatus = NotificationChannel(
                    CHANNEL_SESSION_STATUS,
                    CHANNEL_SESSION_STATUS_NAME,
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = CHANNEL_SESSION_STATUS_DESC
                }
                manager.createNotificationChannels(listOf(decisions, errors, sessionStatus))
            }.onFailure { Log.w(TAG, "Failed to create notification channels", it) }
        }

        // Hardcoded channel/notification strings. v2-redesign write-domain
        // excludes res/values/strings.xml, so we keep these literals inline
        // rather than fragmenting the lane by touching resources. A future
        // i18n pass can promote them to R.string entries.
        private const val CHANNEL_DECISIONS_NAME = "opencode decisions"
        private const val CHANNEL_DECISIONS_DESC = "Permission and question prompts from opencode sessions"
        private const val CHANNEL_ERRORS_NAME = "opencode errors"
        private const val CHANNEL_ERRORS_DESC = "Connection and runtime errors from opencode"
        private const val CHANNEL_SESSION_STATUS_NAME = "opencode session status"
        private const val CHANNEL_SESSION_STATUS_DESC =
            "Ongoing session-status notifications while opencode is connected in the background"
        const val NOTIF_PERMISSION_TITLE = "Permission required"
        const val NOTIF_QUESTION_TITLE = "Question from agent"
        const val NOTIF_DECISION_BODY = "Open the session to review"
        const val NOTIF_ERROR_TITLE = "opencode error"
    }
}
