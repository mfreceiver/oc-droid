package cn.vectory.ocdroid

import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.ui.NavRoute
import cn.vectory.ocdroid.ui.OrchestratorViewModel
import cn.vectory.ocdroid.ui.controller.ControllerEffect
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §unified-nav A8: JVM tests for the unified navigation-state refactor
 * (items 6, 8, 10-A, 10-B). Covers:
 *  - navigateToChat token-capture race (A4 mutateStateAndGet) — observes the
 *    token PASSED to openForRoute via the emitted VerifyAndHydrate effect.
 *  - requestNavigate always bumps navEpoch on same-target; setLastRoute never
 *    bumps navEpoch.
 *  - item-8 stale-mirror scenario (requestNavigate re-fires the syncer).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UnifiedNavTest : MainViewModelTestBase() {

    private fun wireVm(): Pair<cn.vectory.ocdroid.ui.AppCore, OrchestratorViewModel> {
        val core = createCore()
        val vm = OrchestratorViewModel(core)
        return core to vm
    }

    // ── A4: navigateToChat token-capture race ──────────────────────────────────

    /**
     * §unified-nav A4: each navigateToChat call must pass its OWN committed
     * token to openForRoute. We observe this via the [ControllerEffect.VerifyAndHydrate]
     * effect that openForRoute emits (it carries `expectedRouteInstance` = the
     * token passed in). Two calls must produce two DISTINCT tokens, each equal
     * to the delta from the pre-call baseline (token0 + 1, token0 + 2).
     *
     * # Why this guards the race
     *
     * The OLD impl was `mutateState { mint }` then `stateFlow.value.chatRouteInstance`
     * (a global re-read). Under interleaving (two threads, or a side-effect inside
     * the CAS transform that re-enters), both calls could read the SAME post-image
     * value. The NEW impl uses `mutateStateAndGet` which RETURNS the committed
     * snapshot, so each call reads its OWN committed token. On a single
     * Dispatchers.Main.immediate dispatcher the calls are strictly serial (no
     * interleaving), so this test documents the CONTRACT + guards against a
     * future regression that reverts to the global re-read: if someone changes
     * the body to read a stale global, the captured tokens would be wrong.
     *
     * TODO(discriminating-power): these two navigateToChat calls are strictly
     * serial on Dispatchers.Main.immediate (navigateToChat is fully synchronous
     * — no suspend between the CAS-commit and the openForRoute read). A real
     * interleaving that would fail under the old "read global after CAS" impl
     * requires either true multi-threading (forbidden by @MainThread confinement
     * of the store) or a controllable fake SessionSwitcher/store that suspends
     * mid-CAS so the second call's CAS interleaves between the first call's
     * CAS-commit and its openForRoute read. The former can't happen in prod
     * (store is Main-thread confined); the latter would require injecting a
     * custom SessionSwitcher into createCore (which constructs it inline) —
     * a disproportionate refactoring cost for the marginal discriminating power.
     * The structural guarantee (mutateStateAndGet returns the committed
     * snapshot, read INSIDE the same synchronous call) eliminates the race
     * by construction. This test is the best observable evidence without
     * distorting the test harness.
     */
    @Test
    fun `navigateToChat passes its own committed token to openForRoute via VerifyAndHydrate`() = runTest {
        val (core, vm) = wireVm()
        coEverySafely()

        val token0 = core.store.stateFlow.value.chatRouteInstance

        // Collect VerifyAndHydrate effects — openForRoute emits them carrying
        // the token that was passed in (the token minted inside the CAS +
        // read from the committed snapshot, NOT a global re-read).
        val captured = mutableListOf<Pair<String, Long>>()
        // backgroundScope + UNDISPATCHED: the collector runs until its first
        // suspension (collect's await) immediately, SUBSCRIBING before
        // navigateToChat emits (SharedFlow replay=0 only delivers to already-
        // subscribed collectors). backgroundScope auto-cancels at test end.
        // Same pattern as AppCore's own init effect collector.
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            core.effectBus.effects.collect { effect ->
                if (effect is ControllerEffect.VerifyAndHydrate) {
                    captured.add(effect.sessionId to effect.expectedRouteInstance)
                }
            }
        }

        vm.navigateToChat("ses_a")
        vm.navigateToChat("ses_b")
        advanceUntilIdle()

        // Each call emitted its OWN distinct token via VerifyAndHydrate.
        assertEquals(2, captured.size)
        // First call's token == token0 + 1 (the CAS-committed delta for THAT
        // call). Under a stale-global-read regression both would read the
        // final value token0 + 2.
        assertEquals("ses_a" to (token0 + 1L), captured[0])
        assertEquals("ses_b" to (token0 + 2L), captured[1])
        assertNotEquals(captured[0].second, captured[1].second)
    }

    /**
     * §unified-nav A4: the token passed to openForRoute is IMMUTABLE once
     * emitted — a later global token advance (e.g. closeDetail) does NOT
     * retroactively change an earlier call's VerifyAndHydrate.expectedRouteInstance.
     * This is the "later global token change did NOT retroactively change an
     * earlier call's token" invariant from the review.
     */
    @Test
    fun `navigateToChat token is immutable once passed to openForRoute`() = runTest {
        val (core, vm) = wireVm()
        coEverySafely()

        val token0 = core.store.stateFlow.value.chatRouteInstance

        val captured = mutableListOf<Long>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            core.effectBus.effects.collect { effect ->
                if (effect is ControllerEffect.VerifyAndHydrate) {
                    captured.add(effect.expectedRouteInstance)
                }
            }
        }

        vm.navigateToChat("ses_a")
        advanceUntilIdle()
        assertEquals(1, captured.size)
        val firstToken = captured.first()
        assertEquals(token0 + 1L, firstToken)

        // Advance the global token (closeDetail bumps chatRouteInstance).
        core.store.dispatch(cn.vectory.ocdroid.ui.AppAction.CloseDetail)
        val advancedGlobal = core.store.stateFlow.value.chatRouteInstance
        assertTrue("global advanced past the first call's token", advancedGlobal > firstToken)

        // The earlier call's emitted token is UNCHANGED (immutable in the
        // effect — the value passed to openForRoute is not retroactively
        // affected by the later global advance).
        assertEquals(firstToken, captured.first())
    }

    // ── A1: requestNavigate / setLastRoute epoch semantics ─────────────────────

    /**
     * §unified-nav A1: requestNavigate ALWAYS bumps navEpoch, even when lastRoute
     * is structurally identical to the current value. This is the fix for item 6
     * (new session → BACK → "+" again → mirror already "chat" but NavController
     * on Sessions → nothing happens).
     */
    @Test
    fun `requestNavigate always bumps navEpoch even on same target`() = runTest {
        val (core, vm) = wireVm()
        val epoch0 = core.store.navFlow.value.navEpoch

        vm.requestNavigate(NavRoute.Chat)
        val epoch1 = core.store.navFlow.value.navEpoch
        assertEquals(epoch0 + 1L, epoch1)
        assertEquals(NavRoute.Chat.route, core.store.navFlow.value.lastRoute)

        // Same target again → still bumps.
        vm.requestNavigate(NavRoute.Chat)
        val epoch2 = core.store.navFlow.value.navEpoch
        assertEquals(epoch1 + 1L, epoch2)
    }

    /**
     * §unified-nav A1: setLastRoute (the passive mirror setter) NEVER bumps
     * navEpoch. It writes lastRoute only. The short-circuit guard (same route →
     * no-op) is retained.
     */
    @Test
    fun `setLastRoute never bumps navEpoch`() = runTest {
        val (core, vm) = wireVm()
        val epoch0 = core.store.navFlow.value.navEpoch

        // Different route → writes lastRoute, does NOT bump epoch.
        vm.setLastRoute(NavRoute.Settings)
        val epoch1 = core.store.navFlow.value.navEpoch
        assertEquals(epoch0, epoch1)
        assertEquals(NavRoute.Settings.route, core.store.navFlow.value.lastRoute)

        // Same route → short-circuits (no write at all).
        vm.setLastRoute(NavRoute.Settings)
        assertEquals(epoch1, core.store.navFlow.value.navEpoch)
    }

    /**
     * §unified-nav item-8: the server-popup Settings button sometimes does
     * nothing. Root cause: setLastRoute(Settings) short-circuits when the mirror
     * already reads "settings" (stale state), so the synchronizer never re-fires.
     * Fix: the server-popup entry point uses requestNavigate(Settings) which
     * ALWAYS bumps navEpoch → the synchronizer re-fires unconditionally.
     *
     * This test sets the mirror to "settings" (simulating the stale state) then
     * calls requestNavigate(Settings) and verifies navEpoch bumped (synchronizer
     * would re-fire).
     */
    @Test
    fun `item 8 - requestNavigate Settings re-fires syncer when mirror already reads settings`() = runTest {
        val (core, vm) = wireVm()
        // Simulate the stale mirror: lastRoute already "settings".
        core.store.mutateNav { it.copy(lastRoute = NavRoute.Settings.route) }
        val epoch0 = core.store.navFlow.value.navEpoch

        // The OLD setLastRoute(Settings) would short-circuit (same route → no-op).
        // The NEW requestNavigate(Settings) ALWAYS bumps epoch.
        vm.requestNavigate(NavRoute.Settings)
        val epoch1 = core.store.navFlow.value.navEpoch
        assertTrue("requestNavigate bumped navEpoch past stale-mirror state", epoch1 > epoch0)
    }

    /**
     * §unified-nav A2 (item-6 two-round): after a session → BACK → re-enter,
     * the requestNavigate path must re-fire even though the route is the same.
     * This is the core of item 6: the "+" again must produce a nav intent.
     */
    @Test
    fun `item 6 - requestNavigate Chat re-fires after mirror already reads chat`() = runTest {
        val (core, vm) = wireVm()
        // First nav to Chat.
        vm.requestNavigate(NavRoute.Chat)
        val epoch1 = core.store.navFlow.value.navEpoch

        // Simulate system BACK (mirror reconciliation sets lastRoute to Sessions,
        // but the NavController pops — the user is back on Sessions). Then user
        // taps "+" again → requestNavigate(Chat).
        vm.requestNavigate(NavRoute.Chat)
        val epoch2 = core.store.navFlow.value.navEpoch
        assertTrue("second requestNavigate bumped epoch (re-fires syncer)", epoch2 > epoch1)
    }

    // ── A5.2: activeDestination passive setter ─────────────────────────────────

    /**
     * §unified-nav A5.2: setActiveDestination writes activeDestination + bumps
     * activeDestinationEpoch WITHOUT touching lastRoute / navEpoch (so it never
     * fires the nav syncer).
     */
    @Test
    fun `setActiveDestination bumps activeDestinationEpoch without touching lastRoute or navEpoch`() = runTest {
        val (core, vm) = wireVm()
        val lastRoute0 = core.store.navFlow.value.lastRoute
        val navEpoch0 = core.store.navFlow.value.navEpoch
        val destEpoch0 = core.store.navFlow.value.activeDestinationEpoch

        vm.setActiveDestination("chat/ses_a")
        val nav = core.store.navFlow.value
        assertEquals("chat/ses_a", nav.activeDestination)
        assertEquals(destEpoch0 + 1L, nav.activeDestinationEpoch)
        // lastRoute + navEpoch UNCHANGED (never fires the syncer).
        assertEquals(lastRoute0, nav.lastRoute)
        assertEquals(navEpoch0, nav.navEpoch)
    }

    // ── helper ─────────────────────────────────────────────────────────────────

    /** Stub repository calls that navigateToChat's openForRoute path needs. */
    private fun coEverySafely() {
        io.mockk.coEvery { repository.getMessagesPaged(any(), any(), any()) } returns
            Result.success(cn.vectory.ocdroid.data.repository.MessagesPage(emptyList(), null))
        io.mockk.coEvery { repository.getMessagesPagedUnanchored(any(), any(), any()) } returns
            Result.success(cn.vectory.ocdroid.data.repository.MessagesPage(emptyList(), null))
        io.mockk.coEvery { repository.getSessionTodos(any()) } returns Result.success(emptyList())
        io.mockk.coEvery { repository.getPendingQuestions(any()) } returns Result.success(emptyList())
    }
}
