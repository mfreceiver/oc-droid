package cn.vectory.ocdroid.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import cn.vectory.ocdroid.di.AppLifecycleMonitor
import cn.vectory.ocdroid.service.identity.ConnectionIdentity
import cn.vectory.ocdroid.service.lifecycle.Layer
import cn.vectory.ocdroid.service.lifecycle.StreamingLifecycleCoordinator
import cn.vectory.ocdroid.util.DebugLog
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * CP9 (notify Phase-0 switchover): the trigger that promotes the live SSE
 * connection ownership from [cn.vectory.ocdroid.ui.controller.ConnectionCoordinator]
 * (CC) into [SessionStreamingService].
 *
 * **Why a launcher, not a direct `startForegroundService` call from CC**:
 *  - CC lives in the `ui` package and has no Android `Context`; injecting a
 *    raw Context into CC would widen its dependency surface and harm
 *    testability.
 *  - The eligibility matrix (foreground truth + lifecycle layer) MUST be
 *    enforced at the trigger site, not at the Service. The Service is
 *    Android-driven (`onStartCommand`) and cannot decline a start that has
 *    already been issued; the launcher is the single chokepoint.
 *
 * **Eligibility matrix** (FGS spec §4.3 / CP9 plan §C15):
 *  - background (`AppLifecycleMonitor.isInForeground.value == false`) →
 *    return `false`; NEVER call `startForegroundService`. Background recovery
 *    is limited to user-reopen / notification Action / system sticky restart
 *    (FGS spec §4.1).
 *  - foreground AND lifecycle layer is NOT [Layer.L3] → return `true`
 *    WITHOUT starting anything. The existing L1/L2 Service instance already
 *    owns the live SSE / lifecycle (a second start would either be a no-op
 *    Android redelivery or a duplicate bootstrap state machine).
 *  - foreground AND [Layer.L3] → `ContextCompat.startForegroundService` with
 *    [SessionStreamingService.ACTION_BOOTSTRAP]; the Service runs its §5
 *    sequence (`startForeground` placeholder → async bootstrap → coordinator
 *    `onBootstrapResult` → L1/L2/L3 decision matrix → `StartSse` /
 *    `StopPoller` / etc.).
 *
 * **D4-B B1 (two-stage Starting→Ready ownership)**:
 *  - The Service registers **Starting** ownership synchronously when it
 *    verifies the requested identity (within the 5s [OwnershipAckPolicy]
 *    window). Starting DOES close the unowned window (a concurrent
 *    reconfigure teardown observes + releases it) but DOES NOT satisfy the
 *    launcher's readiness waiter.
 *  - The launcher's waiter completes with [OwnershipStartResult.Ready] ONLY
 *    after the Service marks ownership Ready (Stage 2: the SSE transport
 *    delivered a valid current-identity frame AND the coordinator committed
 *    the Bootstrap handoff). The launcher MUST NOT map Starting to Connected
 *    — CC publishes [cn.vectory.ui.ConnectionPhase.Connected] exclusively on
 *    Ready.
 *  - Exact-identity owner reuse: a Ready owner for the same identity returns
 *    Ready immediately (no second startForegroundService); a Starting owner
 *    for the same identity makes the launcher await the Stage-2 promotion.
 *
 * **No "non-L3 means no-op" assumption** (D4-B): a non-L3 layer does not by
 * itself imply the Service owns a working transport. The launcher ALWAYS
 * consults the ownership gate rather than inferring readiness from the layer.
 *
 * Returns `true` iff the start was issued OR not needed (already running /
 * non-L3); returns `false` iff the start was refused (background or platform
 * rejection). Callers treat `false` as "SSE will not be re-established by
 * the Service this tick" — the foreground UI keeps running on its existing
 * CC-owned state; the gap is closed by the next foreground return path.
 *
 * CP9 plan §C16: ALM (AppLifecycleMonitor) lifecycle callbacks MUST NOT
 * start the Service. ALM lacks a healthy bound identity; starting there
 * races CC health / TOFU and creates two bootstrap state machines. ALM is
 * ONLY: foreground truth source + legacy pending-item poller host.
 */
interface StreamingServiceLauncher {
    /**
     * Ensures [SessionStreamingService] is started iff the eligibility matrix
     * permits. See the interface KDoc for the matrix.
     *
     * @return `true` iff the Service is running (or already running) OR the
     *   layer makes a start unnecessary; `false` iff the start was refused.
     */
    suspend fun ensureStarted(identity: ConnectionIdentity): OwnershipStartResult
}

/**
 * Production Android impl of [StreamingServiceLauncher].
 *
 * `@Singleton @Inject constructor` — Hilt auto-provides (concrete class, no
 * `@Binds`/`@Provides` module needed). The qualifier-free constructor
 * arguments are themselves Hilt-injected singletons.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Singleton
class AndroidStreamingServiceLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appLifecycleMonitor: AppLifecycleMonitor,
    private val coordinator: StreamingLifecycleCoordinator,
    private val ownershipGate: StreamingOwnershipGate,
    private val ackPolicy: OwnershipAckPolicy,
) : StreamingServiceLauncher {

    override suspend fun ensureStarted(identity: ConnectionIdentity): OwnershipStartResult {
        // §C15 step 1: foreground truth. A background start would trip a
        // ForegroundServiceStartNotAllowedException on Android 12+ and is
        // explicitly out-of-scope (FGS spec §4.1 legal recovery entries only).
        if (!appLifecycleMonitor.isInForeground.value) {
            DebugLog.i(TAG, "ensureStarted: NOT foreground → refuse (no FGS in background)")
            return OwnershipStartResult.Refused(OwnershipRefusal.Background)
        }
        // D5-2 (#4): prepared-attempt + split-deferred model. prepareAttempt
        // handles the four cases (Ready same / Starting same / no owner /
        // different owner). Only the "no owner" case sets launchRequired=true.
        val attempt = ownershipGate.prepareAttempt(identity)
        if (attempt.launchRequired) {
            // §C15 step 3: foreground + L3 → start the Service with the §5
            // bootstrap action. The Service runs its §5 sequence (placeholder
            // promotion → async bootstrap → coordinator.onBootstrapResult →
            // StartSse via the §4 decision matrix). D5-2 (#4): stamp the
            // attempt ID so the late Service invocation can validate it.
            try {
                val intent = Intent(context, SessionStreamingService::class.java).apply {
                    action = SessionStreamingService.ACTION_BOOTSTRAP
                    putExtra(OwnershipRequestParser.EXTRA_EPOCH, identity.epoch)
                    putExtra(OwnershipRequestParser.EXTRA_SERVER_GROUP_FP, identity.serverGroupFp)
                    putExtra(OwnershipRequestParser.EXTRA_WORKDIR, identity.normalizedWorkdir)
                    putExtra(OwnershipRequestParser.EXTRA_ENDPOINT_FP, identity.endpointFp)
                    putExtra(OwnershipRequestParser.EXTRA_ATTEMPT_ID, attempt.attemptId)
                }
                ContextCompat.startForegroundService(context, intent)
                DebugLog.i(TAG, "ensureStarted: startForegroundService issued (ACTION_BOOTSTRAP, attemptId=${attempt.attemptId})")
            } catch (t: Throwable) {
                // §C15 step 4: platform start rejection (ForegroundServiceStart-
                // NotAllowedException, SecurityException on quota exhaustion,
                // etc.). MUST NOT crash the health coroutine that called us —
                // surface as a refused start so CC reports its own connect
                // state honestly and the foreground return path retries.
                DebugLog.w(TAG, "ensureStarted: platform rejected startForegroundService: ${t.message}")
                val refusal = OwnershipRefusal.PlatformRejected(t)
                ownershipGate.expireAttempt(attempt.attemptId, refusal)
                return OwnershipStartResult.Refused(refusal)
            }
        }
        // D5-2 (#4): the 5s wall-clock covers ONLY Stage 1 acceptance
        // (registerStarting). On acceptance, await terminal WITHOUT a second
        // wall-clock timeout — the 30s transport timeout runs ONLY inside
        // ServiceSseConnectionOwner.setupConnectLocked (already true from D4-B).
        val startingAck = withTimeoutOrNull(ackPolicy.timeoutMs) { attempt.starting.await() }
        // Race safety: withTimeoutOrNull may return null at the exact boundary
        // even though `starting` was just completed. If so, use the completed
        // value — the Service has already recorded Stage 1 ownership.
        @Suppress("UNCHECKED_CAST")
        val ack: StartingAck? = startingAck
            ?: (attempt.starting as? CompletableDeferred<StartingAck>)
                ?.takeIf { it.isCompleted }
                ?.let { runCatching { it.getCompleted() }.getOrNull() }
        return when (ack) {
            is StartingAck.Accepted -> {
                // Stage 1 accepted — await Stage 2 with NO second wall-clock.
                attempt.terminal.await()
            }
            is StartingAck.Refused -> OwnershipStartResult.Refused(ack.reason)
            null -> {
                // Stage 1 NOT accepted within 5s → EXPIRE the attempt ID at
                // the gate so any LATE Service registerStarting for this
                // attemptId returns Expired and the Service aborts (no orphan
                // owner). Settles the attempt's deferreds with AckTimeout.
                DebugLog.w(TAG, "ensureStarted: Stage 1 AckTimeout (attemptId=${attempt.attemptId}) → expireAttempt")
                ownershipGate.expireAttempt(attempt.attemptId, OwnershipRefusal.AckTimeout)
                OwnershipStartResult.Refused(OwnershipRefusal.AckTimeout)
            }
        }
    }

    private companion object {
        private const val TAG = "StreamingSvcLauncher"
    }
}
