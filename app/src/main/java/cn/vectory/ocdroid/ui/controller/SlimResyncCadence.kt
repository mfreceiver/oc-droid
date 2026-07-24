package cn.vectory.ocdroid.ui.controller

import cn.vectory.ocdroid.util.DebugLog
import java.util.concurrent.atomic.AtomicReference

/**
 * P3 §5.2 (slim/standard DAG scaffold): the bounded re-sync cadence, extracted
 * as a PURE state object (§11.2 ⑦ — non-god-orchestrator).
 *
 * Owns the cadence [AtomicReference] + the three state transitions
 * ([resetCadenceForGeneration] / [maybeScheduleResync] / [finishResyncCadence]).
 * Moved verbatim from `SessionSyncCoordinator` L537–659; the CAS loops and
 * DebugLog messages are byte-identical so [SessionSyncCoordinatorCadenceTest]
 * (which drives the cadence via `performSlimResync` + the
 * `resyncClockMsForTest` seam) stays GREEN.
 *
 * # Purity (§11.2 ①③⑥⑦)
 *
 *  - Holds NO [SessionSyncCoordinator] reference (①).
 *  - Does NOT call [SessionSyncCoordinator.handleEvent] or any coordinator
 *    method (②③).
 *  - Does NOT launch the resync worker — that stays in SSC (⑥ "worker 执行留
 *    SSC"). [finishResyncCadence] instead returns a [TrailingDecision] sealed
 *    command (⑤ "子节点返回 sealed result/command"); SSC interprets
 *    [TrailingDecision.RelaunchTrailing] and does the `scope.launch {
 *    performSlimResync(…) }` itself.
 *
 * # Clock injection (the §5.2 "snag")
 *
 * The wall-clock is supplied as a `() -> Long` lambda — this class NEVER reads
 * `System.currentTimeMillis` nor the file-level `clockOverride` directly (that
 * direct read was the test snag noted in §5.2). SSC constructs it with
 * `clock = this.clock`, where `this.clock` resolves `clockOverride` live — so
 * the existing `resyncClockMsForTest` seam (which sets `clockOverride`) keeps
 * working unchanged, AND the cadence is unit-testable in isolation by passing a
 * fake clock.
 */
internal class SlimResyncCadence(
    private val clock: () -> Long,
    private val tag: String = "SessionSyncCoordinator",
) {
    /**
     * G-F1: cadence state.
     *  - [lastSuccessfulResyncAt] = epoch-ms of the most recent successful
     *    resync sweep (used for the 15-min interval).
     *  - [resyncInFlight] = true while a sweep is in-progress.
     *  - [trailingQueued] = true if a second coalesced trigger is waiting.
     *  - [resyncHostGeneration] = the generation this state machine is tracking
     *    (triggers with a mismatched gen are stale).
     *  - [resyncDirty] = true when a trigger was suppressed due to interval;
     *    the next eligible trigger will immediately launch.
     */
    internal data class ResyncCadence(
        val lastSuccessfulResyncAt: Long = 0L,
        val resyncInFlight: Boolean = false,
        val trailingQueued: Boolean = false,
        val resyncHostGeneration: Long = 0L,
        val resyncDirty: Boolean = false,
    )

    /** G-F1: bounded re-sync cadence state machine. Atomic for thread-safe updates. */
    private val state = AtomicReference(ResyncCadence())

    /** Diagnostic/test snapshot of the current cadence state. */
    internal fun snapshot(): ResyncCadence = state.get()

    /**
     * G-F1: reset the cadence state for a new host generation. Clears any
     * in-flight / trailing / dirty state and sets the generation.
     */
    internal fun resetCadenceForGeneration(gen: Long) {
        state.set(ResyncCadence(resyncHostGeneration = gen))
    }

    /**
     * G-F1: bounded re-sync cadence guard. Call this (on the coordinator's
     * Main-immediate scope) before launching a resync sweep. Returns
     * [ScheduleDecision.Proceed] if the sweep should proceed (respecting
     * interval, single-flight, trailing); [ScheduleDecision.Declined] otherwise.
     *
     * When [isManual] is true, the 15-min interval is bypassed.
     * On stale generation (triggerGeneration != resyncHostGeneration), returns
     * [ScheduleDecision.Declined] (caller should drop).
     *
     * Side effects: updates the cadence state (in-flight, dirty, trailing).
     * Does NOT actually launch the sweep — the caller must launch
     * `performSlimResync` after receiving [ScheduleDecision.Proceed], and call
     * [finishResyncCadence] afterward.
     */
    internal fun maybeScheduleResync(
        triggerGeneration: Long,
        isManual: Boolean = false,
        bypassIntervalCheck: Boolean = false,
    ): ScheduleDecision {
        // CAS loop for atomic update of the cadence state.
        while (true) {
            val cur = state.get()
            if (triggerGeneration != cur.resyncHostGeneration) {
                DebugLog.d(tag, "maybeScheduleResync: stale generation $triggerGeneration != ${cur.resyncHostGeneration}")
                return ScheduleDecision.Declined
            }
            val now = clock()
            val tooSoon = !isManual && !bypassIntervalCheck &&
                cur.lastSuccessfulResyncAt > 0L &&
                now - cur.lastSuccessfulResyncAt < 15 * 60 * 1000L
            if (tooSoon) {
                DebugLog.d(tag, "maybeScheduleResync: too soon (<15min), mark dirty")
                val next = cur.copy(resyncDirty = true)
                if (state.compareAndSet(cur, next)) return ScheduleDecision.Declined
                continue
            }
            if (cur.resyncInFlight) {
                if (!cur.trailingQueued) {
                    DebugLog.d(tag, "maybeScheduleResync: in-flight, queue trailing")
                    val next = cur.copy(trailingQueued = true)
                    if (state.compareAndSet(cur, next)) return ScheduleDecision.Declined
                    continue
                } else {
                    DebugLog.d(tag, "maybeScheduleResync: in-flight + already queued, skip")
                }
                return ScheduleDecision.Declined
            }
            // Launch eligible; clear dirty and trailing, set in-flight.
            val next = cur.copy(
                resyncInFlight = true,
                resyncDirty = false,
                trailingQueued = false,
            )
            if (state.compareAndSet(cur, next)) return ScheduleDecision.Proceed
            // CAS failed -> retry
            continue
        }
    }

    /**
     * G-F1: call AFTER `performSlimResync` completes (success or failure).
     * Updates the cadence state based on outcome, then returns a
     * [TrailingDecision]:
     *  - [TrailingDecision.RelaunchTrailing] — a coalesced trailing trigger was
     *    waiting; SSC must launch `performSlimResync(bypassIntervalCheck = true)`
     *    and re-call [finishResyncCadence] with its outcome.
     *  - [TrailingDecision.Idle] — nothing trailing.
     *
     * The CAS-clear of `trailingQueued` happens here (pure state transition);
     * only the worker LAUNCH stays in SSC (§11.2 ⑥).
     */
    internal fun finishResyncCadence(hadFailure: Boolean): TrailingDecision {
        val now = clock()
        // CAS loop for atomic update.
        while (true) {
            val cur = state.get()
            val next = cur.copy(
                resyncInFlight = false,
                lastSuccessfulResyncAt = if (hadFailure) cur.lastSuccessfulResyncAt else now,
            )
            if (state.compareAndSet(cur, next)) break
        }
        // Check trailing: if a trailing was queued while in-flight, signal SSC
        // to launch it now.
        val curAfter = state.get()
        if (curAfter.trailingQueued) {
            // CAS clear trailingQueued.
            while (true) {
                val cur2 = state.get()
                if (!cur2.trailingQueued) break
                val next2 = cur2.copy(trailingQueued = false)
                if (state.compareAndSet(cur2, next2)) break
            }
            // NOTE: the trailing relaunch runs with bypassIntervalCheck=true
            // (was pre-approved when queued). The internal guard inside
            // performSlimResync is the SOLE cadence authority — SSC launches it
            // UNCONDITIONALLY (NOT wrapping in maybeScheduleResync) to avoid the
            // double-guard that re-set inFlight and made the internal guard
            // decline -> emptyMap -> finishResyncCadence re-launch -> livelock
            // (B1.5). inFlight was cleared above; trailingQueued was cleared
            // above; bypassIntervalCheck=true skips the 15-min interval.
            return TrailingDecision.RelaunchTrailing
        }
        return TrailingDecision.Idle
    }

    /**
     * Sealed cadence-schedule decision (§11.2 ⑤ — child returns sealed
     * result/command; SSC translates [Proceed] to "launch the sweep").
     */
    internal sealed class ScheduleDecision {
        /** The sweep is eligible to run; SSC launches `performSlimResync`. */
        object Proceed : ScheduleDecision()
        /** Stale generation / too soon / in-flight / already-queued — drop. */
        object Declined : ScheduleDecision()
    }

    /**
     * Sealed trailing-relaunch decision returned by [finishResyncCadence]
     * (§11.2 ⑤). SSC interprets [RelaunchTrailing] and performs the worker
     * launch; the cadence never calls back into SSC (§11.2 ③).
     */
    internal sealed class TrailingDecision {
        /** A trailing trigger was queued; SSC launches the trailing sweep. */
        object RelaunchTrailing : TrailingDecision()
        /** No trailing trigger pending. */
        object Idle : TrailingDecision()
    }
}
