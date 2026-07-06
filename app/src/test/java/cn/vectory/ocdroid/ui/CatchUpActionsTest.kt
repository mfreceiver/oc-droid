package cn.vectory.ocdroid.ui

import android.util.Log
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.MessageWithParts
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * §R18 Phase 5+: direct unit tests for [launchCatchUp] and [launchCloseGap].
 *
 * High-yield tail-reload + gap-detection + auto-closure budget logic (~320
 * lines). The skip probe, the sentinel off-by-one (anchor at 4th slot is still
 * contiguous → no gap), the progressive closeGap walk, and the cursor-exhausted
 * / maxSteps stop conditions all have observable slice effects.
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
        every { settingsManager.getAgentForSession(any()) } returns null
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
        val fetched = listOf(
            MessageWithParts(info = Message(id = "anchor", role = "assistant", time = Message.TimeInfo(created = 50L))),
            MessageWithParts(info = Message(id = "new1", role = "user", time = Message.TimeInfo(created = 100L))),
        )
        coEvery { repository.getMessagesPaged("s1", any(), any()) } returns Result.success(MessagesPage(fetched, nextCursor = null))

        launchCatchUp(scope, repository, slices, "s1", onCacheWindow = { sid, w -> cachedWindows += sid to w })
        advanceUntilIdle()

        // anchor detected in fetched → no gap; staleNotice cleared.
        assertFalse(slices.chat.value.isLoadingMessages)
        assertNull(slices.chat.value.gapInfo)
        assertFalse(slices.chat.value.staleNotice)
        assertEquals(1, cachedWindows.size)
    }

    @Test
    fun `launchCatchUp opens gap when anchor not in fetched window`() = runTest {
        val anchor = Message(id = "anchor", role = "assistant", time = Message.TimeInfo(created = 50L))
        store.mutateChat { it.copy(currentSessionId = "s1", messages = listOf(anchor)) }
        coEvery { repository.probeLatestMessageId("s1") } returns Result.success("server-newer")
        // Anchor NOT in fetched tail → gap.
        val fetched = listOf(
            MessageWithParts(info = Message(id = "new1", role = "user", time = Message.TimeInfo(created = 100L))),
            MessageWithParts(info = Message(id = "new2", role = "assistant", time = Message.TimeInfo(created = 110L))),
        )
        coEvery { repository.getMessagesPaged("s1", any(), any()) } returns Result.success(MessagesPage(fetched, nextCursor = "tailCursor"))

        launchCatchUp(scope, repository, slices, "s1")
        advanceUntilIdle()

        val gap = slices.chat.value.gapInfo
        assertNotNull(gap)
        assertTrue(gap!!.open)
        assertEquals("anchor", gap.anchorNewestId)
        assertEquals("tailCursor", gap.tailOldestCursor)
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
        assertFalse(slices.chat.value.isLoadingMessages)
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

    // ── launchCloseGap ────────────────────────────────────────────────────────

    @Test
    fun `launchCloseGap no-ops when there is no gapInfo`() = runTest {
        store.mutateChat { it.copy(currentSessionId = "s1", gapInfo = null) }

        launchCloseGap(scope, repository, slices, "s1")
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.getMessagesPaged(any(), any(), any()) }
    }

    @Test
    fun `launchCloseGap no-ops when gap is already closed`() = runTest {
        store.mutateChat {
            it.copy(
                currentSessionId = "s1",
                gapInfo = GapInfo(anchorNewestId = "a", tailOldestId = "t", tailOldestCursor = "c", open = false),
            )
        }

        launchCloseGap(scope, repository, slices, "s1")
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.getMessagesPaged(any(), any(), any()) }
    }

    @Test
    fun `launchCloseGap closes gap when null cursor means no more history to page`() = runTest {
        store.mutateChat {
            it.copy(
                currentSessionId = "s1",
                gapInfo = GapInfo(anchorNewestId = "a", tailOldestId = "t", tailOldestCursor = null, open = true),
            )
        }

        launchCloseGap(scope, repository, slices, "s1")
        advanceUntilIdle()

        // No cursor → mark gap closed (open=false), no fetch.
        coVerify(exactly = 0) { repository.getMessagesPaged(any(), any(), any()) }
        assertFalse(slices.chat.value.gapInfo!!.open)
    }

    @Test
    fun `launchCloseGap closes when anchor reappears in fetched page`() = runTest {
        val current = Message(id = "tail1", role = "user", time = Message.TimeInfo(created = 100L))
        store.mutateChat {
            it.copy(
                currentSessionId = "s1",
                messages = listOf(current),
                gapInfo = GapInfo(anchorNewestId = "anchor", tailOldestId = "tail1", tailOldestCursor = "c1", open = true),
            )
        }
        // Page returns the anchor → closed.
        val page = listOf(
            MessageWithParts(info = Message(id = "bridge1", role = "user", time = Message.TimeInfo(created = 80L))),
            MessageWithParts(info = Message(id = "anchor", role = "assistant", time = Message.TimeInfo(created = 50L))),
        )
        coEvery { repository.getMessagesPaged("s1", any(), eq("c1")) } returns Result.success(MessagesPage(page, nextCursor = null))

        launchCloseGap(scope, repository, slices, "s1")
        advanceUntilIdle()

        assertNull(slices.chat.value.gapInfo)
        // Bridged messages merged in.
        assertTrue(slices.chat.value.messages.any { it.id == "bridge1" })
        assertFalse(slices.chat.value.isLoadingMessages)
    }

    @Test
    fun `launchCloseGap marks gap closed when cursor exhausted without anchor`() = runTest {
        val current = Message(id = "tail1", role = "user", time = Message.TimeInfo(created = 100L))
        store.mutateChat {
            it.copy(
                currentSessionId = "s1",
                messages = listOf(current),
                gapInfo = GapInfo(anchorNewestId = "anchor", tailOldestId = "tail1", tailOldestCursor = "c1", open = true),
            )
        }
        // Page returns messages but no anchor and no nextCursor → can't bridge.
        val page = listOf(
            MessageWithParts(info = Message(id = "bridge1", role = "user", time = Message.TimeInfo(created = 80L))),
        )
        coEvery { repository.getMessagesPaged("s1", any(), eq("c1")) } returns Result.success(MessagesPage(page, nextCursor = null))

        launchCloseGap(scope, repository, slices, "s1")
        advanceUntilIdle()

        // cursor null → gap marked closed (open=false).
        assertFalse(slices.chat.value.gapInfo!!.open)
    }

    @Test
    fun `launchCloseGap stops at maxSteps budget leaving gap open`() = runTest {
        val current = Message(id = "tail1", role = "user", time = Message.TimeInfo(created = 1000L))
        store.mutateChat {
            it.copy(
                currentSessionId = "s1",
                messages = listOf(current),
                gapInfo = GapInfo(anchorNewestId = "anchor", tailOldestId = "tail1", tailOldestCursor = "c1", open = true),
            )
        }
        // Each step returns one bridged msg + next cursor, never finding anchor.
        coEvery { repository.getMessagesPaged("s1", any(), any()) } returns Result.success(
            MessagesPage(
                listOf(MessageWithParts(info = Message(id = "bridge", role = "user", time = Message.TimeInfo(created = 500L)))),
                nextCursor = "c2",
            )
        )

        launchCloseGap(scope, repository, slices, "s1", step = 1, maxSteps = 2)
        advanceUntilIdle()

        // Exactly 2 fetches (budget), gap still open.
        coVerify(exactly = 2) { repository.getMessagesPaged(eq("s1"), any(), any()) }
        assertTrue(slices.chat.value.gapInfo!!.open)
        assertFalse(slices.chat.value.isLoadingMessages)
    }

    @Test
    fun `launchCloseGap preserves already-loaded messages across walk`() = runTest {
        // Sanity: closeGap merges bridged pages WITHOUT losing existing tail messages.
        val current = Message(id = "tail1", role = "user", time = Message.TimeInfo(created = 1000L))
        store.mutateChat {
            it.copy(
                currentSessionId = "s1",
                messages = listOf(current),
                gapInfo = GapInfo(anchorNewestId = "anchor", tailOldestId = "tail1", tailOldestCursor = "c1", open = true),
            )
        }
        val page = listOf(
            MessageWithParts(info = Message(id = "bridge1", role = "user", time = Message.TimeInfo(created = 500L))),
            MessageWithParts(info = Message(id = "anchor", role = "assistant", time = Message.TimeInfo(created = 50L))),
        )
        coEvery { repository.getMessagesPaged("s1", any(), eq("c1")) } returns Result.success(MessagesPage(page, nextCursor = null))

        launchCloseGap(scope, repository, slices, "s1")
        advanceUntilIdle()

        // tail1 preserved; bridge + anchor merged.
        val ids = slices.chat.value.messages.map { it.id }
        assertTrue(ids.containsAll(listOf("tail1", "bridge1", "anchor")))
    }
}
