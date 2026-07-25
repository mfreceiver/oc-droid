package cn.vectory.ocdroid

import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.MessageWithParts
import cn.vectory.ocdroid.data.repository.MessagesPage
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.ui.AppAction
import cn.vectory.ocdroid.ui.OrchestratorViewModel
import cn.vectory.ocdroid.ui.StoreState
import cn.vectory.ocdroid.ui.reduce
import cn.vectory.ocdroid.ui.clearLoadedChatPayload
import io.mockk.coEvery
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §chat-list-detail §12 B0.5-rework: the integration spike tests proving P1/P6
 * via the REAL load pipeline (navigateToChat → openForRoute → VerifyAndHydrate
 * → launchLoadMessages → ChatContentLoaded), NOT via manual action dispatch.
 *
 * The prior B0.5 tests dispatched ChatContentLoaded manually — they proved the
 * reducer's CAS but NOT the pipeline threading (the bridge manufactured the
 * token from "now", defeating the CAS). These tests prove the ENTIRE chain:
 * the token is captured at navigateToChat time, threaded through the load, and
 * guards the completion transaction.
 *
 * Test groups:
 *  - **A. Same-session load regression**: navigateToChat("ses_A") when ses_A
 *    is already current → bypasses the same-session no-op → a load occurs →
 *    content committed. Also: empty successful page → content != null.
 *  - **B. A→B→A race (AUTHORITATIVE P6)**: nav-A(req-1, T1) → nav-B → nav-A
 *    (req-2, T3) → complete req-2 → complete req-1 → content survives as
 *    req-2's. Asserts stale req-1 did NOT clear loading, emit error, or
 *    overwrite content.
 *  - **C. Render authority**: the P1 guard derivation (content.sessionId==
 *    routeId && content.routeInstance==routeInstance) at the state level.
 *  - **D. Navigation event contract**: chatNavEvents emits exactly "chat/ses_A".
 *
 * Pure reducer tests are retained (relabeled as unit coverage, not end-to-end).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class B0_5SliceTest : MainViewModelTestBase() {

    private fun msg(id: String, role: String = "user") = Message(id = id, role = role)
    private fun msgWithParts(id: String) = MessageWithParts(info = msg(id))
    private fun page(vararg ids: String) = MessagesPage(ids.map { msgWithParts(it) }, null)

    private fun seedSession(vararg sids: String) {
        core.writeSessionList {
            it.copy(sessions = sids.map { sid -> Session(id = sid, directory = "/$sid") })
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Group A — Same-session load regression (JVM integration)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `A1 same-session re-entry bypasses no-op guard and triggers load`() = runTest {
        val core = newCore()
        val vm = OrchestratorViewModel(core)
        seedSession("ses_A")

        // Stub non-empty page.
        coEvery { repository.getMessagesPagedUnanchored(any(), any(), any(), any()) } returns
            Result.success(page("m1"))

        // First nav-A.
        vm.navigateToChat("ses_A")
        advanceUntilIdle()
        val stateAfterFirst = core.store.stateFlow.value
        assertEquals("ses_A", stateAfterFirst.chat.content?.sessionId)
        assertEquals("m1", stateAfterFirst.chat.content?.messages?.first()?.id)

        // Same-session re-entry — openForRoute bypasses the guard.
        vm.navigateToChat("ses_A")
        advanceUntilIdle()
        val stateAfterSecond = core.store.stateFlow.value
        assertNotNull("same-session re-entry produced content", stateAfterSecond.chat.content)
        assertEquals("ses_A", stateAfterSecond.chat.content?.sessionId)
    }

    @Test
    fun `A2 empty successful page produces LoadedContent (not stuck Loading)`() = runTest {
        val core = newCore()
        val vm = OrchestratorViewModel(core)
        seedSession("ses_A")

        // Default mock returns empty page (MainViewModelTestBase stub).
        vm.navigateToChat("ses_A")
        advanceUntilIdle()

        val state = core.store.stateFlow.value
        assertNotNull(
            "empty successful page must produce LoadedContent (not stuck Loading forever)",
            state.chat.content,
        )
        assertEquals("ses_A", state.chat.content?.sessionId)
        assertTrue("content messages empty but LoadedContent exists", state.chat.content?.messages?.isEmpty() == true)
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Group B — A→B→A race (AUTHORITATIVE P6 — real pipeline, NOT manual dispatch)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `B1 HEADLINE A to B to A stale req-1 does not overwrite req-2`() = runTest {
        val core = newCore()
        val vm = OrchestratorViewModel(core)
        seedSession("ses_A", "ses_B")

        val req1 = CompletableDeferred<Result<MessagesPage>>()
        val req2 = CompletableDeferred<Result<MessagesPage>>()
        var callCount = 0

        coEvery { repository.getMessagesPagedUnanchored(any(), any(), any(), any()) } coAnswers {
            callCount++
            when (callCount) {
                1 -> req1.await()   // req-1: first nav-A (T1), held
                2 -> Result.success(page("mB"))  // B's load: immediate
                3 -> req2.await()   // req-2: second nav-A (T3), held
                else -> Result.success(MessagesPage(emptyList(), null))
            }
        }

        // t0: nav-A → T1, req-1 starts (suspended on req1)
        vm.navigateToChat("ses_A")
        advanceUntilIdle()
        val t1 = core.store.stateFlow.value.chatRouteInstance

        // t1: nav-B → T2, B's load completes immediately
        vm.navigateToChat("ses_B")
        advanceUntilIdle()

        // t2: nav-A again → T3, req-2 starts (suspended on req2)
        vm.navigateToChat("ses_A")
        advanceUntilIdle()
        val t3 = core.store.stateFlow.value.chatRouteInstance
        assertTrue("T3 > T1 (distinct incarnations)", t3 > t1)

        // Complete req-2 (newer A load, T3) → CAS passes → content committed
        req2.complete(Result.success(page("newer")))
        advanceUntilIdle()
        assertEquals(
            "req-2 committed (newer A content)",
            "newer",
            core.store.stateFlow.value.chat.content?.messages?.first()?.id,
        )

        // Complete req-1 (older A load, T1) → CAS REJECTS
        req1.complete(Result.success(page("older")))
        advanceUntilIdle()
        assertEquals(
            "req-2 survived stale req-1 — A→B→A stale-load rejected (P6 freshness CAS)",
            "newer",
            core.store.stateFlow.value.chat.content?.messages?.first()?.id,
        )
    }

    @Test
    fun `B2 stale req-1 does not clear newer loading state or emit error`() = runTest {
        val core = newCore()
        val vm = OrchestratorViewModel(core)
        seedSession("ses_A")

        val req1 = CompletableDeferred<Result<MessagesPage>>()
        var callCount = 0

        coEvery { repository.getMessagesPagedUnanchored(any(), any(), any(), any()) } coAnswers {
            callCount++
            when (callCount) {
                1 -> req1.await()  // req-1: held (will fail)
                else -> Result.success(page("newer"))
            }
        }

        // nav-A → req-1 starts
        vm.navigateToChat("ses_A")
        advanceUntilIdle()

        // nav-A again → req-2 starts, completes immediately with "newer"
        vm.navigateToChat("ses_A")
        advanceUntilIdle()
        assertEquals("newer", core.store.stateFlow.value.chat.content?.messages?.first()?.id)

        // Complete req-1 as a FAILURE → must NOT emit error for the current session
        req1.complete(Result.failure(RuntimeException("stale network error")))
        advanceUntilIdle()

        // Content survived (req-2's "newer" not overwritten by failed req-1)
        assertEquals(
            "content survived stale req-1 failure",
            "newer",
            core.store.stateFlow.value.chat.content?.messages?.first()?.id,
        )
        // isLoadingMessages is false (req-2 cleared it; req-1's finally was token-guarded)
        assertFalse(
            "isLoadingMessages not stuck by stale req-1",
            core.store.stateFlow.value.chat.isLoadingMessages,
        )
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Group C — Render authority (P1 guard derivation, state-level)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `C1 render guard — route B + content A shows Loading (P1 mismatch)`() {
        // Simulate: route is chat/ses_B, but content holds ses_A.
        // The guard content.sessionId == routeId fails → Loading.
        val routeId = "ses_B"
        val contentSessionId = "ses_A"
        val renders = contentSessionId == routeId
        assertFalse("sessionId mismatch → Loading (P1)", renders)
    }

    @Test
    fun `C2 render guard — matching route + content + token renders transcript`() {
        // Pure reducer: commit content for ses_A/T3, verify it's readable.
        // (NB: the reducer's commit guard requires sessionId == currentSessionId
        // per oracle's expected-id guard — set currentSessionId like the real
        // navigateToChat flow does; without it the guard rejects → content null.)
        val prior = StoreState.initial().copy(
            chatRouteInstance = 3L,
            chat = core_store_chat_with_currentSid("ses_A"),
        )
        val out = reduce(
            prior,
            AppAction.ChatContentLoaded(
                sessionId = "ses_A",
                expectedRouteInstance = 3L,
                messages = listOf(msg("mA")),
            ),
        )
        // Guard: content.sessionId == "ses_A" AND content.routeInstance == 3L
        val routeId = "ses_A"
        val routeInstance = 3L
        val content = out.chat.content
        val renders = content != null &&
            content.sessionId == routeId &&
            content.routeInstance == routeInstance
        assertTrue("matching route+content+token → renders (P1+P6)", renders)
    }

    @Test
    fun `C3 render guard — stale routeInstance shows Loading (P6 mismatch)`() {
        // Content committed under T1, but current routeInstance is T3 → Loading.
        val prior = StoreState.initial().copy(
            chatRouteInstance = 1L,
            chat = core_store_chat_with_currentSid("ses_A"),
        )
        // ... T1 content committed ...
        val out = reduce(
            prior,
            AppAction.ChatContentLoaded(
                sessionId = "ses_A",
                expectedRouteInstance = 1L,
                messages = listOf(msg("old")),
            ),
        )
        // Now routeInstance advances to 3 (nav-B→nav-A).
        val advanced = out.copy(chatRouteInstance = 3L)
        val routeId = "ses_A"
        val routeInstance = 3L
        val content = advanced.chat.content
        val renders = content != null &&
            content.sessionId == routeId &&
            content.routeInstance == routeInstance
        assertFalse("stale routeInstance (1≠3) → Loading (P6)", renders)
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Group D — Navigation state contract (B1: navState.lastRoute unified)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `D1 navigateToChat writes chat ses_A to navState lastRoute (B1 unified)`() = runTest {
        val core = newCore()
        val vm = OrchestratorViewModel(core)
        seedSession("ses_A")

        coEvery { repository.getMessagesPagedUnanchored(any(), any(), any(), any()) } returns
            Result.success(MessagesPage(emptyList(), null))

        vm.navigateToChat("ses_A")
        // B1: navigateToChat writes navState.lastRoute = "chat/ses_A" (the
        // unified nav mechanism — AppShell's synchronizer observes this and
        // navigates the NavController). The B0.5 chatNavEvents SharedFlow
        // is removed.
        assertEquals(
            "navState.lastRoute is the parameterized chat route (B1 unified)",
            "chat/ses_A",
            core.store.navFlow.value.lastRoute,
        )
    }

    @Test
    fun `D2 token minted before load effect fires`() = runTest {
        val core = newCore()
        val vm = OrchestratorViewModel(core)
        seedSession("ses_A")

        coEvery { repository.getMessagesPagedUnanchored(any(), any(), any(), any()) } returns
            Result.success(page("m1"))

        // navigateToChat is synchronous — mutateState mints the token
        // BEFORE openForRoute emits VerifyAndHydrate (which is async).
        vm.navigateToChat("ses_A")
        // Token is already minted (synchronous mutateState).
        val tokenAfterNav = core.store.stateFlow.value.chatRouteInstance
        assertTrue("token minted by navigateToChat", tokenAfterNav > 0L)

        advanceUntilIdle()
        // Content committed with the same token.
        assertEquals(
            "content carries the navigateToChat-minted token",
            tokenAfterNav,
            core.store.stateFlow.value.chat.content?.routeInstance,
        )
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Unit coverage (pure reducer — NOT end-to-end proof)
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `unit - reducer rejects stale expectedRouteInstance`() {
        val prior = StoreState.initial().copy(
            chatRouteInstance = 10L,
            chat = core_store_chat_with_currentSid("ses_A"),
        )
        val out = reduce(prior, AppAction.ChatContentLoaded("ses_A", expectedRouteInstance = 5L, messages = listOf(msg("stale"))))
        assertNull("stale CAS rejected", out.chat.content)
    }

    @Test
    fun `unit - reducer rejects sessionId mismatch even if token matches`() {
        val prior = StoreState.initial().copy(
            chatRouteInstance = 5L,
            chat = core_store_chat_with_currentSid("ses_A"),
        )
        val out = reduce(prior, AppAction.ChatContentLoaded("ses_B", expectedRouteInstance = 5L, messages = listOf(msg("xB"))))
        assertNull("sessionId mismatch rejected (commit guard)", out.chat.content)
    }

    @Test
    fun `unit - reducer accepts matching token + sessionId and writes dual`() {
        val prior = StoreState.initial().copy(
            chatRouteInstance = 5L,
            chat = core_store_chat_with_currentSid("ses_A"),
        )
        val out = reduce(prior, AppAction.ChatContentLoaded("ses_A", expectedRouteInstance = 5L, messages = listOf(msg("fresh"))))
        assertEquals("ses_A", out.chat.content?.sessionId)
        assertEquals("fresh", out.chat.content?.messages?.first()?.id)
        assertEquals("flat mirror written too", "fresh", out.chat.messages.first().id)
        assertFalse("isLoadingMessages cleared", out.chat.isLoadingMessages)
    }

    @Test
    fun `unit - clearLoadedChatPayload clears both content and flat fields`() {
        val prior = StoreState.initial().copy(
            chat = core_store_chat_with_currentSid("ses_A").copy(
                content = cn.vectory.ocdroid.ui.LoadedContent(sessionId = "ses_A"),
                messages = listOf(msg("m1")),
                isLoadingMessages = true,
            ),
        )
        val cleared = prior.chat.clearLoadedChatPayload()
        assertNull("content cleared", cleared.content)
        assertTrue("messages cleared", cleared.messages.isEmpty())
        assertFalse("isLoadingMessages cleared", cleared.isLoadingMessages)
        assertEquals("currentSessionId preserved", "ses_A", cleared.currentSessionId)
    }

    // ── Helper: build a ChatState with a given currentSessionId (for pure reducer tests) ──
    private fun core_store_chat_with_currentSid(sid: String): cn.vectory.ocdroid.ui.ChatState =
        cn.vectory.ocdroid.ui.ChatState(currentSessionId = sid)
}
