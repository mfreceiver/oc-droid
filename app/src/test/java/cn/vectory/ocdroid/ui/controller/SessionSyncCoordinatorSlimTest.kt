package cn.vectory.ocdroid.ui.controller

import android.util.Log
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.MessageWithParts
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.model.PermissionRequest
import cn.vectory.ocdroid.data.model.QuestionInfo
import cn.vectory.ocdroid.data.model.QuestionRequest
import cn.vectory.ocdroid.data.model.SSEEvent
import cn.vectory.ocdroid.data.model.SSEPayload
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SlimSessionDigest
import cn.vectory.ocdroid.data.model.SlimapiQuestionEntry
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.data.repository.SlimColdStartSnapshot
import cn.vectory.ocdroid.data.repository.SlimFetchMessages
import cn.vectory.ocdroid.ui.SharedEffectBus
import cn.vectory.ocdroid.ui.SharedStateStore
import cn.vectory.ocdroid.ui.SliceFlows
import cn.vectory.ocdroid.util.SettingsManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Cluster A / Phase 2 (Lane-CoordSvc): unit tests for slim wiring in
 * [SessionSyncCoordinator] — `session.digest`, slim questions single-shot,
 * routeToken preserve, cold-start snapshot fold.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SessionSyncCoordinatorSlimTest {

    private lateinit var slices: SliceFlows
    private lateinit var effects: SharedEffectBus
    private lateinit var settingsManager: SettingsManager
    private lateinit var scope: TestScope
    private lateinit var repository: OpenCodeRepository
    private var slimMode: Boolean = true

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.d(any<String>(), any<String>()) } returns 0

        val store = SharedStateStore()
        slices = store.slices
        settingsManager = mockk(relaxed = true)
        effects = SharedEffectBus()
        scope = TestScope(UnconfinedTestDispatcher())
        repository = mockk(relaxed = true)
        slimMode = true
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun coordinator(): SessionSyncCoordinator =
        SessionSyncCoordinator(
            scope = scope,
            slices = slices,
            settingsManager = settingsManager,
            effects = effects,
            currentServerGroupFp = { "test-fp" },
            isSlimMode = { slimMode },
            repository = repository,
        )

    private fun digestEvent(
        sessionId: String,
        status: String? = null,
        updatedAt: Long? = null,
        messageId: String? = null,
        archived: Long? = null,
        deleted: Boolean? = null,
        directory: String? = "/proj",
    ): SSEEvent {
        val props = buildJsonObject {
            put("sessionID", sessionId)
            directory?.let { put("directory", it) }
            status?.let { put("status", it) }
            updatedAt?.let { put("updatedAt", it) }
            messageId?.let { put("messageID", it) }
            archived?.let { put("archived", it) }
            deleted?.let { put("deleted", it) }
        }
        return SSEEvent(payload = SSEPayload(type = "session.digest", properties = props))
    }

    @Test
    fun `session digest applies reducer and merges messages for open session`() = runTest {
        slices.mutateChat { it.copy(currentSessionId = "sess-1") }
        every { repository.applySlimDigest(any()) } returns SlimFetchMessages(
            sessionId = "sess-1",
            since = 0L,
        )
        coEvery { repository.getSlimapiMessagesSince("sess-1", 0L) } returns Result.success(
            listOf(
                MessageWithParts(
                    info = Message(id = "m1", role = "assistant", sessionId = "sess-1"),
                    parts = listOf(Part(id = "p1", messageId = "m1", sessionId = "sess-1", type = "text", text = "hi")),
                ),
            ),
        )

        val c = coordinator()
        c.handleEvent(digestEvent("sess-1", status = "busy", updatedAt = 1000L, messageId = "m1"))
        scope.testScheduler.advanceUntilIdle()

        verify(exactly = 1) { repository.applySlimDigest(match { it.sessionId == "sess-1" && it.updatedAt == 1000L }) }
        coVerify(exactly = 1) { repository.getSlimapiMessagesSince("sess-1", 0L) }
        assertEquals(listOf("m1"), slices.chat.value.messages.map { it.id })
        assertEquals("hi", slices.chat.value.partsByMessage["m1"]?.firstOrNull()?.text)
        assertEquals("busy", slices.sessionList.value.sessionStatuses["sess-1"]?.type)
    }

    @Test
    fun `session digest skips message fetch when not current session`() = runTest {
        slices.mutateChat { it.copy(currentSessionId = "other") }
        every { repository.applySlimDigest(any()) } returns SlimFetchMessages(
            sessionId = "sess-1",
            since = 50L,
        )

        val c = coordinator()
        c.handleEvent(digestEvent("sess-1", updatedAt = 100L))
        scope.testScheduler.advanceUntilIdle()

        verify(exactly = 1) { repository.applySlimDigest(any()) }
        coVerify(exactly = 0) { repository.getSlimapiMessagesSince(any(), any()) }
    }

    @Test
    fun `session digest is handled and does not count as unknown event`() = runTest {
        every { repository.applySlimDigest(any()) } returns null
        val c = coordinator()
        c.handleEvent(digestEvent("sess-1", status = "idle"))
        assertEquals(
            "session.digest must not land in unknown-event counter",
            null,
            c.unknownEventCountsSnapshot()["session.digest"],
        )
    }

    @Test
    fun `loadPendingQuestions slim uses single getSlimapiQuestions and preserves routeToken`() = runTest {
        every { settingsManager.currentWorkdir } returns "/proj"
        every { settingsManager.getRecentWorkdirs(any()) } returns emptyList()
        coEvery { repository.getSlimapiQuestions(any()) } returns Result.success(
            listOf(
                SlimapiQuestionEntry(
                    id = "q1",
                    sessionId = "s1",
                    questions = listOf(QuestionInfo("Q?", "H", emptyList())),
                    routeToken = "rt-abc",
                ),
            ),
        )

        val c = coordinator()
        c.loadPendingQuestionsAllWorkdirs(repository)
        scope.testScheduler.advanceUntilIdle()

        coVerify(exactly = 1) { repository.getSlimapiQuestions(any()) }
        coVerify(exactly = 0) { repository.getPendingQuestions(any()) }
        val q = slices.sessionList.value.pendingQuestions.single()
        assertEquals("q1", q.id)
        assertEquals("rt-abc", q.routeToken)
    }

    @Test
    fun `loadPendingQuestions legacy still fans out when not slim`() = runTest {
        slimMode = false
        every { settingsManager.currentWorkdir } returns "/proj"
        every { settingsManager.getRecentWorkdirs(any()) } returns emptyList()
        coEvery { repository.getPendingQuestions("/proj") } returns Result.success(
            listOf(QuestionRequest(id = "q-legacy", sessionId = "s1", questions = emptyList())),
        )

        val c = coordinator()
        c.loadPendingQuestionsAllWorkdirs(repository)
        scope.testScheduler.advanceUntilIdle()

        coVerify(exactly = 1) { repository.getPendingQuestions("/proj") }
        coVerify(exactly = 0) { repository.getSlimapiQuestions(any()) }
        assertNull(slices.sessionList.value.pendingQuestions.single().routeToken)
    }

    @Test
    fun `question asked SSE preserves routeToken from properties`() = runTest {
        val props = buildJsonObject {
            put("id", "q-sse")
            put("sessionID", "s1")
            put("questions", kotlinx.serialization.json.buildJsonArray { })
            put("routeToken", "rt-sse")
        }
        val c = coordinator()
        c.handleEvent(SSEEvent(payload = SSEPayload(type = "question.asked", properties = props)))

        val q = slices.sessionList.value.pendingQuestions.single()
        assertEquals("q-sse", q.id)
        assertEquals("rt-sse", q.routeToken)
    }

    @Test
    fun `permission asked SSE with routeToken folds into pendingPermissions`() = runTest {
        val props = buildJsonObject {
            put("id", "p1")
            put("sessionID", "s1")
            put("permission", "edit")
            put("routeToken", "rt-perm")
        }
        val c = coordinator()
        c.handleEvent(SSEEvent(payload = SSEPayload(type = "permission.asked", properties = props)))

        val p = slices.sessionList.value.pendingPermissions.single()
        assertEquals("p1", p.id)
        assertEquals("rt-perm", p.routeToken)
    }

    @Test
    fun `routeToken defaults to null for legacy QuestionRequest and PermissionRequest`() {
        val q = QuestionRequest(id = "q", sessionId = "s", questions = emptyList())
        val p = PermissionRequest(id = "p", sessionId = "s")
        assertNull(q.routeToken)
        assertNull(p.routeToken)
    }

    @Test
    fun `applySlimColdStartSnapshot folds sessions questions permissions and messages`() = runTest {
        slices.mutateChat { it.copy(currentSessionId = "sess-1") }
        val snapshot = SlimColdStartSnapshot(
            sessions = listOf(Session(id = "sess-1", directory = "/proj", title = "T")),
            questions = listOf(
                SlimapiQuestionEntry(
                    id = "q1",
                    sessionId = "sess-1",
                    questions = emptyList(),
                    routeToken = "rt-cs",
                ),
            ),
            permissions = emptyList(),
            messages = listOf(
                MessageWithParts(
                    info = Message(id = "m1", role = "user", sessionId = "sess-1"),
                    parts = emptyList(),
                ),
            ),
        )

        val c = coordinator()
        c.applySlimColdStartSnapshot(snapshot)

        assertEquals(1, slices.sessionList.value.sessions.size)
        assertEquals("T", slices.sessionList.value.sessions.single().title)
        assertEquals("rt-cs", slices.sessionList.value.pendingQuestions.single().routeToken)
        assertEquals(listOf("m1"), slices.chat.value.messages.map { it.id })
    }

    @Test
    fun `SlimapiQuestionEntry toQuestionRequest preserves routeToken`() {
        val entry = SlimapiQuestionEntry(
            id = "q",
            sessionId = "s",
            questions = emptyList(),
            routeToken = "tok",
        )
        val mapped = entry.toQuestionRequest()
        assertEquals("tok", mapped.routeToken)
        assertEquals("q", mapped.id)
    }
}
