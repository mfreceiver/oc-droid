package cn.vectory.ocdroid.service.bootstrap

import cn.vectory.ocdroid.data.repository.http.TofuDecision
import cn.vectory.ocdroid.util.DebugLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Observable TOFU bootstrap state, shared between the foreground
 * [cn.vectory.ocdroid.ui.controller.ConnectionCoordinator] and the background
 * `SessionStreamingService`.
 *
 * States (see [FGS spec §10 / §5][spec]):
 *  - [Idle] — no TOFU prompt outstanding; SSE bootstrap is free to proceed.
 *  - [TrustPending] — an SSL/cert failure was classified as trust-on-first-use
 *    and a capture + prompt are in flight. While pending, the SSE bootstrap is
 *    FROZEN: the connect retry loop SUSPENDS on the
 *    [CompletableDeferred]<[TofuDecision]> installed via [setTofuDecision],
 *    [startSSE][ccStartSse]/[coldStartReconnect][ccCold] early-return, and a
 *    re-entrant [testConnection][ccTest] defers — exactly the three guards the
 *    pre-extraction CC held privately on `pendingTofuHostPort != null`.
 *  - [DegradedNeedsActivity] — FGS spec §5「未决 TOFU 且无 Activity」: a TOFU
 *    prompt is owed but no Activity is available to show the dialog (the
 *    Service is running the bootstrap on a cold / sticky-restart path with no
 *    started Activity). The bootstrap MUST NOT block on a UI deferred that can
 *    never complete and MUST NOT burn the SSE retry budget looping; the
 *    outstanding [CompletableDeferred] is cancelled so the awaiter resumes
 *    cleanly and the [tofuState] surfaces this so a placeholder notification
 *    (with an Open action) can be shown. The notification UI itself is Phase 1;
 *    this coordinator only exposes the state.
 *
 * Thread-safety: `@Singleton` + a private intrinsic lock serialise every
 * mutating op because both CC (main thread) and the Service (its own scope)
 * call into the same instance concurrently. [tofuState] is a
 * [MutableStateFlow] (thread-safe by contract); the pending
 * [CompletableDeferred] reference is guarded by the same lock.
 *
 * Extraction boundary (P0.5): this class ONLY encapsulates the TOFU state CC
 * held privately (`pendingTofuHostPort` / `pendingTofuDecision` /
 * `resolveTofuTrust`) plus the degraded path. The actual re-wiring of CC to
 * delegate here is the switch-over lane; CC is intentionally untouched.
 *
 * [spec]: docs/ocmar/specs/2026-07-13-notification-background-fgs-design.md
 *   §5 (START_STICKY 冷启动 bootstrap — degraded TOFU path) and
 *   §10 (TOFU / bootstrap 抽离).
 *
 * [ccStartSse]: cn.vectory.ocdroid.ui.controller.ConnectionCoordinator.startSSE
 * [ccCold]: cn.vectory.ocdroid.ui.controller.ConnectionCoordinator.coldStartReconnect
 * [ccTest]: cn.vectory.ocdroid.ui.controller.ConnectionCoordinator.testConnection
 */
sealed interface TofuState {
    /** No TOFU prompt outstanding — SSE bootstrap is free to proceed. */
    object Idle : TofuState

    /**
     * A TOFU trust prompt is in flight for [hostPort]; SSE bootstrap is FROZEN
     * until the user decides. Mirrors CC's `pendingTofuHostPort != null`.
     */
    data class TrustPending(
        val hostPort: String,
        val capture: cn.vectory.ocdroid.data.repository.OpenCodeRepository.TofuCaptureResult? = null,
    ) : TofuState

    /**
     * A TOFU prompt is owed for [hostPort] but no Activity is available to show
     * it (FGS spec §5). The pending deferred has been cancelled so the
     * connection bootstrap does not block on a UI that cannot answer and does
     * not burn the SSE retry budget. A placeholder notification (Phase 1) with
     * an Open action should surface this; resolving trust later clears it via
     * [ConnectionBootstrapCoordinator.clearPendingTofu].
     */
    data class DegradedNeedsActivity(
        val hostPort: String,
        val capture: cn.vectory.ocdroid.data.repository.OpenCodeRepository.TofuCaptureResult? = null,
    ) : TofuState
}

/**
 * FGS spec §10 / §5: application-level shared coordinator holding the TOFU
 * (trust-on-first-use) bootstrap state that [ConnectionCoordinator] held
 * privately pre-extraction (`pendingTofuHostPort`, `pendingTofuDecision`,
 * `resolveTofuTrust`). Both the foreground CC and the background
 * `SessionStreamingService` call this same instance so the bootstrap cannot
 * fork into two TLS/SSE state machines.
 *
 * `@Singleton @Inject constructor` is sufficient for Hilt (no `@Binds` module:
 * this is a concrete class, not an interface implementation).
 *
 * Semantics mirror CC exactly (read CC ~line 121–124, 198–200, 241–243, 322,
 * 331–340, 383, 391, 397–399, 451–453, 661–664, 678–681): while
 * [pendingTofuHostPort] is non-null, SSE bootstrap is FROZEN; resolving trust
 * unfreezes. The single new behaviour beyond CC is the
 * [DegradedNeedsActivity] transition ([markDegradedNeedsActivity]).
 */
@Singleton
class ConnectionBootstrapCoordinator @Inject constructor() {

    private val lock = Any()

    private val _tofuState = MutableStateFlow<TofuState>(TofuState.Idle)

    /** Observable bootstrap state (FGS spec §10/§5). Thread-safe read. */
    val tofuState: StateFlow<TofuState> = _tofuState.asStateFlow()

    @Volatile
    private var pendingTofuDecision: CompletableDeferred<TofuDecision>? = null

    /**
     * The pending TOFU hostPort, or null when no TOFU is outstanding. Non-null
     * for BOTH [TofuState.TrustPending] and [TofuState.DegradedNeedsActivity]
     * — either way the SSE bootstrap stays FROZEN (mirrors CC's
     * `pendingTofuHostPort != null` freeze guard read at the
     * testConnection entry, the retry-loop top, coldStartReconnect, and
     * startSSE). Callers that need single-flight (don't stack a second prompt)
     * check `pendingTofuHostPort() == null` before calling [setPendingTofu],
     * exactly as CC's call site did with the private field.
     */
    fun pendingTofuHostPort(): String? = when (val s = _tofuState.value) {
        is TofuState.TrustPending -> s.hostPort
        is TofuState.DegradedNeedsActivity -> s.hostPort
        TofuState.Idle -> null
    }

    /**
     * Occupies the pending-trust slot for [hostPort] and transitions to
     * [TofuState.TrustPending]. Mirrors CC's `pendingTofuHostPort = hostPort`
     * direct field write (CC ~line 331). Callers MUST have already checked
     * [pendingTofuHostPort]`() == null` to preserve single-flight (CC's
     * `(d)` guard at line 322); this method does not second-guess the caller,
     * matching CC's lack of an in-setter guard.
     */
    fun setPendingTofu(hostPort: String) {
        synchronized(lock) {
            _tofuState.value = TofuState.TrustPending(hostPort)
        }
    }

    fun setPendingCapture(
        capture: cn.vectory.ocdroid.data.repository.OpenCodeRepository.TofuCaptureResult,
    ) {
        synchronized(lock) {
            val current = _tofuState.value
            if (current is TofuState.TrustPending && current.hostPort == capture.hostPort) {
                _tofuState.value = current.copy(capture = capture)
            }
        }
    }

    /**
     * Installs the [CompletableDeferred] the bootstrap retry loop is suspending
     * on (the one [resolveTofuTrust] completes). Mirrors CC's
     * `pendingTofuDecision = deferred` (CC ~line 340). Pass `null` to drop the
     * reference without completing it (CC's inner-finally clear at line 383);
     * [clearPendingTofu] also clears it as part of a full reset.
     */
    fun setTofuDecision(decision: CompletableDeferred<TofuDecision>?) {
        synchronized(lock) {
            pendingTofuDecision = decision
        }
    }

    /**
     * Full reset to [TofuState.Idle]: clears the pending hostPort AND drops the
     * outstanding decision reference. Mirrors CC's outer-finally
     * `pendingTofuHostPort = null` (CC ~line 391) combined with the
     * inner-finally `pendingTofuDecision = null` (CC ~line 383). Idempotent.
     */
    fun clearPendingTofu() {
        synchronized(lock) {
            _tofuState.value = TofuState.Idle
            pendingTofuDecision = null
        }
    }

    /**
     * Applies the user's TOFU trust decision for the pending endpoint. Mirrors
     * CC's [ConnectionCoordinator.resolveTofuTrust] verbatim (CC ~line 678–681):
     * completes the installed [CompletableDeferred] so the suspended retry loop
     * resumes and writes the pin (Accept/Trust) or settles false (Cancel);
     * no-op (warn-log) when no decision is pending. Does NOT clear
     * [tofuState] — the caller calls [clearPendingTofu] from its finally block,
     * exactly as CC's finally cleared `pendingTofuHostPort` after `await()`
     * returned.
     */
    fun resolveTofuTrust(decision: TofuDecision) {
        pendingTofuDecision?.complete(decision)
            ?: DebugLog.w(TAG, "resolveTofuTrust: no pending TOFU decision — ignoring $decision")
    }

    /**
     * FGS spec §5「未决 TOFU 且无 Activity」degraded transition. Moves a
     * [TofuState.TrustPending] into [TofuState.DegradedNeedsActivity] (same
     * hostPort) and CANCELS the outstanding [CompletableDeferred] so the
     * bootstrap retry loop that is suspended on `await()` resumes immediately
     * with a [kotlinx.coroutines.CancellationException] — it does NOT
     * infinitely block on a UI deferred that can never complete and does NOT
     * consume the SSE retry budget looping. The hostPort is retained so a
     * placeholder notification (Phase 1) with an Open action can identify the
     * endpoint. No-op when not currently [TofuState.TrustPending] (an
     * Idempotent / Degraded → Degraded or Idle → Degraded transition would
     * lose information or invent a missing hostPort).
     *
     * The actual placeholder notification is Phase 1 — this method only
     * effects the state transition and deferred cancellation.
     */
    fun markDegradedNeedsActivity() {
        synchronized(lock) {
            val current = _tofuState.value
            if (current !is TofuState.TrustPending) {
                DebugLog.i(TAG, "markDegradedNeedsActivity: skipping — state is $current (need TrustPending)")
                return
            }
            _tofuState.value = TofuState.DegradedNeedsActivity(current.hostPort, current.capture)
            pendingTofuDecision?.cancel()
            pendingTofuDecision = null
        }
    }

    private companion object {
        private const val TAG = "BootstrapCoord"
    }
}
