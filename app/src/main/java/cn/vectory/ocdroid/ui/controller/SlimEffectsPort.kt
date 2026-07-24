package cn.vectory.ocdroid.ui.controller

import cn.vectory.ocdroid.ui.UiEvent

/**
 * P3 §5.2 (slim/standard DAG scaffold): the SINGLE side-effect port future
 * slim collaborators (P4 `SlimSessionReconciler`, P5 `SlimQuestionLoader` /
 * `SlimColdStartSnapshotApplier`, …) use to emit cross-domain signals.
 *
 * # Why a port (not the [cn.vectory.ocdroid.ui.SharedEffectBus] directly)
 *
 * §11.2 ④ ("副作用只走单一 `SlimEffectsPort`（SSC 实现）") + ① ("子节点不持
 * `SessionSyncCoordinator`"): a slim child must NOT hold a
 * [SessionSyncCoordinator] reference (that would re-create the callback ring the
 * DAG split exists to break). It also must NOT grab the shared
 * [cn.vectory.ocdroid.ui.SharedEffectBus] singleton directly (that would bypass
 * SSC ownership and let a second effects path appear). Instead the child depends
 * on this narrow interface; [SessionSyncCoordinator] is the sole implementor,
 * so every slim side effect still funnels through SSC's single `effects` bus.
 *
 * This is the producer surface only (children emit; consumption / the
 * `init`-block collector stays in SSC). Mirrors
 * [cn.vectory.ocdroid.ui.SharedEffectBus]'s producer methods 1:1 so a child has
 * exactly the same emission semantics it would have had inline in SSC.
 *
 * # Scaffold status (P3)
 *
 * Defined + implemented by SSC in P3. No child consumes it yet — P4 is the
 * first extractor to inject it. Defining it now keeps P4 a pure move (SSC
 * already implements the port; P4 just passes `this`).
 *
 * # Visibility note (Kotlin-imposed, NOT an API expansion)
 *
 * The interface itself is `internal`, but its members are implicitly `public`:
 * Kotlin does NOT allow `internal` (or `protected`) modifiers on interface
 * members. Consequently SSC's overrides are `public override` — overriding
 * visibility cannot be weakened below the interface member. This is a
 * Kotlin-imposed visibility, not an intentional widening of SSC's API: the port
 * TYPE is internal, so only module-internal collaborators (P4/P5) consume it,
 * and the referenced types ([ControllerEffect] / [UiEvent] /
 * [cn.vectory.ocdroid.ui.SharedEffectBus]) are already `public`, so no new type
 * is exposed at the module boundary. (Same rule applies to [StripeLock]:
 * `stripeCount` is public; `stripeFor` is public because
 * [cn.vectory.ocdroid.ui.controller.sse.SseDispatchHost] requires it.)
 */
internal interface SlimEffectsPort {
    /**
     * Synchronous producer for a [ControllerEffect] (FIFO, SUSPEND-on-full in the
     * underlying bus). Returns false if the buffer was full. Mirrors
     * [cn.vectory.ocdroid.ui.SharedEffectBus.tryEmitEffect].
     */
    fun tryEmitEffect(effect: ControllerEffect): Boolean

    /**
     * Suspend producer for a [ControllerEffect] (enqueues FIFO, suspends if the
     * buffer is full). Mirrors [cn.vectory.ocdroid.ui.SharedEffectBus.emitEffect].
     */
    suspend fun emitEffect(effect: ControllerEffect)

    /**
     * Synchronous producer for a [UiEvent] (DROP_OLDEST, fire-and-forget UI
     * feedback). Mirrors [cn.vectory.ocdroid.ui.SharedEffectBus.tryEmitUiEvent].
     */
    fun tryEmitUiEvent(event: UiEvent): Boolean

    /**
     * Suspend producer for a [UiEvent]. Mirrors
     * [cn.vectory.ocdroid.ui.SharedEffectBus.emitUiEvent].
     */
    suspend fun emitUiEvent(event: UiEvent)
}
