package cn.vectory.ocdroid

import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.MessageWithParts
import cn.vectory.ocdroid.data.repository.MessagesPage
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.ui.ChatState
import cn.vectory.ocdroid.ui.SharedStateStore
import cn.vectory.ocdroid.ui.SliceFlows
import cn.vectory.ocdroid.ui.chat.GapDetection
import cn.vectory.ocdroid.ui.chat.GapFillCoordinator
import cn.vectory.ocdroid.ui.launchCatchUp
import cn.vectory.ocdroid.ui.launchLoadMessages
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §Phase1B/1C → R-20 Phase 2: unit tests for the catch-up gap-detection logic
 * ([launchCatchUp] + [cn.vectory.ocdroid.ui.chat.BackfillAlgorithm.detectGap]).
 *
 * **Sentinel off-by-one regression** (gpter 致命#4, generalised to the 5-slot
 * probe): the probe fetches 5 newest messages; the anchor (pre-reload local
 * newest) at the 5th/oldest slot is still detected as contiguous (exactly-4-new
 * → no gap). The boundary now sits at exactly-4-new (no gap) vs exactly-5-new
 * (gap opens). The legacy 4-message "3 display + 1 sentinel" test is preserved
 * here in its Phase-2 5-slot form.
 *
 * The legacy `launchCloseGap` tests were removed (plan §3 N5 — the function
 * was deleted; the 50-step backward fill is now owned by
 * [cn.vectory.ocdroid.ui.chat.GapFillCoordinator], covered by
 * `GapFillCoordinatorTest`).
 *
 * §R-17 batch2 step e final: real [SliceFlows] seeded via a [ChatState] fixture.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CatchUpGapTest {

    private fun msg(id: String, created: Long, role: String = "user") =
        MessageWithParts(info = Message(id = id, role = role, time = Message.TimeInfo(created = created)))

    private fun repo(): OpenCodeRepository = mockk(relaxed = true)

    private fun makeSlices(chat: ChatState = ChatState()): SliceFlows {
        val store = SharedStateStore()
        store.mutateChat { chat }
        return store.slices
    }

    @Test
    fun `catchUp skips reload when probe matches local newest`() = runTest {
        val slices = makeSlices(
            ChatState(
                currentSessionId = "s1",
                messages = listOf(
                    Message(id = "old", role = "user", time = Message.TimeInfo(created = 10L)),
                    Message(id = "A", role = "assistant", time = Message.TimeInfo(created = 100L))
                )
            )
        )
        val repository = repo()
        coEvery { repository.probeLatestMessageId("s1") } returns Result.success("A")

        launchCatchUp(this, repository, slices, "s1")
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.getMessagesPaged(any(), any(), any()) }
        assertTrue(slices.chat.value.gapMarkers.isEmpty())
    }

    @Test
    fun `catchUp no gap when anchor within fetched window`() = runTest {
        val slices = makeSlices(
            ChatState(
                currentSessionId = "s1",
                messages = listOf(Message(id = "A", role = "user", time = Message.TimeInfo(created = 100L)))
            )
        )
        val repository = repo()
        coEvery { repository.probeLatestMessageId("s1") } returns Result.success("Z")
        // Fetched tail (ascending) INCLUDES A → contiguous, no gap.
        val tail = listOf(
            msg("A", 100L, "user"),
            msg("Z", 500L, "assistant")
        )
        coEvery { repository.getMessagesPaged("s1", any(), null) } returns Result.success(MessagesPage(tail, null))

        launchCatchUp(this, repository, slices, "s1")
        advanceUntilIdle()

        assertTrue(slices.chat.value.gapMarkers.isEmpty())
    }

    @Test
    fun `catchUp detects gap when anchor outside a full 5-message probe`() = runTest {
        // R-20 Phase 2: a full 5-message probe page WITHOUT the anchor → gap.
        // detectGap returns GapExists; launchCatchUp delegates to the
        // coordinator's openAndFill (mocked — its 50-step fill is covered by
        // GapFillCoordinatorTest). The fetched5 merge + gap open are the
        // coordinator's responsibility, so we only assert the delegation here.
        val slices = makeSlices(
            ChatState(
                currentSessionId = "s1",
                messages = listOf(Message(id = "A", role = "user", time = Message.TimeInfo(created = 100L)))
            )
        )
        val repository = repo()
        coEvery { repository.probeLatestMessageId("s1") } returns Result.success("n5")
        val tail = (1..5).map { i -> msg("n$i", (200 + i).toLong(), "assistant") }
        coEvery { repository.getMessagesPaged("s1", any(), null) } returns
            Result.success(MessagesPage(tail, "cursor-from-tail-oldest"))
        val coordinator = mockk<GapFillCoordinator>(relaxed = true)

        launchCatchUp(
            this, repository, slices, "s1",
            gapFillCoordinator = coordinator,
            currentServerGroupFp = { "fp1" },
        )
        advanceUntilIdle()

        coVerify(exactly = 1) {
            coordinator.openAndFill(
                slices = any(),
                serverGroupFp = "fp1",
                sessionId = "s1",
                detection = any<GapDetection.GapExists>(),
                fetched5 = any(),
                fetched5Parts = any(),
                onCacheWindow = any(),
                onColdSnapshot = any(),
            )
        }
    }

    @Test
    fun `messages ordering is oldest-first after preserveUnfetched merge`() = runTest {
        val slices = makeSlices(
            ChatState(
                currentSessionId = "s1",
                messages = listOf(
                    Message(id = "h1", role = "user", time = Message.TimeInfo(created = 10L)),
                    Message(id = "h2", role = "assistant", time = Message.TimeInfo(created = 20L))
                ),
                olderMessagesCursor = "old-cursor",
                hasMoreMessages = true
            )
        )
        val repository = repo()
        val tail = listOf(msg("t2", 200L, "user"), msg("t3", 300L, "assistant"))
        coEvery { repository.getMessagesPaged("s1", any(), null) } returns Result.success(MessagesPage(tail, "tail-cursor"))

        launchLoadMessages(this, repository, slices, "s1", resetLimit = false)
        advanceUntilIdle()

        val ids = slices.chat.value.messages.map { it.id }
        assertEquals(listOf("h1", "h2", "t2", "t3"), ids)
        val newest = slices.chat.value.messages.maxByOrNull { it.time?.created ?: -1L }
        assertEquals("t3", newest?.id)
        assertEquals("old-cursor", slices.chat.value.olderMessagesCursor)
    }

    @Test
    fun `catchUp reloads when probe fails but does not open a gap from empty local`() = runTest {
        val slices = makeSlices(ChatState(currentSessionId = "s1", messages = emptyList()))
        val repository = repo()
        coEvery { repository.probeLatestMessageId("s1") } returns Result.failure(java.io.IOException("net"))
        val tail = listOf(msg("X", 300L), msg("Y", 400L))
        coEvery { repository.getMessagesPaged("s1", any(), null) } returns Result.success(MessagesPage(tail, "c"))

        launchCatchUp(this, repository, slices, "s1")
        advanceUntilIdle()

        assertEquals(listOf("X", "Y"), slices.chat.value.messages.map { it.id })
        assertTrue("no gap when local had no anchor", slices.chat.value.gapMarkers.isEmpty())
    }

    @Test
    fun `catchUp is no-op while a load is in flight`() = runTest {
        val slices = makeSlices(
            ChatState(
                currentSessionId = "s1",
                isLoadingMessages = true,
                messages = listOf(Message(id = "A", role = "user", time = Message.TimeInfo(created = 100L)))
            )
        )
        val repository = repo()
        launchCatchUp(this, repository, slices, "s1")
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.probeLatestMessageId(any()) }
        coVerify(exactly = 0) { repository.getMessagesPaged(any(), any(), any()) }
    }

    @Test
    fun `resetLimit reload clears a pre-existing gap`() = runTest {
        val slices = makeSlices(
            ChatState(
                currentSessionId = "s1",
                messages = listOf(Message(id = "A", role = "user", time = Message.TimeInfo(created = 100L))),
                gapMarkers = listOf(
                    cn.vectory.ocdroid.ui.chat.GapMarker(
                        gapId = "g1",
                        lowerAnchorMessageId = "A",
                        upperBoundaryMessageId = "A",
                        nextBeforeCursor = "c",
                        fillState = cn.vectory.ocdroid.ui.chat.GapFillState.Idle,
                    )
                )
            )
        )
        val repository = repo()
        val fresh = listOf(msg("Z", 500L, "assistant"))
        coEvery { repository.getMessagesPaged("s1", any(), null) } returns Result.success(MessagesPage(fresh, null))

        launchLoadMessages(this, repository, slices, "s1", resetLimit = true)
        advanceUntilIdle()

        assertTrue("resetLimit reload clears stale gap markers", slices.chat.value.gapMarkers.isEmpty())
    }

    // ── R-20 Phase 2: sentinel off-by-one (5-slot probe) ────────────────────

    @Test
    fun `catchUp sentinel avoids false gap when exactly 4 new arrived`() = runTest {
        // R-20 Phase 2 sentinel (gpter 致命#4, generalised): the probe fetches
        // 5 newest messages. When exactly 4 new arrived during the outage, the
        // anchor (pre-reload local newest "A") lands at the 5th/oldest slot of
        // the fetched window → still detected as contiguous → NO gap. Fetching
        // 5 (not 4) ensures the "exactly 4 new" boundary is absorbed. This is
        // the Phase-2 generalisation of the legacy "fetch 4 = 3 display + 1
        // sentinel → exactly-3-new absorbed" regression.
        val slices = makeSlices(
            ChatState(
                currentSessionId = "s1",
                messages = listOf(Message(id = "A", role = "user", time = Message.TimeInfo(created = 100L)))
            )
        )
        val repository = repo()
        coEvery { repository.probeLatestMessageId("s1") } returns Result.success("n4")
        // Fetched 5-slot probe (ascending): anchor A at the oldest (5th) slot.
        val tail = listOf(
            msg("A", 100L, "user"),
            msg("n1", 200L, "assistant"),
            msg("n2", 300L, "user"),
            msg("n3", 400L, "assistant"),
            msg("n4", 500L, "user")
        )
        coEvery { repository.getMessagesPaged("s1", any(), null) } returns Result.success(MessagesPage(tail, null))

        launchCatchUp(this, repository, slices, "s1")
        advanceUntilIdle()

        // anchor A is in the fetched 5 (sentinel slot) → NO gap.
        assertTrue("sentinel must absorb the exactly-4-new boundary", slices.chat.value.gapMarkers.isEmpty())
        // The merged window includes all 5 (A deduped against local, n1..n4 added).
        assertEquals(listOf("A", "n1", "n2", "n3", "n4"), slices.chat.value.messages.map { it.id })
    }

    @Test
    fun `catchUp opens gap when exactly 5 new arrived`() = runTest {
        // The other side of the sentinel boundary: exactly 5 new messages push
        // the anchor OUT of the 5-slot probe window → a real gap (> probe page)
        // → GapExists → delegated to the coordinator.
        val slices = makeSlices(
            ChatState(
                currentSessionId = "s1",
                messages = listOf(Message(id = "A", role = "user", time = Message.TimeInfo(created = 100L)))
            )
        )
        val repository = repo()
        coEvery { repository.probeLatestMessageId("s1") } returns Result.success("n5")
        // Fetched 5-slot probe (ascending): anchor A is NOT present (5 new only).
        val tail = (1..5).map { i -> msg("n$i", (200 + i).toLong(), "assistant") }
        coEvery { repository.getMessagesPaged("s1", any(), null) } returns Result.success(MessagesPage(tail, "next-cursor"))
        val coordinator = mockk<GapFillCoordinator>(relaxed = true)

        launchCatchUp(
            this, repository, slices, "s1",
            gapFillCoordinator = coordinator,
            currentServerGroupFp = { "fp1" },
        )
        advanceUntilIdle()

        // GapExists → coordinator.openAndFill invoked.
        coVerify(exactly = 1) {
            coordinator.openAndFill(
                slices = any(),
                serverGroupFp = "fp1",
                sessionId = "s1",
                detection = any<GapDetection.GapExists>(),
                fetched5 = any(), fetched5Parts = any(),
                onCacheWindow = any(), onColdSnapshot = any(),
            )
        }
    }

    // ── R-20 Phase 2 fix-#3: launchCatchUp fp guard (gpter #3) ─────────────

    @Test
    fun `fix-3 catchUp drops onSuccess merge when host group switched during the probe`() = runTest {
        // gpter #3: a cross-group same-sessionId collision (plan §0 N1). The
        // probe REST was initiated against fp-A (captured at call time), but
        // during the suspend the user switched host → the current fp is now
        // fp-B. Without the fp leg in the onSuccess guard, the stale probe
        // would write fp-A's tail into fp-B's chat slice. After the fix, the
        // guard compares expectedServerGroupFp (captured) vs
        // currentServerGroupFp() (current) and drops the merge on mismatch.
        val slices = makeSlices(
            ChatState(
                currentSessionId = "s1",
                messages = listOf(Message(id = "A", role = "user", time = Message.TimeInfo(created = 100L)))
            )
        )
        val repository = repo()
        coEvery { repository.probeLatestMessageId("s1") } returns Result.success("Z")
        val tail = listOf(msg("Z", 500L, "assistant"))
        coEvery { repository.getMessagesPaged("s1", any(), null) } returns Result.success(MessagesPage(tail, null))

        // Probe initiated against fp-A; currentServerGroupFp returns fp-B
        // (host switched during the probe).
        launchCatchUp(
            this, repository, slices, "s1",
            expectedServerGroupFp = "fp-A",
            currentServerGroupFp = { "fp-B" },
        )
        advanceUntilIdle()

        // The stale fp-A tail must NOT be merged into the (fp-B) slice.
        // The local anchor "A" is preserved; "Z" (the stale tail) is dropped.
        assertEquals(
            "stale fp-A probe must not merge into fp-B's slice",
            listOf("A"),
            slices.chat.value.messages.map { it.id },
        )
        // Loading flag cleared on the early return.
        assertFalse(slices.chat.value.isLoadingMessages)
    }
}
