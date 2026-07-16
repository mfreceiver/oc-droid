package cn.vectory.ocdroid.di

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import android.provider.Settings
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
import cn.vectory.ocdroid.ui.controller.IdleUnreadAlert
import cn.vectory.ocdroid.ui.controller.UnreadPollResult
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
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Qualifier
import javax.inject.Singleton
import javax.inject.Inject

/**
 * T5-C4b: shared publish point for the §18 decision/idle notifications.
 *
 * Extracted verbatim from the prior private methods on [AppLifecycleMonitor]
 * so the SSE-side bridge ([cn.vectory.ocdroid.service.streaming.SseNotificationBridge])
 * can call the SAME publish logic the 30s poller uses. The notification id
 * is always `key.hashCode()`; both call sites pass the SAME key for the
 * SAME logical event so a bridge-fired notification and a poller-fired
 * notification for one item visually replace each other instead of stacking
 * (the shared dedup set is the primary guard; the matched id is the
 * visual fallback).
 *
 * Internal visibility: the bridge lives in a sister package and is built
 * only by [SessionStreamingService] (the same module). Not a Hilt-provided
 * object — ALM owns one instance and exposes it via
 * [AppLifecycleMonitor.notifier].
 */
internal class SessionNotifier internal constructor(
    private val application: Application,
    private val notificationManagerCompat: NotificationManagerCompat,
) {
    /**
     * Returns `true` when a notification was actually posted (permission
     * granted + [NotificationManagerCompat.notify] invoked); `false` when
     * suppressed by a missing/denied permission. Callers use the result to
     * decide whether to record the item in their dedup set (a `false` return
     * MUST roll back the dedup claim so a later attempt can retry — see
     * [NotificationDedup]).
     */
    @Suppress("MissingPermission") // see hasNotificationPermission() guard
    fun notifyDecision(sessionId: String, title: String, body: String, key: String): Boolean {
        if (!hasNotificationPermission()) return false
        val notificationId = key.hashCode()
        val notification = NotificationCompat.Builder(application, AppLifecycleMonitor.CHANNEL_DECISIONS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_VIBRATE)
            .setAutoCancel(true)
            .setContentIntent(buildContentIntent(sessionId, notificationId))
            .build()
        // §notify-fix: a thrown notify() (channel missing / transient
        // SecurityException) must report failure so the caller does NOT
        // record the item in its dedup set — otherwise it would be
        // permanently suppressed and never re-notified once the condition
        // recovers. isSuccess is false on any thrown exception.
        return runCatching { notificationManagerCompat.notify(notificationId, notification) }.isSuccess
    }

    @Suppress("MissingPermission") // see hasNotificationPermission() guard
    fun notifyIdle(rootId: String, title: String, key: String): Boolean {
        if (!hasNotificationPermission()) return false
        val notificationId = key.hashCode()
        val notification = NotificationCompat.Builder(application, AppLifecycleMonitor.CHANNEL_IDLE)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(AppLifecycleMonitor.NOTIF_IDLE_TITLE)
            .setContentText(title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(title))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_VIBRATE)
            .setAutoCancel(true)
            .setContentIntent(buildContentIntent(rootId, notificationId))
            .build()
        return runCatching { notificationManagerCompat.notify(notificationId, notification) }.isSuccess
    }

    /**
     * Builds the deep-link [PendingIntent] that opens [MainActivity] on the
     * given session/root id. Internal so the bridge (which constructs its
     * own notifications only when the publisher is mocked in tests) does not
     * need to reach in here in production — the bridge delegates to
     * [notifyDecision] / [notifyIdle], which call this internally.
     */
    internal fun buildContentIntent(sessionId: String, requestCode: Int): PendingIntent {
        val intent = Intent(application, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            action = Intent.ACTION_VIEW
            putExtra(MainActivity.EXTRA_SESSION_ID, sessionId)
        }
        return PendingIntent.getActivity(
            application,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun hasNotificationPermission(): Boolean {
        // POST_NOTIFICATIONS is runtime on API 33+; granted-bypass on older.
        return NotificationManagerCompat.from(application).areNotificationsEnabled()
    }
}

/**
 * T5-C4a: thread-safe atomic-claim dedup set shared by the 30s poller and
 * the SSE bridge so the same logical event id is notified at most once
 * (a double-notify would stack `SOUND|VIBRATE` and confuse the user).
 *
 * Contract: callers MUST use [claim] BEFORE notifying, and MUST call
 * [release] if the downstream notify returned `false` (e.g. permission
 * denied — §Stage D / gpter 重要 #4: a failed notify must remain eligible
 * for future delivery). This preserves the prior "add-after-success"
 * semantics while making the claim atomic against a concurrent producer
 * (the SSE bridge runs on the Service's MainScope; the poller runs on
 * `@ApplicationScope` = Default dispatcher — they genuinely race).
 *
 * The underlying set is a [ConcurrentHashMap]-backed key set: `add` is
 * atomic (returns true iff the key was absent), so two racing `claim`
 * callers can never both observe "newly added". A successful claim reserves
 * the slot; the caller either keeps it (notify succeeded) or rolls it back
 * via [release] (notify failed → eligible for the next attempt).
 */
/**
 * T5-C4a + T5-review I1: thread-safe atomic-claim dedup set shared by the 30s
 * poller and the SSE bridge so the same logical event id is notified at most
 * once (a double-notify would stack `SOUND|VIBRATE` and confuse the user).
 *
 * Contract: callers MUST use [claim] BEFORE notifying. [claim] returns a
 * unique **owner-aware token** (or `null` if the key is already in flight /
 * posted) — exactly one claimant wins per key. On a successful notify the
 * winner MUST call [complete] with the SAME token to transition the slot to
 * [State.Posted] (which makes it eligible for idle pruning). On a failed /
 * suppressed notify the winner MUST call [release] with the SAME token to
 * roll back the claim so the next attempt can retry (§Stage D / gpter 重要 #4).
 *
 * T5-review I1 fix: the prior implementation used a flat `ConcurrentHashMap`
 * key set + key-only `release`. That had two defects:
 *  1. `retainAll(active)` (idle pruning) removed in-flight CLAIMED entries →
 *     a second claimant could then win `claim(K)` for an item whose first
 *     claimant was still posting → both notified.
 *  2. Key-only `release` had an ABA surface: claimant A released → claimant
 *     B claimed → claimant A's stale `release(K)` from a re-entrant path
 *     would remove B's claim.
 *
 * The new state-aware map fixes both: pruning touches ONLY [State.Posted]
 * entries (in-flight CLAIMED entries survive); [release] / [complete] are
 * token-gated via [MutableMap.compute] so only the owning claimant can
 * transition the slot. All three mutations (`claim`, `complete`/`release`,
 * and `retainAll`-based pruning) are individually atomic via CHM's
 * `putIfAbsent` / `compute` / iterator-remove — no lock needed.
 */
internal class NotificationDedup {

    // T5-round-4 I1-S: widened from `private` to `internal` so the fenced
    // prune API ([snapshotPosted] / [pruneStaleCandidates]) can express its
    // return/param type as `State.Posted` — the exact captured generation
    // instance the atomic `computeIfPresent` compares against. Still
    // module-internal (the owning class [NotificationDedup] is `internal`);
    // no subtype can be added from outside (sealed) and the backing `keys`
    // map stays private, so external code cannot inject state.
    internal sealed interface State {
        /** Reserved by a claimant that has not yet completed (token = owner id). */
        class Claimed(val token: String) : State
        /**
         * Notify succeeded — eligible for idle pruning.
         *
         * T5-re-review M1-R Posted-ABA fix: the prior `object Posted`
         * singleton made every completed generation identity-equal, so a
         * stale pruner that captured A's old `Posted` could later remove
         * B's freshly-completed `Posted` at the same key (they were `===`
         * to the SAME singleton). Mirroring `Claimed(val token: String)`,
         * `Posted` now carries the completing owner's token. The data-class
         * `==` includes the token, so a stale pruner that captured
         * `Posted(tokenA)` cannot match B's `Posted(tokenB)` — a stale
         * pruner removes NEITHER a current `Claimed(tokenB)` NOR a newer
         * `Posted(tokenB)` belonging to a different generation.
         */
        data class Posted(val token: String) : State
    }

    private val keys: MutableMap<String, State> = ConcurrentHashMap()

    /**
     * Atomically claims [key]. Returns a fresh owner token iff the key was
     * newly claimed (was absent); returns `null` if the key is already
     * [State.Claimed] or [State.Posted] (another claimant won, or this key
     * was already notified and not yet pruned). The returned token MUST be
     * passed to [complete] / [release] so only this owner can transition
     * the slot.
     */
    fun claim(key: String): String? {
        val token = java.util.UUID.randomUUID().toString()
        // putIfAbsent is atomic on CHM: the first claimant wins.
        val prior = keys.putIfAbsent(key, State.Claimed(token))
        return if (prior == null) token else null
    }

    /**
     * Transitions the owner's [claim] to [State.Posted] (notify succeeded).
     * Returns `true` iff the slot was [State.Claimed] with this exact token.
     * No-op (returns `false`) if the slot was already transitioned by another
     * path or the token does not match — guards against stale completions.
     *
     * T5-re-review M1-R Posted-ABA fix: installs `Posted(token)` — the
     * completer's OWN token — so the resulting [State.Posted] instance is
     * generation-distinct. A later stale pruner that observed a prior
     * `Posted(tokenOther)` cannot remove this entry via `retainAll` (the
     * data-class `==` compares tokens).
     */
    fun complete(key: String, token: String): Boolean {
        var transitioned = false
        keys.compute(key) { _, state ->
            if (state is State.Claimed && state.token == token) {
                transitioned = true
                State.Posted(token)
            } else {
                state
            }
        }
        return transitioned
    }

    /**
     * Rolls back the owner's [claim] (notify failed / suppressed) so a future
     * attempt can retry. Token-gated: a no-op if the slot was already
     * [State.Posted] (a successful [complete]) or the token does not match.
     * This closes the prior ABA on key-only `release` — only the owning
     * claimant can release its own claim.
     */
    fun release(key: String, token: String) {
        keys.compute(key) { _, state ->
            if (state is State.Claimed && state.token == token) null else state
        }
    }

    /** Read-only membership test (no claim). Used by tests. */
    fun contains(key: String): Boolean = keys.containsKey(key)

    /**
     * §18.1 idle pruning — drops entries whose session is no longer active.
     *
     * T5-review I1 fix: removes ONLY [State.Posted] entries — in-flight
     * [State.Claimed] entries survive pruning so a polling race cannot
     * strip a claim out from under a claimant that is mid-notify (which
     * would let a second claimant win and double-notify).
     *
     * T5-re-review I1-R fix: the prior iterator-remove (`it.remove()`)
     * was NOT atomic-conditional on [State.Posted]. The iterator observed
     * the value and removed the KEY — between the check and the remove
     * another path could install [State.Claimed](tokenB) at the same key,
     * and the stale `it.remove()` would strip B's fresh claim. Residual
     * race driver: lifecycle restart cancels `pollJob` without joining
     * (`:490-519`), so two overlapping `retainAll` passes CAN interleave.
     * The per-key [ConcurrentHashMap.computeIfPresent] is atomic: the
     * removal fires ONLY if the value is STILL [State.Posted] at the
     * instant of the compute — a [State.Claimed] entry can NEVER be
     * removed by pruning, even under overlapping pruners.
     *
     * T5-re-review M1-R Posted-ABA fix: `State.Posted` is now a
     * `data class Posted(token)` carrying the completer's token. To close
     * the ABA surface (stale pruner captured A's old `Posted(tokenA)`,
     * pauses; B re-completes K to a FRESH `Posted(tokenB)`; stale pruner
     * resumes and would have stripped B's freshly-completed Posted because
     * `=== State.Posted` matched the SAME singleton), each pruner iteration
     * captures the EXACT observed [State.Posted] value `p` (token included)
     * and the atomic `computeIfPresent` removes the entry ONLY if the
     * current value `== p` (data-class equality compares the token). A
     * stale pruner holding `Posted(tokenA)` therefore cannot match B's
     * `Posted(tokenB)` — the newer generation survives. The captured `p`
     * is a stable snapshot (data class is immutable); CHM's
     * `computeIfPresent` runs the remap under its per-bucket lock, so the
     * observed-vs-current comparison is atomic and free of TOCTOU.
     */
    fun retainAll(active: Set<String>) {
        keys.forEach { (key, state) ->
            if (key !in active && state is State.Posted) {
                // Capture the EXACT observed Posted generation (token included).
                val captured = state
                keys.computeIfPresent(key) { _, v ->
                    // Data-class == compares the token; a stale pruner that
                    // captured Posted(tokenA) does NOT match a newer
                    // Posted(tokenB) installed between the snapshot and this
                    // compute. A Claimed(tokenB) also does not match
                    // (different State subtype) — it survives.
                    if (v == captured) null else v
                }
            }
        }
    }

    /**
     * T5-round-4 I1-S fence step 1: captures the currently-[State.Posted]
     * entries (key → the EXACT captured `Posted(token)` generation instance)
     * as an immutable snapshot. Each entry is observed atomically per-key
     * via the [ConcurrentHashMap] iterator; the captured [State.Posted] is a
     * stable data-class instance (immutable), so the snapshot is a stable
     * read of "which keys were Posted, and at which generation, at the
     * instant [snapshotPosted] ran".
     *
     * The caller MUST capture this BEFORE the prune-time active set is
     * computed (before the poll runs). The returned map is then handed to
     * [pruneStaleCandidates] as the candidate fence: a `Posted` created
     * AFTER this snapshot is NOT in the returned map and is therefore
     * NEVER touched by that prune cycle (see [pruneStaleCandidates]).
     */
    fun snapshotPosted(): Map<String, State.Posted> {
        // Explicit local (not buildMap): a buildMap lambda's MutableMap
        // receiver has its own `keys: Set<K>` property that would shadow
        // the outer `keys` field (Map.keys vs this.keys), breaking the
        // entry-destructuring forEach below.
        val snapshot = LinkedHashMap<String, State.Posted>()
        keys.forEach { (k, v) -> if (v is State.Posted) snapshot[k] = v }
        return snapshot
    }

    /**
     * T5-round-4 I1-S fence step 2: prunes ONLY entries present in
     * [candidates] (captured BEFORE the poll by [snapshotPosted]) that are
     * absent from [active]. For each such `(k, capturedPosted)`, atomically
     * removes the entry ONLY if the current value is STILL that exact
     * captured generation:
     *
     * ```
     * keys.computeIfPresent(k) { _, v -> if (v == capturedPosted) null else v }
     * ```
     *
     * Data-class `==` compares the token, so a stale pruner holding
     * `Posted(tokenA)` cannot match a newer `Posted(tokenB)` (the existing
     * M1-R guard), and a `Claimed(tokenB)` (different subtype) never matches
     * (the existing I1-R guard).
     *
     * **The I1-S closure**: the scan is restricted to [candidates], NOT to
     * the live map. A `Posted(tokenB)` created AFTER [snapshotPosted] ran
     * (e.g. the SSE bridge completing a claim during the poll) is NOT in
     * [candidates] → `computeIfPresent` is never invoked on its key by this
     * cycle → it survives. Under the OLD bare `retainAll(active)` the poller
     * scanned the map AFTER the active set was computed, so it could capture
     * B's freshly-completed `Posted(tokenB)` and (since captured == current,
     * both tokenB) remove it — C re-claimed → duplicate sound/vibration.
     * Token-awareness could not help: P captured B's CURRENT token, not an
     * old one. The fence is the only fix: the candidate set is frozen before
     * the poll.
     *
     * A legitimate stale entry kept one cycle longer (became Posted after
     * the snapshot) is harmless — the NEXT cycle's snapshot captures it and
     * prunes it if still stale. The dedup intent (don't re-notify) is
     * preserved either way.
     */
    fun pruneStaleCandidates(candidates: Map<String, State.Posted>, active: Set<String>) {
        candidates.forEach { (k, captured) ->
            if (k !in active) {
                keys.computeIfPresent(k) { _, v -> if (v == captured) null else v }
            }
        }
    }

    /**
     * T5-re-review M1-R Posted-ABA test hook: deterministically drives the
     * stale-pruner interleaving that production [retainAll] must survive.
     *
     * Captures the current [State.Posted] generation at [key] (mirroring
     * what a real pruner observes when it first scans the entry), runs
     * [between] (simulating P1's pause — during which P2 may prune the
     * entry and B may re-claim + re-complete it), then resumes the atomic
     * conditional remove against the CAPTURED generation. The capture +
     * atomic-resume shape is identical to production [retainAll] per-key;
     * the only difference is the interleaving seam (production does not
     * pause between capture and compute, but it COULD be preempted by
     * another pruner — this hook forces that preemption deterministically).
     *
     * Test-only; production callers use [retainAll].
     */
    internal fun testOnlyPruneWithInterleave(key: String, between: () -> Unit) {
        val state = keys[key]
        if (state is State.Posted) {
            val captured = state
            between()
            keys.computeIfPresent(key) { _, v -> if (v == captured) null else v }
        }
    }
}

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
    @UiApplicationScope private val uiScope: CoroutineScope,
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

    private val notificationManagerCompat = NotificationManagerCompat.from(application)

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
    }

    internal fun currentLifecycleGeneration(): Long = lifecycleGeneration.get()

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
                idleNotificationSnapshot.pruneStaleCandidates(candidates, active)
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
        val token = idleNotificationSnapshot.claim(alert.key) ?: return
        try {
            withContext(Dispatchers.Main.immediate) {
                if (_isInForeground.value) return@withContext
                val notified = notifier.notifyIdle(alert.rootId, alert.title, alert.key)
                if (notified) idleNotificationSnapshot.complete(alert.key, token)
            }
        } finally {
            idleNotificationSnapshot.release(alert.key, token)
        }
    }

    internal suspend fun handlePendingPermission(permission: PermissionRequest) {
        val key = "perm:${permission.id}"
        // §Stage D (gpter 重要 #4) + T5-C4a: atomic claim + release-on-fail.
        // T5-review I1: owner-aware token-gated claim/complete/release.
        // T5-review I2 + T5-re-review I2-R: the final foreground gate +
        // notify + complete run on Dispatchers.Main.immediate, serialized
        // with lifecycle callbacks (see [handleIdleAlert]).
        if (_isInForeground.value) return
        val token = notificationSnapshot.claim(key) ?: return
        try {
            withContext(Dispatchers.Main.immediate) {
                if (_isInForeground.value) return@withContext
                val notified = notifier.notifyDecision(
                    sessionId = permission.sessionId,
                    title = NOTIF_PERMISSION_TITLE,
                    body = permission.permission ?: NOTIF_DECISION_BODY,
                    key = key,
                )
                if (notified) notificationSnapshot.complete(key, token)
            }
        } finally {
            notificationSnapshot.release(key, token)
        }
    }

    internal suspend fun handlePendingQuestion(question: QuestionRequest) {
        val key = "q:${question.id}"
        // §Stage D (gpter 重要 #4) + T5-C4a: see handlePendingPermission.
        // T5-review I1: owner-aware token-gated claim/complete/release.
        // T5-review I2 + T5-re-review I2-R: Main.immediate publish gate
        // (see [handleIdleAlert]).
        if (_isForeground()) return
        val token = notificationSnapshot.claim(key) ?: return
        try {
            withContext(Dispatchers.Main.immediate) {
                if (_isForeground()) return@withContext
                val headline = question.questions.firstOrNull()?.header ?: NOTIF_DECISION_BODY
                val notified = notifier.notifyDecision(
                    sessionId = question.sessionId,
                    title = NOTIF_QUESTION_TITLE,
                    body = headline,
                    key = key,
                )
                if (notified) notificationSnapshot.complete(key, token)
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

    // Avoids the StateFlow getter indirection in tight loops.
    private fun _isForeground(): Boolean = _isInForeground.value

    companion object {
        private const val TAG = "AppLifecycleMonitor"

        const val CHANNEL_DECISIONS = "ocdroid.decisions"
        const val CHANNEL_IDLE = "ocdroid.idle"
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

        /**
         * Q5: silent counterpart of [CHANNEL_SESSION_STATUS] at
         * IMPORTANCE_MIN. MIN is the only importance that reliably suppresses
         * OEM-ROM heads-up banners (CN-ROMs often peek even LOW channels, and
         * `setSilent(true)` is near no-op on LOW). New id → no runtime
         * downgrade restriction applies. Route silent NotificationSpec
         * variants (transient placeholder + persistent-min) here.
         */
        const val CHANNEL_SESSION_STATUS_MIN = "ocdroid.session_status_min"

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
         * alongside the existing [CHANNEL_DECISIONS] / [CHANNEL_IDLE] /
         * [CHANNEL_ERRORS].
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
                    enableVibration(true)
                    setSound(
                        Settings.System.DEFAULT_NOTIFICATION_URI,
                        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build(),
                    )
                }
                val idle = NotificationChannel(
                    CHANNEL_IDLE,
                    CHANNEL_IDLE_NAME,
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = CHANNEL_IDLE_DESC
                    enableVibration(true)
                    setSound(
                        Settings.System.DEFAULT_NOTIFICATION_URI,
                        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build(),
                    )
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
                // Q5: IMPORTANCE_MIN channel for silent FGS notifications.
                // MIN is what actually suppresses OEM-ROM heads-up banners;
                // LOW does not on many CN-ROMs. Used by the transient
                // placeholder + the persistent-min variant.
                val sessionStatusMin = NotificationChannel(
                    CHANNEL_SESSION_STATUS_MIN,
                    CHANNEL_SESSION_STATUS_NAME,
                    NotificationManager.IMPORTANCE_MIN
                ).apply {
                    description = "Silent ongoing FGS notification. Shows only when shade expanded."
                    setShowBadge(false)
                }
                manager.createNotificationChannels(listOf(decisions, idle, errors, sessionStatus, sessionStatusMin))
            }.onFailure { Log.w(TAG, "Failed to create notification channels", it) }
        }

        // Hardcoded channel/notification strings. v2-redesign write-domain
        // excludes res/values/strings.xml, so we keep these literals inline
        // rather than fragmenting the lane by touching resources. A future
        // i18n pass can promote them to R.string entries.
        private const val CHANNEL_DECISIONS_NAME = "opencode decisions"
        private const val CHANNEL_DECISIONS_DESC = "Permission and question prompts from opencode sessions"
        private const val CHANNEL_IDLE_NAME = "opencode completions"
        private const val CHANNEL_IDLE_DESC = "Completed sessions that are ready to review"
        private const val CHANNEL_ERRORS_NAME = "opencode errors"
        private const val CHANNEL_ERRORS_DESC = "Connection and runtime errors from opencode"
        private const val CHANNEL_SESSION_STATUS_NAME = "opencode session status"
        private const val CHANNEL_SESSION_STATUS_DESC =
            "Ongoing session-status notifications while opencode is connected in the background"
        const val NOTIF_PERMISSION_TITLE = "Permission required"
        const val NOTIF_QUESTION_TITLE = "Question from agent"
        const val NOTIF_DECISION_BODY = "Open the session to review"
        const val NOTIF_ERROR_TITLE = "opencode error"
        const val NOTIF_IDLE_TITLE = "Session finished"
    }
}
