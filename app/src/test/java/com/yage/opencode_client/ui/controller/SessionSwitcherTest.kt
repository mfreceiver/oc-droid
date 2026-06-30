package com.yage.opencode_client.ui.controller

import com.yage.opencode_client.data.model.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * R-16 M2: contract-level unit test for [SessionSwitcher] (partial extraction).
 *
 * The core `selectSession` flow (~130 lines, deeply coupled with AppState,
 * SliceFlows, SettingsManager, OpenCodeRepository) remains in MainViewModel.
 * This test validates:
 *  1. The [SessionSwitcherCallbacks] contract — each method is correctly
 *     recorded by a spy, proving the interface boundary is viable.
 *  2. The minimal [SessionSwitcher.currentSessionId] facade — delegates to
 *     callbacks without adding logic.
 *
 * Zero reflection — the controller + callbacks are driven through public API
 * only, following the [ForegroundCatchUpControllerTest] pattern from M1.
 */
class SessionSwitcherTest {

    private lateinit var callbacks: RecordingSessionSwitcherCallbacks
    private lateinit var switcher: SessionSwitcher

    @Before
    fun setUp() {
        callbacks = RecordingSessionSwitcherCallbacks()
        switcher = SessionSwitcher(callbacks)
    }

    // ── currentSessionId ────────────────────────────────────────────────────

    @Test
    fun `currentSessionId delegates to callbacks`() {
        callbacks._currentSessionId = "session-42"

        val result = switcher.currentSessionId()

        assertEquals("session-42", result)
        assertEquals(1, callbacks.currentSessionIdCalls)
    }

    @Test
    fun `currentSessionId returns null when callbacks returns null`() {
        callbacks._currentSessionId = null

        val result = switcher.currentSessionId()

        assertNull(result)
    }

    // ── Callback contract validation ────────────────────────────────────────

    @Test
    fun `saveDraft callback contract is invocable`() {
        callbacks.saveDraft("s1", "hello")
        assertEquals("s1", callbacks.saveDraftCalls.single().first)
        assertEquals("hello", callbacks.saveDraftCalls.single().second)
    }

    @Test
    fun `getDraft callback contract is invocable`() {
        callbacks._draft = "cached draft"
        assertEquals("cached draft", callbacks.getDraft("any"))
    }

    @Test
    fun `persistOpenSessionIds callback contract is invocable`() {
        callbacks.persistOpenSessionIds(listOf("a", "b"), "a")
        assertEquals(listOf("a", "b"), callbacks.persistOpenIdsCalls.single().first)
        assertEquals("a", callbacks.persistOpenIdsCalls.single().second)
    }

    @Test
    fun `persistSessionCache callback contract is invocable`() {
        val sessions = listOf(Session(id = "s1", directory = "/tmp"))
        callbacks.persistSessionCache(sessions, listOf("s1"), "s1", "/tmp")
        assertEquals(1, callbacks.persistSessionCacheCalls.size)
    }

    @Test
    fun `upsertSession callback contract is invocable`() {
        val session = Session(id = "s1", directory = "/tmp/a")
        callbacks.upsertSession(session)
        assertEquals("s1", callbacks.upsertSessionCalls.single().id)
    }

    @Test
    fun `syncCurrentDirectory callback contract is invocable`() {
        callbacks.syncCurrentDirectory("/home/user/proj")
        assertEquals("/home/user/proj", callbacks.syncCurrentDirectoryCalls.single())
    }

    @Test
    fun `loadChildSessions callback contract is invocable`() {
        callbacks.loadChildSessions("parent-1")
        assertEquals("parent-1", callbacks.loadChildSessionsCalls.single())
    }

    @Test
    fun `loadMessages callback contract is invocable`() {
        callbacks.loadMessages("s1", resetLimit = false)
        assertEquals("s1", callbacks.loadMessagesCalls.single().first)
        assertTrue(callbacks.loadMessagesCalls.single().second == false)
    }

    // ── RecordingSessionSwitcherCallbacks ───────────────────────────────────

    private class RecordingSessionSwitcherCallbacks : SessionSwitcherCallbacks {
        var _currentSessionId: String? = null
        var _draft: String = ""
        var currentSessionIdCalls = 0
        val saveDraftCalls = mutableListOf<Pair<String, String>>()
        val persistOpenIdsCalls = mutableListOf<Pair<List<String>, String?>>()
        val persistSessionCacheCalls = mutableListOf<PersistCall>()
        val upsertSessionCalls = mutableListOf<Session>()
        val syncCurrentDirectoryCalls = mutableListOf<String?>()
        val refreshDirectorySessionsCalls = mutableListOf<String>()
        val loadChildSessionsCalls = mutableListOf<String>()
        val loadMessagesCalls = mutableListOf<Pair<String, Boolean>>()
        val loadSessionStatusCalls = mutableListOf<String>()

        data class PersistCall(
            val sessions: List<Session>,
            val openIds: List<String>,
            val currentId: String?,
            val currentWorkdir: String?
        )

        override fun saveDraft(sessionId: String, text: String) {
            saveDraftCalls.add(sessionId to text)
        }

        override fun getDraft(sessionId: String): String = _draft

        override fun persistOpenSessionIds(openIds: List<String>, currentId: String?) {
            persistOpenIdsCalls.add(openIds to currentId)
        }

        override fun persistSessionCache(
            sessions: List<Session>,
            openIds: List<String>,
            currentId: String?,
            currentWorkdir: String?
        ) {
            persistSessionCacheCalls.add(PersistCall(sessions, openIds, currentId, currentWorkdir))
        }

        override fun upsertSession(session: Session) {
            upsertSessionCalls.add(session)
        }

        override fun syncCurrentDirectory(directory: String?) {
            syncCurrentDirectoryCalls.add(directory)
        }

        override fun refreshDirectorySessions(workdir: String) {
            refreshDirectorySessionsCalls.add(workdir)
        }

        override fun loadChildSessions(sessionId: String) {
            loadChildSessionsCalls.add(sessionId)
        }

        override fun loadMessages(sessionId: String, resetLimit: Boolean) {
            loadMessagesCalls.add(sessionId to resetLimit)
        }

        override fun loadSessionStatus() {
            loadSessionStatusCalls.add("called")
        }

        override fun currentSessionId(): String? {
            currentSessionIdCalls++
            return _currentSessionId
        }
    }
}
