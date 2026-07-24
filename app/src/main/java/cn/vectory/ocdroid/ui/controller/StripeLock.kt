package cn.vectory.ocdroid.ui.controller

import kotlinx.coroutines.sync.Mutex

/**
 * P3 §5.2 (slim/standard DAG scaffold): the per-sid striped-lock port.
 *
 * Exposes the fixed array of [SessionSyncCoordinator.STRIPES] (= 64) [Mutex]es
 * so future slim collaborators serialize competing per-sid reconcile writes
 * WITHOUT holding a [SessionSyncCoordinator] reference and WITHOUT a second
 * lock set (§11.2 ① + §5.2 "禁造第二套"). [SessionSyncCoordinator] owns the
 * single `reconcileStripes` array and is the sole implementor.
 *
 * # Single ownership, no second set
 *
 * The lock ARRAY lives on [SessionSyncCoordinator] (initialized once at
 * construction). [stripeFor] returns an element of THAT array — there is no
 * shadow copy. [stripeCount] mirrors [SessionSyncCoordinator.STRIPES] (the
 * test-visible frozen constant referenced by
 * `SessionSyncDeadlockRegressionTest`); the constant itself stays on SSC's
 * companion (F5 freeze).
 *
 * # stripeFor vs SseDispatchHost.stripeFor
 *
 * [cn.vectory.ocdroid.ui.controller.sse.SseDispatchHost] already declares
 * `fun stripeFor(sid: String): Mutex` for the SSE handlers. This port re-declares
 * the identical signature so the slim DAG children (a different consumer set)
 * depend on this seam, not on the SSE-handler host. SSC's single
 * `override fun stripeFor` satisfies both interfaces.
 *
 * # Scaffold status (P3)
 *
 * Defined + implemented by SSC in P3. No child consumes it yet — P4
 * (`SlimSessionReconciler`) is the first extractor to inject it (the reconcile
 * body wraps `stripeLock.stripeFor(sid).withLock { ... }`). Defining it now
 * keeps P4 a pure move.
 */
internal interface StripeLock {
    /** Stripe count (mirrors [SessionSyncCoordinator.STRIPES] = 64). */
    val stripeCount: Int

    /**
     * Returns the per-sid stripe [Mutex]. Selection is
     * `floorMod(sid.hashCode(), stripeCount)` so distinct sids usually land on
     * different stripes (parallel); sids with the same residue collide and
     * serialize (rare, benign).
     */
    fun stripeFor(sid: String): Mutex
}
