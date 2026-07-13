package cn.vectory.ocdroid.service

/**
 * FGS spec §5 / §16-U1 — pure-JVM intent-action dispatcher for
 * [SessionStreamingService.onStartCommand].
 *
 * Extracted as a tiny pure function so the routing decision (which branch
 * the Service takes) is unit-testable on a plain JVM, without Robolectric /
 * without instantiating the Android Service. The Service's `onStartCommand`
 * is a 3-line forwarder:
 *
 * ```kotlin
 * when (val route = StartCommandRouter.routeFor(intent?.action)) {
 *     StartCommandRoute.CloseBackground -> controller?.requestUserClose()
 *     StartCommandRoute.Bootstrap -> { /* §5 host-check + placeholder + bootstrap */ }
 * }
 * ```
 *
 * Both routes return [Service.START_STICKY] (the manifest-declared service is
 * `START_STICKY`); the router does NOT decide stickiness, only the body.
 *
 * Why a sealed enum and not a `when` over String in the Service: the test
 * fixture can directly assert "ACTION_CLOSE_BACKGROUND → CloseBackground" and
 * "null → Bootstrap" without a real Android `Intent`, and adding a future
 * action (e.g. an abort action §9) only requires extending the sealed type +
 * the router + the Service `when`.
 */
object StartCommandRouter {

    /**
     * The branch [SessionStreamingService.onStartCommand] takes for a given
     * intent action.
     */
    sealed interface Route {
        /**
         * §16-U1 user-explicit close: ongoing-notification Action 「关闭后台」.
         * The Service forwards to
         * [cn.vectory.ocdroid.service.streaming.SessionStreamingController.requestUserClose]
         * → L3 teardown. Does NOT re-bootstrap.
         */
        data object CloseBackground : Route

        /**
         * §5 START_STICKY bootstrap path. Covers:
         *  - null Intent (sticky rebuild, §5 / §4.3 — legal FGS-start context);
         *  - any other unrecognised action (forward-compat: a future caller
         *    that delivers an explicit action like "REFRESH" still falls
         *    through to the bootstrap path, which is single-flight safe).
         */
        data object Bootstrap : Route
    }

    /**
     * Decides the [Route] for [action]. Pure function — no Android deps.
     */
    fun routeFor(action: String?): Route =
        if (action == SessionStreamingService.ACTION_CLOSE_BACKGROUND) Route.CloseBackground
        else Route.Bootstrap
}
