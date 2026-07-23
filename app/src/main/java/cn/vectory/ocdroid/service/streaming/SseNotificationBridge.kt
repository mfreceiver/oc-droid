package cn.vectory.ocdroid.service.streaming

import cn.vectory.ocdroid.data.model.SSEEvent
import cn.vectory.ocdroid.di.NotificationDedup
import cn.vectory.ocdroid.di.SessionNotifier
import cn.vectory.ocdroid.service.events.IdentifiedSseEvent
import cn.vectory.ocdroid.ui.controller.IdleUnreadAlert
import cn.vectory.ocdroid.ui.parseQuestionAskedEvent
import cn.vectory.ocdroid.ui.parseSessionStatusEvent
import cn.vectory.ocdroid.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import cn.vectory.ocdroid.di.publishIdleNotification
import kotlinx.coroutines.sync.Mutex
import kotlin.coroutines.cancellation.CancellationException

/**
 * T5-C4 — SSE → temporary notification bridge.
 *
 * Subscribes to the control-class SSE event surface (the
 * [cn.vectory.ocdroid.service.bridge.SseEventBridge.controlEvents] flow —
 * `session.status` / `question.*` are §11 control-class, so they survive a
 * delta flood) and turns two event shapes into a TEMPORARY notification
 * when the process is NOT in the foreground:
 *  - `question.asked` (new question id) → decision notification
 *    (`notifyDecision`); the dedup key is `"q:${question.id}"`.
 *  - `session.status{idle}` for a ROOT session that the shared store
 *    considers "unread + idle" → idle notification (`notifyIdle`); the
 *    dedup key is the [IdleUnreadAlert.key] produced by the resolver
 *    (same shape the 30s poller uses — see
 *    [cn.vectory.ocdroid.ui.controller.idleNotificationKey]).
 *
 * Both paths share the SAME [SessionNotifier] + [NotificationDedup]
 * instances as [cn.vectory.ocdroid.di.AppLifecycleMonitor]'s 30s poller
 * (T5-C4a + T5-C4b). The dedup claim is atomic (CHM-backed); the
 * notification id is `key.hashCode()` (so a bridge-fired notification and
 * a poller-fired notification for the same key visually replace each other
 * even if the dedup race somehow loses — defense in depth).
 *
 * Lifecycle: the bridge runs on the Service's [scope] (MainScope). When
 * the Service dies (L3 teardown), [scope] cancels → the collector job
 * dies → no further notifications. The bridge is process-L2-bounded: it
 * only runs while the SSE collector is alive (the Service is the SSE
 * producer; without the Service there is nothing to subscribe to).
 *
 * Foreground suppression: notifications fire ONLY when
 * `!isInForeground()`. In foreground the in-app cards / status slot
 * surface the same information — a notification would be redundant and
 * annoying.
 *
 * Concurrency model: the dedup claim is the single race boundary. The
 * bridge runs on the Service's MainScope; the 30s poller runs on
 * `@ApplicationScope` (Default dispatcher). Both can produce a
 * notification for the same logical event (e.g. a `question.asked` arrives
 * via SSE AND the next 30s poll sees it pending). The CHM `add` is atomic
 * — exactly one of the two callers observes `claim == true`, the other
 * gets `false` and skips. A failed `notifyXxx` (e.g. permission denied)
 * rolls back the claim via [NotificationDedup.release] so the next attempt
 * can retry.
 *
 * NOT a Hilt-provided object — built by [SessionStreamingService] in
 * `onCreate` and torn down in `onDestroy`. The Service supplies the
 * ALM-shared [SessionNotifier] + [NotificationDedup] + the production
 * [rootIdleResolver]; tests construct the bridge with mock collaborators.
 *
 * T5-review fixes:
 *  - C1 (Critical): the bridge collects from
 *    `SseEventBridge.notificationControlEvents` (an additive SharedFlow tap),
 *    NOT `controlEvents` (a single-consumer Channel that AppCore already
 *    drains). The competing-collector wiring would have stolen frames from
 *    AppCore's fold.
 *  - I1 (Important): dedup is owner-aware — `claim` returns a token;
 *    `complete` / `release` are token-gated. Idle pruning touches only
 *    Posted entries, never in-flight claims.
 *  - I2 (Important): foreground is rechecked at the FINAL publish boundary
 *    (after the claim, before the platform notify) and the claim is
 *    released on suppression so a later background attempt can retry.
 *  - I3 (Important): the per-event publish path is wrapped in try/finally
 *    so any non-cancellation failure (parser throw, builder throw,
 *    platform SecurityException) still releases the claim; the collector
 *    isolates per-event failures (catch / log / continue) so one bad event
 *    cannot kill the bridge for the Service's lifetime.
 *
 * @param events the SSE control-class SharedFlow tap (typically
 *  `SseEventBridge.notificationControlEvents`).
 * @param notifier ALM-shared publish point (T5-C4b).
 * @param decisionDedup ALM-shared `"perm:<id>"` / `"q:<id>"` dedup set
 *  (T5-C4a) — the SAME set the 30s poller consults for permission/question.
 * @param idleDedup ALM-shared idle dedup set (T5-C4a) — the SAME set the
 *  30s poller consults for idle alerts.
 * @param idleMutex ALM-shared [Mutex] that serializes the IDLE publish
 *  critical section (this bridge's idle branch + the poller's
 *  [cn.vectory.ocdroid.di.AppLifecycleMonitor.handleIdleAlert]) with the
 *  poller's IDLE prune + side-effect critical section. Closes the
 *  deferred-stale-snapshot race (a stale post-prune `cancel +
 *  removePostedIdle` loop clobbering a fresh completion+`addPostedIdle`).
 *  NOT used on the decision (`question.asked`) path — decision dedup is
 *  in-memory only and has no deferred side-effects.
 * @param isInForeground foreground truth-source (ALM.isInForeground.value).
 *  Polled per event — TWICE on the publish path (top-of-handle eligibility
 *  + final publish boundary) so a lifecycle flip between the two cannot
 *  post sound/vibration while foregrounded (T5-review I2).
 * @param rootIdleResolver resolves an SSE-observed sessionID to an
 *  [IdleUnreadAlert] ONLY when (a) the session is a ROOT, (b) the shared
 *  store currently considers it "unread + idle". Returns null otherwise
 *  (non-root, no unread, or unknown — the 30s poller will catch it later
 *  if it really is idle-with-unread). This is the reuse seam for the
 *  unread-detection logic (the production resolver queries the same
 *  SharedStateStore the [cn.vectory.ocdroid.ui.controller.BackgroundUnreadPoller]
 *  uses, so the key shape + idleSince align end-to-end with the poller).
 * @param scope the Service's MainScope (L2-bounded).
 * @param onIdlePosted invoked with the alert key AFTER a successful idle
 *  notify + dedup completion. The production wiring routes this to
 *  [cn.vectory.ocdroid.di.AppLifecycleMonitor.persistIdlePosted] so an
 *  idle key completed via the SSE bridge is mirrored into the durable
 *  dedup store — without it the key would be lost on process death (only
 *  self-healing ≤30s later via the poller's post-prune save). Default
 *  no-op so pure-JVM tests don't need to supply it. NOT invoked on the
 *  decision (`question.asked`) path — decision dedup is in-memory only.
 * @param questionTitle hard-coded title for `question.asked` notifications
 *  (mirrors [cn.vectory.ocdroid.di.AppLifecycleMonitor.NOTIF_QUESTION_TITLE];
 *  duplicated as a literal here so this class is pure-JVM testable without
 *  the Android resources indirection).
 * @param questionFallbackBody body used when the question has no header.
 * @param tag log tag override (tests).
 */
internal class SseNotificationBridge internal constructor(
    private val events: Flow<IdentifiedSseEvent>,
    private val notifier: SessionNotifier,
    private val decisionDedup: NotificationDedup,
    private val idleDedup: NotificationDedup,
    private val idleMutex: Mutex,
    private val isInForeground: () -> Boolean,
    private val rootIdleResolver: (sessionId: String) -> IdleUnreadAlert?,
    private val scope: CoroutineScope,
    private val onIdlePosted: (String) -> Unit = {},
    private val questionTitle: String = "Question from agent",
    private val questionFallbackBody: String = "Open the session to review",
    private val tag: String = TAG,
) {
    /**
     * The single collector job. Null until [start]; cancelled by [stop].
     * Idempotent: a second [start] while running is a no-op.
     */
    private var job: Job? = null

    /**
     * Begins collecting [events] on [scope]. Idempotent — a second call
     * while a collector is active is a no-op (the Service is the single
     * owner; a re-create re-binds via a fresh instance).
     *
     * T5-review C1: launches with [CoroutineStart.UNDISPATCHED] so the
     * subscription is installed SYNCHRONOUSLY during [start] (which the
     * Service calls in `onCreate`, before `onStartCommand` can start any
     * SSE connection). This guarantees the SharedFlow subscriber is
     * registered before the first frame can flow, even on a sticky-rebuild
     * where `onStartCommand` immediately follows `onCreate`.
     *
     * T5-review I3: per-event try/catch isolates failures — one malformed
     * or platform-failing event cannot terminate the collector for the
     * Service's lifetime. Only [CancellationException] is rethrown so
     * `stop()` / scope-cancel propagates cleanly.
     */
    fun start() {
        if (job?.isActive == true) {
            DebugLog.i(tag, "start: collector already active — ignoring")
            return
        }
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            events.collect { identified ->
                try {
                    handle(identified.event)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    // T5-review I3: catch / log PER EVENT so collection
                    // survives a single bad frame (parser throw, builder
                    // throw, platform SecurityException, ...). The
                    // publish-path try/finally inside handle() already
                    // released the dedup claim before the exception escaped
                    // — this catch just keeps the collector alive.
                    DebugLog.e(tag, "SSE notification handler failed — continuing collection", t)
                }
            }
        }
    }

    /**
     * Cancels the collector. The Service's onDestroy cancels its MainScope
     * which structurally cancels the job too — this method is exposed for
     * explicit / test cleanup.
     */
    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * Per-event dispatch. Public-visible as `internal` so the test can
     * drive single events synchronously without a Flow.
     *
     * `suspend` because the idle branch ([handleSessionStatus]) acquires
     * the shared [idleMutex] (a suspend [Mutex.withLock]); the collector
     * already runs in a coroutine on [scope] so the suspending call chain
     * is natural. The decision branch ([handleQuestionAsked]) does not
     * acquire the Mutex — it stays a regular function.
     */
    internal suspend fun handle(event: SSEEvent) {
        // Foreground suppression: checked ONCE per event before any
        // dedup mutation so a foreground transition mid-event does not
        // leave a stale claim.
        if (isInForeground()) return
        when (event.payload.type) {
            "question.asked" -> handleQuestionAsked(event)
            "session.status" -> handleSessionStatus(event)
            // other control events (server.connected / permission.*) are
            // intentionally not bridged — permission is covered by the
            // 30s poller's permission branch, and server.connected has no
            // user-facing "you should look" semantics.
            else -> Unit
        }
    }

    private fun handleQuestionAsked(event: SSEEvent) {
        // Reuses the production parser — same one SessionSyncCoordinator
        // uses for its question fold, so wire-format drift surfaces here
        // too. parseQuestionAskedEvent returns null on a malformed payload
        // (handled identically to the coordinator: silent drop).
        val question = parseQuestionAskedEvent(event) ?: return
        val key = "q:${question.id}"
        // T5-C4a + T5-review I1: atomic claim BEFORE notify, returning an
        // owner-aware token. The 30s poller shares this exact set, so a
        // racing poll that observes the same pending id MUST lose to this
        // claim. Token-gated release/complete below so neither idle pruning
        // nor a racing release can strip this claimant's slot.
        val token = decisionDedup.claim(key) ?: return
        try {
            // T5-review I2: FINAL foreground boundary. Lifecycle transitions
            // run on Main; this bridge runs on the Service's MainScope too,
            // but the top-of-handle check at [handle] could have observed
            // background an instant before an ON_START. Recheck here, after
            // the claim, so a flip between the two cannot post sound /
            // vibration while foregrounded. The finally block releases the
            // claim so a later background attempt can retry.
            if (isInForeground()) return
            val body = question.questions.firstOrNull()?.header ?: questionFallbackBody
            val posted = notifier.notifyDecision(
                sessionId = question.sessionId,
                title = questionTitle,
                body = body,
                key = key,
            )
            // T5-review I1: transition this owner's slot to Posted on
            // success (makes it eligible for idle pruning). On any failure
            // (posted == false OR a thrown exception caught by the outer
            // collector isolation) the finally block releases the claim.
            if (posted) decisionDedup.complete(key, token)
        } finally {
            // Token-gated release — a no-op if [complete] already
            // transitioned the slot to Posted. Guarantees the claim is
            // released on every non-completed exit (suppression, failed
            // post, thrown exception).
            decisionDedup.release(key, token)
        }
    }

    private suspend fun handleSessionStatus(event: SSEEvent) {
        // Reuses the production parser + SessionStatus model.
        val parsed = parseSessionStatusEvent(event) ?: return
        // Only idle roots with unread fire a notification. busy/retry are
        // surfaced via the FGS ongoing notification + the in-app status
        // slot — a temp notification would be noise.
        if (!parsed.status.isIdle) return
        // T5-C4 reuse: delegate root + unread detection to the same logic
        // the 30s BackgroundUnreadPoller uses (the production resolver
        // reads SharedStateStore.unreadFlow + sessionList). Returns null
        // for non-root, no-unread, or unknown — the 30s poller will catch
        // those if they genuinely warrant a notification.
        val alert = rootIdleResolver(parsed.sessionId) ?: return
        // Race-closure: the entire publish critical section
        // (claim → [foreground gate] → notifyIdle → complete →
        // onIdlePosted → release) runs under the shared [idleMutex] so it
        // cannot interleave with the poller's prune + side-effect region
        // (which acquires the SAME mutex) NOR with the poller's own
        // [handleIdleAlert] publish. Closes the deferred-stale-snapshot
        // race documented on [idleMutex]. The decision
        // (`question.asked`) path is intentionally NOT under this lock —
        // decision dedup is in-memory only and has no deferred
        // side-effects.
        // T5-re-review I2-R core extracted to [publishIdleNotification]; no
        // withContext wrapper — the bridge collector is already on Main.
        publishIdleNotification(
            dedup = idleDedup,
            notifier = notifier,
            mutex = idleMutex,
            alert = alert,
            isInForeground = { isInForeground() },
            onPersist = { onIdlePosted(it) },
        )
    }

    companion object {
        private const val TAG = "SseNotificationBridge"
    }
}
