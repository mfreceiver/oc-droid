package cn.vectory.ocdroid.ui

import android.util.Log
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.ui.controller.CachedSessionWindow
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
        // §chat-ux-batch T8 (B3): mock setup for getAgentForSession /
        // getModelForSession removed (deleted APIs).
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
        coEvery { repository.getMessagesPaged("s1", any(), any(), any()) } returns Result.success(MessagesPage(msgs, null))
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
        coEvery { repository.getMessagesPaged("s1", any(), any(), any()) } returns Result.failure(IllegalStateException("500"))
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
        coEvery { repository.getMessagesPaged(any(), any(), any(), any()) } returns Result.success(MessagesPage(emptyList(), null))
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
        coVerify(exactly = 0) { repository.getMessagesPaged(any(), any(), any(), any()) }
        assertTrue(slices.chat.value.isLoadingMessages) // still true (we did not touch it)
    }

    @Test
    fun `launchLoadMessages does not write messages for a non-current session`() = runTest {
        val msgs = listOf(MessageWithParts(info = Message(id = "x1", role = "user")))
        coEvery { repository.getMessagesPaged("other", any(), any(), any()) } returns Result.success(MessagesPage(msgs, null))
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
        // §history-load-fix round-2 (gpter 🟠): the stale (non-current session)
        // load does NOT clear isLoadingMessages — the session-guarded finally
        // only clears for the current session so it can't clobber a new
        // session's flag. In production a session switch (SessionSwitcher)
        // resets chat state including this flag; this test isolates the load
        // without the switch, so the flag the stale load set remains true.
        assertTrue(slices.chat.value.isLoadingMessages)
    }

    @Test
    fun `launchLoadMessages resetLimit=true clears streaming overlay when session finalized`() = runTest {
        val msgs = listOf(MessageWithParts(info = Message(id = "a1", role = "assistant")))
        coEvery { repository.getMessagesPaged("s1", any(), any(), any()) } returns Result.success(MessagesPage(msgs, null))
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
        coEvery { repository.getMessagesPaged("s1", any(), any(), any()) } returns Result.success(MessagesPage(msgs, null))
        coEvery { repository.getSessionTodos("s1") } returns Result.success(emptyList())
        store.mutateChat {
            it.copy(
                currentSessionId = "s1",
                streamingPartTexts = mapOf("p1" to "partial"),
            )
        }
        store.mutateSessionList {
            it.withProjection(mapOf("s1" to SessionStatus(type = "busy")))
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
        coEvery { repository.getMessagesPaged("s1", any(), any(), any()) } returns Result.success(MessagesPage(fetched, null))
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
        coEvery { repository.getMessagesPaged("s1", any(), any(), any()) } returns Result.success(MessagesPage(fetched, nextCursor = "cursor-1"))
        coEvery { repository.getSessionTodos("s1") } returns Result.success(emptyList())
        store.mutateChat { it.copy(currentSessionId = "s1") }

        launchLoadMessages(scope, repository, slices, "s1", resetLimit = true, emit = emit)
        advanceUntilIdle()

        assertEquals("cursor-1", slices.chat.value.olderMessagesCursor)
        assertTrue(slices.chat.value.hasMoreMessages)
    }

    @Test
    fun `launchLoadMessages forceInitialWindow=true routes to getMessagesPagedUnanchored (empty-window-fix)`() = runTest {
        // §empty-window-fix: forceInitialWindow=true (ONLY the VerifyAndHydrate
        // cold-load branch sets it) must call getMessagesPagedUnanchored (the
        // UNANCHORED slim fetch, since=0L) — NOT the anchored
        // getMessagesPaged. The merge / cursor-seeding logic is identical
        // (both return MessagesPage), so the result hydration + cursor seed
        // behave the same as resetLimit=true on the anchored path.
        val fetched = listOf(MessageWithParts(info = Message(id = "m1", role = "user")))
        coEvery { repository.getMessagesPagedUnanchored("s1", any(), any(), any()) } returns
            Result.success(MessagesPage(fetched, nextCursor = "cursor-init"))
        coEvery { repository.getSessionTodos("s1") } returns Result.success(emptyList())
        store.mutateChat { it.copy(currentSessionId = "s1") }

        launchLoadMessages(
            scope = scope,
            repository = repository,
            slices = slices,
            sessionId = "s1",
            resetLimit = true,
            emit = emit,
            forceInitialWindow = true,
        )
        advanceUntilIdle()

        // Messages hydrated from the unanchored fetch.
        assertEquals(listOf("m1"), slices.chat.value.messages.map { it.id })
        // Cursor seeded from the unanchored fetch's nextCursor (same as the
        // anchored resetLimit=true path — preserves loadMore continuity).
        assertEquals("cursor-init", slices.chat.value.olderMessagesCursor)
        assertTrue(slices.chat.value.hasMoreMessages)
        // The unanchored method was called.
        coVerify(atLeast = 1) { repository.getMessagesPagedUnanchored("s1", any(), any(), any()) }
        // The anchored method was NOT called.
        coVerify(exactly = 0) { repository.getMessagesPaged(any(), any(), any(), any()) }
    }

    @Test
    fun `launchLoadMessages rebuilds cursor when resetLimit=false but cursor is unseeded`() = runTest {
        // §F3-rebuild: 缓存水合后 olderMessagesCursor=null / hasMoreMessages=false（toWindow
        // 重建结果）。Verified 分支的跟随加载是 resetLimit=false——此时必须用 page.nextCursor
        // 重建 cursor/hasMore，否则"加载更多"按钮永不出现（从死按钮矫枉过正成无按钮）。
        val fetched = listOf(MessageWithParts(info = Message(id = "m1", role = "user")))
        coEvery { repository.getMessagesPaged("s1", any(), any(), any()) } returns Result.success(MessagesPage(fetched, nextCursor = "cursor-1"))
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
        coEvery { repository.getMessagesPaged("s1", any(), any(), any()) } returns Result.success(MessagesPage(fetched, nextCursor = "server-new-cursor"))
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
        coEvery { repository.getMessagesPaged("s1", any(), any(), any()) } returns Result.success(MessagesPage(emptyList(), null))
        coEvery { repository.getSessionTodos("s1") } returns Result.success(listOf(todo))
        store.mutateChat { it.copy(currentSessionId = "s1") }

        launchLoadMessages(scope, repository, slices, "s1", emit = emit)
        advanceUntilIdle()

        assertEquals(listOf(todo), slices.sessionList.value.sessionTodos["s1"])
    }

    // §chat-ux-batch T8 (B3): the former test
    // `launchLoadMessages syncs selectedAgentName from per-session override`
    // was DELETED here. It exercised the legacy global←per-session
    // selectedAgentName backfill in launchLoadMessages — both the backfill
    // block and the field were deleted in T8 (T7 rewired agent selection to
    // the TRANSIENT pendingAgent chat-slice field).

    // ── launchLoadMoreMessages ────────────────────────────────────────────────

    @Test
    fun `launchLoadMoreMessages no-ops when hasMoreMessages is false`() = runTest {
        store.mutateChat { it.copy(currentSessionId = "s1", hasMoreMessages = false, olderMessagesCursor = "c1") }

        launchLoadMoreMessages(scope, repository, slices, "s1")
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.getMessagesPaged(any(), any(), any(), any()) }
    }

    @Test
    fun `launchLoadMoreMessages no-ops when cursor is null`() = runTest {
        store.mutateChat { it.copy(currentSessionId = "s1", hasMoreMessages = true, olderMessagesCursor = null) }

        launchLoadMoreMessages(scope, repository, slices, "s1")
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.getMessagesPaged(any(), any(), any(), any()) }
    }

    @Test
    fun `launchLoadMoreMessages no-ops when isLoadingMoreMessages already true`() = runTest {
        store.mutateChat {
            it.copy(currentSessionId = "s1", hasMoreMessages = true, olderMessagesCursor = "c1", isLoadingMoreMessages = true)
        }

        launchLoadMoreMessages(scope, repository, slices, "s1")
        advanceUntilIdle()

        // §history-load-fix: self-reentry (rapid double-click / fast scroll)
        // coalesces on the OWN flag (isLoadingMoreMessages), not the background
        // reload flag.
        coVerify(exactly = 0) { repository.getMessagesPaged(any(), any(), any(), any()) }
    }

    @Test
    fun `launchLoadMoreMessages proceeds when background isLoadingMessages is true (history-load-fix regression)`() = runTest {
        // §history-load-fix: the 0.6.0 "加载历史对话需要多次点击" regression — a
        // background reload holding isLoadingMessages used to silently swallow
        // the user's "load more" click (the three load paths shared one guard).
        // loadMore now uses its own isLoadingMoreMessages flag + a session
        // mutex, so the click proceeds even while isLoadingMessages=true.
        val existing = Message(id = "cur1", role = "user", time = Message.TimeInfo(created = 500L))
        val olderPage = listOf(
            MessageWithParts(info = Message(id = "old1", role = "user", time = Message.TimeInfo(created = 100L))),
        )
        coEvery { repository.getMessagesPaged("s1", any(), eq("c1"), any()) } returns Result.success(MessagesPage(olderPage, nextCursor = "c2"))
        store.mutateChat {
            it.copy(
                currentSessionId = "s1",
                messages = listOf(existing),
                olderMessagesCursor = "c1",
                hasMoreMessages = true,
                // A background reload is in flight — the OLD shared-flag guard
                // would have dropped the click here.
                isLoadingMessages = true,
            )
        }

        launchLoadMoreMessages(scope, repository, slices, "s1")
        advanceUntilIdle()

        // The click was NOT swallowed: a fetch was issued...
        coVerify(exactly = 1) { repository.getMessagesPaged("s1", any(), eq("c1"), any()) }
        // ...the older page was prepended...
        assertEquals(listOf("old1", "cur1"), slices.chat.value.messages.map { it.id })
        // ...loadMore's own flag cleared on completion...
        assertFalse(slices.chat.value.isLoadingMoreMessages)
        // ...and the background reload flag was left untouched by loadMore.
        assertTrue("background isLoadingMessages must be left untouched by loadMore", slices.chat.value.isLoadingMessages)
    }

    @Test
    fun `launchLoadMoreMessages success prepends older page with cursor update`() = runTest {
        val existing = Message(id = "cur1", role = "user", time = Message.TimeInfo(created = 500L))
        val olderPage = listOf(
            MessageWithParts(info = Message(id = "old1", role = "user", time = Message.TimeInfo(created = 100L))),
        )
        coEvery { repository.getMessagesPaged("s1", any(), eq("cursor-1"), any()) } returns Result.success(MessagesPage(olderPage, nextCursor = "cursor-2"))
        store.mutateChat {
            it.copy(currentSessionId = "s1", messages = listOf(existing), olderMessagesCursor = "cursor-1", hasMoreMessages = true)
        }
        var cached: CachedSessionWindow? = null

        launchLoadMoreMessages(scope, repository, slices, "s1") { _, w -> cached = w }
        advanceUntilIdle()

        assertEquals(listOf("old1", "cur1"), slices.chat.value.messages.map { it.id })
        assertEquals("cursor-2", slices.chat.value.olderMessagesCursor)
        assertFalse(slices.chat.value.isLoadingMoreMessages)
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
        coEvery { repository.getMessagesPaged(any(), any(), any(), any()) } returns Result.success(MessagesPage(olderPage, nextCursor = null))
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
        coEvery { repository.getMessagesPaged(any(), any(), any(), any()) } returns Result.failure(IllegalStateException("timeout"))
        store.mutateChat {
            it.copy(currentSessionId = "s1", olderMessagesCursor = "c1", hasMoreMessages = true)
        }

        launchLoadMoreMessages(scope, repository, slices, "s1")
        advanceUntilIdle()

        assertFalse(slices.chat.value.isLoadingMoreMessages)
        // Manual paging: hasMore kept so the user can retry.
        assertTrue(slices.chat.value.hasMoreMessages)
    }

    @Test
    fun `launchLoadMoreMessages empty page keeps messages and updates cursor`() = runTest {
        val existing = Message(id = "cur1", role = "user")
        coEvery { repository.getMessagesPaged(any(), any(), any(), any()) } returns Result.success(MessagesPage(emptyList(), nextCursor = null))
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
        // (collision: same sessionId, different profileId). The REST
        // response from G1 must NOT write G1's messages into G2's chat slice.
        // sessionId guard alone passes (s1==s1); the fp guard catches the
        // cross-group collision.
        val msgs = listOf(MessageWithParts(info = Message(id = "g1-msg", role = "user")))
        coEvery { repository.getMessagesPaged("s1", any(), any(), any()) } returns Result.success(MessagesPage(msgs, null))
        coEvery { repository.getSessionTodos("s1") } returns Result.success(emptyList())
        store.mutateChat { it.copy(currentSessionId = "s1") }

        // Launch with expectedProfileId = "g1" (the old group).
        // The currentProfileId provider returns "g2" (the new group,
        // simulating a host switch that happened during the REST call).
        launchLoadMessages(
            scope = scope,
            repository = repository,
            slices = slices,
            sessionId = "s1",
            onCacheWindow = { _, _ -> },
            emit = emit,
            expectedProfileId = "g1",
            currentProfileId = { "g2" }, // simulate post-switch fp
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
        coEvery { repository.getMessagesPaged("s1", any(), any(), any()) } returns Result.success(MessagesPage(msgs, null))
        coEvery { repository.getSessionTodos("s1") } returns Result.success(emptyList())
        store.mutateChat { it.copy(currentSessionId = "s1") }

        launchLoadMessages(
            scope = scope,
            repository = repository,
            slices = slices,
            sessionId = "s1",
            onCacheWindow = { _, _ -> },
            emit = emit,
            expectedProfileId = "g1",
            currentProfileId = { "g1" }, // same fp → guard passes
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
        coEvery { repository.getMessagesPaged("s1", any(), any(), any()) } returns Result.success(MessagesPage(msgs, null))
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
        coEvery { repository.getMessagesPaged("s1", any(), eq("c1"), any()) } returns Result.success(MessagesPage(olderPage, nextCursor = "c2"))
        store.mutateChat {
            it.copy(currentSessionId = "s1", messages = listOf(existing), olderMessagesCursor = "c1", hasMoreMessages = true)
        }

        launchLoadMoreMessages(
            scope = scope,
            repository = repository,
            slices = slices,
            sessionId = "s1",
            expectedProfileId = "g1",
            currentProfileId = { "g2" }, // simulate post-switch fp
            onCacheWindow = { _, _ -> },
        )
        advanceUntilIdle()

        // G1's older page must NOT be prepended — the fp guard dropped it.
        assertEquals(
            "stale G1 older-page response must not write after host group switch",
            listOf("cur1"),
            slices.chat.value.messages.map { it.id },
        )
        assertFalse(slices.chat.value.isLoadingMoreMessages)
    }

    @Test
    fun `gpter-final-fix launchLoadMoreMessages writes when fp matches (happy path)`() = runTest {
        val existing = Message(id = "cur1", role = "user", time = Message.TimeInfo(created = 500L))
        val olderPage = listOf(
            MessageWithParts(info = Message(id = "old1", role = "user", time = Message.TimeInfo(created = 100L))),
        )
        coEvery { repository.getMessagesPaged("s1", any(), eq("c1"), any()) } returns Result.success(MessagesPage(olderPage, nextCursor = null))
        store.mutateChat {
            it.copy(currentSessionId = "s1", messages = listOf(existing), olderMessagesCursor = "c1", hasMoreMessages = true)
        }

        launchLoadMoreMessages(
            scope = scope,
            repository = repository,
            slices = slices,
            sessionId = "s1",
            expectedProfileId = "g1",
            currentProfileId = { "g1" },
            onCacheWindow = { _, _ -> },
        )
        advanceUntilIdle()

        assertEquals(listOf("old1", "cur1"), slices.chat.value.messages.map { it.id })
    }

    // ── streaming-send-ux-fix regression: swallowed-reload timing ─────────────
    //
    // §context: the post-send full-list refresh (onRefreshSessions fan-out,
    // removed in this commit) used to start an IMMEDIATE launchLoadMessages
    // that occupied the single-flight isLoadingMessages slot. The better-
    // timed 400ms post-send targeted reload (loadMessagesWithRetry →
    // launchLoadMessagesWithRetry → onLoadMessages → launchLoadMessages) was
    // then DISCARDED by launchLoadMessages' `if (isLoadingMessages) return`
    // guard (MessageActions.kt:56-59) with NO trailing-edge retry → stale
    // transcript committed, new user message hidden until the user switched
    // away and back. These two tests pin the timing at JVM level using a
    // gate-controlled repository mock + the test scheduler.

    @Test
    fun `streaming-send-ux regression - immediate post-send load swallows the delayed targeted reload (bug shape)`() = runTest {
        // Reproduces the message-half of the bug at JVM level. The immediate
        // post-send load (the buggy fan-out) returns a STALE transcript; the
        // delayed targeted reload (which would carry the fresh user message)
        // is discarded by the single-flight guard because the immediate load
        // is still in flight.
        val immediateLoadGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { repository.getMessagesPaged("s1", any(), any(), any()) } coAnswers {
            immediateLoadGate.await()
            Result.success(MessagesPage(emptyList(), null))
        }
        coEvery { repository.getSessionTodos("s1") } returns Result.success(emptyList())
        store.mutateChat { it.copy(currentSessionId = "s1") }

        // Simulate the (now-removed) buggy fan-out: the post-send full-list
        // refresh triggered an immediate launchLoadMessages that holds the
        // single-flight slot. The unconfined dispatcher runs the body up to
        // repository.getMessagesPaged, which suspends on the gate →
        // isLoadingMessages stays true.
        launchLoadMessages(
            scope = scope,
            repository = repository,
            slices = slices,
            sessionId = "s1",
            emit = emit,
        )
        // DO NOT advance time / complete the gate — keep the immediate load
        // in flight (the buggy race window).
        assertTrue("immediate load must hold isLoadingMessages=true", slices.chat.value.isLoadingMessages)

        // Simulate the post-send delayed targeted reload (the survivor of the
        // fix). The callback mirrors the real wiring (loadMessagesWithRetry →
        // loadMessagesForEffect → launchLoadMessages).
        launchLoadMessagesWithRetry(scope, "s1", slices, resetLimit = true) { sid, reset ->
            launchLoadMessages(
                scope = scope,
                repository = repository,
                slices = slices,
                sessionId = sid,
                resetLimit = reset,
                emit = emit,
            )
        }
        // Advance past the retry delay — the delayed callback fires.
        scope.advanceTimeBy(MainViewModelTimings.messageRetryDelayMs)
        scope.advanceUntilIdle()
        scope.runCurrent()

        // The delayed callback's launchLoadMessages call hit the
        // single-flight guard and returned WITHOUT calling
        // repository.getMessagesPaged. Exactly ONE call observed — the
        // immediate load's. This is the bug: the delayed reload was swallowed.
        coVerify(exactly = 1) { repository.getMessagesPaged("s1", any(), any(), any()) }
        assertTrue(
            "immediate load still in flight (delayed reload did not clear the flag)",
            slices.chat.value.isLoadingMessages,
        )

        // Complete the gate to let the immediate load finish with its STALE
        // (empty) transcript, then assert the slice reflects ONLY the stale
        // result — the fresh delayed reload never ran.
        immediateLoadGate.complete(Unit)
        scope.advanceUntilIdle()
        scope.runCurrent()
        assertTrue(
            "stale empty transcript committed; the fresh delayed reload was discarded (bug reproducible at JVM)",
            slices.chat.value.messages.isEmpty(),
        )
        assertFalse(slices.chat.value.isLoadingMessages)
    }

    @Test
    fun `streaming-send-ux regression - without the immediate post-send load (fix applied), the delayed targeted reload executes`() = runTest {
        // Pins the FIX shape: with the post-send immediate load REMOVED (this
        // commit), nothing occupies the single-flight slot during the 400ms
        // retry delay. The delayed targeted reload — which carries the fresh
        // user message via the better-timed refresh — executes and surfaces
        // the new prompt.
        val freshMsgs = listOf(MessageWithParts(info = Message(id = "user-prompt", role = "user")))
        coEvery { repository.getMessagesPaged("s1", any(), any(), any()) } returns Result.success(MessagesPage(freshMsgs, null))
        coEvery { repository.getSessionTodos("s1") } returns Result.success(emptyList())
        store.mutateChat { it.copy(currentSessionId = "s1") }

        // NO immediate launchLoadMessages call here — the fix removed the
        // post-send fan-out. isLoadingMessages is still false.
        assertFalse(
            "no send-triggered immediate load may occupy the slot (fix applied)",
            slices.chat.value.isLoadingMessages,
        )

        // The sole post-send fallback: the delayed targeted reload.
        launchLoadMessagesWithRetry(scope, "s1", slices, resetLimit = true) { sid, reset ->
            launchLoadMessages(
                scope = scope,
                repository = repository,
                slices = slices,
                sessionId = sid,
                resetLimit = reset,
                emit = emit,
            )
        }
        // Advance past the retry delay — the callback fires.
        scope.advanceTimeBy(MainViewModelTimings.messageRetryDelayMs)
        scope.advanceUntilIdle()
        scope.runCurrent()

        // The delayed reload's launchLoadMessages call LANDED — repository
        // fetched exactly once (the fresh transcript), the slot is no longer
        // starved.
        coVerify(exactly = 1) { repository.getMessagesPaged("s1", any(), any(), any()) }
        assertEquals(
            "fresh user message surfaced via the delayed targeted reload (the fix)",
            listOf("user-prompt"),
            slices.chat.value.messages.map { it.id },
        )
        assertFalse(slices.chat.value.isLoadingMessages)
    }

    // ── P0-2 freeze: SSE-off / terminal-degraded foreground observability ──────
    //
    // §context: "SSE-off" = the debug sse_disabled=true flag (REST-only
    // degraded mode, ConnectionPhase.SseDisabled) OR a real terminal outage
    // (ConnectionPhase.Disconnected past the 90s
    // SSE_DISCONNECT_UNANCHORED_THRESHOLD_MS). "terminal-degraded" = the slim
    // repository is stuck mid-reconfigure (every GET returns
    // StaleSlimCommitException because the SSE-driven reconfigure never
    // settles). The contract: a foreground chat open under these conditions
    // must NOT silently leave the window blank.
    //
    // §evidence — the existing launchLoadMessages logic ALREADY covers this
    // via four mechanisms (NO new poller / mutation retry needed):
    //
    //  ① forceInitialWindow=true (set by performGlobalColdStartRefresh /
    //     performForceRefresh / VerifyAndHydrate cold-load) routes the slim
    //     fetch UNANCHORED (getMessagesPagedUnanchored → since=0L), bypassing
    //     a stale slim watermark. (MessageActions.kt:124-128)
    //
    //  ② §stale-retry-fix: a StaleSlimCommitException from the slim GET
    //     (mid-reconfigure on SSE reconnect / resume / host switch — the
    //     exact terminal-degraded transition window) is retried up to 2
    //     times with 500ms delay, covering the typical ~1s reconfigure
    //     settle. Applies to BOTH forceInitialWindow branches.
    //     (MessageActions.kt:129-139)
    //
    //  ③ §history-load-fix: after retry exhaustion (or a non-stale failure),
    //     onFailure emits UiEvent.Error(R.string.error_load_messages_failed)
    //     for the CURRENT session — the user sees a visible error, NOT a
    //     silent blank. (MessageActions.kt:513-526)
    //
    //  ④ CancellationException is NEVER swallowed: runSuspendCatching (R-14)
    //     in MessageSource re-throws it; launchLoadMessages' outer
    //     try/catch re-throws it; the finally clears the loading flag.
    //     (MessageActions.kt:552-554). launchLoadMessages issues ONLY GET
    //     fetches (getMessagesPaged* + getSessionTodos) — it never touches
    //     a mutation POST, so "no auto-retry mutation POST" is structural.
    //
    // The tests below pin each guarantee so a future refactor cannot regress
    // the P0-2 freeze contract.

    @Test
    fun `P0-2 forceInitialWindow=true retries StaleSlimCommitException and recovers foreground messages (SSE-off cold-load)`(): Unit = runTest {
        // §11.1 fix-9 P0-7 (sse-sync-degradation-remediation.md P0-2): the
        // SSE-off cold-load path (forceInitialWindow=true + resetLimit=true)
        // retries ONCE (not 2×) via getMessagesPagedUnanchored when the first
        // fetch fails with a StaleSlimCommitException (a java.io.IOException
        // subclass). The stale-retry-fix (unconditional 2-retry) was removed
        // in V2 — the only retry is the P0-7 SSE-off first-fetch retry.
        // Mock: first attempt fails stale → retry succeeds.
        val msgs = listOf(MessageWithParts(info = Message(id = "m1", role = "user")))
        coEvery { repository.getMessagesPagedUnanchored("s1", any(), any(), any()) } returnsMany listOf(
            Result.failure(OpenCodeRepository.StaleSlimCommitException()),
            Result.success(MessagesPage(msgs, nextCursor = null)),
        )
        coEvery { repository.getSessionTodos("s1") } returns Result.success(emptyList())
        store.mutateChat { it.copy(currentSessionId = "s1") }

        launchLoadMessages(
            scope = scope,
            repository = repository,
            slices = slices,
            sessionId = "s1",
            resetLimit = true,
            forceInitialWindow = true,
            emit = emit,
            isSseLive = { false }, // SSE-off → retry engages
        )
        // §note: scope (setUp) has its own TestCoroutineScheduler separate from
        // runTest's; the retry loop's delay(500) suspends on scope's scheduler,
        // so we must advance SCOPE's scheduler (mirrors the existing
        // launchLoadMessagesWithRetry delay-based tests).
        scope.advanceUntilIdle()
        scope.runCurrent()

        // 2 attempts: initial + 1 retry (the P0-7 SSE-off single-retry).
        coVerify(exactly = 2) { repository.getMessagesPagedUnanchored("s1", any(), any(), any()) }
        // Foreground window recovered — NOT a silent blank.
        assertEquals(listOf("m1"), slices.chat.value.messages.map { it.id })
        // Retry succeeded → no user-facing error.
        assertTrue(emitted.filterIsInstance<UiEvent.Error>().isEmpty())
        assertFalse(slices.chat.value.isLoadingMessages)
    }

    @Test
    fun `P0-2 forceInitialWindow=true emits UiEvent Error after stale retry exhaustion (terminal-degraded is observable, not silent blank)`(): Unit = runTest {
        // §11.1 fix-9 P0-7: V2 retries only ONCE under SSE-off (not 2×).
        // Terminal-degraded: every slim GET returns StaleSlimCommitException.
        // After 1 initial + 1 retry (2 attempts total) the load MUST surface
        // a user-visible error instead of leaving the foreground chat blank.
        coEvery { repository.getMessagesPagedUnanchored("s1", any(), any(), any()) } returns
            Result.failure(OpenCodeRepository.StaleSlimCommitException())
        coEvery { repository.getSessionTodos("s1") } returns Result.success(emptyList())
        store.mutateChat { it.copy(currentSessionId = "s1") }

        launchLoadMessages(
            scope = scope,
            repository = repository,
            slices = slices,
            sessionId = "s1",
            resetLimit = true,
            forceInitialWindow = true,
            emit = emit,
            isSseLive = { false }, // SSE-off → retry engages
        )
        // §note: advance SCOPE's scheduler (separate from runTest's) past the
        // single retry delay(500).
        scope.advanceUntilIdle()
        scope.runCurrent()

        // 2 attempts then give up (P0-7 single-retry ceiling).
        coVerify(exactly = 2) { repository.getMessagesPagedUnanchored("s1", any(), any(), any()) }
        // §the-key-assertion: the failure is OBSERVABLE — UiEvent.Error fires
        // for the current (foreground) session. The window is empty (no data)
        // but the user is told why, so it is NOT a silent blank.
        val err = emitted.filterIsInstance<UiEvent.Error>().single()
        assertEquals(R.string.error_load_messages_failed, err.resId)
        assertTrue(slices.chat.value.messages.isEmpty())
        assertFalse(slices.chat.value.isLoadingMessages)
    }

    @Test
    fun `P0-2 anchored cold-load (forceInitialWindow=false) retries StaleSlimCommitException once via unanchored`(): Unit = runTest {
        // §11.1 fix-9 P0-7: V2 retries only on resetLimit=true + SSE-off,
        // and always via getMessagesPagedUnanchored (not getMessagesPaged).
        // The old "periodic reload (resetLimit=false) also retries stale"
        // behavior was removed. This test covers the anchored cold-load path
        // (forceInitialWindow=false, resetLimit=true): the initial anchored
        // fetch (getMessagesPaged) fails stale → P0-7 retries once via
        // getMessagesPagedUnanchored and recovers.
        val msgs = listOf(MessageWithParts(info = Message(id = "m1", role = "user")))
        coEvery { repository.getMessagesPaged("s1", any(), any(), any()) } returns
            Result.failure(OpenCodeRepository.StaleSlimCommitException())
        coEvery { repository.getMessagesPagedUnanchored("s1", any(), any(), any()) } returns
            Result.success(MessagesPage(msgs, nextCursor = null))
        coEvery { repository.getSessionTodos("s1") } returns Result.success(emptyList())
        store.mutateChat { it.copy(currentSessionId = "s1") }

        launchLoadMessages(
            scope = scope,
            repository = repository,
            slices = slices,
            sessionId = "s1",
            resetLimit = true,       // retry requires resetLimit=true in V2
            forceInitialWindow = false, // anchored: uses getMessagesPaged
            emit = emit,
            isSseLive = { false },   // SSE-off → retry engages
        )
        // §note: advance SCOPE's scheduler (separate from runTest's) past the
        // retry delay(500).
        scope.advanceUntilIdle()
        scope.runCurrent()

        // 1 anchored fetch + 1 unanchored retry. atLeast=1 is intentional:
        // exactly=1 was attempted (rev-gpt MINOR #6) but mockk's default-arg
        // matcher-recording interaction with suspend functions that have
        // default parameters prevents exact-count verification via partial
        // matchers. The 4-param matcher test at :1181 independently pins
        // these exact counts (exactly=1 anchored + exactly=1 unanchored).
        coVerify(atLeast = 1) { repository.getMessagesPaged("s1", any(), any(), any()) }
        coVerify(atLeast = 1) { repository.getMessagesPagedUnanchored("s1", any(), any(), any()) }
        assertEquals(listOf("m1"), slices.chat.value.messages.map { it.id })
        assertTrue(emitted.filterIsInstance<UiEvent.Error>().isEmpty())
    }

    @Test
    fun `P0-2 non-stale foreground failure emits UiEvent Error immediately (observable, no retry)`(): Unit = runTest {
        // §mechanism ③: a generic transport failure (HTTP 503 / network) is
        // NOT a stale token, so the retry loop does NOT engage — but the
        // failure is STILL observable: UiEvent.Error fires immediately so
        // the user doesn't stare at a silent blank under SSE-off / degraded
        // transport.
        // §11.1 fix-9 P0-7: the SSE-off first-fetch retry (P0-7) is
        // DISABLED here by passing `isSseLive = { true }` — SSE is "live"
        // so the P0-7 retry does not fire. The P0-7-specific behavior
        // (SSE-off → one extra retry) is covered by
        // `P0-7 SSE-off first-fetch failure retries once via unanchored`.
        coEvery { repository.getMessagesPaged("s1", any(), any(), any()) } returns
            Result.failure(java.io.IOException("HTTP 503"))
        coEvery { repository.getSessionTodos("s1") } returns Result.success(emptyList())
        store.mutateChat { it.copy(currentSessionId = "s1") }

        launchLoadMessages(scope, repository, slices, "s1", emit = emit, isSseLive = { true })
        advanceUntilIdle()

        // Exactly 1 attempt — non-stale failures don't retry when SSE is live.
        coVerify(exactly = 1) { repository.getMessagesPaged("s1", any(), any(), any()) }
        val err = emitted.filterIsInstance<UiEvent.Error>().single()
        assertEquals(R.string.error_load_messages_failed, err.resId)
        assertTrue("failure cause surfaced to the user", err.args.any { it.toString().contains("503") })
        assertFalse(slices.chat.value.isLoadingMessages)
    }

    @Test
    fun `P0-7 SSE-off first-fetch failure retries once via unanchored REST`() = runTest {
        // §11.1 fix-9 P0-7 (sse-sync-degradation-remediation.md P0-2):
        // when SSE transport is NOT live AND the first fetch fails with a
        // non-stale IOException, launchLoadMessages retries ONCE via the
        // unanchored path. Mock the first call (getMessagesPaged) to fail
        // with IOException, then the unanchored retry call to succeed.
        // Asserts: exactly 1 getMessagesPaged call AND at-least 1
        // getMessagesPagedUnanchored call (the P0-7 retry).
        coEvery { repository.getMessagesPaged("s1", any(), any(), any()) } returns
            Result.failure(java.io.IOException("HTTP 503"))
        coEvery { repository.getMessagesPagedUnanchored("s1", any(), any(), any()) } returns
            Result.success(MessagesPage(emptyList(), null))
        coEvery { repository.getSessionTodos("s1") } returns Result.success(emptyList())
        store.mutateChat { it.copy(currentSessionId = "s1") }

        launchLoadMessages(
            scope, repository, slices, "s1",
            emit = emit,
            isSseLive = { false }, // P0-7: SSE-off → retry engages
        )
        // §note: scope (setUp) has its own TestCoroutineScheduler separate
        // from runTest's; the retry loop's delay(500) suspends on scope's
        // scheduler, so we must advance SCOPE's scheduler (mirrors the
        // existing launchLoadMessagesWithRetry delay-based tests).
        scope.advanceUntilIdle()
        scope.runCurrent()

        // Exactly 1 first-fetch (getMessagesPaged) + at-least 1 P0-7 retry
        // (getMessagesPagedUnanchored). We use atLeast for both to avoid
        // mockk's default-arg matcher-recording interaction: each fetch's
        // default `token = captureSlimCommitToken()` is recorded as a
        // separate call, so `exactly = 1` on getMessagesPaged would also
        // pin captureSlimCommitToken to exactly 1 (and we have 2 — one per
        // fetch). The semantic check (1 first-fetch + retry happened) is
        // preserved by `atLeast = 1` on each.
        coVerify(atLeast = 1) { repository.getMessagesPaged("s1", any(), any(), any()) }
        coVerify(atLeast = 1) { repository.getMessagesPagedUnanchored("s1", any(), any(), any()) }
        // No error — the retry succeeded (empty success is the "no history"
        // legitimate state).
        assertTrue(
            "P0-7: SSE-off retry success suppresses UiEvent.Error (got $emitted)",
            emitted.filterIsInstance<UiEvent.Error>().isEmpty(),
        )
        assertFalse(slices.chat.value.isLoadingMessages)
    }

    @Test
    fun `P0-7 SSE-off first-fetch failure retries once and surfaces Error when retry also fails`() = runTest {
        // §11.1 fix-9 P0-7: SSE-off + first-fetch failure + retry also
        // fails → UiEvent.Error fires (the user is notified that the
        // foreground load could not complete).
        coEvery { repository.getMessagesPaged("s1", any(), any(), any()) } returns
            Result.failure(java.io.IOException("HTTP 503"))
        coEvery { repository.getMessagesPagedUnanchored("s1", any(), any(), any()) } returns
            Result.failure(java.io.IOException("HTTP 503 retry"))
        coEvery { repository.getSessionTodos("s1") } returns Result.success(emptyList())
        store.mutateChat { it.copy(currentSessionId = "s1") }

        launchLoadMessages(
            scope, repository, slices, "s1",
            emit = emit,
            isSseLive = { false }, // P0-7: SSE-off → retry engages
        )
        scope.advanceUntilIdle()
        scope.runCurrent()

        coVerify(atLeast = 1) { repository.getMessagesPaged("s1", any(), any(), any()) }
        coVerify(atLeast = 1) { repository.getMessagesPagedUnanchored("s1", any(), any(), any()) }
        val err = emitted.filterIsInstance<UiEvent.Error>().single()
        assertEquals(R.string.error_load_messages_failed, err.resId)
        assertFalse(slices.chat.value.isLoadingMessages)
    }

    @Test
    fun `P0-7 SSE-on first-fetch failure does NOT engage P0-7 retry`() = runTest {
        // §11.1 fix-9 P0-7: when SSE transport IS live, the P0-7 retry
        // does NOT fire (the SSE digest relay will deliver updates; the
        // REST retry is unnecessary).
        coEvery { repository.getMessagesPaged("s1", any(), any(), any()) } returns
            Result.failure(java.io.IOException("HTTP 503"))
        coEvery { repository.getSessionTodos("s1") } returns Result.success(emptyList())
        store.mutateChat { it.copy(currentSessionId = "s1") }

        launchLoadMessages(
            scope, repository, slices, "s1",
            emit = emit,
            isSseLive = { true }, // P0-7: SSE-on → no retry
        )
        scope.advanceUntilIdle()
        scope.runCurrent()

        coVerify(atLeast = 1) { repository.getMessagesPaged("s1", any(), any(), any()) }
        coVerify(exactly = 0) { repository.getMessagesPagedUnanchored(any(), any(), any(), any()) }
        emitted.filterIsInstance<UiEvent.Error>().single()
        assertFalse(slices.chat.value.isLoadingMessages)
    }

    @Test
    fun `P1-4 non-IOException does NOT trigger SSE-off retry`() = runTest {
        // §11.1 fix-10 P1-4 (rev-ogpt 五轮评审): the SSE-off retry condition is
        // narrowed to IOException ONLY (MessageActions.kt:181 `retryCause is
        // java.io.IOException`). A programming error (RuntimeException /
        // ClassCastException / etc.) must NOT engage the retry even when SSE
        // is off — retrying a crash-inducing call would mask bugs (double the
        // crash before reporting). The failure surfaces UiEvent.Error
        // immediately on the first fetch.
        coEvery { repository.getMessagesPaged("s1", any(), any(), any()) } returns
            Result.failure(RuntimeException("programming error"))
        coEvery { repository.getSessionTodos("s1") } returns Result.success(emptyList())
        store.mutateChat { it.copy(currentSessionId = "s1") }

        launchLoadMessages(
            scope, repository, slices, "s1",
            emit = emit,
            isSseLive = { false }, // SSE-off — but retry MUST NOT fire (non-IOException)
        )
        scope.advanceUntilIdle()
        scope.runCurrent()

        // No retry: unanchored NEVER called.
        coVerify(exactly = 0) { repository.getMessagesPagedUnanchored(any(), any(), any(), any()) }
        // RuntimeException surfaces as UiEvent.Error immediately (not swallowed,
        // not retried).
        val err = emitted.filterIsInstance<UiEvent.Error>().single()
        assertEquals(R.string.error_load_messages_failed, err.resId)
        assertTrue(
            "non-IOException cause surfaced to the user",
            err.args.any { it.toString().contains("programming error") },
        )
        assertFalse(slices.chat.value.isLoadingMessages)
    }

    @Test
    fun `P1-4 CancellationException in Result failure is re-thrown by retry guard`(): Unit = runTest {
        // §11.1 fix-10 P1-4 (rev-ogpt 五轮评审): the retry-decision guard
        // (MessageActions.kt:176-178) checks the failure cause for
        // CancellationException FIRST and re-throws it, BEFORE evaluating the
        // IOException retry condition. This is a defensive backstop:
        // runSuspendCatching (R-14) already re-throws CancellationException so
        // the repository normally THROWS (not Result.failure) — covered by the
        // existing P0-2 test below. But if a future code path returns
        // Result.failure(CE), the guard ensures structured concurrency is
        // preserved (coroutine cancels cleanly, no retry, no misleading
        // UiEvent.Error). This test pins that guard.
        coEvery { repository.getMessagesPaged("s1", any(), any(), any()) } returns
            Result.failure(kotlinx.coroutines.CancellationException("cooperative cancel"))
        coEvery { repository.getSessionTodos("s1") } returns Result.success(emptyList())
        store.mutateChat { it.copy(currentSessionId = "s1") }

        launchLoadMessages(
            scope, repository, slices, "s1",
            emit = emit,
            isSseLive = { false }, // SSE-off — but retry MUST NOT fire (cancellation)
        )
        scope.advanceUntilIdle()
        scope.runCurrent()

        // CancellationException re-thrown by the guard → coroutine cancels →
        // onFailure NEVER reached → NO UiEvent.Error (cancellation is not a
        // user-facing error).
        assertTrue(
            "CancellationException (Result.failure path) must NOT emit UiEvent.Error",
            emitted.filterIsInstance<UiEvent.Error>().isEmpty(),
        )
        // No retry: unanchored NEVER called (cancellation propagated before retry).
        coVerify(exactly = 0) { repository.getMessagesPagedUnanchored(any(), any(), any(), any()) }
        // Session-guarded finally still cleared the loading flag on the cancel exit.
        assertFalse(slices.chat.value.isLoadingMessages)
    }

    @Test
    fun `P1-4 IOException under SSE-off retries EXACTLY once (initial plus single retry)`() = runTest {
        // §11.1 fix-10 P1-4 (rev-ogpt 五轮评审): tighten the existing P0-7
        // tests which use atLeast=1 — a weak assertion that would still pass
        // if the code regressed to retry 2+ times. This test pins the retry
        // count to EXACTLY one initial fetch + EXACTLY one retry (no infinite
        // loop, no double-retry). total fetch calls = 2 (1 getMessagesPaged
        // initial + 1 getMessagesPagedUnanchored retry).
        coEvery { repository.getMessagesPaged("s1", any(), any(), any()) } returns
            Result.failure(java.io.IOException("HTTP 503"))
        coEvery { repository.getMessagesPagedUnanchored("s1", any(), any(), any()) } returns
            Result.success(MessagesPage(emptyList(), null))
        coEvery { repository.getSessionTodos("s1") } returns Result.success(emptyList())
        store.mutateChat { it.copy(currentSessionId = "s1") }

        launchLoadMessages(
            scope, repository, slices, "s1",
            emit = emit,
            isSseLive = { false }, // SSE-off → retry engages
        )
        scope.advanceUntilIdle()
        scope.runCurrent()

        // Exactly one initial fetch + exactly one retry (2 total repository
        // fetch calls). Pins "retry exactly once" — a regression to 2+ retries
        // (e.g. a loop bug) would fail this assertion.
        //
        // §mockk-default-arg: provide ALL 4 matchers (including `token` — the
        // 4th `any()`) so Kotlin does NOT evaluate the default arg
        // `token = captureSlimCommitToken()` inside the verify lambda. With only
        // 3 matchers the default-arg evaluation is recorded as an extra mockk
        // call, making `exactly = 1` fail (2 captureSlimCommitToken calls —
        // one per fetch). This is the same root cause the existing P0-7 tests
        // paper over with `atLeast = 1`; providing 4 matchers is the precise
        // fix. (Mirrors the P0-7 SSE-on test which already uses 4 matchers for
        // its `exactly = 0` assertion on getMessagesPagedUnanchored.)
        coVerify(exactly = 1) { repository.getMessagesPaged("s1", any(), any(), any()) }
        coVerify(exactly = 1) { repository.getMessagesPagedUnanchored("s1", any(), any(), any()) }
        // Retry succeeded → no error surfaced.
        assertTrue(
            "P1-4: SSE-off single retry success suppresses UiEvent.Error (got $emitted)",
            emitted.filterIsInstance<UiEvent.Error>().isEmpty(),
        )
        assertFalse(slices.chat.value.isLoadingMessages)
    }

    @Test
    fun `fix-refresh-storm P0-1 retry returning CancellationException is re-thrown (no UiEvent Error)`(): Unit = runTest {
        // §fix-refresh-storm P0-1: the FIRST getMessagesPaged fails with
        // IOException under SSE-off → the retry path engages (delay 500 +
        // getMessagesPagedUnanchored). During that retry window a refresh-storm
        // supersedes this coroutine, so the retry returns
        // Result.failure(CancellationException("canceled")). The retry-path CE
        // guard (added in MessageActions.kt) MUST re-throw it so the coroutine
        // cancels cleanly WITHOUT reaching .onFailure → no misleading
        // "Failed to load messages: canceled" toast. This pins the regression
        // that the first-request CE guard (covered by the P1-4 test above) did
        // NOT catch because it only inspects the initial fetch's cause.
        coEvery { repository.getMessagesPaged("s1", any(), any(), any()) } returns
            Result.failure(java.io.IOException("HTTP 503"))
        coEvery { repository.getMessagesPagedUnanchored("s1", any(), any(), any()) } returns
            Result.failure(kotlinx.coroutines.CancellationException("canceled"))
        coEvery { repository.getSessionTodos("s1") } returns Result.success(emptyList())
        store.mutateChat { it.copy(currentSessionId = "s1") }

        launchLoadMessages(
            scope, repository, slices, "s1",
            emit = emit,
            isSseLive = { false }, // SSE-off → retry engages; retry then returns CE
        )
        scope.advanceUntilIdle()
        scope.runCurrent()

        // The retry-path CE guard re-threw the CancellationException → .onFailure
        // NEVER reached → NO UiEvent.Error (cancellation is not user-facing).
        assertTrue(
            "retry returning CancellationException must NOT emit UiEvent.Error (got $emitted)",
            emitted.filterIsInstance<UiEvent.Error>().isEmpty(),
        )
        // Retry DID fire (initial IOException under SSE-off engaged the retry).
        coVerify(exactly = 1) { repository.getMessagesPagedUnanchored("s1", any(), any(), any()) }
        // Session-guarded finally still cleared the loading flag on the cancel exit.
        assertFalse(slices.chat.value.isLoadingMessages)
    }

    @Test
    fun `P0-2 CancellationException from the slim GET propagates (not swallowed into silent Result failure)`(): Unit = runTest {
        // §mechanism ④: runSuspendCatching (R-14) re-throws CancellationException
        // so the repository GET propagates it as a throw (NOT Result.failure).
        // launchLoadMessages' outer try/catch(kotlin.coroutines...CancellationException)
        // re-throws, so the coroutine cancels cleanly WITHOUT emitting
        // UiEvent.Error (which would mislead the user on a routine ViewModel
        // clear). Pins the §history-load-fix round-1 contract.
        coEvery { repository.getMessagesPaged("s1", any(), any(), any()) } throws
            kotlinx.coroutines.CancellationException("viewModel cleared")
        store.mutateChat { it.copy(currentSessionId = "s1") }

        launchLoadMessages(scope, repository, slices, "s1", emit = emit)
        advanceUntilIdle()

        // CancellationException propagated past the retry loop / onFailure →
        // NO UiEvent.Error emitted (a swallowed-then-failed path would emit).
        assertTrue(
            "CancellationException must NOT reach onFailure (structured concurrency preserved)",
            emitted.filterIsInstance<UiEvent.Error>().isEmpty(),
        )
        // The session-guarded finally still cleared the loading flag on the
        // cancellation exit (idempotent backstop).
        assertFalse(slices.chat.value.isLoadingMessages)
    }

    @Test
    fun `P0-2 launchLoadMessages issues only GET fetches (no mutation POST auto-retry)`(): Unit = runTest {
        // §mechanism ④ (structural guard): launchLoadMessages performs ONLY
        // read-side calls — getMessagesPaged* (GET) + getSessionTodos
        // (progressive enhancement). It has no reference to sendMessage /
        // any mutation POST, so "don't auto-retry mutation POST" is enforced
        // structurally. This test pins that surface so a future edit cannot
        // silently introduce a mutation call here.
        coEvery { repository.getMessagesPaged("s1", any(), any(), any()) } returns
            Result.success(MessagesPage(emptyList(), null))
        coEvery { repository.getSessionTodos("s1") } returns Result.success(emptyList())
        store.mutateChat { it.copy(currentSessionId = "s1") }

        launchLoadMessages(scope, repository, slices, "s1", emit = emit)
        advanceUntilIdle()

        // Only read-side calls observed.
        coVerify(atLeast = 1) { repository.getMessagesPaged("s1", any(), any(), any()) }
        coVerify(atLeast = 1) { repository.getSessionTodos("s1") }
        // No mutation POST (sendMessage) was issued — not auto-retried, not
        // even called once. (mockk relaxed → an unstubbed sendMessage would
        // return a default Result; coVerify(exactly=0) asserts it never ran.)
        coVerify(exactly = 0) { repository.sendMessage(any(), any()) }
    }
}
