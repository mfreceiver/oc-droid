package cn.vectory.ocdroid.service.streaming

import cn.vectory.ocdroid.di.AppLifecycleMonitor
import cn.vectory.ocdroid.di.ApplicationScope
import cn.vectory.ocdroid.service.OwnershipRefusal
import cn.vectory.ocdroid.service.OwnershipStartResult
import cn.vectory.ocdroid.service.StreamingOwnershipGate
import cn.vectory.ocdroid.service.StreamingServiceLauncher
import cn.vectory.ocdroid.service.identity.ConnectionIdentity
import cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

// ── Retry schedule ─────────────────────────────────────────────────────────

/**
 * M3 §4.3 — the approved foreground-retry backoff schedule
 * (`0s, 2s, 10s, 30s, 60s, 120s, then every 300s`).
 *
 * Implementations MUST be deterministic and side-effect free. The supervisor
 * injects this so tests can substitute a virtual-time schedule; production
 * binds [DefaultSseReconnectRetrySchedule], which preserves the approved values.
 *
 * [delayMillis] returns the delay before the [attempt]-th reconcile attempt,
 * where `attempt == 0` is the first (immediate) try and higher values are
 * retries. The steady-state cap is 300s (design §4.3 / M3-C5).
 */
fun interface SseReconnectRetrySchedule {
    fun delayMillis(attempt: Int): Long

    companion object {
        val Default: SseReconnectRetrySchedule = DefaultSseReconnectRetrySchedule()
    }
}

/**
 * Production [SseReconnectRetrySchedule]: `0, 2, 10, 30, 60, 120, 300, 300, …`
 * seconds. `@Singleton @Inject constructor` so Hilt auto-provides it (no
 * `@Binds`/`@Provides` needed) and Wave 2 can wire the supervisor without an
 * extra binding for the schedule.
 */
@Singleton
class DefaultSseReconnectRetrySchedule @Inject constructor() : SseReconnectRetrySchedule {
    override fun delayMillis(attempt: Int): Long = when (attempt) {
        0 -> 0L
        1 -> 2_000L
        2 -> 10_000L
        3 -> 30_000L
        4 -> 60_000L
        5 -> 120_000L
        else -> 300_000L
    }
}

// ── Supervisor ─────────────────────────────────────────────────────────────

/**
 * M3 — the single foreground-reconnect decision maker (design §6, §2 I3).
 *
 * Observes [SseTransportRuntimeStore] transport truth, [AppLifecycleMonitor]
 * foreground truth, and [ConnectionIdentityStore] current identity. When the
 * current identity has a [SseTransportState.Dropped] transport AND the app is
 * foreground, it reconciles exactly once via [ForegroundTransportStartPreparer]
 * then [StreamingServiceLauncher]. It is the ONLY production caller of
 * [StreamingServiceLauncher.ensureStarted] for foreground recovery.
 *
 * ## Lifecycle (rev-ogpt fix 1)
 *  - [ensureConnected] auto-creates the internal scope but does NOT launch
 *    observation; a later [start] still launches observation exactly once.
 *  - [start] is idempotent (observation launched once even if the scope was
 *    pre-created by [ensureConnected]).
 *  - [stop] cancels the internal scope + observation + EVERY in-flight flight,
 *    completing all awaiters with [OwnershipRefusal.ServiceStopped]. No scope
 *    or job leaks.
 *
 * ## Single-flight registry (rev-ogpt fix 2)
 *  - ONE lock ([stateLock]) guards scope/observation AND the active flight.
 *  - A newer demand (different identity OR newer drop-id) CANCELS and COMPLETES
 *    the old flight (StaleIdentity) before replacing it — no CompletableDeferred
 *    is ever left permanently pending.
 *  - Background / null-identity / superseded transitions cancel the active
 *    flight's delay immediately while preserving the runtime drop ticket.
 *
 * ## Freshness (rev-ogpt fix 3)
 *  - After the launcher returns [OwnershipStartResult.Ready], the supervisor
 *    re-validates identity + drop-id before acknowledging; a stale result is
 *    rejected (StaleIdentity) and never suppresses a newer demand.
 *
 * ## Retry semantics (rev-ogpt fix 4)
 *  - All retryable preparer/launcher exceptions and non-terminal refusals are
 *    retried on the approved schedule; demand is preserved.
 *  - [OwnershipRefusal.SseDisabled] PAUSES (resolves the awaiter, stops the
 *    timer). Demand stays retryable; an explicit [requestReconcile] (e.g. after
 *    a setting change) resumes.
 *
 * ## Non-goals (design §2 invariants / I1, I5)
 *  - The supervisor NEVER mutates [SseTransportRuntimeStore]. The
 *    owner/coordinator/launcher own those transitions.
 *
 * NOT wired into DI in this lane — Wave 2 (M6) binds
 * `DefaultSseReconnectSupervisor` → [SseReconnectSupervisor] and eagerly calls
 * [start]. The `@Singleton @Inject constructor` annotation is present so that
 * binding is a plain `@Binds`.
 */
@Singleton
class DefaultSseReconnectSupervisor @Inject constructor(
    private val runtimeStore: SseTransportRuntimeStore,
    private val launcher: StreamingServiceLauncher,
    private val ownershipGate: StreamingOwnershipGate,
    private val identityStore: ConnectionIdentityStore,
    private val appLifecycleMonitor: AppLifecycleMonitor,
    @ApplicationScope private val parentScope: CoroutineScope,
    private val preparer: ForegroundTransportStartPreparer,
    private val retrySchedule: SseReconnectRetrySchedule = DefaultSseReconnectRetrySchedule(),
) : SseReconnectSupervisor {

    /**
     * Single monitor guarding ALL mutable supervisor state: the child
     * [scope]/[supervisorJob]/[observationJob] and the [activeFlight] registry.
     * Every guarded section is non-suspending (creating a flight only launches a
     * coroutine, which does not run its body inline on the inherited
     * dispatcher), so a plain monitor suffices and there is no lock-ordering
     * hazard with [SseTransportRuntimeStore]'s own lock.
     */
    private val stateLock = Any()

    @Volatile private var supervisorJob: Job? = null
    @Volatile private var scope: CoroutineScope? = null
    @Volatile private var observationJob: Job? = null

    /**
     * The active single-flight demand. At most one is alive; a superseded
     * demand is cancelled+completed before being replaced.
     */
    @Volatile private var activeFlight: ReconcileFlight? = null

    // ── Public contract ────────────────────────────────────────────────

    /**
     * Idempotent. Begins observing transport/foreground/identity and triggers a
     * reconcile when a current-identity [SseTransportState.Dropped] is observed
     * in the foreground. Safe to call after [ensureConnected] pre-created the
     * scope — observation is launched exactly once.
     */
    override fun start() {
        val s = ensureStarted()
        launchObservation(s)
    }

    /**
     * Wakes foreground reconciliation for the current identity WITHOUT creating
     * a duplicate launch: it funnels into the same single-flight registry as
     * [ensureConnected] and the observation path. No-op when stopped, when no
     * identity is bound, or when the app is backgrounded. Used to resume after a
     * [OwnershipRefusal.SseDisabled] pause once the setting changes.
     */
    override fun requestReconcile() {
        val s = scope ?: return
        val identity = identityStore.currentIdentity.value ?: return
        if (!appLifecycleMonitor.isInForeground.value) return
        s.launch { obtainFlight(identity, SseReconnectTrigger.EXPLICIT_RECONCILE) }
    }

    /**
     * Single-flight foreground recovery for [identity].
     *
     * Returns the single outcome of the in-flight reconcile job for this
     * (identity, current-drop-id) demand — concurrent callers await the same
     * result and produce at most one [StreamingServiceLauncher.ensureStarted]
     * invocation per demand. Stale identity (no longer current) →
     * [OwnershipRefusal.StaleIdentity]. Background → [OwnershipRefusal.Background]
     * with zero launcher calls; the runtime drop ticket is preserved (this
     * supervisor never clears it).
     */
    override suspend fun ensureConnected(
        identity: ConnectionIdentity,
        trigger: SseReconnectTrigger,
    ): OwnershipStartResult {
        ensureStarted()
        if (!identityStore.isCurrent(identity)) {
            return OwnershipStartResult.Refused(OwnershipRefusal.StaleIdentity)
        }
        val flight = obtainFlight(identity, trigger)
        return flight.result.await()
    }

    // ── Lifecycle internals ────────────────────────────────────────────

    /**
     * Creates the internal child scope if not already active and returns it
     * (idempotent). Does NOT launch observation — [launchObservation] is
     * [start]'s responsibility so an [ensureConnected] that pre-creates the
     * scope cannot prevent a later [start] from observing.
     */
    private fun ensureStarted(): CoroutineScope = synchronized(stateLock) {
        scope?.let { return it }
        val parentJob = parentScope.coroutineContext[Job]
        val job = SupervisorJob(parentJob)
        supervisorJob = job
        val derived = CoroutineScope(parentScope.coroutineContext + job)
        scope = derived
        derived
    }

    /**
     * Cancels observation + the scope + EVERY in-flight flight and drops the
     * internal state. Safe to call multiple times. All pending reconcile
     * awaiters resolve with [OwnershipRefusal.ServiceStopped]. Not on the
     * [SseReconnectSupervisor] interface — impl-level teardown hook.
     */
    fun stop() {
        val job: Job?
        val flight: ReconcileFlight?
        synchronized(stateLock) {
            job = supervisorJob
            flight = activeFlight
            supervisorJob = null
            scope = null
            observationJob = null
            activeFlight = null
        }
        flight?.completeRefused(OwnershipRefusal.ServiceStopped)
        flight?.job?.cancel()
        job?.cancel()
    }

    /**
     * Launches the observation collector exactly once (idempotent via
     * [observationJob]). Called by [start].
     */
    private fun launchObservation(s: CoroutineScope) {
        synchronized(stateLock) {
            if (observationJob != null) return
            observationJob = s.launch {
                combine(
                    appLifecycleMonitor.isInForeground,
                    runtimeStore.state,
                    identityStore.currentIdentity,
                ) { fg, transport, identity ->
                    Observation(fg, transport, identity)
                }
                    .distinctUntilChanged()
                    .collect { obs -> onObservation(obs) }
            }
        }
    }

    private data class Observation(
        val foreground: Boolean,
        val transport: SseTransportState,
        val identity: ConnectionIdentity?,
    )

    /**
     * Observation reaction (rev-ogpt fix 2/5):
     *  - background → cancel the active flight immediately (ticket preserved);
     *  - foreground with a foreign/null identity vs the active flight → cancel
     *    the stale flight (StaleIdentity);
     *  - foreground + matching Dropped → ensure exactly one flight.
     */
    private suspend fun onObservation(obs: Observation) {
        if (!obs.foreground) {
            cancelActiveFlight(OwnershipRefusal.Background)
            return
        }
        val active = synchronized(stateLock) { activeFlight }
        if (active != null && !active.result.isCompleted) {
            when {
                active.identity != obs.identity -> {
                    cancelActiveFlight(OwnershipRefusal.StaleIdentity)
                }
                active.demandKey.dropId != null &&
                    !isHealthyLive(active.identity) &&
                    !demandStillOutstanding(active) -> {
                    // A same-identity observation can still invalidate a
                    // Dropped flight: Stopped and a fresh, unrelated attempt
                    // both remove the recovery ticket. Cancel now rather than
                    // waiting for the retry delay to expire.
                    cancelActiveFlight(OwnershipRefusal.StaleIdentity)
                }
            }
        }
        val identity = obs.identity ?: return
        val transport = obs.transport
        if (transport is SseTransportState.Dropped && transport.ticket.identity == identity) {
            obtainFlight(identity, SseReconnectTrigger.DROPPED_TRANSPORT)
        }
    }

    // ── Single-flight registry ─────────────────────────────────────────

    private data class DemandKey(
        val identity: ConnectionIdentity,
        val dropId: Long?,
    )

    private class ReconcileFlight(
        val demandKey: DemandKey,
        val identity: ConnectionIdentity,
        val trigger: SseReconnectTrigger,
        val result: CompletableDeferred<OwnershipStartResult>,
    ) {
        @Volatile var job: Job? = null
    }

    /**
     * Returns the active flight for the current (identity, drop-id) demand,
     * joining an in-flight matching demand; otherwise CANCELS+COMPLETES any
     * superseded in-flight flight (foreign identity or newer drop-id) and
     * creates + launches a new one. Guarantees at most one launch per demand
     * and that no CompletableDeferred is left permanently pending.
     */
    private suspend fun obtainFlight(
        identity: ConnectionIdentity,
        trigger: SseReconnectTrigger,
    ): ReconcileFlight {
        // Snapshot the demand + scope outside the lock (cheap runtime reads).
        val dropTicket = runtimeStore.currentDropTicket(identity)
        val demandKey = DemandKey(identity, dropTicket?.dropId)
        val s = scope
        return synchronized(stateLock) {
            // Join an in-flight, matching demand if present.
            val active = activeFlight
            if (active != null && !active.result.isCompleted &&
                active.demandKey == demandKey
            ) {
                return@synchronized active
            }
            // Supersede any prior in-flight flight (foreign identity / newer
            // drop) so its awaiter resolves and no deferred is left pending.
            if (active != null && !active.result.isCompleted) {
                active.result.complete(
                    OwnershipStartResult.Refused(OwnershipRefusal.StaleIdentity),
                )
                active.job?.cancel()
            }
            val result = CompletableDeferred<OwnershipStartResult>()
            val flight = ReconcileFlight(demandKey, identity, trigger, result)
            if (s != null && supervisorJob?.isActive == true) {
                flight.job = s.launch { runReconcile(flight) }
                activeFlight = flight
            } else {
                // Supervisor stopped — resolve immediately, do not register.
                result.complete(
                    OwnershipStartResult.Refused(OwnershipRefusal.ServiceStopped),
                )
            }
            flight
        }
    }

    /**
     * Cancels the active flight (if any) with [reason]: completes the awaiter
     * and cancels the retry job (interrupting any pending [delay] immediately).
     * Does NOT touch runtime state — the drop ticket is preserved by design.
     */
    private fun cancelActiveFlight(reason: OwnershipRefusal) {
        val toCancel = synchronized(stateLock) {
            val f = activeFlight
            activeFlight = null
            f
        }
        toCancel?.let {
            it.completeRefused(reason)
            it.job?.cancel()
        }
    }

    /** Clears [activeFlight] iff it still points at [flight] (no newer demand). */
    private fun clearFlightIfCurrent(flight: ReconcileFlight) {
        synchronized(stateLock) {
            if (activeFlight === flight) {
                activeFlight = null
            }
        }
    }

    // ── Test-only introspection (same module; no production callers) ────

    /** True iff the child scope is active (not stopped). */
    internal fun isObservationActive(): Boolean = supervisorJob?.isActive == true

    /** True iff the observation collector has been launched. */
    internal fun isObserving(): Boolean = observationJob?.isActive == true

    /** True iff a reconcile flight is in flight (registered + not completed). */
    internal fun hasInflightDemand(): Boolean = synchronized(stateLock) {
        activeFlight?.let { !it.result.isCompleted } ?: false
    }

    // ── Reconcile loop ─────────────────────────────────────────────────

    /**
     * The single reconcile state machine for [flight]. Runs §4.3 retry schedule
     * while foreground + current-identity + demand outstanding. Retries all
     * retryable preparer/launcher exceptions and non-terminal refusals; pauses
     * on [OwnershipRefusal.SseDisabled]; stops on success (with post-launch
     * freshness re-validation), background, or staleness. Never mutates
     * [SseTransportRuntimeStore].
     */
    private suspend fun runReconcile(flight: ReconcileFlight) {
        var attempt = 0
        try {
            loop@ while (true) {
                // Guard: foreground. Background suppresses launch entirely and
                // preserves the ticket.
                if (!appLifecycleMonitor.isInForeground.value) {
                    flight.completeRefused(OwnershipRefusal.Background)
                    return
                }
                // Guard: identity still current.
                if (!identityStore.isCurrent(flight.identity)) {
                    flight.completeRefused(OwnershipRefusal.StaleIdentity)
                    return
                }
                // Guard: success short-circuit — Live + Ready for this identity.
                if (isHealthyLive(flight.identity)) {
                    flight.result.complete(OwnershipStartResult.Ready(flight.identity))
                    return
                }
                // Schedule delay (virtual-time compatible via [delay]).
                val delayMs = retrySchedule.delayMillis(attempt)
                if (delayMs > 0L) {
                    delay(delayMs)
                }
                // Re-validate after the (possibly long) delay.
                if (!appLifecycleMonitor.isInForeground.value) {
                    flight.completeRefused(OwnershipRefusal.Background)
                    return
                }
                if (!identityStore.isCurrent(flight.identity)) {
                    flight.completeRefused(OwnershipRefusal.StaleIdentity)
                    return
                }
                // rev-ogpt fix 1: demand liveness — a flight created for
                // Dropped(ticket K) must be cancelled when the same identity's
                // demand becomes Stopped or no longer carries the matching
                // recovery attempt. NEVER treat a null currentDropTicket as
                // automatically healthy: inspect runtime state / current
                // attempt so an intentional Stopped or an irrelevant
                // (ticket-less) attempt cancels the stale flight.
                if (!demandStillOutstanding(flight)) {
                    flight.completeRefused(OwnershipRefusal.StaleIdentity)
                    return
                }

                val currentDrop = runtimeStore.currentDropTicket(flight.identity)
                // A Dropped-triggered demand whose drop is no longer the
                // active Dropped state — i.e. an in-progress recovery attempt
                // (Connecting/Live/Retrying) still carries the SAME ticket —
                // must NOT downgrade to HEALTH_CONFIRMED. Skip launching this
                // iteration and wait for the attempt to resolve; the
                // isHealthyLive guard will catch success on the next pass.
                if (flight.demandKey.dropId != null && currentDrop == null) {
                    attempt++
                    continue@loop
                }
                val reason = preparerReason(flight.trigger, currentDrop)

                // Prepare the coordinator (L3 normalization / timer cancel).
                val preparation = try {
                    preparer.prepareForegroundTransportStart(
                        flight.identity,
                        currentDrop?.dropId,
                        reason,
                    )
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Throwable) {
                    // Retryable failure — preserve demand, advance backoff.
                    attempt++
                    continue@loop
                }

                when (preparation) {
                    ForegroundTransportStartPreparation.SupersededIdentity -> {
                        flight.completeRefused(OwnershipRefusal.StaleIdentity)
                        return
                    }
                    ForegroundTransportStartPreparation.NotEligible -> {
                        attempt++
                        continue@loop
                    }
                    ForegroundTransportStartPreparation.Ready -> {
                        val outcome = try {
                            launcher.ensureStarted(flight.identity)
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (e: Throwable) {
                            // Retryable failure — preserve demand, advance backoff.
                            attempt++
                            continue@loop
                        }
                        when (outcome) {
                            is OwnershipStartResult.Ready -> {
                                // POST-LAUNCH FRESHNESS (rev-ogpt fix 3): a
                                // stale result must not acknowledge or suppress
                                // a newer demand.
                                if (!identityStore.isCurrent(flight.identity)) {
                                    flight.completeRefused(OwnershipRefusal.StaleIdentity)
                                    return
                                }
                                if (isSuperseded(flight)) {
                                    flight.completeRefused(OwnershipRefusal.StaleIdentity)
                                    return
                                }
                                // A launcher may have completed after the
                                // runtime was intentionally stopped (or after
                                // the recovery attempt was replaced). A Ready
                                // result is valid only for a still-live
                                // demand, unless the runtime has already
                                // reached the healthy Live + Ready state.
                                if (!isHealthyLive(flight.identity) &&
                                    !demandStillOutstanding(flight)
                                ) {
                                    flight.completeRefused(OwnershipRefusal.StaleIdentity)
                                    return
                                }
                                flight.result.complete(outcome)
                                return
                            }
                            is OwnershipStartResult.Refused -> when (outcome.reason) {
                                OwnershipRefusal.SseDisabled -> {
                                    // PAUSE (rev-ogpt fix 4): resolve the
                                    // awaiter; demand stays retryable via a
                                    // later requestReconcile / setting change.
                                    flight.result.complete(outcome)
                                    return
                                }
                                OwnershipRefusal.StaleIdentity -> {
                                    flight.result.complete(outcome)
                                    return
                                }
                                else -> {
                                    // Retryable refusal — advance backoff.
                                    attempt++
                                }
                            }
                        }
                    }
                }
            }
        } catch (ce: CancellationException) {
            // Background transition / stop / superseded demand cancels the job.
            flight.completeRefused(OwnershipRefusal.ServiceStopped)
            throw ce
        } finally {
            withContext(NonCancellable) { clearFlightIfCurrent(flight) }
        }
    }

    /** Live + Ready ownership for [identity] ⇒ healthy, no launch needed. */
    private fun isHealthyLive(identity: ConnectionIdentity): Boolean {
        val state = runtimeStore.state.value
        return state is SseTransportState.Live &&
            state.attempt.identity == identity &&
            ownershipGate.readyIdentity() == identity
    }

    /**
     * rev-ogpt fix 1 — demand liveness check.
     *
     * True iff the runtime STILL carries [flight]'s drop demand:
     *  - the identity is [SseTransportState.Dropped] with the SAME drop-id, OR
     *  - an active recovery attempt (Connecting/Live/Retrying) for this
     *    identity still carries the SAME [TransportAttemptToken.recoveryTicket]
     *    (recovery in progress — preserve the flight).
     *
     * False (demand GONE — the flight is stale and must be cancelled) when the
     * state is [SseTransportState.Stopped], a fresh (ticket-less) attempt, a
     * foreign identity, or a superseding (different drop-id) Dropped.
     *
     * A HEALTH_CONFIRMED flight (null drop-id) has no drop demand to track and
     * is always considered outstanding — its liveness is governed by the
     * foreground / identity guards alone.
     *
     * Unlike a naive null-[currentDropTicket] check this NEVER treats the
     * absence of a Dropped state as automatically healthy.
     */
    private fun demandStillOutstanding(flight: ReconcileFlight): Boolean {
        val expectedDropId = flight.demandKey.dropId ?: return true
        val currentDrop = runtimeStore.currentDropTicket(flight.identity)
        if (currentDrop != null) {
            return currentDrop.dropId == expectedDropId
        }
        val attempt = runtimeStore.currentAttempt(flight.identity) ?: return false
        return attempt.recoveryTicket?.dropId == expectedDropId
    }

    /**
     * True iff a NEWER drop (different drop-id) for this identity has superseded
     * [flight]'s demand. Used for POST-LAUNCH freshness only (the recovery
     * ticket may have been legitimately cleared by acknowledgeRecovery during
     * the normal success path, so [demandStillOutstanding] must NOT be used
     * there). Null drop-ids (HEALTH_CONFIRMED) are never superseded.
     */
    private fun isSuperseded(flight: ReconcileFlight): Boolean {
        val expectedDropId = flight.demandKey.dropId ?: return false
        val currentDrop = runtimeStore.currentDropTicket(flight.identity) ?: return false
        return currentDrop.dropId != expectedDropId
    }

    private fun preparerReason(
        trigger: SseReconnectTrigger,
        currentDrop: TransportDropTicket?,
    ): ForegroundTransportStartReason =
        if (trigger == SseReconnectTrigger.HEALTH_CONFIRMED) {
            ForegroundTransportStartReason.HEALTH_CONFIRMED
        } else if (currentDrop != null) {
            ForegroundTransportStartReason.DROPPED_TRANSPORT
        } else {
            ForegroundTransportStartReason.HEALTH_CONFIRMED
        }

    private fun ReconcileFlight.completeRefused(reason: OwnershipRefusal) {
        if (!result.isCompleted) {
            result.complete(OwnershipStartResult.Refused(reason))
        }
    }
}
