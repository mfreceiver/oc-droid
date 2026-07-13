package cn.vectory.ocdroid.service

import cn.vectory.ocdroid.service.StartCommandRouter.Route
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * FGS spec §5 / §16-U1 — pure-JVM unit tests for [StartCommandRouter].
 *
 * Verifies the three routing decisions
 * [SessionStreamingService.onStartCommand] makes:
 *  - ACTION_CLOSE_BACKGROUND → [Route.CloseBackground] (§16-U1 teardown);
 *  - null action (sticky rebuild) → [Route.Bootstrap] (§5 START_STICKY body);
 *  - any other action → [Route.Bootstrap] (forward-compat fall-through).
 *
 * Pure JVM — no Robolectric, no Android Intent construction. The Service's
 * `onStartCommand` is a 3-line forwarder whose correctness depends only on
 * this routing table, so covering the table here is the high-leverage test.
 */
class StartCommandRouterTest {

    @Test
    fun `ACTION_CLOSE_BACKGROUND routes to CloseBackground (section 16-U1)`() {
        assertEquals(
            Route.CloseBackground,
            StartCommandRouter.routeFor(SessionStreamingService.ACTION_CLOSE_BACKGROUND),
        )
    }

    @Test
    fun `null action routes to Bootstrap (section 5 sticky rebuild)`() {
        assertEquals(
            "sticky rebuild (null Intent) takes the §5 bootstrap path",
            Route.Bootstrap,
            StartCommandRouter.routeFor(null),
        )
    }

    @Test
    fun `unknown action falls through to Bootstrap (forward-compat)`() {
        assertEquals(
            Route.Bootstrap,
            StartCommandRouter.routeFor("android.intent.action.SOME_FUTURE"),
        )
        assertEquals(
            Route.Bootstrap,
            StartCommandRouter.routeFor(""),
        )
    }

    @Test
    fun `ACTION_CLOSE_BACKGROUND literal matches the spec-expected form`() {
        // Guard against an accidental rename of the const: the production
        // PendingIntent carries this exact string, and the manifest / tests
        // expect the same literal. If this fails, the routing table and the
        // PendingIntent builder have drifted.
        assertEquals(
            "cn.vectory.ocdroid.action.CLOSE_BACKGROUND",
            SessionStreamingService.ACTION_CLOSE_BACKGROUND,
        )
    }
}
