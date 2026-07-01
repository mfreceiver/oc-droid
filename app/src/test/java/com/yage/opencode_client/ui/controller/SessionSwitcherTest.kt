package com.yage.opencode_client.ui.controller

import com.yage.opencode_client.data.model.Message
import com.yage.opencode_client.data.model.Session
import com.yage.opencode_client.data.model.SessionStatus
import com.yage.opencode_client.ui.AppState
import com.yage.opencode_client.ui.CachedSessionWindow
import com.yage.opencode_client.ui.ComposerState
import com.yage.opencode_client.ui.SliceFlows
import com.yage.opencode_client.ui.syncSlicesFromAppState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * R-16 M2b: independent unit test for [SessionSwitcher.switchTo] — the full
 * 8-step session-switch flow extracted from MainViewModel.
 *
 * Zero reflection — the controller is driven entirely through its public API
 * ([switchTo] / [clearSessionWindowCache] / [peekSessionWindow] /
 * [sessionWindowCacheSize] / [writeSessionWindow]) and asserted via the
 * [RecordingCallbacks] spy + direct StateFlow reads. An injected `clock` makes
 * lastViewedTime deterministic. Follows the [ForegroundCatchUpControllerTest]
 * pattern from M1.
 */
class SessionSwitcherTest {

    private lateinit var state: MutableStateFlow<AppState>
    private lateinit var composerFlow: MutableStateFlow<ComposerState>
    private lateinit var expandedParts: MutableStateFlow<Map<String, Boolean>>
    private lateinit var slices: SliceFlows
    private lateinit var callbacks: RecordingCallbacks
    private var nowMs: Long = 10_000L

    private lateinit var switcher: SessionSwitcher

    @Before
    fun setUp() {
        state = MutableStateFlow(AppState())
        composerFlow = MutableStateFlow(ComposerState())
        expandedParts = MutableStateFlow(emptyMap())
        // Build SliceFlows from fresh MutableStateFlows (they're only read for
        // updateAndSync which writes them from AppState — so they just need to
        // exist and be mutable).
        slices = SliceFlows(
            connection = MutableStateFlow(com.yage.opencode_client.ui.ConnectionState()),
            traffic = MutableStateFlow(com.yage.opencode_client.ui.TrafficState()),
            composer = composerFlow,
            file = MutableStateFlow(com.yage.opencode_client.ui.FileState()),
            settings = MutableStateFlow(com.yage.opencode_client.ui.SettingsState()),
            chat = MutableStateFlow(com.yage.opencode_client.ui.ChatState()),
            sessionList = MutableStateFlow(com.yage.opencode_client.ui.SessionListState()),
            unread = MutableStateFlow(com.yage.opencode_client.ui.UnreadState()),
            host = MutableStateFlow(com.yage.opencode_client.ui.HostState())
        )
        callbacks = RecordingCallbacks()
        switcher = SessionSwitcher(
            state = state,
            composerFlow = composerFlow,
            expandedParts = expandedParts,
            slices = slices,
            callbacks = callbacks,
            clock = { nowMs }
        )
    }

    /**
     * Seeds AppState then propagates to the slices (the switcher reads/writes
     * slices). R-17 M5: controllers no longer read the AppState mirror.
     */
    private fun seed(transform: (AppState) -> AppState) {
        state.value = transform(state.value)
        syncSlicesFromAppState(state.value, slices)
    }

    // ── Step 1: LRU write-back of outgoing session ─────────────────────────

    @Test
    fun `switchTo captures outgoing session window into cache`() {
        seed {
            it.copy(
            currentSessionId = "session-A",
            sessions = listOf(
                Session(id = "session-A", directory = "/tmp/a"),
                Session(id = "session-B", directory = "/tmp/b")
            ),
            messages = listOf(Message(id = "m1", role = "user"))
            )
        }

        switcher.switchTo("session-B")

        val cached = switcher.peekSessionWindow("session-A")
        assertNotNull("outgoing session-A must be cached", cached)
        assertEquals(1, cached!!.messages.size)
        assertEquals("m1", cached.messages[0].id)
    }

    @Test
    fun `switchTo does NOT capture when switching to same session`() {
        seed {
            it.copy(
            currentSessionId = "session-A",
            sessions = listOf(Session(id = "session-A", directory = "/tmp/a")),
            messages = listOf(Message(id = "m1", role = "user"))
            )
        }

        switcher.switchTo("session-A")

        // Same-session switch should not write-back (no LRU pollution)
        assertEquals(0, switcher.sessionWindowCacheSize())
    }

    // ── M5 跟进 (§4.2): clearDeltaBuffers hookup ────────────────────────────

    @Test
    fun `switchTo clears delta buffers when leaving an outgoing session`() {
        seed {
            it.copy(
            currentSessionId = "session-A",
            sessions = listOf(
                Session(id = "session-A", directory = "/tmp/a"),
                Session(id = "session-B", directory = "/tmp/b")
            )
            )
        }

        switcher.switchTo("session-B")

        // Real session change → pending deltas of session-A must be dropped
        // exactly once so a late flush can't write into session-B's state.
        assertEquals(1, callbacks.clearDeltaBuffersCalls)
    }

    @Test
    fun `switchTo does NOT clear delta buffers when switching to same session`() {
        seed {
            it.copy(
            currentSessionId = "session-A",
            sessions = listOf(Session(id = "session-A", directory = "/tmp/a"))
            )
        }

        switcher.switchTo("session-A")

        // Same-session reselect must keep its own in-flight deltas intact.
        assertEquals(0, callbacks.clearDeltaBuffersCalls)
    }

    // ── Step 2: draft save / restore ────────────────────────────────────────

    @Test
    fun `switchTo saves old draft and restores new draft`() {
        callbacks.drafts["s2"] = "draft-for-s2"
        seed {
            it.copy(
            currentSessionId = "s1",
            sessions = listOf(Session(id = "s1", directory = "/d"), Session(id = "s2", directory = "/d"))
            )
        }
        composerFlow.value = composerFlow.value.copy(inputText = "old draft text")

        switcher.switchTo("s2")

        // Saved old draft
        assertEquals(1, callbacks.saveDraftCalls.size)
        assertEquals("s1", callbacks.saveDraftCalls[0].first)
        assertEquals("old draft text", callbacks.saveDraftCalls[0].second)
        // Set current session
        assertEquals("s2", callbacks.setCurrentSessionIdCalls.single())
        // Restored new draft
        assertEquals("draft-for-s2", composerFlow.value.inputText)
    }

    @Test
    fun `switchTo does not save draft when no previous session`() {
        seed {
            it.copy(
            currentSessionId = null,
            sessions = listOf(Session(id = "s1", directory = "/d"))
            )
        }

        switcher.switchTo("s1")

        assertTrue("no saveDraft when previousSessionId is null", callbacks.saveDraftCalls.isEmpty())
    }

    // ── Step 3: cache restore ───────────────────────────────────────────────

    @Test
    fun `switchTo restores cached window on cache hit`() {
        seed {
            it.copy(
            currentSessionId = null,
            sessions = listOf(Session(id = "s1", directory = "/d"))
            )
        }
        val cachedMessages = listOf(Message(id = "cached-m1", role = "user"))
        switcher.writeSessionWindow("s1", CachedSessionWindow(
            messages = cachedMessages,
            partsByMessage = mapOf("cached-m1" to emptyList()),
            olderMessagesCursor = "cursor-abc",
            hasMoreMessages = true
        ))

        switcher.switchTo("s1")

        assertEquals("cached-m1", slices.chat.value.messages[0].id)
        assertEquals("cursor-abc", slices.chat.value.olderMessagesCursor)
        assertTrue(slices.chat.value.hasMoreMessages)
    }

    @Test
    fun `switchTo loads messages with resetLimit=true on cache miss`() {
        seed {
            it.copy(
            currentSessionId = null,
            sessions = listOf(Session(id = "s1", directory = "/d"))
            )
        }

        switcher.switchTo("s1")

        assertEquals(1, callbacks.loadMessagesCalls.size)
        assertEquals("s1", callbacks.loadMessagesCalls[0].first)
        assertTrue("resetLimit=true on cache miss", callbacks.loadMessagesCalls[0].second)
    }

    @Test
    fun `switchTo loads messages with resetLimit=false on cache hit`() {
        seed {
            it.copy(
            currentSessionId = null,
            sessions = listOf(Session(id = "s1", directory = "/d"))
            )
        }
        switcher.writeSessionWindow("s1", CachedSessionWindow(
            messages = emptyList(), partsByMessage = emptyMap(),
            olderMessagesCursor = null, hasMoreMessages = false
        ))

        switcher.switchTo("s1")

        assertEquals(1, callbacks.loadMessagesCalls.size)
        assertFalse("resetLimit=false on cache hit", callbacks.loadMessagesCalls[0].second)
    }

    // ── Step 4: directory-only session upsert ──────────────────────────────

    @Test
    fun `switchTo upserts directory-only session into sessions list`() {
        val dirSession = Session(id = "dir-only", directory = "/tmp/proj")
        seed {
            it.copy(
            currentSessionId = null,
            sessions = emptyList(),
            directorySessions = mapOf("/tmp/proj" to listOf(dirSession))
            )
        }

        switcher.switchTo("dir-only")

        assertTrue("directory-only session upserted into sessions", slices.sessionList.value.sessions.any { it.id == "dir-only" })
    }

    // ── Step 5: expanded parts reset ────────────────────────────────────────

    @Test
    fun `switchTo clears expanded parts`() {
        expandedParts.value = mapOf("msg1|key1" to true, "msg2|key2" to false)
        seed {
            it.copy(
            currentSessionId = null,
            sessions = listOf(Session(id = "s1", directory = "/d"))
            )
        }

        switcher.switchTo("s1")

        assertTrue("expanded parts cleared on switch", expandedParts.value.isEmpty())
    }

    // ── Step 6: directory sync ──────────────────────────────────────────────

    @Test
    fun `switchTo syncs directory to selected session`() {
        seed {
            it.copy(
            currentSessionId = null,
            sessions = listOf(Session(id = "s1", directory = "/home/user/proj"))
            )
        }

        switcher.switchTo("s1")

        assertEquals(1, callbacks.syncDirectoryCalls.size)
        assertEquals("/home/user/proj", callbacks.syncDirectoryCalls[0])
    }

    // ── Step 7: load callbacks ──────────────────────────────────────────────

    @Test
    fun `switchTo triggers loadMessages loadSessionStatus loadChildSessions`() {
        seed {
            it.copy(
            currentSessionId = null,
            sessions = listOf(Session(id = "s1", directory = "/d"))
            )
        }

        switcher.switchTo("s1")

        assertEquals(1, callbacks.loadMessagesCalls.size)
        assertEquals(1, callbacks.loadSessionStatusCalls)
        assertEquals(listOf("s1"), callbacks.loadChildSessionsCalls)
    }

    // ── Step 8: unread state machine ────────────────────────────────────────

    @Test
    fun `switchTo marks new session as temp-cleared and removes from unread`() {
        seed {
            it.copy(
            currentSessionId = null,
            unreadSessions = setOf("s1"),
            sessions = listOf(Session(id = "s1", directory = "/d"))
            )
        }
        nowMs = 50_000L

        switcher.switchTo("s1")

        assertFalse("selected session removed from unread", slices.unread.value.unreadSessions.contains("s1"))
        assertTrue("selected session added to tempClearedUnread", slices.unread.value.tempClearedUnread.contains("s1"))
        assertEquals(50_000L, slices.unread.value.lastViewedTime["s1"])
    }

    @Test
    fun `switchTo re-marks previous busy+cleared session as unread`() {
        seed {
            it.copy(
            currentSessionId = "old-busy",
            sessions = listOf(
                Session(id = "old-busy", directory = "/d"),
                Session(id = "new-target", directory = "/d")
            ),
            tempClearedUnread = setOf("old-busy"),
            sessionStatuses = mapOf("old-busy" to SessionStatus(type = "busy"))
            )
        }

        switcher.switchTo("new-target")

        assertTrue(
            "previous busy+cleared session re-marked as unread",
            slices.unread.value.unreadSessions.contains("old-busy")
        )
    }

    @Test
    fun `switchTo does NOT re-mark previous idle session as unread`() {
        seed {
            it.copy(
            currentSessionId = "old-idle",
            sessions = listOf(
                Session(id = "old-idle", directory = "/d"),
                Session(id = "new-target", directory = "/d")
            ),
            tempClearedUnread = setOf("old-idle"),
            sessionStatuses = mapOf("old-idle" to SessionStatus(type = "idle"))
            )
        }

        switcher.switchTo("new-target")

        assertFalse(
            "previous idle session NOT re-marked as unread",
            slices.unread.value.unreadSessions.contains("old-idle")
        )
    }

    @Test
    fun `switchTo does NOT re-mark previous session if not temp-cleared`() {
        seed {
            it.copy(
            currentSessionId = "old-not-cleared",
            sessions = listOf(
                Session(id = "old-not-cleared", directory = "/d"),
                Session(id = "new-target", directory = "/d")
            ),
            tempClearedUnread = emptySet(),
            sessionStatuses = mapOf("old-not-cleared" to SessionStatus(type = "busy"))
            )
        }

        switcher.switchTo("new-target")

        assertFalse(
            "previous session without tempCleared NOT re-marked",
            slices.unread.value.unreadSessions.contains("old-not-cleared")
        )
    }

    // ── Step 8b: draft discard ──────────────────────────────────────────────

    @Test
    fun `switchTo discards draftWorkdir on select`() {
        seed {
            it.copy(
            currentSessionId = null,
            sessions = listOf(Session(id = "s1", directory = "/d"))
            )
        }
        composerFlow.value = composerFlow.value.copy(draftWorkdir = "/tmp/draft")

        switcher.switchTo("s1")

        assertNull("draftWorkdir cleared", composerFlow.value.draftWorkdir)
        assertNull("draftWorkdir cleared in state mirror", slices.composer.value.draftWorkdir)
    }

    // ── Step 8c: openSessionIds prepend ─────────────────────────────────────

    @Test
    fun `switchTo prepends new session to openSessionIds`() {
        seed {
            it.copy(
            currentSessionId = null,
            openSessionIds = listOf("existing-1", "existing-2"),
            sessions = listOf(Session(id = "new-tab", directory = "/d"))
            )
        }

        switcher.switchTo("new-tab")

        assertEquals(listOf("new-tab", "existing-1", "existing-2"), slices.sessionList.value.openSessionIds)
        assertEquals(1, callbacks.setOpenSessionIdsCalls.size)
        assertEquals(listOf("new-tab", "existing-1", "existing-2"), callbacks.setOpenSessionIdsCalls[0])
    }

    @Test
    fun `switchTo does NOT prepend already-open session`() {
        seed {
            it.copy(
            currentSessionId = null,
            openSessionIds = listOf("already-open", "other"),
            sessions = listOf(Session(id = "already-open", directory = "/d"))
            )
        }

        switcher.switchTo("already-open")

        // openSessionIds should be unchanged (no reorder, no duplicate)
        assertEquals(listOf("already-open", "other"), slices.sessionList.value.openSessionIds)
        assertTrue("no setOpenSessionIds call", callbacks.setOpenSessionIdsCalls.isEmpty())
    }

    @Test
    fun `switchTo does NOT prepend sub-agent sessions`() {
        val childSession = Session(id = "child-1", directory = "/d", parentId = "parent-1")
        seed {
            it.copy(
            currentSessionId = null,
            openSessionIds = emptyList(),
            sessions = listOf(childSession)
            )
        }

        switcher.switchTo("child-1")

        // Sub-agents (parentId != null) should not pollute openSessionIds
        assertTrue("sub-agent not prepended", slices.sessionList.value.openSessionIds.isEmpty())
    }

    @Test
    fun `switchTo caps openSessionIds at 8`() {
        seed {
            it.copy(
            currentSessionId = null,
            openSessionIds = (1..8).map { "existing-$it" },
            sessions = listOf(Session(id = "new-tab", directory = "/d"))
            )
        }

        switcher.switchTo("new-tab")

        assertEquals(8, slices.sessionList.value.openSessionIds.size)
        assertEquals("new-tab", slices.sessionList.value.openSessionIds[0])
    }

    // ── Step 8d: persistSessionCache ────────────────────────────────────────

    @Test
    fun `switchTo persists session cache when opening new tab`() {
        seed {
            it.copy(
            currentSessionId = null,
            openSessionIds = emptyList(),
            sessions = listOf(Session(id = "s1", directory = "/d"))
            )
        }

        switcher.switchTo("s1")

        assertEquals(1, callbacks.persistSessionCacheCalls.size)
        val call = callbacks.persistSessionCacheCalls[0]
        assertTrue(call.sessions.any { it.id == "s1" })
        assertEquals(listOf("s1"), call.openIds)
    }

    @Test
    fun `switchTo does NOT persist cache when session already open`() {
        seed {
            it.copy(
            currentSessionId = null,
            openSessionIds = listOf("s1"),
            sessions = listOf(Session(id = "s1", directory = "/d"))
            )
        }

        switcher.switchTo("s1")

        assertTrue("no persistSessionCache when already open", callbacks.persistSessionCacheCalls.isEmpty())
    }

    // ── LRU cache behavior ──────────────────────────────────────────────────

    @Test
    fun `LRU cache evicts at capacity 12`() {
        seed {
            it.copy(
            currentSessionId = null,
            sessions = (1..13).map { Session(id = "s$it", directory = "/d") }
            )
        }

        // Open sessions 1..12 — each populates the cache via loadMessages' callback.
        // But since we're using RecordingCallbacks (which doesn't actually call
        // writeSessionWindow), we write directly:
        for (i in 1..12) {
            switcher.writeSessionWindow("s$i", CachedSessionWindow(
                messages = listOf(Message(id = "m$i", role = "user")),
                partsByMessage = emptyMap(), olderMessagesCursor = null, hasMoreMessages = false
            ))
        }
        assertEquals(12, switcher.sessionWindowCacheSize())

        // Write one more — should evict the LRU (s1)
        switcher.writeSessionWindow("s13", CachedSessionWindow(
            messages = emptyList(), partsByMessage = emptyMap(),
            olderMessagesCursor = null, hasMoreMessages = false
        ))

        assertEquals(12, switcher.sessionWindowCacheSize())
        assertNull("s1 evicted", switcher.peekSessionWindow("s1"))
        assertNotNull("s13 present", switcher.peekSessionWindow("s13"))
    }

    @Test
    fun `clearSessionWindowCache drops all entries`() {
        switcher.writeSessionWindow("s1", CachedSessionWindow(
            messages = emptyList(), partsByMessage = emptyMap(),
            olderMessagesCursor = null, hasMoreMessages = false
        ))
        assertEquals(1, switcher.sessionWindowCacheSize())

        switcher.clearSessionWindowCache()

        assertEquals(0, switcher.sessionWindowCacheSize())
    }

    // ── Callback ordering (Step 2 before Step 7 before Step 8) ──────────────

    @Test
    fun `switchTo calls saveDraft before setCurrentSessionId before loadMessages`() {
        seed {
            it.copy(
            currentSessionId = "old",
            sessions = listOf(
                Session(id = "old", directory = "/d"),
                Session(id = "new", directory = "/d")
            )
            )
        }
        composerFlow.value = composerFlow.value.copy(inputText = "text")

        switcher.switchTo("new")

        // The ordering: saveDraft → setCurrentSessionId → getDraft → ... →
        // loadMessages → loadSessionStatus → loadChildSessions → ...
        // We verify saveDraft comes before loadMessages
        val saveDraftIdx = callbacks.callOrder.indexOf("saveDraft")
        val setCurrentIdx = callbacks.callOrder.indexOf("setCurrentSessionId")
        val loadMsgIdx = callbacks.callOrder.indexOf("loadMessages")
        assertTrue("saveDraft before setCurrentSessionId", saveDraftIdx < setCurrentIdx)
        assertTrue("setCurrentSessionId before loadMessages", setCurrentIdx < loadMsgIdx)
    }

    // ── RecordingCallbacks ──────────────────────────────────────────────────

    private class RecordingCallbacks : SessionSwitcherCallbacks {
        val drafts = mutableMapOf<String, String>()
        val saveDraftCalls = mutableListOf<Pair<String, String>>()
        val setCurrentSessionIdCalls = mutableListOf<String?>()
        val setOpenSessionIdsCalls = mutableListOf<List<String>>()
        val persistSessionCacheCalls = mutableListOf<PersistCall>()
        val syncDirectoryCalls = mutableListOf<String?>()
        val loadChildSessionsCalls = mutableListOf<String>()
        val loadMessagesCalls = mutableListOf<Pair<String, Boolean>>()
        var loadSessionStatusCalls = 0
        val callOrder = mutableListOf<String>()

        data class PersistCall(
            val sessions: List<Session>,
            val openIds: List<String>,
            val currentId: String?
        )

        override fun saveDraft(sessionId: String, text: String) {
            saveDraftCalls.add(sessionId to text)
            callOrder.add("saveDraft")
        }

        override fun getDraft(sessionId: String): String {
            callOrder.add("getDraft")
            return drafts[sessionId] ?: ""
        }

        override fun setCurrentSessionId(sessionId: String?) {
            setCurrentSessionIdCalls.add(sessionId)
            callOrder.add("setCurrentSessionId")
        }

        override fun setOpenSessionIds(ids: List<String>) {
            setOpenSessionIdsCalls.add(ids)
            callOrder.add("setOpenSessionIds")
        }

        override fun persistSessionCache(sessions: List<Session>, openIds: List<String>, currentId: String?) {
            persistSessionCacheCalls.add(PersistCall(sessions, openIds, currentId))
            callOrder.add("persistSessionCache")
        }

        override fun syncCurrentDirectory(directory: String?) {
            syncDirectoryCalls.add(directory)
            callOrder.add("syncCurrentDirectory")
        }

        override fun loadChildSessions(sessionId: String) {
            loadChildSessionsCalls.add(sessionId)
            callOrder.add("loadChildSessions")
        }

        override fun loadMessages(sessionId: String, resetLimit: Boolean) {
            loadMessagesCalls.add(sessionId to resetLimit)
            callOrder.add("loadMessages")
        }

        override fun loadSessionStatus() {
            loadSessionStatusCalls++
            callOrder.add("loadSessionStatus")
        }

        var clearDeltaBuffersCalls = 0

        override fun onClearDeltaBuffers() {
            clearDeltaBuffersCalls++
            callOrder.add("onClearDeltaBuffers")
        }
    }
}
