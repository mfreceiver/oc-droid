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
 * **No-zero-time-gap guarantee** (FGS spec §1 / CP9 plan): a Service start is
 * asynchronous, so a literal zero-time gap between `onSettled(true)` and
 * `connectSSE()` is impossible. Instead, this launcher guarantees that there
 * is NO TERMINAL connected-without-SSE path: CC calls [ensureStarted]
 * synchronously before reporting success; Service bootstrap leads to
 * `StartSse`; the coordinator's decision matrix is the only legal L3 →
 * running entry (`onBootstrapResult`).
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
        ownershipGate.acceptedIdentity()?.let { owned ->
            return if (owned == identity) {
                OwnershipStartResult.Accepted(identity)
            } else {
                OwnershipStartResult.Refused(OwnershipRefusal.AlreadyOwned(owned))
            }
        }
        // §C15 step 3: foreground + L3 → start the Service with the §5
        // bootstrap action. The Service runs its §5 sequence (placeholder
        // promotion → async bootstrap → coordinator.onBootstrapResult →
        // StartSse via the §4 decision matrix).
        val waiter = ownershipGate.prepare(identity)
        try {
            val intent = Intent(context, SessionStreamingService::class.java).apply {
                action = SessionStreamingService.ACTION_BOOTSTRAP
                putExtra(OwnershipRequestParser.EXTRA_EPOCH, identity.epoch)
                putExtra(OwnershipRequestParser.EXTRA_SERVER_GROUP_FP, identity.serverGroupFp)
                putExtra(OwnershipRequestParser.EXTRA_WORKDIR, identity.normalizedWorkdir)
                putExtra(OwnershipRequestParser.EXTRA_ENDPOINT_FP, identity.endpointFp)
            }
            ContextCompat.startForegroundService(context, intent)
            DebugLog.i(TAG, "ensureStarted: startForegroundService issued (ACTION_BOOTSTRAP)")
            return withTimeoutOrNull(ackPolicy.timeoutMs) { waiter.await() }
                ?: OwnershipStartResult.Refused(OwnershipRefusal.AckTimeout)
        } catch (t: Throwable) {
            // §C15 step 4: platform start rejection (ForegroundServiceStart-
            // NotAllowedException, SecurityException on quota exhaustion,
            // etc.). MUST NOT crash the health coroutine that called us —
            // surface as a refused start so CC reports its own connect state
            // honestly and the foreground return path retries.
            DebugLog.w(TAG, "ensureStarted: platform rejected startForegroundService: ${t.message}")
            val refusal = OwnershipRefusal.PlatformRejected(t)
            ownershipGate.refuse(identity, refusal)
            return OwnershipStartResult.Refused(refusal)
        } finally {
            ownershipGate.cancelWaiter(identity, waiter)
        }
    }

    private companion object {
        private const val TAG = "StreamingSvcLauncher"
    }
}
