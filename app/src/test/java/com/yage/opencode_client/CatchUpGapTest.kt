package com.yage.opencode_client

import com.yage.opencode_client.data.model.Message
import com.yage.opencode_client.data.model.MessageWithParts
import com.yage.opencode_client.data.repository.MessagesPage
import com.yage.opencode_client.data.repository.OpenCodeRepository
import com.yage.opencode_client.ui.AppState
import com.yage.opencode_client.ui.launchCatchUp
import com.yage.opencode_client.ui.launchCloseGap
import com.yage.opencode_client.ui.launchLoadMessages
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §Phase1B/1C unit tests for the catch-up + gap (断层) logic. These exercise
 * the internal [launchCatchUp] / [launchCloseGap] / [launchLoadMessages]
 * top-level functions directly with a controlled MutableStateFlow + mocked
 * repository — no ViewModel construction needed, so they stay fast and focused.
 *
 * Key invariant under test (ora-2): `messages` is oldest-first, so the newest
 * id is found by max-by-time.created, NOT by list position.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CatchUpGapTest {

    private fun msg(id: String, created: Long, role: String = "user") =
        MessageWithParts(info = Message(id = id, role = role, time = Message.TimeInfo(created = created)))

    private fun repo(): OpenCodeRepository = mockk(relaxed = true)

    @Test
    fun `catchUp skips reload when probe matches local newest`() = runTest {
        val state = MutableStateFlow(
            AppState(
                currentSessionId = "s1",
                // local newest by time = "A" (created=100)
                messages = listOf(
                    Message(id = "old", role = "user", time = Message.TimeInfo(created = 10L)),
                    Message(id = "A", role = "assistant", time = Message.TimeInfo(created = 100L))
                )
            )
        )
        val repository = repo()
        coEvery { repository.probeLatestMessageId("s1") } returns Result.success("A")

        launchCatchUp(this, repository, state, "s1")
        advanceUntilIdle()

        // No tail reload issued — the big traffic saving.
        coVerify(exactly = 0) { repository.getMessagesPaged(any(), any(), any()) }
        assertNull(state.value.gapInfo)
    }

    @Test
    fun `catchUp reloads and detects gap when anchor outside fetched window`() = runTest {
        val state = MutableStateFlow(
            AppState(
                currentSessionId = "s1",
                messages = listOf(Message(id = "A", role = "user", time = Message.TimeInfo(created = 100L)))
            )
        )
        val repository = repo()
        // Server newest differs from local A → triggers reload.
        coEvery { repository.probeLatestMessageId("s1") } returns Result.success("Z")
        // Fetched tail (ASCENDING — oldest-first, matching real server order)
        // does NOT contain A → gap opens. Cursor pages older from the tail's
        // oldest (X). §省流 M2: catchUp now pulls 4 (sentinel), not 5.
        val tail = listOf(
            msg("X", 300L, "assistant"),
            msg("Y", 400L, "user"),
            msg("Z", 500L, "assistant")
        )
        coEvery { repository.getMessagesPaged("s1", 4, null) } returns Result.success(MessagesPage(tail, "cursor-from-tail-oldest"))

        launchCatchUp(this, repository, state, "s1")
        advanceUntilIdle()

        val gap = state.value.gapInfo
        assertNotNull("gap must open when anchor not in fetched window", gap)
        assertEquals("A", gap!!.anchorNewestId)
        // tailOldestId = the fetched message with the smallest created time ("X").
        assertEquals("X", gap.tailOldestId)
        assertEquals("cursor-from-tail-oldest", gap.tailOldestCursor)
        assertTrue(gap.open)
    }

    @Test
    fun `catchUp no gap when anchor within fetched window`() = runTest {
        val state = MutableStateFlow(
            AppState(
                currentSessionId = "s1",
                messages = listOf(Message(id = "A", role = "user", time = Message.TimeInfo(created = 100L)))
            )
        )
        val repository = repo()
        coEvery { repository.probeLatestMessageId("s1") } returns Result.success("Z")
        // Fetched tail (ascending) INCLUDES A → contiguous, no gap.
        // §省流 M2: catchUp pulls 4 (sentinel).
        val tail = listOf(
            msg("A", 100L, "user"),
            msg("Z", 500L, "assistant")
        )
        coEvery { repository.getMessagesPaged("s1", 4, null) } returns Result.success(MessagesPage(tail, null))

        launchCatchUp(this, repository, state, "s1")
        advanceUntilIdle()

        assertNull(state.value.gapInfo)
    }

    @Test
    fun `closeGap closes when anchor appears and preserves ascending order`() = runTest {
        // messages = [A(100), Z(500)]; gap between A and Z (tailOldestId=Z).
        val state = MutableStateFlow(
            AppState(
                currentSessionId = "s1",
                messages = listOf(
                    Message(id = "A", role = "user", time = Message.TimeInfo(created = 100L)),
                    Message(id = "Z", role = "assistant", time = Message.TimeInfo(created = 500L))
                ),
                gapInfo = com.yage.opencode_client.ui.GapInfo(
                    anchorNewestId = "A",
                    tailOldestId = "Z",
                    tailOldestCursor = "cursor-1",
                    open = true
                )
            )
        )
        val repository = repo()
        // Paged-older result (ascending) contains the anchor A → closure.
        // B sits between A and Z chronologically. §省流 M2: step defaults to 3.
        val page = listOf(msg("B", 250L, "user"), msg("A", 100L, "user"))
        coEvery { repository.getMessagesPaged("s1", 3, "cursor-1") } returns Result.success(MessagesPage(page, "cursor-2"))

        launchCloseGap(this, repository, state, "s1")
        advanceUntilIdle()

        assertNull("gap closes when anchor found", state.value.gapInfo)
        // §gpt-2 B2: bridged page inserted BEFORE tailOldestId (Z), not at head.
        // B is new, A deduped → merged = [A, B, Z], strictly ascending by time.
        assertEquals(listOf("A", "B", "Z"), state.value.messages.map { it.id })
    }

    @Test
    fun `closeGap advances cursor when anchor not yet found`() = runTest {
        val state = MutableStateFlow(
            AppState(
                currentSessionId = "s1",
                messages = listOf(
                    Message(id = "A", role = "user", time = Message.TimeInfo(created = 100L)),
                    Message(id = "Z", role = "assistant", time = Message.TimeInfo(created = 500L))
                ),
                gapInfo = com.yage.opencode_client.ui.GapInfo(
                    anchorNewestId = "A",
                    tailOldestId = "Z",
                    tailOldestCursor = "cursor-1",
                    open = true
                )
            )
        )
        val repository = repo()
        // Page does NOT contain A; more history remains.
        val page = listOf(msg("M", 250L, "user"))
        coEvery { repository.getMessagesPaged("s1", 3, "cursor-1") } returns Result.success(MessagesPage(page, "cursor-2"))

        // §省流 M2: maxSteps=1 forces the legacy single-step-per-call behaviour
        // so this test asserts ONE page + cursor advance (not a full auto-loop).
        launchCloseGap(this, repository, state, "s1", maxSteps = 1)
        advanceUntilIdle()

        val gap = state.value.gapInfo
        assertNotNull("gap still open", gap)
        assertTrue(gap!!.open)
        assertEquals("cursor advanced toward older", "cursor-2", gap.tailOldestCursor)
    }

    @Test
    fun `closeGap no-op when cursor exhausted`() = runTest {
        val state = MutableStateFlow(
            AppState(
                currentSessionId = "s1",
                messages = listOf(Message(id = "Z", role = "assistant", time = Message.TimeInfo(created = 500L))),
                gapInfo = com.yage.opencode_client.ui.GapInfo(
                    anchorNewestId = "A",
                    tailOldestId = "Z",
                    tailOldestCursor = null,   // no more history to page
                    open = true
                )
            )
        )
        val repository = repo()

        launchCloseGap(this, repository, state, "s1")
        advanceUntilIdle()

        // No fetch issued; gap marked closed (can't bridge).
        coVerify(exactly = 0) { repository.getMessagesPaged(any(), any(), any()) }
        assertNotNull(state.value.gapInfo)
        assertTrue("exhausted cursor closes the divider", !state.value.gapInfo!!.open)
    }

    @Test
    fun `messages ordering is oldest-first after preserveUnfetched merge`() = runTest {
        // §ora-2 protection test: guards the ordering assumption the gap logic
        // relies on. Seed older history, then a resetLimit=false reload merges
        // a fresh tail — the merged list must stay oldest-first so that
        // maxByOrNull{time.created} reliably yields the newest.
        val state = MutableStateFlow(
            AppState(
                currentSessionId = "s1",
                // older already-loaded history (oldest-first)
                messages = listOf(
                    Message(id = "h1", role = "user", time = Message.TimeInfo(created = 10L)),
                    Message(id = "h2", role = "assistant", time = Message.TimeInfo(created = 20L))
                ),
                olderMessagesCursor = "old-cursor",
                hasMoreMessages = true
            )
        )
        val repository = repo()
        // Fresh tail (server ASCENDING: oldest-first). The merged list must be
        // globally oldest-first so maxByOrNull{time.created} reliably yields
        // the newest — the invariant the gap logic relies on.
        val tail = listOf(
            msg("t2", 200L, "user"),
            msg("t3", 300L, "assistant")
        )
        coEvery { repository.getMessagesPaged("s1", 5, null) } returns Result.success(MessagesPage(tail, "tail-cursor"))

        launchLoadMessages(this, repository, state, "s1", resetLimit = false)
        advanceUntilIdle()

        val ids = state.value.messages.map { it.id }
        // Older history kept up front, fresh tail appended; overall oldest-first.
        assertEquals(listOf("h1", "h2", "t2", "t3"), ids)
        // newest by time (NOT by position) = t3.
        val newest = state.value.messages.maxByOrNull { it.time?.created ?: -1L }
        assertEquals("t3", newest?.id)
        // cursor preserved (resetLimit=false must not clobber existing cursor).
        assertEquals("old-cursor", state.value.olderMessagesCursor)
    }

    @Test
    fun `catchUp reloads when probe fails but does not open a gap from empty local`() = runTest {
        // §gpt-2 S4 / glm-1 🟡-4: probe network error (serverNewestId=null) and
        // empty local messages (anchorNewestId=null). catchUp must still reload
        // (degrade gracefully) and must NOT open a gap (no anchor to diff).
        val state = MutableStateFlow(AppState(currentSessionId = "s1", messages = emptyList()))
        val repository = repo()
        coEvery { repository.probeLatestMessageId("s1") } returns Result.failure(java.io.IOException("net"))
        val tail = listOf(msg("X", 300L), msg("Y", 400L))
        coEvery { repository.getMessagesPaged("s1", 4, null) } returns Result.success(MessagesPage(tail, "c"))

        launchCatchUp(this, repository, state, "s1")
        advanceUntilIdle()

        assertEquals(listOf("X", "Y"), state.value.messages.map { it.id })
        assertNull("no gap when local had no anchor", state.value.gapInfo)
    }

    @Test
    fun `catchUp and closeGap are no-op while a load is in flight`() = runTest {
        // §gpt-2 S3: the synchronous isLoadingMessages guard coalesces concurrent
        // triggers. Seed isLoading=true; neither function should fetch.
        val state = MutableStateFlow(
            AppState(
                currentSessionId = "s1",
                isLoadingMessages = true,
                messages = listOf(Message(id = "A", role = "user", time = Message.TimeInfo(created = 100L))),
                gapInfo = com.yage.opencode_client.ui.GapInfo("A", "A", "cursor-1", open = true)
            )
        )
        val repository = repo()
        launchCatchUp(this, repository, state, "s1")
        launchCloseGap(this, repository, state, "s1")
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.probeLatestMessageId(any()) }
        coVerify(exactly = 0) { repository.getMessagesPaged(any(), any(), any()) }
    }

    @Test
    fun `closeGap advances tailOldestId toward the anchor on partial fill`() = runTest {
        // §gpt-2 B2: after a partial closeGap (anchor not yet reached), the gap's
        // tailOldestId must advance to the oldest just-loaded id so the divider
        // follows the shrinking gap, not stay frozen at the original tail.
        val state = MutableStateFlow(
            AppState(
                currentSessionId = "s1",
                messages = listOf(
                    Message(id = "A", role = "user", time = Message.TimeInfo(created = 100L)),
                    Message(id = "Z", role = "assistant", time = Message.TimeInfo(created = 500L))
                ),
                gapInfo = com.yage.opencode_client.ui.GapInfo("A", "Z", "cursor-1", open = true)
            )
        )
        val repository = repo()
        // Page does NOT contain A; M sits between A and Z.
        val page = listOf(msg("M", 250L, "user"))
        coEvery { repository.getMessagesPaged("s1", 3, "cursor-1") } returns Result.success(MessagesPage(page, "cursor-2"))

        // §省流 M2: maxSteps=1 → single step, asserting tailOldestId advance.
        launchCloseGap(this, repository, state, "s1", maxSteps = 1)
        advanceUntilIdle()

        val gap = state.value.gapInfo
        assertNotNull("still open", gap)
        // M inserted before Z; tailOldestId advanced from Z to M (oldest loaded).
        assertEquals(listOf("A", "M", "Z"), state.value.messages.map { it.id })
        assertEquals("M", gap!!.tailOldestId)
        assertEquals("cursor-2", gap.tailOldestCursor)
    }

    @Test
    fun `resetLimit reload clears a pre-existing gap`() = runTest {
        // §gpt-2 S1: an authoritative latest-window replace (resetLimit=true,
        // e.g. message.created) must drop a stale gap whose anchor/tailOldest
        // may no longer exist in the new window.
        val state = MutableStateFlow(
            AppState(
                currentSessionId = "s1",
                messages = listOf(Message(id = "A", role = "user", time = Message.TimeInfo(created = 100L))),
                gapInfo = com.yage.opencode_client.ui.GapInfo("A", "A", "c", open = true)
            )
        )
        val repository = repo()
        val fresh = listOf(msg("Z", 500L, "assistant"))
        coEvery { repository.getMessagesPaged("s1", 5, null) } returns Result.success(MessagesPage(fresh, null))

        launchLoadMessages(this, repository, state, "s1", resetLimit = true)
        advanceUntilIdle()

        assertNull("resetLimit reload clears stale gap", state.value.gapInfo)
    }

    // ── §省流 M2: sentinel + auto-closure loop ────────────────────────────

    @Test
    fun `catchUp sentinel avoids false gap when exactly 3 new arrived`() = runTest {
        // §省流 M2 / gpter 致命#4 (off-by-one): with the OLD limit=3 display
        // window, exactly 3 new messages would push the anchor OUT of the
        // fetched window → false gap. Pulling 4 (3 display + 1 sentinel) keeps
        // the anchor at the sentinel (oldest) slot → correctly detected as
        // contiguous. Local newest (anchor) = "A"; exactly 3 new (n1,n2,n3).
        val state = MutableStateFlow(
            AppState(
                currentSessionId = "s1",
                messages = listOf(Message(id = "A", role = "user", time = Message.TimeInfo(created = 100L)))
            )
        )
        val repository = repo()
        // Server newest (n3) != A → reload fires.
        coEvery { repository.probeLatestMessageId("s1") } returns Result.success("n3")
        // Fetched sentinel window (ascending): anchor A at the oldest slot.
        val tail = listOf(
            msg("A", 100L, "user"),
            msg("n1", 200L, "assistant"),
            msg("n2", 300L, "user"),
            msg("n3", 400L, "assistant")
        )
        coEvery { repository.getMessagesPaged("s1", 4, null) } returns Result.success(MessagesPage(tail, null))

        launchCatchUp(this, repository, state, "s1")
        advanceUntilIdle()

        // anchor A is in the fetched 4 (sentinel slot) → NO gap.
        assertNull("sentinel must absorb the exactly-3-new boundary", state.value.gapInfo)
    }

    @Test
    fun `closeGap auto-loops and closes when anchor appears on a later page`() = runTest {
        // §省流 M2: launchCloseGap pages step-at-a-time up to maxSteps. Anchor
        // not on page 1 but present on page 2 → closes after 2 steps.
        val state = MutableStateFlow(
            AppState(
                currentSessionId = "s1",
                messages = listOf(
                    Message(id = "A", role = "user", time = Message.TimeInfo(created = 100L)),
                    Message(id = "Z", role = "assistant", time = Message.TimeInfo(created = 500L))
                ),
                gapInfo = com.yage.opencode_client.ui.GapInfo("A", "Z", "cursor-1", open = true)
            )
        )
        val repository = repo()
        // Page 1 (cursor-1): M only, no anchor, advances cursor.
        coEvery { repository.getMessagesPaged("s1", 3, "cursor-1") } returns
            Result.success(MessagesPage(listOf(msg("M", 250L, "user")), "cursor-2"))
        // Page 2 (cursor-2): raw page contains anchor A → closure (B is new, A deduped).
        coEvery { repository.getMessagesPaged("s1", 3, "cursor-2") } returns
            Result.success(MessagesPage(listOf(msg("B", 150L, "user"), msg("A", 100L, "user")), "cursor-3"))

        launchCloseGap(this, repository, state, "s1") // default step=3, maxSteps=5
        advanceUntilIdle()

        assertNull("gap closes when anchor found mid-loop", state.value.gapInfo)
        // Ascending by time: A(100), B(150), M(250), Z(500).
        assertEquals(listOf("A", "B", "M", "Z"), state.value.messages.map { it.id })
        // Exactly two pages fetched (auto-loop).
        coVerify(exactly = 2) { repository.getMessagesPaged(any(), any(), any()) }
    }

    @Test
    fun `closeGap stops after maxSteps and leaves gap open for manual tap`() = runTest {
        // §省流 M2 budget cap: anchor never appears; after maxSteps pages the
        // auto-loop STOPS and leaves GapInfo open so the divider stays tappable
        // (manual tap re-enters with a fresh budget).
        val state = MutableStateFlow(
            AppState(
                currentSessionId = "s1",
                messages = listOf(
                    Message(id = "A", role = "user", time = Message.TimeInfo(created = 100L)),
                    Message(id = "Z", role = "assistant", time = Message.TimeInfo(created = 500L))
                ),
                gapInfo = com.yage.opencode_client.ui.GapInfo("A", "Z", "cursor-1", open = true)
            )
        )
        val repository = repo()
        coEvery { repository.getMessagesPaged("s1", 3, "cursor-1") } returns
            Result.success(MessagesPage(listOf(msg("M1", 250L, "user")), "cursor-2"))
        coEvery { repository.getMessagesPaged("s1", 3, "cursor-2") } returns
            Result.success(MessagesPage(listOf(msg("M2", 200L, "user")), "cursor-3"))

        launchCloseGap(this, repository, state, "s1", step = 3, maxSteps = 2)
        advanceUntilIdle()

        val gap = state.value.gapInfo
        assertNotNull("budget exhausted must keep the hint open for manual", gap)
        assertTrue(gap!!.open)
        assertEquals("cursor advanced to last page's next", "cursor-3", gap.tailOldestCursor)
        // Exactly maxSteps (=2) pages — NOT three (the loop must stop at the cap).
        coVerify(exactly = 2) { repository.getMessagesPaged(any(), any(), any()) }
    }

    @Test
    fun `closeGap marks gap closed when history exhausted mid-loop`() = runTest {
        // §省流 M2: if a page returns a null nextCursor before the anchor is
        // reached, history is exhausted → can't bridge → mark open=false.
        val state = MutableStateFlow(
            AppState(
                currentSessionId = "s1",
                messages = listOf(
                    Message(id = "A", role = "user", time = Message.TimeInfo(created = 100L)),
                    Message(id = "Z", role = "assistant", time = Message.TimeInfo(created = 500L))
                ),
                gapInfo = com.yage.opencode_client.ui.GapInfo("A", "Z", "cursor-1", open = true)
            )
        )
        val repository = repo()
        // One page, no anchor, no further history (nextCursor=null).
        coEvery { repository.getMessagesPaged("s1", 3, "cursor-1") } returns
            Result.success(MessagesPage(listOf(msg("M", 250L, "user")), null))

        launchCloseGap(this, repository, state, "s1")
        advanceUntilIdle()

        val gap = state.value.gapInfo
        assertNotNull(gap)
        assertTrue("exhausted history mid-loop closes the divider", !gap!!.open)
        // Only one fetch (loop stops at exhausted cursor).
        coVerify(exactly = 1) { repository.getMessagesPaged(any(), any(), any()) }
    }
}
