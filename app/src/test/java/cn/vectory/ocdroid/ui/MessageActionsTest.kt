package cn.vectory.ocdroid.ui

import android.util.Log
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.MessageWithParts
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.model.SessionStatus
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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
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
 * §R18 Phase 5+: direct unit tests for the [launchLoadMessages] /
 * [launchLoadMoreMessages] / [launchLoadMessagesWithRetry] free functions.
 *
 * These are the highest-yield uncovered blocks in MessageActions.kt (~400
 * lines): slice merge logic, coalescing guards, cursor pagination, failure
 * UiEvent emission. Driven directly via a real [SharedStateStore] (so mutateChat
 * writes propagate to slice reads) + a mockk [OpenCodeRepository] + a capturing
 * [EventEmitter]. No AppCore / Hilt / Robolectric.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessageActionsTest {

    private lateinit var store: SharedStateStore
    private lateinit var slices: SliceFlows
    private lateinit var repository: OpenCodeRepository
    private lateinit var settingsManager: SettingsManager
    private lateinit var scope: TestScope
    private lateinit var emitted: MutableList<UiEvent>
    private lateinit var emit: EventEmitter
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
        every { settingsManager.getModelForSession(any(), any()) } returns null
        scope = TestScope(UnconfinedTestDispatcher())
        emitted = mutableListOf()
        emit = EventEmitter { event -> emitted.add(event) }
        cachedWindows = mutableListOf()
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ── launchLoadMessages ────────────────────────────────────────────────────

    @Test
    fun `launchLoadMessages success writes merged messages and clears loading flag`() = runTest {
        val msgs = listOf(
            MessageWithParts(info = Message(id = "u1", role = "user")),
            MessageWithParts(
                info = Message(id = "a1", role = "assistant"),
                parts = listOf(Part(id = "p1", messageId = "a1", sessionId = "s1", type = "text", text = "hi"))
            )
        )
        coEvery { repository.getMessagesPaged("s1", any(), any()) } returns Result.success(MessagesPage(msgs, null))
        coEvery { repository.getSessionTodos("s1") } returns Result.success(emptyList())
        store.mutateChat { it.copy(currentSessionId = "s1") }

        launchLoadMessages(
            scope = scope,
            repository = repository,
            slices = slices,
            sessionId = "s1",
            onCacheWindow = { sid, w -> cachedWindows += sid to w },
            emit = emit,
        )
        advanceUntilIdle()

        assertEquals(listOf("u1", "a1"), slices.chat.value.messages.map { it.id })
        assertFalse(slices.chat.value.isLoadingMessages)
        assertEquals(1, cachedWindows.size)
        assertEquals("s1", cachedWindows.single().first)
        assertTrue(emitted.isEmpty())
    }

    @Test
    fun `launchLoadMessages failure emits UiEvent Error and clears loading flag`() = runTest {
        coEvery { repository.getMessagesPaged("s1", any(), any()) } returns Result.failure(IllegalStateException("500"))
        coEvery { repository.getSessionTodos("s1") } returns Result.success(emptyList())
        store.mutateChat { it.copy(currentSessionId = "s1") }

        launchLoadMessages(
            scope = scope,
            repository = repository,
            slices = slices,
            sessionId = "s1",
            emit = emit,
        )
        advanceUntilIdle()

        assertFalse(slices.chat.value.isLoadingMessages)
        val err = emitted.filterIsInstance<UiEvent.Error>().single()
        assertEquals(R.string.error_load_messages_failed, err.resId)
        assertTrue(err.args.any { it.toString().contains("500") })
    }

    @Test
    fun `launchLoadMessages coalesces when isLoadingMessages already true`() = runTest {
        coEvery { repository.getMessagesPaged(any(), any(), any()) } returns Result.success(MessagesPage(emptyList(), null))
        coEvery { repository.getSessionTodos(any()) } returns Result.success(emptyList())
        store.mutateChat { it.copy(currentSessionId = "s1", isLoadingMessages = true) }

        launchLoadMessages(
            scope = scope,
            repository = repository,
            slices = slices,
            sessionId = "s1",
            emit = emit,
        )
        advanceUntilIdle()

        // No fetch issued — the in-flight load owns the flag.
        coVerify(exactly = 0) { repository.getMessagesPaged(any(), any(), any()) }
        assertTrue(slices.chat.value.isLoadingMessages) // still true (we did not touch it)
    }

    @Test
    fun `launchLoadMessages does not write messages for a non-current session`() = runTest {
        val msgs = listOf(MessageWithParts(info = Message(id = "x1", role = "user")))
        coEvery { repository.getMessagesPaged("other", any(), any()) } returns Result.success(MessagesPage(msgs, null))
        coEvery { repository.getSessionTodos("other") } returns Result.success(emptyList())
        // currentSessionId points elsewhere — fetch returns but merge is skipped.
        store.mutateChat { it.copy(currentSessionId = "current") }

        launchLoadMessages(
            scope = scope,
            repository = repository,
            slices = slices,
            sessionId = "other",
            emit = emit,
        )
        advanceUntilIdle()

        assertTrue(slices.chat.value.messages.isEmpty())
        assertFalse(slices.chat.value.isLoadingMessages)
    }

    @Test
    fun `launchLoadMessages resetLimit=true clears streaming overlay when session finalized`() = runTest {
        val msgs = listOf(MessageWithParts(info = Message(id = "a1", role = "assistant")))
        coEvery { repository.getMessagesPaged("s1", any(), any()) } returns Result.success(MessagesPage(msgs, null))
        coEvery { repository.getSessionTodos("s1") } returns Result.success(emptyList())
        store.mutateChat {
            it.copy(
                currentSessionId = "s1",
                streamingPartTexts = mapOf("p1" to "partial"),
                streamingReasoningPart = Part(id = "p1", messageId = "a1", sessionId = "s1", type = "reasoning"),
            )
        }
        // sessionStatuses absent → streamingFinalized defaults to true → overlay cleared.

        launchLoadMessages(scope, repository, slices, "s1", resetLimit = true, emit = emit)
        advanceUntilIdle()

        assertTrue(slices.chat.value.streamingPartTexts.isEmpty())
        assertNull(slices.chat.value.streamingReasoningPart)
    }

    @Test
    fun `launchLoadMessages resetLimit=true preserves overlay when session is busy`() = runTest {
        val msgs = listOf(MessageWithParts(info = Message(id = "a1", role = "assistant")))
        coEvery { repository.getMessagesPaged("s1", any(), any()) } returns Result.success(MessagesPage(msgs, null))
        coEvery { repository.getSessionTodos("s1") } returns Result.success(emptyList())
        store.mutateChat {
            it.copy(
                currentSessionId = "s1",
                streamingPartTexts = mapOf("p1" to "partial"),
            )
        }
        store.mutateSessionList {
            it.copy(sessionStatuses = mapOf("s1" to SessionStatus(type = "busy")))
        }

        launchLoadMessages(scope, repository, slices, "s1", resetLimit = true, emit = emit)
        advanceUntilIdle()

        // busy → streamingFinalized=false → overlay preserved.
        assertEquals("partial", slices.chat.value.streamingPartTexts["p1"])
    }

    @Test
    fun `launchLoadMessages preserves older already-loaded pages across reload`() = runTest {
        // Older page already loaded locally (id=old, created earlier than fetched).
        val older = Message(id = "old", role = "user", time = Message.TimeInfo(created = 100L))
        val fetched = listOf(
            MessageWithParts(info = Message(id = "new1", role = "user", time = Message.TimeInfo(created = 200L))),
            MessageWithParts(info = Message(id = "new2", role = "assistant", time = Message.TimeInfo(created = 300L))),
        )
        coEvery { repository.getMessagesPaged("s1", any(), any()) } returns Result.success(MessagesPage(fetched, null))
        coEvery { repository.getSessionTodos("s1") } returns Result.success(emptyList())
        store.mutateChat { it.copy(currentSessionId = "s1", messages = listOf(older)) }

        launchLoadMessages(scope, repository, slices, "s1", resetLimit = false, emit = emit)
        advanceUntilIdle()

        // Older kept + fetched appended (ascending created order).
        assertEquals(listOf("old", "new1", "new2"), slices.chat.value.messages.map { it.id })
    }

    @Test
    fun `launchLoadMessages seeds olderMessagesCursor on resetLimit=true`() = runTest {
        val fetched = listOf(MessageWithParts(info = Message(id = "m1", role = "user")))
        coEvery { repository.getMessagesPaged("s1", any(), any()) } returns Result.success(MessagesPage(fetched, nextCursor = "cursor-1"))
        coEvery { repository.getSessionTodos("s1") } returns Result.success(emptyList())
        store.mutateChat { it.copy(currentSessionId = "s1") }

        launchLoadMessages(scope, repository, slices, "s1", resetLimit = true, emit = emit)
        advanceUntilIdle()

        assertEquals("cursor-1", slices.chat.value.olderMessagesCursor)
        assertTrue(slices.chat.value.hasMoreMessages)
    }

    @Test
    fun `launchLoadMessages rebuilds cursor when resetLimit=false but cursor is unseeded`() = runTest {
        // §F3-rebuild: 缓存水合后 olderMessagesCursor=null / hasMoreMessages=false（toWindow
        // 重建结果）。Verified 分支的跟随加载是 resetLimit=false——此时必须用 page.nextCursor
        // 重建 cursor/hasMore，否则"加载更多"按钮永不出现（从死按钮矫枉过正成无按钮）。
        val fetched = listOf(MessageWithParts(info = Message(id = "m1", role = "user")))
        coEvery { repository.getMessagesPaged("s1", any(), any()) } returns Result.success(MessagesPage(fetched, nextCursor = "cursor-1"))
        coEvery { repository.getSessionTodos("s1") } returns Result.success(emptyList())
        store.mutateChat {
            it.copy(
                currentSessionId = "s1",
                messages = listOf(Message(id = "m1", role = "user")),
                olderMessagesCursor = null,
                hasMoreMessages = false
            )
        }

        launchLoadMessages(scope, repository, slices, "s1", resetLimit = false, emit = emit)
        advanceUntilIdle()

        assertEquals("cursor-1", slices.chat.value.olderMessagesCursor)
        assertTrue(slices.chat.value.hasMoreMessages)
    }

    @Test
    fun `launchLoadMessages preserves an existing cursor on resetLimit=false`() = runTest {
        // §F3-rebuild 反向：用户已加载过历史、cursor 已建立——periodic reload(resetLimit=false)
        // 不得改写它（否则在途拉取会破坏分页位置）。
        val fetched = listOf(MessageWithParts(info = Message(id = "m1", role = "user")))
        coEvery { repository.getMessagesPaged("s1", any(), any()) } returns Result.success(MessagesPage(fetched, nextCursor = "server-new-cursor"))
        coEvery { repository.getSessionTodos("s1") } returns Result.success(emptyList())
        store.mutateChat {
            it.copy(
                currentSessionId = "s1",
                olderMessagesCursor = "user-cursor",
                hasMoreMessages = true
            )
        }

        launchLoadMessages(scope, repository, slices, "s1", resetLimit = false, emit = emit)
        advanceUntilIdle()

        assertEquals("user-cursor", slices.chat.value.olderMessagesCursor)
        assertTrue(slices.chat.value.hasMoreMessages)
    }

    @Test
    fun `launchLoadMessages writes session todos after success`() = runTest {
        val todo = cn.vectory.ocdroid.data.model.TodoItem(id = "t1", content = "done", status = "completed", priority = "high")
        coEvery { repository.getMessagesPaged("s1", any(), any()) } returns Result.success(MessagesPage(emptyList(), null))
        coEvery { repository.getSessionTodos("s1") } returns Result.success(listOf(todo))
        store.mutateChat { it.copy(currentSessionId = "s1") }

        launchLoadMessages(scope, repository, slices, "s1", emit = emit)
        advanceUntilIdle()

        assertEquals(listOf(todo), slices.sessionList.value.sessionTodos["s1"])
    }

    @Test
    fun `launchLoadMessages syncs selectedAgentName from per-session override`() = runTest {
        val msgs = listOf(MessageWithParts(info = Message(id = "a1", role = "assistant", agent = "build")))
        coEvery { repository.getMessagesPaged("s1", any(), any()) } returns Result.success(MessagesPage(msgs, null))
        coEvery { repository.getSessionTodos("s1") } returns Result.success(emptyList())
        every { settingsManager.getAgentForSession(any(), "s1") } returns "plan"
        store.mutateChat { it.copy(currentSessionId = "s1") }

        launchLoadMessages(scope, repository, slices, "s1", settingsManager = settingsManager, emit = emit)
        advanceUntilIdle()

        assertEquals("plan", slices.settings.value.selectedAgentName)
    }

    // ── launchLoadMoreMessages ────────────────────────────────────────────────

    @Test
    fun `launchLoadMoreMessages no-ops when hasMoreMessages is false`() = runTest {
        store.mutateChat { it.copy(currentSessionId = "s1", hasMoreMessages = false, olderMessagesCursor = "c1") }

        launchLoadMoreMessages(scope, repository, slices, "s1")
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.getMessagesPaged(any(), any(), any()) }
    }

    @Test
    fun `launchLoadMoreMessages no-ops when cursor is null`() = runTest {
        store.mutateChat { it.copy(currentSessionId = "s1", hasMoreMessages = true, olderMessagesCursor = null) }

        launchLoadMoreMessages(scope, repository, slices, "s1")
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.getMessagesPaged(any(), any(), any()) }
    }

    @Test
    fun `launchLoadMoreMessages no-ops when isLoadingMessages already true`() = runTest {
        store.mutateChat {
            it.copy(currentSessionId = "s1", hasMoreMessages = true, olderMessagesCursor = "c1", isLoadingMessages = true)
        }

        launchLoadMoreMessages(scope, repository, slices, "s1")
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.getMessagesPaged(any(), any(), any()) }
    }

    @Test
    fun `launchLoadMoreMessages success prepends older page with cursor update`() = runTest {
        val existing = Message(id = "cur1", role = "user", time = Message.TimeInfo(created = 500L))
        val olderPage = listOf(
            MessageWithParts(info = Message(id = "old1", role = "user", time = Message.TimeInfo(created = 100L))),
        )
        coEvery { repository.getMessagesPaged("s1", any(), eq("cursor-1")) } returns Result.success(MessagesPage(olderPage, nextCursor = "cursor-2"))
        store.mutateChat {
            it.copy(currentSessionId = "s1", messages = listOf(existing), olderMessagesCursor = "cursor-1", hasMoreMessages = true)
        }
        var cached: CachedSessionWindow? = null

        launchLoadMoreMessages(scope, repository, slices, "s1") { _, w -> cached = w }
        advanceUntilIdle()

        assertEquals(listOf("old1", "cur1"), slices.chat.value.messages.map { it.id })
        assertEquals("cursor-2", slices.chat.value.olderMessagesCursor)
        assertFalse(slices.chat.value.isLoadingMessages)
        assertNotNull(cached)
        assertEquals(listOf("old1", "cur1"), cached!!.messages.map { it.id })
    }

    @Test
    fun `launchLoadMoreMessages dedups at the seam when server overlaps`() = runTest {
        val existing = Message(id = "overlap", role = "user", time = Message.TimeInfo(created = 100L))
        val olderPage = listOf(
            MessageWithParts(info = Message(id = "old1", role = "user", time = Message.TimeInfo(created = 50L))),
            MessageWithParts(info = Message(id = "overlap", role = "user", time = Message.TimeInfo(created = 100L))),
        )
        coEvery { repository.getMessagesPaged(any(), any(), any()) } returns Result.success(MessagesPage(olderPage, nextCursor = null))
        store.mutateChat {
            it.copy(currentSessionId = "s1", messages = listOf(existing), olderMessagesCursor = "c1", hasMoreMessages = true)
        }

        launchLoadMoreMessages(scope, repository, slices, "s1")
        advanceUntilIdle()

        // "overlap" appears once.
        assertEquals(listOf("old1", "overlap"), slices.chat.value.messages.map { it.id })
        // cursor exhausted → hasMore flips to false.
        assertFalse(slices.chat.value.hasMoreMessages)
    }

    @Test
    fun `launchLoadMoreMessages failure clears loading flag and keeps hasMore for retry`() = runTest {
        coEvery { repository.getMessagesPaged(any(), any(), any()) } returns Result.failure(IllegalStateException("timeout"))
        store.mutateChat {
            it.copy(currentSessionId = "s1", olderMessagesCursor = "c1", hasMoreMessages = true)
        }

        launchLoadMoreMessages(scope, repository, slices, "s1")
        advanceUntilIdle()

        assertFalse(slices.chat.value.isLoadingMessages)
        // Manual paging: hasMore kept so the user can retry.
        assertTrue(slices.chat.value.hasMoreMessages)
    }

    @Test
    fun `launchLoadMoreMessages empty page keeps messages and updates cursor`() = runTest {
        val existing = Message(id = "cur1", role = "user")
        coEvery { repository.getMessagesPaged(any(), any(), any()) } returns Result.success(MessagesPage(emptyList(), nextCursor = null))
        store.mutateChat {
            it.copy(currentSessionId = "s1", messages = listOf(existing), olderMessagesCursor = "c1", hasMoreMessages = true)
        }

        launchLoadMoreMessages(scope, repository, slices, "s1")
        advanceUntilIdle()

        assertEquals(listOf("cur1"), slices.chat.value.messages.map { it.id })
        assertFalse(slices.chat.value.hasMoreMessages)
    }

    // ── launchLoadMessagesWithRetry ───────────────────────────────────────────

    @Test
    fun `launchLoadMessagesWithRetry drops when session changed during delay`() {
        var loadCalls = 0
        store.mutateChat { it.copy(currentSessionId = "s1") }

        launchLoadMessagesWithRetry(scope, "s1", slices, resetLimit = true) { _, _ -> loadCalls += 1 }
        // Switch session BEFORE the retry delay elapses.
        store.mutateChat { it.copy(currentSessionId = "other") }
        scope.advanceTimeBy(MainViewModelTimings.messageRetryDelayMs)
        scope.advanceUntilIdle()
        scope.runCurrent()

        assertEquals(0, loadCalls)
    }

    @Test
    fun `launchLoadMessagesWithRetry invokes onLoadMessages after delay when session matches`() {
        var loadCalls = 0
        store.mutateChat { it.copy(currentSessionId = "s1") }

        launchLoadMessagesWithRetry(scope, "s1", slices, resetLimit = true) { sid, reset ->
            loadCalls += 1
            assertEquals("s1", sid)
            assertTrue(reset)
        }
        // §note: scope is TestScope(UnconfinedTestDispatcher()); pumping scope's
        // own scheduler (not runTest's) drives the delay.
        scope.advanceTimeBy(MainViewModelTimings.messageRetryDelayMs)
        scope.advanceUntilIdle()
        scope.runCurrent()

        assertEquals(1, loadCalls)
    }

    // ── R-20 Phase 1 (gpter 复审 final-fix): compound-key fp guard ──────────

    @Test
    fun `gpter-final-fix launchLoadMessages drops stale REST response when host group changed during fetch`() = runTest {
        // gpter scenario: G1/s1 REST in-flight → user switches to G2/s1
        // (collision: same sessionId, different serverGroupFp). The REST
        // response from G1 must NOT write G1's messages into G2's chat slice.
        // sessionId guard alone passes (s1==s1); the fp guard catches the
        // cross-group collision.
        val msgs = listOf(MessageWithParts(info = Message(id = "g1-msg", role = "user")))
        coEvery { repository.getMessagesPaged("s1", any(), any()) } returns Result.success(MessagesPage(msgs, null))
        coEvery { repository.getSessionTodos("s1") } returns Result.success(emptyList())
        store.mutateChat { it.copy(currentSessionId = "s1") }

        // Launch with expectedServerGroupFp = "g1" (the old group).
        // The currentServerGroupFp provider returns "g2" (the new group,
        // simulating a host switch that happened during the REST call).
        launchLoadMessages(
            scope = scope,
            repository = repository,
            slices = slices,
            sessionId = "s1",
            onCacheWindow = { _, _ -> },
            emit = emit,
            expectedServerGroupFp = "g1",
            currentServerGroupFp = { "g2" }, // simulate post-switch fp
        )
        advanceUntilIdle()

        // G1's messages must NOT be written — the fp guard dropped them.
        assertTrue(
            "stale G1 REST response must not write to slice after host group switch (fp mismatch)",
            slices.chat.value.messages.isEmpty(),
        )
        assertFalse(slices.chat.value.isLoadingMessages)
    }

    @Test
    fun `gpter-final-fix launchLoadMessages writes when fp matches (happy path)`() = runTest {
        // Counterpart: fp matches → normal write proceeds.
        val msgs = listOf(MessageWithParts(info = Message(id = "m1", role = "user")))
        coEvery { repository.getMessagesPaged("s1", any(), any()) } returns Result.success(MessagesPage(msgs, null))
        coEvery { repository.getSessionTodos("s1") } returns Result.success(emptyList())
        store.mutateChat { it.copy(currentSessionId = "s1") }

        launchLoadMessages(
            scope = scope,
            repository = repository,
            slices = slices,
            sessionId = "s1",
            onCacheWindow = { _, _ -> },
            emit = emit,
            expectedServerGroupFp = "g1",
            currentServerGroupFp = { "g1" }, // same fp → guard passes
        )
        advanceUntilIdle()

        assertEquals(listOf("m1"), slices.chat.value.messages.map { it.id })
    }

    @Test
    fun `gpter-final-fix launchLoadMessages default empty fp params preserve legacy behavior`() = runTest {
        // Backward-compat: when fp params are not passed (default ""), the
        // guard is a no-op (both sides "" → equal). Legacy callers and tests
        // that don't pass fp are unaffected.
        val msgs = listOf(MessageWithParts(info = Message(id = "m1", role = "user")))
        coEvery { repository.getMessagesPaged("s1", any(), any()) } returns Result.success(MessagesPage(msgs, null))
        coEvery { repository.getSessionTodos("s1") } returns Result.success(emptyList())
        store.mutateChat { it.copy(currentSessionId = "s1") }

        launchLoadMessages(
            scope = scope,
            repository = repository,
            slices = slices,
            sessionId = "s1",
            emit = emit,
            // fp params intentionally omitted (default "").
        )
        advanceUntilIdle()

        assertEquals(listOf("m1"), slices.chat.value.messages.map { it.id })
    }

    @Test
    fun `gpter-final-fix launchLoadMoreMessages drops stale REST response when host group changed during fetch`() = runTest {
        // Same guard for the older-page pagination path.
        val existing = Message(id = "cur1", role = "user", time = Message.TimeInfo(created = 500L))
        val olderPage = listOf(
            MessageWithParts(info = Message(id = "g1-old", role = "user", time = Message.TimeInfo(created = 100L))),
        )
        coEvery { repository.getMessagesPaged("s1", any(), eq("c1")) } returns Result.success(MessagesPage(olderPage, nextCursor = "c2"))
        store.mutateChat {
            it.copy(currentSessionId = "s1", messages = listOf(existing), olderMessagesCursor = "c1", hasMoreMessages = true)
        }

        launchLoadMoreMessages(
            scope = scope,
            repository = repository,
            slices = slices,
            sessionId = "s1",
            expectedServerGroupFp = "g1",
            currentServerGroupFp = { "g2" }, // simulate post-switch fp
            onCacheWindow = { _, _ -> },
        )
        advanceUntilIdle()

        // G1's older page must NOT be prepended — the fp guard dropped it.
        assertEquals(
            "stale G1 older-page response must not write after host group switch",
            listOf("cur1"),
            slices.chat.value.messages.map { it.id },
        )
        assertFalse(slices.chat.value.isLoadingMessages)
    }

    @Test
    fun `gpter-final-fix launchLoadMoreMessages writes when fp matches (happy path)`() = runTest {
        val existing = Message(id = "cur1", role = "user", time = Message.TimeInfo(created = 500L))
        val olderPage = listOf(
            MessageWithParts(info = Message(id = "old1", role = "user", time = Message.TimeInfo(created = 100L))),
        )
        coEvery { repository.getMessagesPaged("s1", any(), eq("c1")) } returns Result.success(MessagesPage(olderPage, nextCursor = null))
        store.mutateChat {
            it.copy(currentSessionId = "s1", messages = listOf(existing), olderMessagesCursor = "c1", hasMoreMessages = true)
        }

        launchLoadMoreMessages(
            scope = scope,
            repository = repository,
            slices = slices,
            sessionId = "s1",
            expectedServerGroupFp = "g1",
            currentServerGroupFp = { "g1" },
            onCacheWindow = { _, _ -> },
        )
        advanceUntilIdle()

        assertEquals(listOf("old1", "cur1"), slices.chat.value.messages.map { it.id })
    }
}
