package cn.vectory.ocdroid.di

import android.app.Activity
import android.app.Application
import android.app.NotificationManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.PermissionRequest
import cn.vectory.ocdroid.data.model.QuestionRequest
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.service.identity.ConnectionIdentity
import cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
import cn.vectory.ocdroid.util.DebugLog
import cn.vectory.ocdroid.util.SettingsManager
import cn.vectory.ocdroid.util.runSuspendCatching
import cn.vectory.ocdroid.ui.controller.IdleUnreadAlert
import cn.vectory.ocdroid.ui.controller.UnreadPollResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Singleton
import javax.inject.Inject

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
    @UiApplicationScope private val uiScope: CoroutineScope,
    private val repository: OpenCodeRepository,
    // §R18 Phase 2-E step 1: needed to pass the explicit directory header to
    // getPendingQuestions for the background poll (was injected from the
    // global currentDirectory before; that fallback is being phased out).
    private val settingsManager: SettingsManager,
    /**
     * C-D3 rev-3 / Important: background q/p notification poll must not
     * publish under a host that reconfigured mid-flight. Capture + dual-check
     * against this store (and [OpenCodeRepository] slim token) before notify.
     */
    private val identityStore: ConnectionIdentityStore,
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
    // main (unread stale-poll guard, d999259): monotonic generation bumped on
    // lifecycle transitions; BackgroundUnreadPoller captures it via
    // currentLifecycleGeneration() to reject commits whose lifecycle has since
    // moved. Kept alongside the CP8 default-false + §4.3 debounce below.
    private val lifecycleGeneration = java.util.concurrent.atomic.AtomicLong(0)
    private val _isInForeground = MutableStateFlow(false)
    val isInForeground: StateFlow<Boolean> = _isInForeground.asStateFlow()

    /**
     * "Already-notified" snapshot of permission/question IDs. Per §18.1 / N4:
     * **grow-only across ON_STOP/ON_START** so a pending item that the user
     * dismissed does not re-notify on every backgrounding. Cleared only on
     * process death (acceptable UX trade-off vs. the alternative of looping
     * notifications). Key shape: `"perm:<id>"` / `"q:<id>"`.
     *
     * T5-C4a: backed by [NotificationDedup] (CHM key-set) so the SSE bridge
     * can claim against the SAME set atomically with the 30s poller — the
     * two run on different dispatchers (Default vs the Service's MainScope)
     * and would otherwise race a check-then-add.
     */
    internal val notificationSnapshot: NotificationDedup = NotificationDedup()
    internal val idleNotificationSnapshot: NotificationDedup = NotificationDedup()

    /**
     * Race-closure mutex: serializes the IDLE publish critical section
     * ([handleIdleAlert] + the SSE bridge's idle branch) with the IDLE
     * prune + side-effect critical section ([pollPendingItems] post-prune
     * loop) so no deferred-stale side-effect (cancel + removePostedIdle)
     * can interleave with a fresh completion (claim → notifyIdle →
     * complete → addPostedIdle).
     *
     * Background (the closed race): pre-fix the poller (Dispatchers.Default)
     * computed `pruned = candidates.keys - afterPrune.keys` and then ran the
     * DEFERRED `notificationManager.cancel(key.hashCode())` +
     * `dedupStore.removePostedIdle(key)` loop OUTSIDE any lock. In the window
     * between the `afterPrune` snapshot and that loop, the SSE bridge (Main)
     * could re-claim + complete + `addPostedIdle` the same key; the poller's
     * stale deferred remove/cancel then clobbered the fresh completion
     * (deletes the mirror entry → regeneration on process death; cancels the
     * fresh shade → missed notification).
     *
     * Both critical sections (publish + prune+side-effects) now run under
     * THIS mutex. `Mutex.withLock` suspends (it yields the dispatcher); the
     * poller holds it across `withContext(Dispatchers.Main.immediate)` for
     * the notify — if the bridge (on Main) then tries to acquire it
     * suspends and yields Main, letting the poller's Main block run and
     * release. No deadlock (do NOT swap for `synchronized`/`runBlocking`).
     *
     * Module-internal so the SSE bridge (sister package, same module) can
     * receive the SAME instance at construction (see
     * [cn.vectory.ocdroid.service.SessionStreamingService]).
     */
    internal val idleMutex: Mutex = Mutex()

    private val notificationManagerCompat = NotificationManagerCompat.from(application)

    /**
     * Bug-1 (notification regeneration): the framework NotificationManager —
     * used for the [NotificationManager.getActiveNotifications] belt-and-
     * suspenders guard in [SessionNotifier] AND for the
     * [onEnterForeground] shade-hygiene cancellation (decisions + idle only;
     * the ongoing FGS notification and the error notification are NOT
     * cancelled).
     */
    private val notificationManager: NotificationManager =
        application.getSystemService(NotificationManager::class.java)

    /**
     * Bug-1 (notification regeneration): durable dedup store. The in-memory
     * [NotificationDedup] state is wiped on process death; this persists the
     * "posted keys" snapshot to a plain SharedPreferences file so a freshly-
     * booted process can [NotificationDedup.seedPosted] before any claim and
     * suppress re-notification of roots already posted in a prior session.
     */
    private val dedupStore = NotificationDedupStore(application)

    /**
     * T5-C4b: the shared publish point. Held by ALM so the SSE bridge can
     * inject the SAME instance the 30s poller uses (the bridge's
     * constructor takes a [SessionNotifier] reference).
     */
    internal val notifier: SessionNotifier = SessionNotifier(application, notificationManagerCompat)

    @Volatile
    private var backgroundUnreadPoll: (suspend () -> UnreadPollResult)? = null

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
                    lifecycleGeneration.incrementAndGet()
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
                    // main (unread stale-poll guard): bump the generation
                    // immediately on the activity→0 edge so any in-flight
                    // BackgroundUnreadPoller whose lifecycle has moved rejects
                    // its commit. This runs alongside (not instead of) the
                    // §4.3 700ms debounce below — the generation guard and the
                    // foreground-truth debounce solve different races.
                    lifecycleGeneration.incrementAndGet()
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
        // Bug-1 (notification regeneration): seed the in-memory IDLE dedup
        // set from durable storage BEFORE any claim can fire. Without this,
        // a freshly-booted process re-notifies every idle unread root it
        // had already posted before process death.
        //
        // Bug-1-fix-C (scope reduction): the DECISION dedup set is
        // intentionally NOT seeded — the original "process death = only GC,
        // so still-pending blocking items correctly re-remind" contract is
        // preserved. Only the idle dedup was the reported bug; persisting
        // decisions would grow unbounded (a permission id is never reused)
        // and break the re-remind semantics. Safe to run last in init:
        // [startBackgroundPolling] is invoked only on the first
        // onEnterBackground, well after init completes, so the seed always
        // wins the race against the first claim.
        val seededIdle = dedupStore.snapshotIdle()
        idleNotificationSnapshot.seedPosted(seededIdle)
        Log.d(
            TAG,
            "seeded dedup: idle=${seededIdle.size}",
        )
    }

    internal fun currentLifecycleGeneration(): Long = lifecycleGeneration.get()

    /**
     * Bug-1-fix-B (SSE bridge persistence seam): the SSE-side bridge
     * completes an idle dedup claim on a successful idle notify, but
     * without persisting the completed key it would be lost on process
     * death (only self-healing ≤30s later via the poller's post-prune
     * save). The bridge calls this to mirror the completion into the
     * durable store via the SAME additive API the poller's notify path
     * uses, so a restart re-seeds the bridge-completed key. Best-effort:
     * swallows any persistence failure so the bridge path never throws.
     */
    internal fun persistIdlePosted(key: String) {
        runCatching { dedupStore.addPostedIdle(key) }
            .onFailure { Log.w(TAG, "Failed to persist idle dedup (bridge) key=$key", it) }
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

    /** Registers the narrow T3b bridge without coupling lifecycle to UI state. */
    fun registerBackgroundUnreadPoller(poller: suspend () -> UnreadPollResult) {
        backgroundUnreadPoll = poller
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
        // Bug-1 (shade hygiene): cancel DECISION + IDLE notifications that
        // are still in the shade when the app returns to foreground — the
        // user is now in-app and the in-app surfaces are the source of truth,
        // so leaving shade entries would be redundant. This does NOT clear
        // the dedup state, so when the user backgrounds again already-
        // notified roots still won't re-notify (correct). The ongoing FGS
        // notification (CHANNEL_SESSION_STATUS / CHANNEL_SESSION_STATUS_MIN,
        // id 4241) and the error notification (id 4242) are deliberately
        // left alone — they are owned by the FGS / error path, not by the
        // decision/idle notify path. minSdk=34 so getActiveNotifications()
        // (API 23+) needs no version guard.
        runCatching {
            notificationManager.activeNotifications.forEach { n ->
                val channel = n.notification.channelId
                if (channel == NotificationChannels.CHANNEL_DECISIONS || channel == NotificationChannels.CHANNEL_IDLE) {
                    notificationManager.cancel(n.id)
                }
            }
        }.onFailure { Log.w(TAG, "Failed to cancel shade entries on foreground", it) }
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

    /**
     * C-D3 rev-3 round-5 (oracle §9.4 / §10.4): visibility bumped from
     * `private` to `internal` so the background-poller discriminator test
     * can invoke the REAL poll path (with MockWebServer + delayed response +
     * mid-flight reconfigure) instead of approximating it via
     * [handlePendingPermission] with hand-crafted stale fences. The test
     * proves a host switch mid-flight causes the stale A response to be
     * silent-dropped (notifier.notifyDecision NOT called).
     */
    internal suspend fun pollPendingItems() {
        // C-D3 rev-3 Important: capture slim token + connection identity at
        // poll entry so a host switch mid-flight cannot publish old-host
        // permission/question notifications under the new host. Publish
        // re-checks both fences; stale → silent drop (no error toast).
        val slimToken = repository.captureSlimCommitToken()
        val identityAtEntry = identityStore.currentIdentity.value

        // Permissions
        // R-14: runSuspendCatching rethrows CancellationException so the
        // background poller's viewModelScope/appScope cancellation still
        // propagates cleanly (runCatching would swallow it and keep the
        // cancelled poller looping).
        runSuspendCatching { repository.getPendingPermissions().getOrDefault(emptyList()) }
            .onSuccess { permissions ->
                if (!isBackgroundPollStillCurrent(slimToken, identityAtEntry)) {
                    Log.d(TAG, "Background poll permissions dropped (stale host/token)")
                    return@onSuccess
                }
                permissions.forEach { handlePendingPermission(it, slimToken, identityAtEntry) }
            }
            .onFailure { Log.w(TAG, "Background poll getPendingPermissions failed", it) }
        // Questions
        // §R18 Phase 2-E step 1: explicit directory header (was injected from
        // the global currentDirectory before).
        runSuspendCatching {
            repository.getPendingQuestions(settingsManager.currentWorkdir).getOrDefault(emptyList())
        }
            .onSuccess { questions ->
                if (!isBackgroundPollStillCurrent(slimToken, identityAtEntry)) {
                    Log.d(TAG, "Background poll questions dropped (stale host/token)")
                    return@onSuccess
                }
                // §Phase1a instrumentation (Issue 1): workdir queried + count returned.
                DebugLog.d("Question", "pollPendingQuestions workdir=${settingsManager.currentWorkdir ?: "null"} count=${questions.size}")
                questions.forEach { handlePendingQuestion(it, slimToken, identityAtEntry) }
            }
            .onFailure { Log.w(TAG, "Background poll getPendingQuestions failed", it) }

        // Re-check foreground before and after network work. A poll that began
        // in background must not emit sound/vibration after the Activity starts.
        if (_isInForeground.value) return
        // T5-round-4 I1-S fence: capture the Posted candidates BEFORE the poll
        // produces the active set S. A posting created during/after the poll
        // (e.g. the SSE bridge — on the Service's MainScope — completing a
        // claim to `Posted(tokenB)` between S-compute and prune) is NOT in
        // `candidates` and survives this prune cycle. The OLD bare
        // `retainAll(active)` scanned the map AFTER S was computed, so it
        // captured B's freshly-completed `Posted(tokenB)` and (captured ==
        // current, both tokenB) removed it → C re-claimed → duplicate
        // sound/vibration. Routing the idle-prune path through `snapshotPosted`
        // (candidate capture) + `pruneStaleCandidates` (scan restricted to
        // candidates, per-entry `computeIfPresent` guards the exact captured
        // generation) closes the snapshot-acquire-vs-prune-execute TOCTOU.
        val candidates = idleNotificationSnapshot.snapshotPosted()
        runSuspendCatching { backgroundUnreadPoll?.invoke() }
            .onSuccess { result ->
                // T5-round-5 I1-A: distinguish an authoritative snapshot from
                // an aborted poll. The OLD contract returned `emptyList()` for
                // both, so `onSuccess` treated every abort as an authoritative
                // empty snapshot → `active = emptySet()` → the fenced prune
                // removed live `Posted` candidates (exact-generation match) →
                // the next genuine poll re-claimed the same key → duplicate
                // sound/vibration for one logical idle event. The sealed
                // [UnreadPollResult] restores the distinction: only
                // [UnreadPollResult.Authoritative] drives prune + publish;
                // [UnreadPollResult.Aborted] (and a null unregistered poller)
                // skip the prune entirely so the dedup state stays intact.
                // Cancellation is NOT routed here — `runSuspendCatching`
                // rethrows CancellationException, preserving structured
                // cancellation (viewModelScope / appScope cancel propagates).
                val alerts = (result as? UnreadPollResult.Authoritative)?.alerts ?: return@onSuccess
                val active = alerts.mapTo(mutableSetOf()) { it.key }
                // Race-closure: the prune + side-effect region runs under
                // the shared [idleMutex] so it cannot interleave with a
                // concurrent publish (handleIdleAlert here, or the SSE
                // bridge's idle branch). Pre-fix the side-effect loop
                // (`pruned.forEach { cancel + removePostedIdle }`) ran
                // unlocked; in the window between `afterPrune` and that
                // loop the bridge could re-claim+complete+addPostedIdle the
                // same key, and this poller's stale deferred remove/cancel
                // clobbered the fresh completion. Under the lock: if
                // publish ran first the prune's afterPrune (taken inside
                // this locked region, after publish released) sees the key
                // PRESENT → it's excluded from `pruned` → not cancelled/
                // removed. If prune ran first the publish re-claims and
                // re-adds fresh. Both orderings leave B's final state
                // correct. handleIdleAlert (called below, OUTSIDE this
                // withLock) acquires the lock itself — sequential, no
                // nesting.
                idleMutex.withLock {
                    idleNotificationSnapshot.pruneStaleCandidates(candidates, active)
                    // Bug-1-fix-A (missed-notification regression): when a key
                    // is pruned from the dedup set, ALSO cancel its lingering
                    // shade entry. Pre-fix the only cancel site was
                    // [onEnterForeground], so a root that went idle→notified
                    // (user ignored the shade entry) → busy (key pruned via
                    // pruneStaleCandidates, but the shade entry NEVER cancelled)
                    // → idle again (real new completion) would hit the lingering
                    // shade entry (same id = key.hashCode()) and be silently
                    // suppressed by the activeNotifications guard. Cancelling
                    // here re-arms the guard for a future genuine new idle on
                    // the same root.
                    //
                    // Bug-1-fix-C/race (additive persistence): keep the
                    // persisted mirror in sync via additive removePostedIdle
                    // (never a full-snapshot rewrite — the prior
                    // `saveIdleKeys(snapshotPosted().keys)` was called from two
                    // dispatchers (Main.immediate notify + Default prune) and
                    // could transiently drop a key). The diff
                    // `candidates.keys - afterPrune.keys` is the set of keys
                    // that were Posted before the poll but are no longer Posted
                    // — i.e. removed by THIS prune cycle. (Posted is terminal;
                    // only prune removes it between the two snapshots, so the
                    // diff is exactly the pruned set.)
                    val afterPrune = idleNotificationSnapshot.snapshotPosted()
                    val pruned = candidates.keys - afterPrune.keys
                    pruned.forEach { key ->
                        runCatching { notificationManager.cancel(key.hashCode()) }
                            .onFailure { Log.w(TAG, "Failed to cancel shade entry for pruned key=$key", it) }
                        runCatching { dedupStore.removePostedIdle(key) }
                            .onFailure { Log.w(TAG, "Failed to remove persisted idle dedup key=$key", it) }
                    }
                }
                if (!_isInForeground.value) alerts.forEach { handleIdleAlert(it) }
            }
            .onFailure { Log.w(TAG, "Background unread poll failed", it) }
    }

    internal suspend fun handleIdleAlert(alert: IdleUnreadAlert) {
        // T5-C4a: atomic claim BEFORE notify (was check-then-add). The SSE
        // bridge shares [idleNotificationSnapshot], so the claim must be
        // atomic to prevent a double-notify. Release on notify failure so
        // the item stays eligible for the next poll (§Stage D semantics).
        // T5-review I1: claim returns an owner-aware token; complete /
        // release are token-gated so neither pruning nor a racing release
        // can strip this claimant's slot.
        // T5-review I2: recheck foreground at the FINAL publish boundary.
        // T5-re-review I2-R: the final foreground gate + notify + complete
        // run SYNCHRONOUSLY on Dispatchers.Main.immediate, serialized with
        // [onActivityStarted] (which sets `_isInForeground = true` on Main).
        // The poller runs on Dispatchers.Default; the prior read-then-notify
        // on Default was a TOCTOU — a lifecycle ON_START on Main between the
        // Default foreground read and the Default notify posted sound /
        // vibration while foregrounded. Main.immediate closes the window:
        // the gate+notify cannot be interleaved by a lifecycle callback.
        // Suppression / failure releases the claim (the finally below) so a
        // later background attempt can fire.
        if (_isInForeground.value) return
        // Race-closure: the entire publish critical section (claim →
        // foreground gate → notifyIdle → complete → addPostedIdle → release)
        // runs under the shared [idleMutex] so it cannot interleave with the
        // poller's prune + side-effect critical section ([pollPendingItems]
        // post-prune loop) NOR with the SSE bridge's idle publish branch
        // (which acquires the SAME mutex). The fast-path foreground early-out
        // above stays OUTSIDE the lock — no claim has been taken yet, so a
        // stale observation cannot clobber anything. See [idleMutex] for the
        // full deadlock / ordering analysis.
        idleMutex.withLock {
            val token = idleNotificationSnapshot.claim(alert.key) ?: run {
                Log.d(TAG, "idle claim LOST key=${alert.key} (already in dedup)")
                return@withLock
            }
            try {
                withContext(Dispatchers.Main.immediate) {
                    if (_isInForeground.value) {
                        Log.d(TAG, "idle notify SUPPRESSED (foreground) key=${alert.key}")
                        return@withContext
                    }
                    val notified = notifier.notifyIdle(alert.rootId, alert.title, alert.key)
                    if (notified) {
                        val completed = idleNotificationSnapshot.complete(alert.key, token)
                        Log.d(
                            TAG,
                            "idle notify POSTED key=${alert.key} complete=$completed fg=${_isInForeground.value}",
                        )
                        // Bug-1-fix-C/race: persist the new Posted entry via the
                        // additive mirror API (never a full-snapshot rewrite).
                        // The prior `saveIdleKeys(snapshotPosted().keys)` was
                        // called from BOTH this Main.immediate path AND the
                        // Default post-prune path; two racing full-snapshot
                        // writes could transiently drop a key, and each save
                        // allocated + serialized the full set. addPostedIdle
                        // mutates a single shared mirror under a lock — the
                        // disk write is the exact delta. Best-effort; never
                        // throws into the publish boundary.
                        runCatching { dedupStore.addPostedIdle(alert.key) }
                            .onFailure { Log.w(TAG, "Failed to persist idle dedup after notify key=${alert.key}", it) }
                    } else {
                        Log.d(TAG, "idle notify FAILED (permission denied) key=${alert.key}")
                    }
                }
            } finally {
                idleNotificationSnapshot.release(alert.key, token)
            }
        }
    }

    /**
     * True when the background poll's entry fences still match the live
     * repository incarnation and connection identity.
     *
     * - No identity at entry (cold start / post-beginReconfigure window):
     *   rely on slim token only — if the slim marker rotated, drop.
     * - Identity present at entry: both [OpenCodeRepository.isSlimCommitTokenCurrent]
     *   and [ConnectionIdentityStore.isCurrent] must pass.
     */
    private fun isBackgroundPollStillCurrent(
        slimToken: OpenCodeRepository.SlimCommitToken,
        identityAtEntry: ConnectionIdentity?,
    ): Boolean {
        if (!repository.isSlimCommitTokenCurrent(slimToken)) return false
        if (identityAtEntry != null && !identityStore.isCurrent(identityAtEntry)) return false
        return true
    }

    internal suspend fun handlePendingPermission(
        permission: PermissionRequest,
        slimToken: OpenCodeRepository.SlimCommitToken? = null,
        identityAtEntry: ConnectionIdentity? = null,
    ) {
        val key = "perm:${permission.id}"
        // §Stage D (gpter 重要 #4) + T5-C4a: atomic claim + release-on-fail.
        // T5-review I1: owner-aware token-gated claim/complete/release.
        // T5-review I2 + T5-re-review I2-R: the final foreground gate +
        // notify + complete run on Dispatchers.Main.immediate, serialized
        // with lifecycle callbacks (see [handleIdleAlert]).
        if (_isInForeground.value) return
        // C-D3 rev-3: dual fence before claim (entry may have been current;
        // host may have switched while suspended on network).
        if (slimToken != null && !isBackgroundPollStillCurrent(slimToken, identityAtEntry)) {
            Log.d(TAG, "perm notify SUPPRESSED (stale host/token) key=$key")
            return
        }
        val token = notificationSnapshot.claim(key) ?: run {
            Log.d(TAG, "perm claim LOST key=$key (already in dedup)")
            return
        }
        try {
            withContext(Dispatchers.Main.immediate) {
                if (_isInForeground.value) {
                    Log.d(TAG, "perm notify SUPPRESSED (foreground) key=$key")
                    return@withContext
                }
                // Final publish-boundary recheck (Main.immediate; host switch
                // can still land between claim and notify).
                if (slimToken != null && !isBackgroundPollStillCurrent(slimToken, identityAtEntry)) {
                    Log.d(TAG, "perm notify SUPPRESSED (stale host/token at publish) key=$key")
                    return@withContext
                }
                val notified = notifier.notifyDecision(
                    sessionId = permission.sessionId,
                    title = NOTIF_PERMISSION_TITLE,
                    body = permission.permission ?: NOTIF_DECISION_BODY,
                    key = key,
                )
                if (notified) {
                    val completed = notificationSnapshot.complete(key, token)
                    Log.d(
                        TAG,
                        "perm notify POSTED key=$key complete=$completed fg=${_isInForeground.value}",
                    )
                    // Bug-1-fix-C: decision dedup is intentionally NOT
                    // persisted. The original "process death = only GC, so
                    // still-pending blocking items correctly re-remind"
                    // contract is preserved; only idle re-notification was
                    // the reported bug, and persisting decisions would grow
                    // unbounded (a permission id is never reused).
                } else {
                    Log.d(TAG, "perm notify FAILED (permission denied) key=$key")
                }
            }
        } finally {
            notificationSnapshot.release(key, token)
        }
    }

    internal suspend fun handlePendingQuestion(
        question: QuestionRequest,
        slimToken: OpenCodeRepository.SlimCommitToken? = null,
        identityAtEntry: ConnectionIdentity? = null,
    ) {
        val key = "q:${question.id}"
        // §Stage D (gpter 重要 #4) + T5-C4a: see handlePendingPermission.
        // T5-review I1: owner-aware token-gated claim/complete/release.
        // T5-review I2 + T5-re-review I2-R: Main.immediate publish gate
        // (see [handleIdleAlert]).
        if (_isForeground()) return
        if (slimToken != null && !isBackgroundPollStillCurrent(slimToken, identityAtEntry)) {
            Log.d(TAG, "question notify SUPPRESSED (stale host/token) key=$key")
            return
        }
        val token = notificationSnapshot.claim(key) ?: run {
            Log.d(TAG, "question claim LOST key=$key (already in dedup)")
            return
        }
        try {
            withContext(Dispatchers.Main.immediate) {
                if (_isForeground()) {
                    Log.d(TAG, "question notify SUPPRESSED (foreground) key=$key")
                    return@withContext
                }
                if (slimToken != null && !isBackgroundPollStillCurrent(slimToken, identityAtEntry)) {
                    Log.d(TAG, "question notify SUPPRESSED (stale host/token at publish) key=$key")
                    return@withContext
                }
                val headline = question.questions.firstOrNull()?.header ?: NOTIF_DECISION_BODY
                val notified = notifier.notifyDecision(
                    sessionId = question.sessionId,
                    title = NOTIF_QUESTION_TITLE,
                    body = headline,
                    key = key,
                )
                if (notified) {
                    val completed = notificationSnapshot.complete(key, token)
                    Log.d(
                        TAG,
                        "question notify POSTED key=$key complete=$completed fg=${_isInForeground.value}",
                    )
                    // Bug-1-fix-C: decision dedup is intentionally NOT
                    // persisted (see [handlePendingPermission]).
                } else {
                    Log.d(TAG, "question notify FAILED (permission denied) key=$key")
                }
            }
        } finally {
            notificationSnapshot.release(key, token)
        }
    }

    // T5-review M2: the per-handler `notifyDecision` / `notifyIdle` adapters
    // that USED to live here were removed — every handler now calls
    // [notifier] directly. They had no remaining callers (the SSE bridge
    // also calls [notifier] directly via the shared instance).

    // §lint: see notifyDecision — hasNotificationPermission() above is the guard.
    @Suppress("MissingPermission")
    private fun notifyError(error: String) {
        if (!NotificationManagerCompat.from(application).areNotificationsEnabled()) return
        val notification = NotificationCompat.Builder(application, NotificationChannels.CHANNEL_ERRORS)
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

    // Avoids the StateFlow getter indirection in tight loops.
    private fun _isForeground(): Boolean = _isInForeground.value

    companion object {
        private const val TAG = "AppLifecycleMonitor"

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

        const val NOTIF_PERMISSION_TITLE = "Permission required"
        const val NOTIF_QUESTION_TITLE = "Question from agent"
        const val NOTIF_DECISION_BODY = "Open the session to review"
        const val NOTIF_ERROR_TITLE = "opencode error"
        const val NOTIF_IDLE_TITLE = "Session finished"
    }
}
