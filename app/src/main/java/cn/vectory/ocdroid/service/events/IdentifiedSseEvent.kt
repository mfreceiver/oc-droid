package cn.vectory.ocdroid.service.events

import cn.vectory.ocdroid.data.model.SSEEvent
import cn.vectory.ocdroid.service.identity.ConnectionIdentity

/**
 * Wraps an SSE event with the [ConnectionIdentity] of the connection it arrived on, so that
 * epoch validation can happen at the fold boundary instead of being stripped prematurely.
 *
 * See FGS spec §1 «模型 A 收敛»: `SessionStreamingService.events` and
 * `ControllerEffect.OnSseEvent` **both** carry an `IdentifiedSseEvent`; the bridge
 * (`SseEventBridge`, dev-design P0.6) does the **first** strong identity check, and the effect
 * still carries identity so `SessionSyncCoordinator.fold` can perform a **second** validation.
 *
 * FGS spec §1, gpter-MAJOR#2 closure: identity **must not** be stripped before the fold —
 * «不能在 bridge 剥掉 identity 后再声称 SSC 会校验». Carrying it on the event container is what
 * makes the two-stage check possible without trusting the bridge alone.
 *
 * Epoch staleness (FGS spec §2): if [identity].epoch != the current process-level epoch, the
 * event belongs to a stale host / pre-reconfigure collector and must be dropped by every
 * consumer **before** any side effect (state mutation, notification, fold).
 *
 * This type is a pure data carrier (Phase 0 shared contract); it performs no validation itself.
 *
 * @property identity The connection identity (epoch + group/workdir/endpoint fingerprints) the
 *  wrapped [event] was received on.
 * @property event The raw SSE event payload, exactly as produced by `SSEClient`.
 */
data class IdentifiedSseEvent(
    val identity: ConnectionIdentity,
    val event: SSEEvent,
)
