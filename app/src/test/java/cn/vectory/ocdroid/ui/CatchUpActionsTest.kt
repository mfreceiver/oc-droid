package cn.vectory.ocdroid.ui

import android.util.Log
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.MessageWithParts
import cn.vectory.ocdroid.data.repository.MessagesPage
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.ui.chat.GapDetection
import cn.vectory.ocdroid.ui.chat.GapFillCoordinator
import cn.vectory.ocdroid.util.SettingsManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
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
 * R-18 Phase 5+ → R-20 Phase 2: direct unit tests for [launchCatchUp].
 *
 * The legacy `launchCloseGap` section was removed (plan §3 N5 — the function
 * was deleted; its 50-step fill logic now lives in [GapFillCoordinator],
 * covered by `GapFillCoordinatorTest`). The catch-up probe + gap-detection
 * path is retained, with the sentinel off-by-one boundary now at exactly-4-new
 * (anchor in the 5-slot probe → contiguous) vs exactly-5-new (gap) — the
 * Phase-2 generalisation, see [CatchUpGapTest] for the boundary regression.
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
        every { settingsManager.getAgentForSession(any(), any()) } returns null
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
        assertTrue("no gap markers when anchor in fetched window", slices.chat.value.gapMarkers.isEmpty())
        assertFalse(slices.chat.value.staleNotice)
        assertEquals(1, cachedWindows.size)
    }

    @Test
    fun `launchCatchUp delegates to coordinator on full-page gap`() = runTest {
        // R-20 Phase 2: a full 5-message probe page WITHOUT the anchor →
        // detectGap returns GapExists → launchCatchUp delegates to
        // GapFillCoordinator.openAndFill. The coordinator (mocked here) owns
        // the fetched5 merge + the 50-step fill; launchCatchUp just clears its
        // own loading flag.
        val anchor = Message(id = "anchor", role = "assistant", time = Message.TimeInfo(created = 50L))
        store.mutateChat { it.copy(currentSessionId = "s1", messages = listOf(anchor)) }
        coEvery { repository.probeLatestMessageId("s1") } returns Result.success("server-newer")
        val fetched = (1..5).map { i ->
            MessageWithParts(info = Message(id = "new$i", role = "user", time = Message.TimeInfo(created = 100L + i)))
        }
        coEvery { repository.getMessagesPaged("s1", any(), any()) } returns Result.success(MessagesPage(fetched, nextCursor = "tailCursor"))
        val coordinator = mockk<GapFillCoordinator>(relaxed = true)

        launchCatchUp(
            scope = scope,
            repository = repository,
            slices = slices,
            sessionId = "s1",
            gapFillCoordinator = coordinator,
            currentServerGroupFp = { "fp1" },
        )
        advanceUntilIdle()

        // openAndFill delegated exactly once with the GapExists detection.
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
        // launchCatchUp's own loading flag is cleared (the gap's Filling state
        // is the per-marker indicator, orthogonal to isLoadingMessages).
        assertFalse(slices.chat.value.isLoadingMessages)
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
}
