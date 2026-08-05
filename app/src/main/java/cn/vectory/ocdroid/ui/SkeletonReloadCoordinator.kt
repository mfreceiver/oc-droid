package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.repository.MessagesPage
import cn.vectory.ocdroid.data.repository.MessageRepository
import cn.vectory.ocdroid.service.identity.ConnectionIdentity
import cn.vectory.ocdroid.ui.StreamOwnedState
import cn.vectory.ocdroid.ui.chat.PartExpandState
import cn.vectory.ocdroid.ui.chat.PartKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

// ─────────────────────────────────────────────────────────────────────────────
// §4.3 reloadSkeletonPage — lite-v2-dev 核心同步路径
//
// 新的 skeleton reload 协调器：digest / done / resync / idle 等触发点 →
// 拉 sidecar skeleton 单页（无 token / 无 watermark / 无 reconfigure 协议）→
// 权威窗口 merge 进 chat slice。替代旧的 sync engine / full reconciler
// + cold-start snapshot 系统（见 plan §4.1 整文件退役清单）。
//
// 实现严格照抄 plan §4.3.6 完整伪代码（v2.7-final），适配到 SkeletonReloadCoordinator.kt
// 顶层语境：状态 + 行为收敛进 SkeletonReloadCoordinator 类，构造期注入
// scope / repository / slices / currentProfileId。锁序、原子提交事务、
// 身份校验、历史守卫、空页早退、补集分区、deadMsgIds 黑名单、watchdog 退避
// 等细节全部保留——见伪代码注释（被原样保留以便后续 review 比对）。
// ─────────────────────────────────────────────────────────────────────────────

// ─────────────────────────────────────────────────────────────────────────────

// ════════════════════════════════════════════════════════════════════════════
// L3 (slimapi-v2 §C1/C2/R1): unified throttled skeleton-reload scheduler.
// See docs/specs/l3-reload-scheduler-design.md (implementation contract).
// ════════════════════════════════════════════════════════════════════════════

/**
 * C2 marker target tuple. `Tuple = (updatedAt, messageId)` from a content-
 * bearing digest. Only [isComplete] tuples may advance the marker. Per C2,
 * tuple equality is NEVER used to suppress a reload (the marker is bookkeeping
 * for reconcile correctness only); the sole rate control is the scheduler.
 */
internal data class Tuple(
    val updatedAt: Long?,
    val messageId: String?,
) {
    val isComplete: Boolean
        get() = updatedAt != null && updatedAt >= 0L && !messageId.isNullOrBlank()
}

/** Reload priority. FORCE_RECONCILE (limit=200) outranks DIGEST (limit=50). */
internal enum class Priority(val limit: Int, val rank: Int) {
    FORCE_RECONCILE(limit = 200, rank = 1),
    DIGEST(limit = 50, rank = 0),
}

internal fun maxPriority(a: Priority, b: Priority): Priority =
    if (a.rank >= b.rank) a else b

/**
 * The reason a reload was requested. Drives [contentBearing] (empty page → R1
 * bounded retry) and [confirmsAuthoritativeEmpty] (empty page → consume dirty).
 * Note: `limit=200` alone does NOT confirm authoritative empty (§H gotcha #1).
 */
internal enum class ReloadReason(
    val isExternalSignal: Boolean,
    val contentBearing: Boolean = false,
    val confirmsAuthoritativeEmpty: Boolean = false,
) {
    DIGEST(isExternalSignal = true, contentBearing = true),
    DIGEST_MALFORMED(isExternalSignal = true, contentBearing = true),
    REQUEST_RELOAD(isExternalSignal = true),
    TOKEN_STREAM_DONE(isExternalSignal = true),
    TOKEN_PART_REMOVED(isExternalSignal = true),
    SERVER_RECONNECT(isExternalSignal = true),
    TRANSPORT_RESET(isExternalSignal = true),
    FORCE_RECONCILE_AUTHORITATIVE_EMPTY(isExternalSignal = true, confirmsAuthoritativeEmpty = true),
    NETWORK_RETRY(isExternalSignal = false),
    EMPTY_PAGE_RETRY(isExternalSignal = false, contentBearing = true),
}

/** Snapshot of the transport generation + identity captured at submit/launch. */
data class TransportSnapshot(val generation: Long, val identity: ConnectionIdentity?)

/** Read-only snapshot of scheduler state for deterministic tests. */
internal data class SchedulerSnapshot(
    val dirty: Boolean,
    val inFlight: Boolean,
    val timerActive: Boolean,
    val priority: Priority,
    val retryAttempt: Int,
    val demandVersion: Long,
    val queuedRequiresContent: Boolean,
    val queuedReasons: Set<ReloadReason>,
    val marker: Tuple?,
)

/** Per-(transportGeneration, sessionId) reload state. Owned by exactly one
 *  immutable [ownerGeneration]; keyed by [ReloadKey] so a host switch creates a
 *  fresh slot and an old in-flight completion cannot mutate the new slot. */
private data class ReloadKey(val generation: Long, val sessionId: String)

/**
 * Per-(generation, sessionId, routeInstance) locally-injected marker key.
 * Includes [routeInstance] as the session-incarnation discriminator: two
 * route incarnations within the same (generation, sid) are isolated, so
 * [onSessionClosed] can clean up only the closed incarnation's markers
 * without affecting a reopen of the same sid at the new routeInstance.
 * See §rev-gpt blocker #2 (round-4: session-incarnation isolation).
 */
private data class IncarnationKey(
    val generation: Long,
    val sessionId: String,
    val routeInstance: Long,
)

private class ReloadState(val ownerGeneration: Long) {
    var dirty: Boolean = false
    var target: Tuple? = null
    var inFlight: Boolean = false
    var timerJob: Job? = null
    var nextAllowedAt: Long = 0L
    var queuedPriority: Priority = Priority.DIGEST
    var queuedReasons: Set<ReloadReason> = emptySet()
    /** OR-aggregated across submits: a queued FORCE must NOT erase a digest's
     *  content requirement (else an empty result would wrongly clear dirty). */
    var queuedRequiresContent: Boolean = false
    var retryAttempt: Int = 0
    /** Monotonic counter bumped on every [SkeletonReloadCoordinator.submit].
     *  Captured into [LaunchTicket.demandVersion]; a completion may clear
     *  `dirty` only if no newer demand arrived since launch (demandVersion
     *  unchanged) — else the newer demand is retained. */
    var demandVersion: Long = 0L
    /** True once bounded retries (2/4/8/16s) are exhausted for the current
     *  dirty work — blocks auto-relaunch (nudge/timer) until a NEW external
     *  signal resets it. Prevents both infinite retry loops AND the last
     *  scheduled retry being wrongly blocked. */
    var boundedRetriesExhausted: Boolean = false
    var lastSuccessfullyReloadedTarget: Tuple? = null // C2 marker
}

/** Immutable launch permit — everything the in-flight job needs, captured once. */
private data class LaunchTicket(
    val key: ReloadKey,
    val ownerState: ReloadState,
    val target: Tuple?,
    val priority: Priority,
    val reasons: Set<ReloadReason>,
    val requiresContent: Boolean,
    val connectionIdentity: ConnectionIdentity?,
    val bundleStamp: BundleStamp?,
    val routeInstance: Long,
    val demandVersion: Long,
)

/** Outcome of an attempted reload, driving marker / dirty / retry decisions. */
private enum class ReloadOutcome {
    CommittedNonEmpty, Empty, Uncommitted, CasRejected, Failed, GuardRejected, Cancelled, Detached
}

/**
 * L3 unified skeleton-reload scheduler (replaces the v2.7 epoch/watchdog
 * coordinator). Public method seams [requestReload] / [onDigestChange] are kept
 * as thin wrappers; ALL sources funnel through [submit].
 *
 * # Core invariants (design §Decision)
 * 1. Only [submit] creates/updates reload demand.
 * 2. Only private [launchReloadLocked] may issue HTTP.
 * 3. One state is owned by exactly one immutable transport generation.
 * 4. A completion may mutate/commit ONLY if it still owns its (gen,sid) slot.
 * 5. `dirty` is consumed at launch; restored on failure/empty/CAS-reject; a
 *    concurrent submit during in-flight re-sets it and an old completion can
 *    never clear it.
 * 6. The marker advances only on CommittedNonEmpty + a complete request tuple.
 * 7. The trailing timer targets the EARLIEST nextAllowedAt — a later digest
 *    mutates queued work but never moves the timer later (anti-starvation).
 *
 * # Locking
 * Single private monitor [stateLock] guards all scheduler state (no coroutine
 * Mutex: submit / background-cancel / generation-detach must be synchronous and
 * no protected op suspends). Lock order: messageLoadCoordinator session mutex →
 * stateLock → non-suspending store commit. NEVER await HTTP/delay/join under
 * stateLock.
 */
class SkeletonReloadCoordinator(
    private val scope: CoroutineScope,
    private val repository: MessageRepository,
    private val slices: SliceFlows,
    private val foreground: StateFlow<Boolean> = MutableStateFlow(true),
    private val currentTransport: () -> TransportSnapshot? = { null },
    private val currentBundleStamp: () -> BundleStamp? = { null },
    private val monotonicNowMs: () -> Long = { System.currentTimeMillis() },
    private val busyMinIntervalMs: Long = 2_000L,
    private val retryDelaysMs: LongArray = longArrayOf(2_000L, 4_000L, 8_000L, 16_000L),
) {
    private val stateLock = Any()

    private val states = mutableMapOf<ReloadKey, ReloadState>()
    private val reloadJobs = mutableMapOf<ReloadState, Job>()
    /** Per-(generation, sessionId, routeInstance) locally-injected markers.
     *  Keyed by [IncarnationKey] so [onSessionClosed] can atomically remove
     *  only the closed incarnation's entries — a new route incarnation of the
     *  same (generation, sid) survives cleanup. */
    private val locallyInjected = ConcurrentHashMap<IncarnationKey, MutableSet<String>>()

    init {
        // Route/current-session observer (§H gotcha #7): a route switch is NOT a
        // session deletion. Cancel timers for no-longer-current sessions (retain
        // dirty) and nudge the now-current session's retained state.
        scope.launch {
            slices.chat
                .map { it.currentSessionId }
                .distinctUntilChanged()
                .collect { current ->
                    val toStart = mutableListOf<Job>()
                    synchronized(stateLock) {
                        val gen = currentTransport()?.generation ?: 0L
                        for ((key, state) in states) {
                            if (key.generation == gen && key.sessionId != current) {
                                state.timerJob?.takeIf { it.isActive }?.cancel()
                                state.timerJob = null
                            }
                        }
                        current?.let { nudged(states[ReloadKey(gen, it)], toStart) }
                    }
                    startJobs(toStart)
                }
        }
        // Foreground observer: background → cancel trailing timers (in-flight
        // allowed to complete, but its completion schedules nothing); foreground
        // restored → resume retained dirty work. (StateFlow is already distinct.)
        scope.launch {
            foreground.collect { fg ->
                if (fg) {
                    val toStart = mutableListOf<Job>()
                    synchronized(stateLock) {
                        for ((_, state) in states) nudged(state, toStart)
                    }
                    startJobs(toStart)
                } else {
                    cancelForBackground()
                }
            }
        }
    }

    /** Start jobs OUTSIDE [stateLock]: a LAZY coroutine may run its body inline
     *  on Main.immediate, and the body (runReload → commitReload) acquires the
     *  session mutex then re-enters stateLock — inverting the declared lock
     *  order (session mutex → stateLock). Must hold NO lock when called. */
    private fun startJobs(jobs: List<Job>) {
        jobs.forEach { runCatching { it.start() } }
    }

    /** Must hold [stateLock]. */
    private fun nudged(state: ReloadState?, toStart: MutableList<Job>) {
        if (state == null) return
        if (state.dirty && !state.inFlight && state.timerJob?.isActive != true) {
            scheduleTrailingLocked(state, toStart)
        }
    }

    // ── Public seams (thin wrappers; route through submit) ──────────────────

    /** Compatibility wrapper for non-digest callers (limit>=200 → FORCE). */
    fun requestReload(sessionId: String, limit: Int = 50) {
        submit(
            sessionId, tuple = null,
            priority = if (limit >= 200) Priority.FORCE_RECONCILE else Priority.DIGEST,
            reason = ReloadReason.REQUEST_RELOAD,
        )
    }

    /**
     * L3 (blocker #2): nudges the current session's retained dirty state.
     * Call when identity or bundle stamp becomes available after a period
     * of null (cold start birth, reconfigure bind complete, bundle publish)
     * so a temporarily-gated dirty submit can proceed. No-op when the
     * current session has no dirty or in-flight state.
     */
    fun nudgeCurrentSession() {
        val toStart = mutableListOf<Job>()
        synchronized(stateLock) {
            val sid = slices.chat.value.currentSessionId ?: return
            val gen = currentTransport()?.generation ?: return
            val state = states[ReloadKey(gen, sid)] ?: return
            nudged(state, toStart)
        }
        startJobs(toStart)
    }

    /** Digest entry. A digest with no extracted tuple defaults to malformed
     *  (still content-bearing → empty page retries per R1). */
    internal fun onDigestChange(sessionId: String, tuple: Tuple = Tuple(null, null)) {
        submit(
            sessionId, tuple, Priority.DIGEST,
            if (tuple.isComplete) ReloadReason.DIGEST else ReloadReason.DIGEST_MALFORMED,
        )
    }

    // ── Unified entry ───────────────────────────────────────────────────────

    /**
     * The ONLY way to create/update reload demand. Does NOT itself reject
     * background / non-current sessions — those submits still mark the state
     * dirty; only the launch gate (foreground/route/identity) suppresses HTTP.
     */
    internal fun submit(sid: String, tuple: Tuple?, priority: Priority, reason: ReloadReason) {
        val transport = currentTransport()
        val gen = transport?.generation ?: 0L
        val toStart = mutableListOf<Job>()
        synchronized(stateLock) {
            if (transport != null) detachMismatchedGenerationsLocked(gen)
            val key = ReloadKey(gen, sid)
            val state = states.getOrPut(key) { ReloadState(ownerGeneration = gen) }
            state.dirty = true
            state.demandVersion += 1L
            state.queuedPriority = maxPriority(state.queuedPriority, priority)
            state.queuedReasons = state.queuedReasons + reason
            // OR-aggregate: a FORCE replacing a DIGEST must not erase the
            // digest's content requirement (§H gotcha #6).
            state.queuedRequiresContent = state.queuedRequiresContent || reason.contentBearing
            // Only content-bearing signals own the marker target; a tuple=null
            // FORCE/token callback must not overwrite a fresher digest target.
            if (reason.contentBearing) state.target = tuple
            // A new external signal resets the bounded-retry budget.
            if (reason.isExternalSignal) {
                state.retryAttempt = 0
                state.boundedRetriesExhausted = false
            }
            scheduleTrailingLocked(state, toStart)
        }
        startJobs(toStart)
    }

    // ── Generation fence ────────────────────────────────────────────────────

    /**
     * Atomically detach all states whose generation != [newGeneration] and
     * cancel their trailing timers. Does NOT cancel in-flight HTTP — its
     * completion is fenced by [stillOwnsLocked] + [commitCasStillValidLocked].
     * Production reconfigure should call this synchronously after
     * [ConnectionIdentityStore.beginReconfigure].
     */
    internal fun detachGeneration(newGeneration: Long) {
        val staleKeys: List<ReloadKey>
        val timers = synchronized(stateLock) {
            staleKeys = states.keys.filter { it.generation != newGeneration }.toList()
            staleKeys.map { key ->
                val st = states.remove(key) ?: return@map null
                val tj = st.timerJob
                st.timerJob = null
                tj
            }
        }
        timers.forEach { it?.cancel() }
        // Clean up locallyInjected for stale generations (generation-scoped).
        locallyInjected.keys.removeAll { incKey ->
            staleKeys.any { it.generation == incKey.generation && it.sessionId == incKey.sessionId }
        }
    }

    /** Must hold [stateLock]. Eagerly drop stale-generation slots + timers. */
    private fun detachMismatchedGenerationsLocked(newGeneration: Long) {
        val stale = states.entries.filter { it.key.generation != newGeneration }
        for (e in stale) {
            e.value.timerJob?.takeIf { it.isActive }?.cancel()
            e.value.timerJob = null
            states.remove(e.key)
            locallyInjected.keys.removeAll { incKey ->
                incKey.generation == e.key.generation && incKey.sessionId == e.key.sessionId
            }
        }
    }

    // ── Scheduling (trailing guarantee + anti-starvation) ───────────────────

    /**
     * Must hold [stateLock]. Schedule the next launch for [state] at its
     * [ReloadState.nextAllowedAt] deadline. NEVER cancels/re-arms an existing
     * active timer (a later digest mutates queued work but must not move the
     * deadline later — otherwise dense digests perpetually debounce and starve,
     * the original 100ms-debounce bug). NEVER issues HTTP itself. Created jobs
     * are appended to [toStart] (the caller starts them OUTSIDE the lock).
     */
    private fun scheduleTrailingLocked(state: ReloadState, toStart: MutableList<Job>) {
        if (!state.dirty || state.inFlight) return
        if (!foreground.value) return
        // Bounded retries exhausted for this dirty work: stop auto-relaunching
        // (a NEW external submit resets the flag + retryAttempt).
        if (state.boundedRetriesExhausted) return
        val sid = keyForStateLocked(state)?.sessionId ?: return
        val now = monotonicNowMs()
        val dueAt = state.nextAllowedAt
        if (now >= dueAt) {
            launchReloadLocked(sid, state, toStart)
            return
        }
        // Keep the earliest valid deadline; do NOT re-arm on each digest.
        if (state.timerJob?.isActive == true) return
        val owner = state
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                delay((dueAt - monotonicNowMs()).coerceAtLeast(0L))
                val inner = mutableListOf<Job>()
                synchronized(stateLock) {
                    if (!stillOwnsLocked(sid, owner)) return@synchronized
                    if (owner.timerJob === coroutineContext[Job]) owner.timerJob = null
                    scheduleTrailingLocked(owner, inner)
                }
                startJobs(inner)
            } catch (_: CancellationException) {
                // timer cancelled (background / detach / session close)
            }
        }
        state.timerJob = job
        toStart.add(job) // started by the caller, OUTSIDE stateLock
    }

    /**
     * Must hold [stateLock]. Capture an immutable [LaunchTicket], consume dirty,
     * reset the queued fields, and (when busy) push [ReloadState.nextAllowedAt]
     * out by the rate cap. The in-flight job is appended to [toStart] (started
     * outside the lock). Requires a non-null identity AND bundle when transport
     * tracking is active — otherwise retain dirty (a nudge re-schedules when the
     * signals become available).
     */
    private fun launchReloadLocked(sid: String, state: ReloadState, toStart: MutableList<Job>) {
        if (state.inFlight || !state.dirty) return
        val transport = currentTransport()
        if (transport != null && transport.generation != state.ownerGeneration) return
        if (!foreground.value) return
        if (slices.chat.value.currentSessionId != sid) return
        val route = slices.routeInstanceFor(sid)
        if (route == 0L) return
        val identity = transport?.identity
        val bundle = currentBundleStamp()
        // #2a: when transport tracking is active, require BOTH a valid identity
        // and a bundle stamp — else no CAS guard exists and we must not fire.
        if (transport != null && (identity == null || bundle == null)) return
        val ticket = LaunchTicket(
            key = ReloadKey(state.ownerGeneration, sid),
            ownerState = state,
            target = state.target,
            priority = state.queuedPriority,
            reasons = state.queuedReasons,
            requiresContent = state.queuedRequiresContent,
            connectionIdentity = identity,
            bundleStamp = bundle,
            routeInstance = route,
            demandVersion = state.demandVersion,
        )
        state.inFlight = true
        state.dirty = false // consumed by this launch; a concurrent submit re-sets
        state.queuedPriority = Priority.DIGEST
        state.queuedReasons = emptySet()
        state.queuedRequiresContent = false
        if (isBusy(sid)) {
            state.nextAllowedAt = maxOf(state.nextAllowedAt, monotonicNowMs() + busyMinIntervalMs)
        }
        val job = scope.launch(start = CoroutineStart.LAZY) { runReload(ticket) }
        reloadJobs[state] = job
        toStart.add(job) // started by the caller, OUTSIDE stateLock
    }

    /** Re-check every CAS dimension at the moment of sending the HTTP request. */
    private fun preHttpGuard(ticket: LaunchTicket): Boolean {
        if (!foreground.value) return false
        if (slices.chat.value.currentSessionId != ticket.key.sessionId) return false
        if (slices.routeInstanceFor(ticket.key.sessionId) != ticket.routeInstance) return false
        val liveTransport = currentTransport()
        // Legacy mode (no identity captured at launch): skip transport CAS.
        if (ticket.connectionIdentity != null) {
            if (liveTransport == null) return false
            if (liveTransport.generation != ticket.ownerState.ownerGeneration) return false
            if (liveTransport.identity != ticket.connectionIdentity) return false
        }
        if (ticket.bundleStamp != null) {
            val liveBundle = currentBundleStamp() ?: return false
            if (liveBundle != ticket.bundleStamp) return false
        }
        return true
    }

    private suspend fun runReload(ticket: LaunchTicket) {
        val outcome = try {
            if (!preHttpGuard(ticket)) ReloadOutcome.GuardRejected
            else {
                val page = repository.getSlimapiMessagesSkeleton(
                    ticket.key.sessionId, ticket.priority.limit, null,
                )
                // #4a (blocker-4a): re-check cancellation AFTER the HTTP call and
                // BEFORE any commit decision. A cooperative repository call would
                // throw CancellationException mid-IO, but an uncontended
                // [MessageLoadCoordinator] session [Mutex] resolves via tryLock and
                // never re-checks cancellation, so a cancel requested during the
                // HTTP call could otherwise slip through [commitReload] and commit a
                // stale result. ensureActive() is a pure cancellation check (no
                // wall-clock / scheduling assumption): if the job was cancelled, it
                // throws synchronously here → the catch(CancellationException) branch
                // restores dirty demand and re-throws; the stale page can never
                // reach [commitReload]. Empty results are likewise fenced (a stale
                // empty must not consume dirty demand that a newer reload owns).
                currentCoroutineContext().ensureActive()
                if (page.items.isEmpty()) ReloadOutcome.Empty
                else commitReload(ticket, page)
            }
        } catch (ce: CancellationException) {
            // #4a: owned-cancellation must restore the consumed dirty demand.
            withContext(NonCancellable) { onReloadComplete(ticket, ReloadOutcome.Cancelled) }
            throw ce
        } catch (e: Exception) {
            ReloadOutcome.Failed
        }
        onReloadComplete(ticket, outcome)
    }

    /**
     * Merge under session mutex → stateLock; return the commit verdict. The
     * #2b commit-time live guard re-verifies generation/identity/bundle under
     * the lock immediately before merge — correctness does NOT depend on eager
     * detach (a stale in-flight whose old slot is still present is rejected).
     */
    private suspend fun commitReload(ticket: LaunchTicket, page: MessagesPage): ReloadOutcome =
        slices.messageLoadCoordinator.withSessionLock(ticket.key.sessionId) {
            synchronized(stateLock) {
                val sid = ticket.key.sessionId
                val owner = ticket.ownerState
                when {
                    !stillOwnsLocked(sid, owner) -> ReloadOutcome.Detached
                    !commitCasStillValidLocked(ticket) -> ReloadOutcome.CasRejected
                    mergeSkeletonIntoChatSlice(ticket, page) -> ReloadOutcome.CommittedNonEmpty
                    else -> ReloadOutcome.Uncommitted
                }
            }
        }

    /** Must hold [stateLock]. Live CAS re-verification at commit time. Legacy
     *  mode (no identity captured) skips the transport CAS. */
    private fun commitCasStillValidLocked(ticket: LaunchTicket): Boolean {
        val ticketIdentity = ticket.connectionIdentity ?: return true // legacy
        val liveTransport = currentTransport() ?: return false
        if (liveTransport.generation != ticket.ownerState.ownerGeneration) return false
        if (liveTransport.identity != ticketIdentity) return false
        val ticketBundle = ticket.bundleStamp ?: return true
        val liveBundle = currentBundleStamp() ?: return false
        return liveBundle == ticketBundle
    }

    private fun onReloadComplete(ticket: LaunchTicket, outcome: ReloadOutcome) {
        val toStart = mutableListOf<Job>()
        synchronized(stateLock) {
            val sid = ticket.key.sessionId
            val owner = ticket.ownerState
            if (!stillOwnsLocked(sid, owner)) {
                reloadJobs.remove(owner)
                return
            }
            owner.inFlight = false
            reloadJobs.remove(owner)
            // #4b: only clear dirty on success if NO newer demand arrived since
            // launch — else the newer demand is retained + trailing scheduled.
            val newerDemand = owner.demandVersion != ticket.demandVersion
            when (outcome) {
                ReloadOutcome.CommittedNonEmpty -> {
                    if (ticket.target?.isComplete == true) {
                        owner.lastSuccessfullyReloadedTarget = ticket.target
                    }
                    owner.retryAttempt = 0
                    owner.boundedRetriesExhausted = false
                    if (!newerDemand) owner.dirty = false
                    scheduleTrailingLocked(owner, toStart)
                }
                ReloadOutcome.Empty -> when {
                    // R1: content-bearing empty → restore ticket demand + retry.
                    ticket.requiresContent &&
                        ticket.reasons.none { it.confirmsAuthoritativeEmpty } -> {
                        restoreTicketAsDirtyLocked(owner, ticket)
                        scheduleBoundedRetryLocked(owner, ReloadReason.EMPTY_PAGE_RETRY, toStart)
                    }
                    // Authoritative-empty (separately confirmed) → consume dirty
                    // (only if no newer demand arrived and the ticket carries
                    // NO content-bearing demand — a merged authoritative-empty +
                    // content digest must NOT lose the content demand).
                    ticket.reasons.any { it.confirmsAuthoritativeEmpty } -> {
                        val ticketHasContentDemand = ticket.requiresContent ||
                            ticket.reasons.any { it.contentBearing }
                        if (ticketHasContentDemand) {
                            // §rev-gpt fix (blocker #4): mixed authoritative-empty +
                            // content demand. Restore the full ticket demand (content-
                            // bearing reasons/target survive) and schedule a bounded
                            // EMPTY_PAGE_RETRY — only the authoritative-empty probe
                            // portion is consumed; the content-demand side retains dirty
                            // and retries for eventual content (R1 zero-loss).
                            restoreTicketAsDirtyLocked(owner, ticket)
                            scheduleBoundedRetryLocked(owner, ReloadReason.EMPTY_PAGE_RETRY, toStart)
                        } else {
                            // Pure authoritative-empty: consume dirty
                            // (unless newer demand arrived during flight).
                            if (!newerDemand) owner.dirty = false
                            owner.retryAttempt = 0
                            owner.boundedRetriesExhausted = false
                            scheduleTrailingLocked(owner, toStart)
                        }
                    }
                    // §rev-gpt fix (blocker #3): Non-content probe empty → consume
                    // only this ticket's work. Do NOT restore this ticket's own
                    // demand (that would re-queue the same non-content probe,
                    // causing an infinite empty-page loop). If NEWER demand arrived
                    // during flight (a concurrent content-bearing submit), its
                    // dirty is preserved; otherwise dirty is cleared.
                    else -> {
                        if (!newerDemand) owner.dirty = false
                        scheduleTrailingLocked(owner, toStart)
                    }
                }
                // #3: every non-success outcome restores the FULL ticket demand
                // (priority / reasons / requiresContent) before the retry — a
                // FORCE that fails retries as limit=200, a content digest that
                // fails retries as content-bearing (R1 zero-loss preserved).
                ReloadOutcome.Uncommitted -> {
                    restoreTicketAsDirtyLocked(owner, ticket)
                    scheduleBoundedRetryLocked(owner, ReloadReason.EMPTY_PAGE_RETRY, toStart)
                }
                ReloadOutcome.CasRejected -> {
                    restoreTicketAsDirtyLocked(owner, ticket)
                    scheduleBoundedRetryLocked(owner, ReloadReason.EMPTY_PAGE_RETRY, toStart)
                }
                ReloadOutcome.Failed -> {
                    restoreTicketAsDirtyLocked(owner, ticket)
                    scheduleBoundedRetryLocked(owner, ReloadReason.NETWORK_RETRY, toStart)
                }
                // Guard rejected (background/route/identity/bundle moved): restore
                // the full demand, do NOT consume retry budget, do NOT schedule
                // (a foreground/route/identity collector nudges later).
                ReloadOutcome.GuardRejected -> restoreTicketAsDirtyLocked(owner, ticket)
                // #4a: owned-cancellation restores the consumed dirty demand.
                ReloadOutcome.Cancelled -> restoreTicketAsDirtyLocked(owner, ticket)
                ReloadOutcome.Detached -> return
            }
        }
        startJobs(toStart)
    }

    /**
     * Must hold [stateLock]. Restore a non-completed ticket's demand onto
     * [state] so a retry carries the ORIGINAL priority / content requirement
     * (not a downgraded DIGEST). Unions with any demand submitted during
     * in-flight (a concurrent higher-priority submit is preserved). Does NOT
     * touch [ReloadState.target] (it is never cleared at launch, so a fresher
     * concurrent content target is preserved).
     */
    private fun restoreTicketAsDirtyLocked(state: ReloadState, ticket: LaunchTicket) {
        state.dirty = true
        state.queuedPriority = maxPriority(state.queuedPriority, ticket.priority)
        state.queuedReasons = state.queuedReasons + ticket.reasons
        state.queuedRequiresContent = state.queuedRequiresContent || ticket.requiresContent
    }

    /** Must hold [stateLock]. R1 / network bounded backoff (2/4/8/16s, then stop).
     *  `dirty` is ALWAYS retained (the caller already restored it) so an
     *  exhausted state stays dirty for the next external signal. */
    private fun scheduleBoundedRetryLocked(
        state: ReloadState, reason: ReloadReason, toStart: MutableList<Job>,
    ) {
        state.queuedReasons = state.queuedReasons + reason
        state.queuedRequiresContent = state.queuedRequiresContent || reason.contentBearing
        if (state.retryAttempt >= retryDelaysMs.size) {
            // Exhausted: retain dirty, stop auto-retry.
            state.boundedRetriesExhausted = true
            return
        }
        state.boundedRetriesExhausted = false
        val delayMs = retryDelaysMs[state.retryAttempt]
        state.retryAttempt += 1
        state.nextAllowedAt = maxOf(state.nextAllowedAt, monotonicNowMs() + delayMs)
        scheduleTrailingLocked(state, toStart)
    }

    /** Background transition: cancel trailing timers; in-flight completes but
     *  its completion schedules no trailing (foreground.value==false gate). */
    internal fun cancelForBackground() {
        synchronized(stateLock) {
            for ((_, state) in states) {
                state.timerJob?.takeIf { it.isActive }?.cancel()
                state.timerJob = null
            }
        }
    }

    /** True iff [owner] still occupies its (generation, sid) slot — ABA fence. */
    private fun stillOwnsLocked(sid: String, owner: ReloadState): Boolean =
        states[ReloadKey(owner.ownerGeneration, sid)] === owner

    private fun keyForStateLocked(state: ReloadState): ReloadKey? =
        states.entries.firstOrNull { it.value === state }?.key

    private fun isBusy(sid: String): Boolean =
        slices.sessionList.value.sessionStatuses[sid]
            ?.let { it.isBusy || it.isRetry } ?: false

    /** Test-only: true iff the calling thread currently holds [stateLock].
     *  Used by the #1 regression test to prove a LAZY job body does not begin
     *  executing while the lock is held (Main.immediate inline-run hazard). */
    internal fun stateLockHeldForTest(): Boolean = Thread.holdsLock(stateLock)

    /**
     * Test-only: cancels the in-flight reload Job for [(generation, sid)] WITHOUT
     * removing the state slot — so the [Cancelled] outcome runs with the state
     * still owned. This exercises the genuine owned-cancellation path (not
     * detached-no-op). No-op if no in-flight job is found.
     */
    internal fun cancelInFlightForTest(generation: Long, sid: String) {
        val job = synchronized(stateLock) {
            val key = ReloadKey(generation, sid)
            val st = states[key]
            if (st == null || !st.inFlight) null
            else reloadJobs[st]
        }
        job?.cancel(CancellationException("test-owned-cancellation"))
    }

    /**
     * Confirmed session deletion / lifecycle dispose: detach the slot and
     * cancel+join its in-flight job (prevents a detached completion from
     * dispatching). Lock under [stateLock] for the detach, join outside.
     */
    suspend fun onSessionClosed(sessionId: String) {
        val removalKeys = mutableListOf<IncarnationKey>()
        val jobs = synchronized(stateLock) {
            val matched = states.entries
                .filter { it.key.sessionId == sessionId }
                .map { it.value }
            val gen = matched.firstOrNull()?.ownerGeneration
            matched.flatMap { st ->
                val key = ReloadKey(st.ownerGeneration, sessionId)
                states.remove(key)
                val timerJob = st.timerJob
                st.timerJob = null
                val reloadJob = reloadJobs.remove(st)
                listOfNotNull(timerJob, reloadJob)
            }.also {
                // Collect locallyInjected entries matching the closed
                // (generation, sessionId) — all route incarnations of this
                // session are closed.
                if (gen != null) {
                    removalKeys.addAll(locallyInjected.keys.filter {
                        it.generation == gen && it.sessionId == sessionId
                    })
                }
            }
        }
        jobs.forEach { runCatching { it.cancelAndJoin() } }
        // Only remove the closed incarnation's markers. A new incarnation
        // (different routeInstance) that registered markers during
        // cancelAndJoin survives because its IncarnationKey differs.
        removalKeys.forEach { locallyInjected.remove(it) }
    }

    // ── Authoritative-window merge (algorithm preserved; commit verdict added) ──
    //
    // Caller holds session mutex + stateLock (see commitReload). Returns whether
    // the reducer accepted (dispatchAndVerify). Empty page handled by the caller
    // (never reaches here). locallyInjected clear moved AFTER verified commit
    // (§B: a rejected route/host commit must not destroy the local-injection
    // guard).

    @Suppress("UNUSED_PARAMETER")
    private fun mergeSkeletonIntoChatSlice(ticket: LaunchTicket, page: MessagesPage): Boolean {
        val sessionId = ticket.key.sessionId
        // Single chat snapshot; all src* read from this one to stay self-consistent.
        val chat = slices.chat.value
        val srcMessages = chat.messages
        val srcParts = chat.partsByMessage
        val srcStreamingTexts = chat.streamingPartTexts
        val srcStreamingReasoning = chat.streamingReasoningPart
        val srcStreamOwned = chat.streamOwned
        val srcCursor = chat.olderMessagesCursor
        val srcHasMore = chat.hasMoreMessages

        // Defensive ascending sort (N ≤ 200); concatenation order depends on it.
        val fetched = page.items.map { it.info }
            .sortedWith(compareBy({ it.time?.created ?: Long.MAX_VALUE }, { it.id }))
        val fetchedParts = page.items.associate { it.info.id to it.parts }
        val fetchedIds = fetched.mapTo(HashSet()) { it.id }

        // NOTE: locallyInjected clear moved to AFTER the verified commit below.
        // Use generation-keyed lookup (§rev-gpt blocker #5).
        val incarnationKey = IncarnationKey(ticket.key.generation, sessionId, ticket.routeInstance)
        val injectedBeforeClear: Set<String> = locallyInjected[incarnationKey] ?: emptySet()

        val fetchedCreated = fetched.mapNotNull { it.time?.created }
        val oldestFetched = fetchedCreated.minOrNull()
        val newestFetched = fetchedCreated.maxOrNull()

        // ── Deletion detection (containment method) ──
        fun isServerDeleted(m: Message): Boolean {
            if (m.id in fetchedIds) return false
            if (m.id in injectedBeforeClear) return false
            val created = m.time?.created ?: return false
            val oldest = oldestFetched ?: return false
            if (newestFetched != null && created >= newestFetched) return false
            return created > oldest
        }

        // ── Complement-partition merge (survivors are never lost) ──
        val survivors = srcMessages.filterNot(::isServerDeleted)
        val notFetched = survivors.filter { it.id !in fetchedIds }
        val olderKept = notFetched.filter { m ->
            val c = m.time?.created
            c != null && oldestFetched != null && c <= oldestFetched
        }
        val olderKeptIds = olderKept.mapTo(HashSet()) { it.id }
        val newerKept = notFetched.filter { it.id !in olderKeptIds }
        val keptIds = HashSet(olderKeptIds).apply { addAll(newerKept.map { it.id }) }

        val mergedMessages = (olderKept + fetched + newerKept).distinctBy { it.id }

        // ── Parts merge: in-place replace, preserving expanded content ──
        val expandedKeys = chat.partExpandStates
            .filterValues { it is PartExpandState.Loaded }.keys

        val mergedPartsMut = HashMap<String, List<Part>>(srcParts.filterKeys { it in keptIds })
        for ((msgId, fetchedPartList) in fetchedParts) {
            val localById = srcParts[msgId]?.associateBy { it.id }
            mergedPartsMut[msgId] = if (localById == null) fetchedPartList else fetchedPartList.map { fp ->
                val lp = localById[fp.id]
                if (lp != null && PartKey(msgId, fp.id) in expandedKeys &&
                    fp.isTruncatedMarker() && !lp.isTruncatedMarker()
                ) lp else fp
            }
        }

        val liveIds = mergedMessages.mapTo(HashSet()) { it.id }
        mergedPartsMut.keys.retainAll(liveIds)
        var mergedParts: Map<String, List<Part>> = mergedPartsMut

        // ── Historical Guard 1: §flicker-fix (placeholder survival) ──
        val srcSessionStatuses = slices.sessionList.value.sessionStatuses
        val streamingFinalized = srcSessionStatuses[sessionId]
            ?.let { st -> !st.isBusy && !st.isRetry } ?: true
        val streamingPartIds = srcStreamingTexts.keys
        if (!streamingFinalized && streamingPartIds.isNotEmpty()) {
            val withPlaceholders = mergedParts.toMutableMap()
            for ((oldMsgId, oldParts) in srcParts) {
                if (oldMsgId !in liveIds) continue
                for (p in oldParts) {
                    if (p.id in streamingPartIds && (p.isText || p.isReasoning)) {
                        val merged = withPlaceholders[oldMsgId]
                        if (merged == null || merged.none { it.id == p.id }) {
                            withPlaceholders[oldMsgId] = (merged ?: emptyList()) + p
                        }
                    }
                }
            }
            mergedParts = withPlaceholders
        }

        // ── Historical Guard 2: §append-safe + §Q10 overlay guard ──
        val partOwnerIndex = srcParts.entries
            .flatMap { (mid, ps) -> ps.map { it.id to mid } }.toMap()

        val overlayOwnerMsgIds = srcStreamingTexts.keys.mapNotNull { pid ->
            partOwnerIndex[pid]
        }.toSet()
        val overlayFinalized = overlayOwnerMsgIds.isEmpty() ||
            overlayOwnerMsgIds.all { it in fetchedIds }

        val reasoningOwnerMsgId = srcStreamingReasoning?.let { r -> partOwnerIndex[r.id] }
        val reasoningFinalized = reasoningOwnerMsgId == null || reasoningOwnerMsgId in fetchedIds

        val ownedStreamingKeys = srcStreamOwned
            .filterValues { it == StreamOwnedState.STREAMING }.keys
        val legacyWouldClear = streamingFinalized && overlayFinalized
        val authoritative = legacyWouldClear && ownedStreamingKeys.isEmpty()
        val newStreamingTexts = when {
            authoritative -> emptyMap()
            legacyWouldClear -> srcStreamingTexts.filterKeys { it in ownedStreamingKeys }
            else -> srcStreamingTexts
        }
        val newStreamingReasoning =
            if (streamingFinalized && reasoningFinalized && ownedStreamingKeys.isEmpty()) null
            else srcStreamingReasoning

        val srcIds = srcMessages.mapTo(HashSet()) { it.id }
        val deadMsgIds = srcIds - liveIds
        val deadPartIds = deadMsgIds.flatMapTo(HashSet()) { mid ->
            srcParts[mid].orEmpty().map { it.id }
        }
        val prunedStreamingTexts = newStreamingTexts.filterKeys { it !in deadPartIds }
        val prunedReasoning = newStreamingReasoning?.takeUnless { r ->
            partOwnerIndex[r.id]?.let { it in deadMsgIds } == true
        }

        val cursorUnseeded = srcCursor == null
        val historyAlreadyPaged = !cursorUnseeded && olderKept.isNotEmpty()
        val newCursor = if (cursorUnseeded && !historyAlreadyPaged) page.nextCursor else srcCursor
        val newHasMore = if (cursorUnseeded && !historyAlreadyPaged) (page.nextCursor != null) else srcHasMore

        // §D: dispatchAndVerify returns whether the reducer actually committed
        // (route/session/bundle CAS). The marker advances only on a true commit.
        val committed = slices.store.dispatchAndVerify(
            AppAction.ChatContentLoaded(
                sessionId = sessionId,
                expectedRouteInstance = ticket.routeInstance, // captured value
                messages = mergedMessages,
                partsByMessage = mergedParts,
                streamingPartTexts = prunedStreamingTexts,
                streamingReasoningPart = prunedReasoning,
                olderMessagesCursor = newCursor,
                hasMoreMessages = newHasMore,
                currentModel = inferCurrentModel(mergedMessages),
                authoritative = authoritative,
                bundleStamp = ticket.bundleStamp,
            )
        )
        // §B: clear locallyInjected ONLY after a verified commit, so a rejected
        // (stale route/host) commit does not destroy the local-injection guard.
        if (committed) {
            locallyInjected[incarnationKey]?.removeAll(fetchedIds)
        }
        return committed
    }

    // ── Part truncation marker (mirrors ExpandedPartsReconcile.kt:65-67) ──
    private fun Part.isTruncatedMarker(): Boolean = hasFull == true && omitted != null

    /** Test-only scheduler state snapshot (deterministic assertions). */
    internal fun schedulerSnapshotForTest(sid: String, generation: Long): SchedulerSnapshot? {
        synchronized(stateLock) {
            val s = states[ReloadKey(generation, sid)] ?: return null
            return SchedulerSnapshot(
                dirty = s.dirty,
                inFlight = s.inFlight,
                timerActive = s.timerJob?.isActive == true,
                priority = s.queuedPriority,
                retryAttempt = s.retryAttempt,
                demandVersion = s.demandVersion,
                queuedRequiresContent = s.queuedRequiresContent,
                queuedReasons = s.queuedReasons,
                marker = s.lastSuccessfullyReloadedTarget,
            )
        }
    }

    // ── Local-injection marker (synchronous; eliminates registration race) ──
    // Order contract (MANDATORY): callers must markLocallyInjected BEFORE
    // publishing the slice update (see historical kdoc).
    fun markLocallyInjected(sessionId: String, messageId: String) {
        val gen = currentTransport()?.generation ?: 0L
        val route = slices.routeInstanceFor(sessionId)
        locallyInjected.computeIfAbsent(IncarnationKey(gen, sessionId, route)) { ConcurrentHashMap.newKeySet() }.add(messageId)
    }
}
