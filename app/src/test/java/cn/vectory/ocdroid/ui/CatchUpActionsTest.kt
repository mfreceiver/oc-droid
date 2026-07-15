package cn.vectory.ocdroid.ui

import android.util.Log
import cn.vectory.ocdroid.ui.controller.CachedSessionWindow
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.MessageWithParts
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.repository.MessagesPage
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.util.SettingsManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
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
 * R-18 Phase 5+ → R-20 Phase 2 → remove-message-persistence Task 4: direct
 * unit tests for [launchCatchUp].
 *
 * The legacy `launchCloseGap` section + the gap-coordinator delegation test
 * were removed (the non-contiguous gap mechanism was deleted in Task 4 —
 * catch-up now always merges the fetched window via `mergeProbeIntoSlice`,
 * with manual "load more" paging covering older history).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CatchUpActionsTest {

    private lateinit var store: SharedStateStore
    private lateinit var slices: SliceFlows
    private lateinit var repository: OpenCodeRepository
    private lateinit var settingsManager: SettingsManager
    private lateinit var scope: TestScope
    private lateinit var cachedWindows: MutableList<Pair<String, CachedSessionWindow>>

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0

        store = SharedStateStore()
        slices = store.slices
        repository = mockk(relaxed = true)
        settingsManager = mockk(relaxed = true)
        // §chat-ux-batch T8 (B3): mock setup for getAgentForSession removed (deleted API).
        scope = TestScope(UnconfinedTestDispatcher())
        cachedWindows = mutableListOf()
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ── launchCatchUp ─────────────────────────────────────────────────────────

    @Test
    fun `launchCatchUp skips reload when server newest equals anchor`() = runTest {
        // Local newest message id m_new.
        val localNewest = Message(id = "m_new", role = "assistant", time = Message.TimeInfo(created = 100L))
        store.mutateChat { it.copy(currentSessionId = "s1", messages = listOf(localNewest)) }
        // Server reports the same newest.
        coEvery { repository.probeLatestMessageId("s1") } returns Result.success("m_new")

        launchCatchUp(
            scope = scope,
            repository = repository,
            slices = slices,
            sessionId = "s1",
            onCacheWindow = { sid, w -> cachedWindows += sid to w },
        )
        advanceUntilIdle()

        // No tail reload issued.
        coVerify(exactly = 0) { repository.getMessagesPaged(any(), any(), any()) }
        // Loading flag restored to false; no other state changes.
        assertFalse(slices.chat.value.isLoadingMessages)
        assertTrue(cachedWindows.isEmpty())
    }

    @Test
    fun `launchCatchUp coalesces when isLoadingMessages already true`() = runTest {
        store.mutateChat { it.copy(currentSessionId = "s1", isLoadingMessages = true) }

        launchCatchUp(scope, repository, slices, "s1")
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.probeLatestMessageId(any()) }
        assertTrue(slices.chat.value.isLoadingMessages)
    }

    @Test
    fun `launchCatchUp reloads tail and clears staleNotice on success`() = runTest {
        val anchor = Message(id = "anchor", role = "assistant", time = Message.TimeInfo(created = 50L))
        store.mutateChat { it.copy(currentSessionId = "s1", messages = listOf(anchor), staleNotice = true) }
        coEvery { repository.probeLatestMessageId("s1") } returns Result.success("server-newer")
        // Fetched contains anchor (2 msgs, < probe page) → contiguous / NoGap.
        val fetched = listOf(
            MessageWithParts(info = Message(id = "anchor", role = "assistant", time = Message.TimeInfo(created = 50L))),
            MessageWithParts(info = Message(id = "new1", role = "user", time = Message.TimeInfo(created = 100L))),
        )
        coEvery { repository.getMessagesPaged("s1", any(), any()) } returns Result.success(MessagesPage(fetched, nextCursor = null))

        launchCatchUp(scope, repository, slices, "s1", onCacheWindow = { sid, w -> cachedWindows += sid to w })
        advanceUntilIdle()

        // anchor detected in fetched → no gap; staleNotice cleared.
        assertFalse(slices.chat.value.isLoadingMessages)
        assertFalse(slices.chat.value.staleNotice)
        assertEquals(1, cachedWindows.size)
    }

    @Test
    fun `launchCatchUp merges a full 5-message probe page directly`() = runTest {
        // remove-message-persistence Task 4: the non-contiguous gap mechanism
        // was deleted. A full 5-message probe page WITHOUT the anchor now
        // merges directly (the bigger history is recoverable via the manual
        // "load more" pager, not via an automatic backfill).
        val anchor = Message(id = "anchor", role = "assistant", time = Message.TimeInfo(created = 50L))
        store.mutateChat { it.copy(currentSessionId = "s1", messages = listOf(anchor)) }
        coEvery { repository.probeLatestMessageId("s1") } returns Result.success("server-newer")
        val fetched = (1..5).map { i ->
            MessageWithParts(info = Message(id = "new$i", role = "user", time = Message.TimeInfo(created = 100L + i)))
        }
        coEvery { repository.getMessagesPaged("s1", any(), any()) } returns Result.success(MessagesPage(fetched, nextCursor = "tailCursor"))

        launchCatchUp(
            scope = scope,
            repository = repository,
            slices = slices,
            sessionId = "s1",
            currentServerGroupFp = { "fp1" },
            onCacheWindow = { sid, w -> cachedWindows += sid to w },
        )
        advanceUntilIdle()

        // No delegation — the fetched 5 merge directly into the slice.
        assertFalse(slices.chat.value.isLoadingMessages)
        val ids = slices.chat.value.messages.map { it.id }
        assertTrue("anchor preserved across the merge", ids.contains("anchor"))
        assertTrue("fetched 5 merged into the slice", ids.containsAll(listOf("new1", "new2", "new3", "new4", "new5")))
        assertEquals(1, cachedWindows.size)
    }

    @Test
    fun `launchCatchUp does not write messages for a non-current session`() = runTest {
        store.mutateChat { it.copy(currentSessionId = "current") }
        coEvery { repository.probeLatestMessageId("other") } returns Result.success("server-new")
        val fetched = listOf(MessageWithParts(info = Message(id = "x", role = "user")))
        coEvery { repository.getMessagesPaged("other", any(), any()) } returns Result.success(MessagesPage(fetched, null))

        launchCatchUp(scope, repository, slices, "other")
        advanceUntilIdle()

        // session mismatch → no merge.
        assertTrue(slices.chat.value.messages.isEmpty())
        // §history-load-fix round-2 (gpter 🟠): stale (non-current session)
        // catchUp does NOT clear isLoadingMessages — deferred to the
        // session-guarded finally + SessionSwitcher (a switch resets chat
        // state). This test isolates the load without the switch, so the flag
        // the stale catchUp set remains true.
        assertTrue(slices.chat.value.isLoadingMessages)
    }

    @Test
    fun `launchCatchUp failure clears loading flag`() = runTest {
        store.mutateChat { it.copy(currentSessionId = "s1") }
        coEvery { repository.probeLatestMessageId("s1") } returns Result.failure(IllegalStateException("probe failed"))
        coEvery { repository.getMessagesPaged(any(), any(), any()) } returns Result.failure(IllegalStateException("tail fail"))

        launchCatchUp(scope, repository, slices, "s1")
        advanceUntilIdle()

        assertFalse(slices.chat.value.isLoadingMessages)
    }

    @Test
    fun `launchCatchUp preserves olderMessagesCursor and hasMoreMessages on resetLimit=false`() = runTest {
        store.mutateChat {
            it.copy(
                currentSessionId = "s1",
                olderMessagesCursor = "preserved",
                hasMoreMessages = true,
            )
        }
        coEvery { repository.probeLatestMessageId("s1") } returns Result.success("server-new")
        val fetched = listOf(MessageWithParts(info = Message(id = "new1", role = "user")))
        coEvery { repository.getMessagesPaged("s1", any(), any()) } returns Result.success(MessagesPage(fetched, null))

        launchCatchUp(scope, repository, slices, "s1", onCacheWindow = { sid, w -> cachedWindows += sid to w })
        advanceUntilIdle()

        assertEquals("preserved", slices.chat.value.olderMessagesCursor)
        assertTrue(slices.chat.value.hasMoreMessages)
        assertEquals("preserved", cachedWindows.single().second.olderMessagesCursor)
    }

    @Test
    fun `launchCatchUp skips probe when SSE covers the session workdir`() = runTest {
        // R-20 Phase 2 (G6): when the SSE feed is live for the session's workdir
        // AND the session was cold-snapshotted, shouldProbe=false → skip.
        every { settingsManager.currentWorkdir } returns "/repo"
        store.mutateChat { it.copy(currentSessionId = "s1") }

        launchCatchUp(
            scope = scope,
            repository = repository,
            slices = slices,
            sessionId = "s1",
            settingsManager = settingsManager,
            sseCurrentWorkdir = "/repo",
            sessionsEverColdSnapshotted = setOf("s1"),
        )
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.probeLatestMessageId(any()) }
        coVerify(exactly = 0) { repository.getMessagesPaged(any(), any(), any()) }
    }

    // ── remove-message-persistence final-review M6: merge order/parts + onColdSnapshot ──

    @Test
    fun `launchCatchUp merges in exact order and overrides parts for re-fetched ids`() = runTest {
        // mergeProbeIntoSlice (resetLimit=false): olderKept = src msgs not in
        // fetched AND older than the oldest fetched; merged = olderKept + fetched.
        // m_old (created 10) is older than the fetched window (oldest 50) and not
        // re-fetched → kept up front. m_mid IS re-fetched (same id) → its src
        // part is dropped and replaced by the fetched part.
        val mOld = Message(id = "m_old", role = "assistant", time = Message.TimeInfo(created = 10L))
        val mMid = Message(id = "m_mid", role = "assistant", time = Message.TimeInfo(created = 50L))
        val partOld = Part(id = "p_mid_old", type = "text", text = "old")
        val partNew = Part(id = "p_mid_new", type = "text", text = "new")
        val partNew2 = Part(id = "p_new", type = "text", text = "new2")
        store.mutateChat {
            it.copy(
                currentSessionId = "s1",
                messages = listOf(mOld, mMid),
                partsByMessage = mapOf("m_mid" to listOf(partOld)),
            )
        }
        coEvery { repository.probeLatestMessageId("s1") } returns Result.success("m_new")
        val fetched = listOf(
            MessageWithParts(info = Message(id = "m_mid", role = "assistant", time = Message.TimeInfo(created = 50L)), parts = listOf(partNew)),
            MessageWithParts(info = Message(id = "m_new", role = "user", time = Message.TimeInfo(created = 100L)), parts = listOf(partNew2)),
        )
        coEvery { repository.getMessagesPaged("s1", any(), any()) } returns Result.success(MessagesPage(fetched, nextCursor = null))

        launchCatchUp(scope, repository, slices, "s1", onCacheWindow = { sid, w -> cachedWindows += sid to w })
        advanceUntilIdle()

        assertFalse(slices.chat.value.isLoadingMessages)
        assertEquals(
            "olderKept up front, fetched window after",
            listOf("m_old", "m_mid", "m_new"),
            slices.chat.value.messages.map { it.id },
        )
        assertEquals(
            "re-fetched id's part is overridden by the fetched part",
            listOf(partNew),
            slices.chat.value.partsByMessage["m_mid"],
        )
        assertEquals(listOf(partNew2), slices.chat.value.partsByMessage["m_new"])
        assertTrue("m_old had no part and is not re-fetched", !slices.chat.value.partsByMessage.containsKey("m_old"))
        assertEquals(1, cachedWindows.size)
    }

    @Test
    fun `launchCatchUp marks onColdSnapshot on the anchor-equals-server short-circuit`() = runTest {
        // anchor == serverNewest → no probe-page reload; the short-circuit still
        // marks the cold-snapshot baseline when this is the current session.
        val localNewest = Message(id = "m_new", role = "assistant", time = Message.TimeInfo(created = 100L))
        store.mutateChat { it.copy(currentSessionId = "s1", messages = listOf(localNewest)) }
        coEvery { repository.probeLatestMessageId("s1") } returns Result.success("m_new")
        val snapshotted = mutableListOf<String>()

        launchCatchUp(scope, repository, slices, "s1", onColdSnapshot = { snapshotted += it })
        advanceUntilIdle()

        assertEquals(listOf("s1"), snapshotted)
        coVerify(exactly = 0) { repository.getMessagesPaged(any(), any(), any()) }
    }

    @Test
    fun `launchCatchUp marks onColdSnapshot after a successful tail merge`() = runTest {
        val anchor = Message(id = "anchor", role = "assistant", time = Message.TimeInfo(created = 50L))
        store.mutateChat { it.copy(currentSessionId = "s1", messages = listOf(anchor)) }
        coEvery { repository.probeLatestMessageId("s1") } returns Result.success("server-newer")
        val fetched = listOf(MessageWithParts(info = Message(id = "new1", role = "user", time = Message.TimeInfo(created = 100L))))
        coEvery { repository.getMessagesPaged("s1", any(), any()) } returns Result.success(MessagesPage(fetched, nextCursor = null))
        val snapshotted = mutableListOf<String>()

        launchCatchUp(scope, repository, slices, "s1", onColdSnapshot = { snapshotted += it })
        advanceUntilIdle()

        assertEquals(listOf("s1"), snapshotted)
    }

    @Test
    fun `launchCatchUp does not mark onColdSnapshot on a session mismatch`() = runTest {
        store.mutateChat { it.copy(currentSessionId = "current") }
        coEvery { repository.probeLatestMessageId("other") } returns Result.success("server-new")
        coEvery { repository.getMessagesPaged("other", any(), any()) } returns Result.success(
            MessagesPage(listOf(MessageWithParts(info = Message(id = "x", role = "user"))), nextCursor = null),
        )
        val snapshotted = mutableListOf<String>()

        launchCatchUp(scope, repository, slices, "other", onColdSnapshot = { snapshotted += it })
        advanceUntilIdle()

        assertTrue("session mismatch must not establish a baseline", snapshotted.isEmpty())
    }

    @Test
    fun `launchCatchUp does not mark onColdSnapshot on a probe failure`() = runTest {
        store.mutateChat { it.copy(currentSessionId = "s1") }
        coEvery { repository.probeLatestMessageId("s1") } returns Result.failure(IllegalStateException("probe failed"))
        coEvery { repository.getMessagesPaged(any(), any(), any()) } returns Result.failure(IllegalStateException("tail fail"))
        val snapshotted = mutableListOf<String>()

        launchCatchUp(scope, repository, slices, "s1", onColdSnapshot = { snapshotted += it })
        advanceUntilIdle()

        assertTrue("probe failure must not establish a baseline", snapshotted.isEmpty())
    }
}
