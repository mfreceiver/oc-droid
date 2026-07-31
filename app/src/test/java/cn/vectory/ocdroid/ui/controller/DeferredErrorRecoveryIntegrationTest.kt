package cn.vectory.ocdroid.ui.controller

import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.MessageWithParts
import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.data.model.SlimSessionLastError
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.data.state.AuthorityOp
import cn.vectory.ocdroid.data.state.EntryOrigin
import cn.vectory.ocdroid.data.state.Freshness
import cn.vectory.ocdroid.data.state.ScopeKey
import cn.vectory.ocdroid.data.state.SessionEntry
import cn.vectory.ocdroid.ui.AppAction
import cn.vectory.ocdroid.ui.SharedStateStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * §P0-E(c) integration-level test for the FULL two-phase GET-fallback
 * error recovery chain:
 *
 * 1. [cn.vectory.ocdroid.ui.AuthorityReducer] detects a busy/retry →
 *    terminal-idle transition and sets `pendingErrorCheck`.
 * 2. [ErrorRecoveryCoordinator] collector finds `pendingErrorCheck` entries
 *    where `sessionErrorsById[sid] != null` and `lastAssistant` has no error.
 * 3. Coordinator calls `repository.getMessages(sid, limit=50)` to locate the
 *    error-bearing assistant message (server-identified, per B2).
 * 4. Coordinator dispatches [AppAction.ErrorLocalizationSettled], which
 *    clears the marker and attaches the durable error to the correct message.
 *
 * Uses a real [SharedStateStore] + mock [OpenCodeRepository] so the
 * coordinator's stateFlow collector fires on real state transitions. The
 * coordinator runs on an [UnconfinedTestDispatcher] so it processes emissions
 * eagerly without needing virtual-time advancement for the collector itself.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DeferredErrorRecoveryIntegrationTest {

    private lateinit var store: SharedStateStore
    private lateinit var repository: OpenCodeRepository
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        store = SharedStateStore()
        repository = mockk(relaxed = true)
        scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun makeCoordinator(): ErrorRecoveryCoordinator = ErrorRecoveryCoordinator(
        scope = scope,
        store = store,
        repository = repository,
    )

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private fun err(name: String) = Message.MessageError(name = name)

    private fun assistantMsg(id: String, error: Message.MessageError? = null) =
        Message(id = id, role = "assistant", error = error)

    private fun userMsg(id: String) = Message(id = id, role = "user")

    private fun msgWithParts(
        mid: String,
        role: String = "assistant",
        error: Message.MessageError? = null,
        createdAt: Long = 0L,
    ) = MessageWithParts(
        info = Message(id = mid, role = role, error = error,
            time = Message.TimeInfo(created = createdAt)),
    )

    /** Seed authority with a single busy entry for [sid]. */
    private fun seedBusyAuthority(sid: String, scope: ScopeKey = ScopeKey("", "")) {
        store.mutateState { s ->
            s.copy(
                authority = s.authority.copy(
                    bySid = mapOf(sid to SessionEntry(
                        status = SessionStatus("busy"),
                        serverRound = null,
                        optimisticClaim = null,
                        origin = EntryOrigin.SSE_SLIM,
                        freshness = Freshness.Fresh,
                        updatedMonotonic = 0L,
                        workdir = null,
                        scopeKey = scope,
                    )),
                ),
            )
        }
    }

    /** Dispatch an [AuthorityOp.ApplyEvent] that transitions [sid] busy → idle. */
    private fun transitionToIdle(sid: String, scope: ScopeKey = ScopeKey("", "")) {
        store.dispatch(AppAction.AuthorityEvent(
            op = AuthorityOp.ApplyEvent(
                sid = sid,
                status = SessionStatus("idle"),
                origin = EntryOrigin.SSE_SLIM,
                scopeKey = scope,
                connectionMonotonicMs = 1000L,
            ),
        ))
    }

    // ── Positive: full chain localizes the error ──────────────────────────────

    @Test
    fun `deferred drain chain localizes durable error from busy-to-idle transition via GET fallback`() = runTest {
        val sid = "S1"
        val errorMsgId = "m99"
        val errObj = err("rate_limit")

        // ── Seed ──────────────────────────────────────────────────────────
        // Authority: S1 is busy.
        seedBusyAuthority(sid)
        // SessionList: S1 has a durable error banner.
        store.mutateSessionList { it.copy(
            sessionErrorsById = mapOf(sid to SlimSessionLastError(name = "rate_limit")),
        )}
        // Chat: user is viewing S1; messages exist but last assistant has NO error.
        store.mutateChat { it.copy(
            currentSessionId = sid,
            messages = listOf(userMsg("m1"), assistantMsg(errorMsgId)),
        )}

        // ── Phase 1: busy→idle transition → AuthorityReducer sets pendingErrorCheck ──
        transitionToIdle(sid)

        // Verify the reducer set the marker.
        val stateAfterTransition = store.stateFlow.value
        assertTrue(
            "pendingErrorCheck contains $sid after busy→idle transition",
            sid in stateAfterTransition.chat.pendingErrorCheck,
        )

        // ── Phase 2: mock GET to return messages where last assistant has a durable error ──
        coEvery { repository.getMessages(sid, limit = 50) } returns Result.success(
            listOf(
                msgWithParts("m1", role = "user", createdAt = 1L),
                msgWithParts(errorMsgId, role = "assistant", error = errObj, createdAt = 2L),
            ),
        )

        // Create coordinator — collector fires immediately on UnconfinedTestDispatcher.
        makeCoordinator()
        advanceUntilIdle()

        // ── Verify recovery ───────────────────────────────────────────────
        val finalState = store.stateFlow.value

        // Marker cleared by reduceErrorLocalizationSettled.
        assertFalse(
            "pendingErrorCheck cleared for $sid after GET fallback",
            sid in finalState.chat.pendingErrorCheck,
        )

        // Durable error attached to the server-identified message.
        assertEquals(
            "durable error attached to message $errorMsgId",
            errObj,
            finalState.chat.messages.find { it.id == errorMsgId }?.error,
        )

        // GET was called exactly once for this sid.
        coVerify(exactly = 1) { repository.getMessages(sid, limit = 50) }
    }

    // ── Negative: no session error banner → no drain ─────────────────────────

    @Test
    fun `deferred drain does NOT fire when sessionErrorsById has no entry for the transitioning session`() = runTest {
        val sid = "S2"

        // ── Seed ──────────────────────────────────────────────────────────
        // Authority: S2 is busy.
        seedBusyAuthority(sid)
        // sessionErrorsById is EMPTY — no error banner for S2.
        // Chat: user is viewing S2 with a single assistant message (no error).
        store.mutateChat { it.copy(
            currentSessionId = sid,
            messages = listOf(assistantMsg("m1")),
        )}

        // ── Phase 1: busy→idle transition → AuthorityReducer sets pendingErrorCheck ──
        transitionToIdle(sid)

        // Verify the reducer set the marker.
        val stateAfterTransition = store.stateFlow.value
        assertTrue(
            "pendingErrorCheck contains $sid after busy→idle transition",
            sid in stateAfterTransition.chat.pendingErrorCheck,
        )

        // Mock getMessages for ANY sid (should never be called for S2).
        coEvery { repository.getMessages(any(), limit = any()) } returns Result.success(emptyList())

        // Create coordinator — should detect no sessionErrorsById entry → skip.
        makeCoordinator()
        advanceUntilIdle()

        // ── Verify no drain fired ─────────────────────────────────────────
        // getMessages was NEVER called for S2.
        coVerify(exactly = 0) { repository.getMessages(sid, any()) }

        // pendingErrorCheck still contains S2 (not drained — nothing to localize).
        val finalState = store.stateFlow.value
        assertTrue(
            "pendingErrorCheck still contains $sid (no drain fired)",
            sid in finalState.chat.pendingErrorCheck,
        )
    }
}
