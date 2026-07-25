package cn.vectory.ocdroid

import cn.vectory.ocdroid.ui.AppAction
import cn.vectory.ocdroid.ui.OrchestratorViewModel
import cn.vectory.ocdroid.ui.StoreState
import cn.vectory.ocdroid.ui.reduce
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §chat-list-detail §12 B0 / §7.2: regression guard for the freshness-token
 * machinery (route-instance counter on [StoreState.chatRouteInstance]) that
 * B0.5/B2 build the §7.2 A→B→A stale-load CAS on.
 *
 * Covers the three monotonicity defects both judges flagged on the initial B0:
 *  - **CloseDetail advances (not resets)**: resetting to 0 let a
 *    close→reopen-of-same-session reuse a token, so a stale load from the
 *    first incarnation passed the CAS after reopen. Fix: advance via `+1L`.
 *  - **navigateToChat mints inside the CAS transform**: a pre-read outside
 *    `mutateState` captured a stale value the CAS retry couldn't fix, so two
 *    concurrent calls could mint the same token. Fix: compute `+1L` inside
 *    the lambda (mirrors `SharedStateStore.mutateSseConnected`).
 *  - **SelectConversation/DetailMissing use maxOf**: blindly assigning the
 *    caller-supplied routeInstance let a stale/out-of-order action regress
 *    the counter. Fix: `maxOf(current, action.routeInstance)` — stale = no-op.
 *
 * Two test groups:
 *  1. **Pure reducer tests** — `reduce(snapshot, action)` returns a new
 *     [StoreState] with the correct monotonic token behavior. No store, no
 *     AppCore — just the pure function (matches [AppActionReducerTest]'s style).
 *  2. **navigateToChat tests** — construct an [OrchestratorViewModel] via
 *     [createCore] and assert the token is strictly monotonic across
 *     sequential calls + close→reopen (no reuse). The mint happens inside
 *     `mutateState`'s CAS, so concurrent calls cannot collide.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class B0FreshnessTokenTest : MainViewModelTestBase() {

    // ── 1. Pure reducer tests ──────────────────────────────────────────────

    @Test
    fun `reduce CloseDetail advances the token (never resets to 0)`() {
        // The defect: a reset to 0 let close→reopen reuse a token. Fix: advance.
        val prior = StoreState.initial().copy(chatRouteInstance = 5L)
        val out = reduce(prior, AppAction.CloseDetail)
        assertEquals(
            "CloseDetail must ADVANCE past 5, not reset to 0",
            6L,
            out.chatRouteInstance,
        )
    }

    @Test
    fun `reduce CloseDetail from zero advances to one`() {
        val prior = StoreState.initial().copy(chatRouteInstance = 0L)
        val out = reduce(prior, AppAction.CloseDetail)
        assertEquals(1L, out.chatRouteInstance)
    }

    @Test
    fun `reduce SelectConversation stamps the new incarnation when newer`() {
        val prior = StoreState.initial().copy(chatRouteInstance = 5L)
        val out = reduce(prior, AppAction.SelectConversation(sessionId = "ses_A", routeInstance = 7L))
        assertEquals(7L, out.chatRouteInstance)
    }

    @Test
    fun `reduce SelectConversation never regresses (stale action ignored via maxOf)`() {
        // The defect: blindly assigning action.routeInstance let a stale
        // SelectConversation(routeInstance=3) regress the counter from 10 to 3.
        // Fix: maxOf(current, action.routeInstance) — a stale action is a no-op.
        val prior = StoreState.initial().copy(chatRouteInstance = 10L)
        val out = reduce(prior, AppAction.SelectConversation(sessionId = "ses_A", routeInstance = 3L))
        assertEquals(
            "stale SelectConversation(routeInstance=3) must NOT regress the live counter (10)",
            10L,
            out.chatRouteInstance,
        )
    }

    @Test
    fun `reduce SelectConversation idempotent when routeInstance equals current`() {
        val prior = StoreState.initial().copy(chatRouteInstance = 7L)
        val out = reduce(prior, AppAction.SelectConversation(sessionId = "ses_A", routeInstance = 7L))
        assertEquals(7L, out.chatRouteInstance)
    }

    @Test
    fun `reduce DetailMissing stamps the new incarnation when newer`() {
        val prior = StoreState.initial().copy(chatRouteInstance = 5L)
        val out = reduce(prior, AppAction.DetailMissing(sessionId = "ses_A", routeInstance = 8L))
        assertEquals(8L, out.chatRouteInstance)
    }

    @Test
    fun `reduce DetailMissing never regresses (stale action ignored via maxOf)`() {
        val prior = StoreState.initial().copy(chatRouteInstance = 10L)
        val out = reduce(prior, AppAction.DetailMissing(sessionId = "ses_A", routeInstance = 3L))
        assertEquals(
            "stale DetailMissing(routeInstance=3) must NOT regress the live counter (10)",
            10L,
            out.chatRouteInstance,
        )
    }

    @Test
    fun `close then stale SelectConversation does not regress past the advance`() {
        // Sequence: token=5 → CloseDetail advances to 6 → a stale
        // SelectConversation(routeInstance=5) arrives late → maxOf(6, 5)=6
        // (no regress; the close-advance holds).
        var state: StoreState = StoreState.initial().copy(chatRouteInstance = 5L)
        state = reduce(state, AppAction.CloseDetail)
        assertEquals(6L, state.chatRouteInstance)
        state = reduce(state, AppAction.SelectConversation(sessionId = "ses_A", routeInstance = 5L))
        assertEquals(
            "stale SelectConversation(5) after CloseDetail advance must not regress",
            6L,
            state.chatRouteInstance,
        )
    }

    // ── 2. navigateToChat monotonicity (atomic in-transform mint) ──────────

    @Test
    fun `navigateToChat mints strictly increasing tokens across sequential calls`() = runTest {
        val core = newCore()
        val vm = OrchestratorViewModel(core)

        vm.navigateToChat("ses_A")
        val t1 = core.store.stateFlow.value.chatRouteInstance

        vm.navigateToChat("ses_A")
        val t2 = core.store.stateFlow.value.chatRouteInstance

        vm.navigateToChat("ses_B")
        val t3 = core.store.stateFlow.value.chatRouteInstance

        assertTrue("T1 must be > 0 (minted from initial 0)", t1 > 0L)
        assertTrue("T1 < T2 (strictly increasing)", t1 < t2)
        assertTrue("T2 < T3 (strictly increasing)", t2 < t3)
    }

    @Test
    fun `navigateToChat then CloseDetail then navigateToChat produces distinct tokens (no reuse)`() = runTest {
        // The critical §7.2 property: close→reopen of the SAME session must
        // NOT reuse a token. A reset-to-0 CloseDetail would make reopen mint
        // the same token as the first open (both = initial+1), letting a
        // stale load from the first incarnation pass the CAS after reopen.
        val core = newCore()
        val vm = OrchestratorViewModel(core)

        vm.navigateToChat("ses_A")
        val t1 = core.store.stateFlow.value.chatRouteInstance

        // CloseDetail dispatched (B0.5+ does this through the VM; here we
        // drive the reducer directly via the store to assert the
        // close→reopen no-reuse property the freshness CAS depends on).
        core.store.dispatch(AppAction.CloseDetail)
        val tClose = core.store.stateFlow.value.chatRouteInstance

        vm.navigateToChat("ses_A")
        val t2 = core.store.stateFlow.value.chatRouteInstance

        assertTrue("CloseDetail advances (tClose > t1)", tClose > t1)
        assertTrue("reopen token strictly > first-open token (no reuse)", t2 > t1)
        assertNotEquals(
            "close→reopen of same session must not reuse a token (§7.2 不可复用 token)",
            t1,
            t2,
        )
        assertTrue("reopen token strictly > close token", t2 > tClose)
    }

    @Test
    fun `navigateToChat with null sessionId is deferred in B0_5 (chat new path not yet wired)`() = runTest {
        val core = newCore()
        val vm = OrchestratorViewModel(core)

        vm.navigateToChat("ses_A")
        val t1 = core.store.stateFlow.value.chatRouteInstance

        // B0.5 scope: only the chat/{id} path is wired (Sessions->tap->chat).
        // The chat/new (null sessionId, D4) path needs draft/workdir handling
        // and is DEFERRED — navigateToChat(null) early-returns (no-op) in B0.5.
        // A later batch (B1/B3) wires chat/new; per the §7.2 sole-minter
        // principle navigateToChat(null) must THEN mint a monotonic token
        // (t2 > t1) — re-enable that assertion when chat/new is wired.
        vm.navigateToChat(null)
        val t2 = core.store.stateFlow.value.chatRouteInstance

        assertEquals("chat/new deferred in B0.5 — navigateToChat(null) is a no-op", t1, t2)
    }

    @Test
    fun `navigateToChat sets currentSessionId, mints token, clears content for the chat-id route`() = runTest {
        val core = newCore()
        val vm = OrchestratorViewModel(core)

        // B1: navigateToChat writes navState.lastRoute = "chat/$sid" (the
        // unified nav mechanism — the B0.5 chatNavEvents SharedFlow + the
        // LaunchedEffect(requestedRoute) mirror are both removed). Assert
        // the observable state effects.
        val before = core.store.stateFlow.value.chatRouteInstance
        vm.navigateToChat("ses_abc123")
        val state = core.store.stateFlow.value
        assertEquals("ses_abc123", state.chat.currentSessionId)
        assertTrue("token minted (sole-minter, in-transform)", state.chatRouteInstance > before)
        assertEquals(null, state.chat.content)
    }
}
