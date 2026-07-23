package cn.vectory.ocdroid.ui.controller

import android.util.Log
import cn.vectory.ocdroid.MainDispatcherRule
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
import cn.vectory.ocdroid.data.repository.ProbeResult
import cn.vectory.ocdroid.data.repository.SlimColdStartSnapshot
import cn.vectory.ocdroid.data.repository.SlimFetchMessages
import cn.vectory.ocdroid.data.repository.SlimSessionState
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Cluster A / Phase 2 (Lane-CoordSvc): unit tests for slim wiring in
 * [SessionSyncCoordinator] — `session.digest`, slim questions single-shot,
 * routeToken preserve, cold-start snapshot fold.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SessionSyncCoordinatorSlimTest {

    @get:org.junit.Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

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

        // C-D3 token guard stubs.
        // captureSlimCommitToken: let relaxed mock auto-answer.
        every { repository.isSlimCommitTokenCurrent(any()) } returns true
        every { repository.commitIfSlimTokenCurrent(any(), any()) } answers {
            secondArg<() -> Unit>().invoke()
            true
        }
        // Boolean wrappers default to false on relaxed mocks — stub to true.
        every { repository.clearSlimLocalMessages(any(), any()) } returns true
        every { repository.markSlimReconcileFailure(any(), any()) } returns true
        every { repository.markSlimReconcileAligned(any(), any()) } returns true
        every { repository.markSlimSessionDeleted(any(), any()) } returns true
        every { repository.markSlimDirty(any(), any()) } returns true
        every { repository.invalidateSlimLocalApplied(any(), any()) } returns true
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
            supportsWatermarkResync = { slimMode },
            repository = repository,
            reconcileDispatcher = UnconfinedTestDispatcher(),
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
        every { repository.applySlimDigest(any(), any()) } returns SlimFetchMessages(
            sessionId = "sess-1",
            since = 0L,
        )
        // T11 reconcile: probe is the single decision source. Set up a
        // probe that drives needsCatchUp=true. T11 round-2 (oracle I2):
        // when localAppliedUpdatedAt != null, /since path is used; when
        // null, cursor drain façade. Pin the /since path by setting
        // localAppliedUpdatedAt.
        every { repository.getSlimSessionState("sess-1") } returns SlimSessionState(
            sessionId = "sess-1",
            remoteMessageId = "m1",
            remoteUpdatedAt = 1000L,
            localAppliedMessageId = "m0",
            localAppliedUpdatedAt = 0L,
            dirty = true,
        )
        coEvery { repository.probeLatestSlim("sess-1") } returns ProbeResult(
            ok = true,
            messageID = "m1",
            updatedAt = 1000L,
        )
        coEvery { repository.getSlimapiMessagesSince("sess-1", 0L, any(), any(), any()) } returns Result.success(
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

        verify { repository.applySlimDigest(match { it.sessionId == "sess-1" && it.updatedAt == 1000L }, any()) }
        coVerify(exactly = 1) { repository.getSlimapiMessagesSince("sess-1", 0L, any(), any(), any()) }
        assertEquals(listOf("m1"), slices.chat.value.messages.map { it.id })
        assertEquals("hi", slices.chat.value.partsByMessage["m1"]?.firstOrNull()?.text)
        assertEquals("busy", slices.sessionList.value.sessionStatuses["sess-1"]?.type)
    }

    @Test
    fun `session digest skips message fetch when not current session`() = runTest {
        slices.mutateChat { it.copy(currentSessionId = "other") }
        every { repository.applySlimDigest(any(), any()) } returns SlimFetchMessages(
            sessionId = "sess-1",
            since = 50L,
        )

        val c = coordinator()
        c.handleEvent(digestEvent("sess-1", updatedAt = 100L))
        scope.testScheduler.advanceUntilIdle()

        verify { repository.applySlimDigest(any(), any()) }
        coVerify(exactly = 0) { repository.getSlimapiMessagesSince(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `session digest is handled and does not count as unknown event`() = runTest {
        every { repository.applySlimDigest(any(), any()) } returns null
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
        coEvery { repository.getSlimapiQuestions(any(), any()) } returns Result.success(
            cn.vectory.ocdroid.data.repository.SlimAggregationOutcome.Success(
                items = listOf(
                    SlimapiQuestionEntry(
                        id = "q1",
                        sessionId = "s1",
                        questions = listOf(QuestionInfo("Q?", "H", emptyList())),
                        routeToken = "rt-abc",
                    ),
                ),
                authoritativeDirectories = null,
            ),
        )

        val c = coordinator()
        c.loadPendingQuestionsAllWorkdirs(repository)
        scope.testScheduler.advanceUntilIdle()

        coVerify(exactly = 1) { repository.getSlimapiQuestions(any(), any()) }
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
        coVerify(exactly = 0) { repository.getSlimapiQuestions(any(), any()) }
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
            questions = cn.vectory.ocdroid.data.repository.SlimAggregationOutcome.Success(
                items = listOf(
                    SlimapiQuestionEntry(
                        id = "q1",
                        sessionId = "sess-1",
                        questions = emptyList(),
                        routeToken = "rt-cs",
                    ),
                ),
                authoritativeDirectories = null,
            ),
            permissions = cn.vectory.ocdroid.data.repository.SlimAggregationOutcome.Success(items = emptyList(), authoritativeDirectories = null),
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
    fun `applySlimColdStartSnapshot with Complete=false keeps prior page intact`() = runTest {
        // Given: prior state has existing sessions, questions, permissions, messages
        slices.mutateSessionList { it.copy(sessions = listOf(Session(id = "existing", directory = "/proj", title = "Prior"))) }
        slices.mutateSessionList { it.copy(pendingQuestions = listOf(QuestionRequest(id = "q-prior", sessionId = "existing", questions = emptyList(), directory = "/proj"))) }
        slices.mutateSessionList { it.copy(pendingPermissions = listOf(PermissionRequest(id = "p-prior", sessionId = "existing", directory = "/proj"))) }
        slices.mutateChat { it.copy(currentSessionId = "existing", messages = listOf(Message(id = "m-prior", role = "user", sessionId = "existing"))) }

        // When: a snapshot arrives with complete=false (incomplete)
        val snapshot = SlimColdStartSnapshot(
            sessions = null, // fetch failed; keep prior
            questions = cn.vectory.ocdroid.data.repository.SlimAggregationOutcome.Failure("incomplete"),
            permissions = cn.vectory.ocdroid.data.repository.SlimAggregationOutcome.Failure("incomplete"),
            messages = null,
            complete = false,
        )

        val c = coordinator()
        c.applySlimColdStartSnapshot(snapshot)

        // Then: prior data is RETAINED (not wiped)
        assertEquals(1, slices.sessionList.value.sessions.size)
        assertEquals("Prior", slices.sessionList.value.sessions.single().title)
        assertEquals("q-prior", slices.sessionList.value.pendingQuestions.single().id)
        assertEquals("p-prior", slices.sessionList.value.pendingPermissions.single().id)
        assertEquals("m-prior", slices.chat.value.messages.single().id)
    }

    /**
     * Sessions-merge regression (cold-start limit data-loss).
     *
     * Root cause: `coldStartSlimSync` does NOT pass a session limit, and the
     * sidecar defaults to returning only the most recent ~100 sessions. With
     * the historical FULL REPLACE in `applySlimColdStartSnapshot`, an SSE
     * first-frame / cold-start payload covering only directory `/A` would
     * substitute the entire prior list (e.g. 374 sessions spanning /A, /B,
     * /C, …) with just /A's 100 — the rest of the session list "vanished".
     *
     * Lock the MERGE fix: fetched payload may only overwrite sessions in the
     * directories it actually carries; sessions in every other directory
     * must survive.
     */
    @Test
    fun `applySlimColdStartSnapshot sessions merge preserves prior sessions in unmentioned directories`() = runTest {
        // Prior: sessions spread across three directories.
        val priorA = Session(id = "s-a-1", directory = "/A", title = "old-A1")
        val priorA2 = Session(id = "s-a-2", directory = "/A", title = "old-A2")
        val priorB = Session(id = "s-b-1", directory = "/B", title = "keep-B1")
        val priorC = Session(id = "s-c-1", directory = "/C", title = "keep-C1")
        slices.mutateSessionList {
            it.copy(
                sessions = listOf(priorA, priorA2, priorB, priorC),
                directorySessions = mapOf(
                    "/A" to listOf(priorA, priorA2),
                    "/B" to listOf(priorB),
                    "/C" to listOf(priorC),
                ),
            )
        }

        // Fetched snapshot covers ONLY /A (e.g. sidecar returned the most
        // recent 100, all from /A). Includes an updated title for s-a-1 and
        // a brand-new session s-a-3.
        val fetchedA1 = Session(id = "s-a-1", directory = "/A", title = "NEW-A1")
        val fetchedA3 = Session(id = "s-a-3", directory = "/A", title = "new-A3")
        val snapshot = SlimColdStartSnapshot(
            sessions = listOf(fetchedA1, fetchedA3),
            questions = cn.vectory.ocdroid.data.repository.SlimAggregationOutcome.Success(
                items = emptyList(),
                authoritativeDirectories = null,
            ),
            permissions = cn.vectory.ocdroid.data.repository.SlimAggregationOutcome.Success(
                items = emptyList(),
                authoritativeDirectories = null,
            ),
            messages = null,
        )

        val c = coordinator()
        c.applySlimColdStartSnapshot(snapshot)
        scope.testScheduler.advanceUntilIdle()

        val list = slices.sessionList.value

        // /A was overwritten by fetched (s-a-2 dropped, s-a-1 updated, s-a-3 added).
        // /B and /C MUST survive (the bug: they were erased under FULL REPLACE).
        val byId = list.sessions.associateBy { it.id }
        assertEquals("fetched /A entry updated", "NEW-A1", byId["s-a-1"]?.title)
        assertTrue("fetched /A new entry added", byId.containsKey("s-a-3"))
        assertFalse("dropped /A stale entry s-a-2", byId.containsKey("s-a-2"))
        assertTrue("/B prior preserved (data-loss regression)", byId.containsKey("s-b-1"))
        assertTrue("/C prior preserved (data-loss regression)", byId.containsKey("s-c-1"))
        assertEquals("merged size = 4 (2 fetched /A + /B + /C)", 4, list.sessions.size)

        // directorySessions must mirror the merge: /A overwritten, /B + /C kept.
        assertEquals(setOf("/A", "/B", "/C"), list.directorySessions.keys)
        assertEquals(
            "fetched /A bucket = fetched list",
            listOf("s-a-1", "s-a-3"),
            list.directorySessions.getValue("/A").map { it.id },
        )
        assertEquals(
            "/B bucket preserved",
            listOf("s-b-1"),
            list.directorySessions.getValue("/B").map { it.id },
        )
        assertEquals(
            "/C bucket preserved",
            listOf("s-c-1"),
            list.directorySessions.getValue("/C").map { it.id },
        )
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

    // ── I-2 discriminator tests ──────────────────────────────────────────

    /**
     * I-2 D1: A succeeds, B fails → Partial; A-new replaces A-old; B-old preserved.
     */
    @Test
    fun `I2-D1 A succeeds B fails - A replaced B preserved`() = runTest {
        val snapshot = SlimColdStartSnapshot(
            sessions = null,
            questions = cn.vectory.ocdroid.data.repository.SlimAggregationOutcome.Partial(
                items = listOf(
                    SlimapiQuestionEntry(
                        id = "q-a", sessionId = "s1",
                        questions = listOf(QuestionInfo("new", "h", emptyList())),
                        directory = "/dir-a",
                    ),
                ),
                errors = listOf(cn.vectory.ocdroid.data.model.SlimapiAggregationError(directory = "/dir-b", code = "err")),
                authoritativeDirectories = setOf("/dir-a"),
            ),
            permissions = cn.vectory.ocdroid.data.repository.SlimAggregationOutcome.Failure(),
            messages = null,
        )
        // Prior: A-old and B-old.
        slices.mutateSessionList {
            it.copy(
                pendingQuestions = listOf(
                    QuestionRequest(id = "q-a", sessionId = "s1", questions = listOf(QuestionInfo("old", "h", emptyList())), directory = "/dir-a"),
                    QuestionRequest(id = "q-b", sessionId = "s2", questions = listOf(QuestionInfo("old-b", "h", emptyList())), directory = "/dir-b"),
                ),
                pendingPermissions = emptyList(),
            )
        }

        val c = coordinator()
        c.applySlimColdStartSnapshot(snapshot)
        scope.testScheduler.advanceUntilIdle()

        val qs = slices.sessionList.value.pendingQuestions
        assertEquals(2, qs.size)
        assertEquals("new", qs.first { it.id == "q-a" }.questions.first().question)
        assertEquals("old-b", qs.first { it.id == "q-b" }.questions.first().question)
    }

    /**
     * I-2 D2: All sources succeed with empty items → Success(null dirs) → complete clear.
     */
    @Test
    fun `I2-D2 global Success empty replaces all prior`() = runTest {
        val snapshot = SlimColdStartSnapshot(
            sessions = null,
            questions = cn.vectory.ocdroid.data.repository.SlimAggregationOutcome.Success(
                items = emptyList(),
                authoritativeDirectories = null,
            ),
            permissions = cn.vectory.ocdroid.data.repository.SlimAggregationOutcome.Success(
                items = emptyList(),
                authoritativeDirectories = null,
            ),
            messages = null,
        )
        slices.mutateSessionList {
            it.copy(
                pendingQuestions = listOf(
                    QuestionRequest(id = "q-old", sessionId = "s1", questions = emptyList()),
                ),
                pendingPermissions = listOf(
                    PermissionRequest(id = "p-old", sessionId = "s1"),
                ),
            )
        }

        val c = coordinator()
        c.applySlimColdStartSnapshot(snapshot)
        scope.testScheduler.advanceUntilIdle()

        assertTrue("questions cleared", slices.sessionList.value.pendingQuestions.isEmpty())
        assertTrue("permissions cleared", slices.sessionList.value.pendingPermissions.isEmpty())
    }

    /**
     * I-2 D3: Failure → prior retained byte-for-byte.
     */
    @Test
    fun `I2-D3 Failure retains all prior state`() = runTest {
        val priorQ = QuestionRequest(id = "q-keep", sessionId = "s1", questions = emptyList())
        val priorP = PermissionRequest(id = "p-keep", sessionId = "s1")
        val snapshot = SlimColdStartSnapshot(
            sessions = null,
            questions = cn.vectory.ocdroid.data.repository.SlimAggregationOutcome.Failure(),
            permissions = cn.vectory.ocdroid.data.repository.SlimAggregationOutcome.Failure(),
            messages = null,
        )
        slices.mutateSessionList {
            it.copy(pendingQuestions = listOf(priorQ), pendingPermissions = listOf(priorP))
        }

        val c = coordinator()
        c.applySlimColdStartSnapshot(snapshot)
        scope.testScheduler.advanceUntilIdle()

        assertEquals(listOf(priorQ), slices.sessionList.value.pendingQuestions)
        assertEquals(listOf(priorP), slices.sessionList.value.pendingPermissions)
    }

    /**
     * I-2 D4: Partial does NOT clobber existing complete questions (merge semantics).
     */
    @Test
    fun `I2-D4 Partial merges with existing - unmentioned IDs preserved`() = runTest {
        val priorQ = QuestionRequest(id = "q-existing", sessionId = "s1", questions = listOf(QuestionInfo("keep", "h", emptyList())), directory = "/dir-x")
        val snapshot = SlimColdStartSnapshot(
            sessions = null,
            questions = cn.vectory.ocdroid.data.repository.SlimAggregationOutcome.Partial(
                items = listOf(
                    SlimapiQuestionEntry(id = "q-new", sessionId = "s2", questions = listOf(QuestionInfo("new", "h", emptyList())), directory = "/dir-a"),
                ),
                errors = listOf(cn.vectory.ocdroid.data.model.SlimapiAggregationError(directory = "/dir-b", code = "err")),
                authoritativeDirectories = setOf("/dir-a"),
            ),
            permissions = cn.vectory.ocdroid.data.repository.SlimAggregationOutcome.Failure(),
            messages = null,
        )
        slices.mutateSessionList { it.copy(pendingQuestions = listOf(priorQ), pendingPermissions = emptyList()) }

        val c = coordinator()
        c.applySlimColdStartSnapshot(snapshot)
        scope.testScheduler.advanceUntilIdle()

        val qs = slices.sessionList.value.pendingQuestions
        assertTrue("q-existing preserved", qs.any { it.id == "q-existing" })
        assertTrue("q-new added", qs.any { it.id == "q-new" })
        assertEquals(2, qs.size)
    }

    /**
     * I-2 D5: Directory scope — questions from a different directory are NOT applied.
     */
    @Test
    fun `I2-D5 directory scope filters unrelated directory questions`() = runTest {
        val priorQ = QuestionRequest(id = "q-other-dir", sessionId = "s1", questions = emptyList(), directory = "/dir-other")
        val snapshot = SlimColdStartSnapshot(
            sessions = null,
            questions = cn.vectory.ocdroid.data.repository.SlimAggregationOutcome.Success(
                items = listOf(
                    SlimapiQuestionEntry(id = "q-target", sessionId = "s2", questions = emptyList(), directory = "/dir-target"),
                ),
                authoritativeDirectories = setOf("/dir-target"),
            ),
            permissions = cn.vectory.ocdroid.data.repository.SlimAggregationOutcome.Failure(),
            messages = null,
        )
        slices.mutateSessionList { it.copy(pendingQuestions = listOf(priorQ), pendingPermissions = emptyList()) }

        val c = coordinator()
        c.applySlimColdStartSnapshot(snapshot)
        scope.testScheduler.advanceUntilIdle()

        val qs = slices.sessionList.value.pendingQuestions
        assertTrue("q-other-dir preserved (different directory)", qs.any { it.id == "q-other-dir" })
        assertTrue("q-target added", qs.any { it.id == "q-target" })
    }

    /**
     * I-2 D6: Partial's errors are accessible through the outcome type.
     */
    @Test
    fun `I2-D6 partial outcome carries errors for retry affordance`() = runTest {
        val errors = listOf(
            cn.vectory.ocdroid.data.model.SlimapiAggregationError(directory = "/dir-down", code = "upstream_timeout"),
        )
        val outcome = cn.vectory.ocdroid.data.repository.SlimAggregationOutcome.Partial(
            items = listOf(SlimapiQuestionEntry(id = "q-ok", sessionId = "s1", questions = emptyList(), directory = "/dir-ok")),
            errors = errors,
            authoritativeDirectories = setOf("/dir-ok"),
        )
        assertTrue("outcome is Partial", outcome is cn.vectory.ocdroid.data.repository.SlimAggregationOutcome.Partial)
        assertEquals(1, outcome.errors.size)
        assertEquals("/dir-down", outcome.errors[0].directory)
        assertEquals("upstream_timeout", outcome.errors[0].code)
    }

    // ── I-2 v2 production-fold discriminators ────────────────────────────

    /**
     * I-2 v2 §4.6: Partial production fold via applySlimColdStartSnapshot
     * writes INCOMPLETE signal + failedSources, preserves failed-dir prior,
     * and rejects a fetched item attributed to the failed directory.
     */
    @Test
    fun `I2-v2 partial production fold writes INCOMPLETE signal and filters failed-dir fetched`() = runTest {
        val priorA = QuestionRequest(
            id = "q-a",
            sessionId = "s1",
            questions = listOf(QuestionInfo("old-a", "h", emptyList())),
            directory = "/a",
        )
        val priorB = QuestionRequest(
            id = "q-b",
            sessionId = "s2",
            questions = listOf(QuestionInfo("old-b", "h", emptyList())),
            directory = "/b",
        )
        slices.mutateSessionList {
            it.copy(pendingQuestions = listOf(priorA, priorB), pendingPermissions = emptyList())
        }

        val snapshot = SlimColdStartSnapshot(
            sessions = null,
            questions = cn.vectory.ocdroid.data.repository.SlimAggregationOutcome.Partial(
                items = listOf(
                    SlimapiQuestionEntry(
                        id = "q-a",
                        sessionId = "s1",
                        questions = listOf(QuestionInfo("new-a", "h", emptyList())),
                        directory = "/a",
                    ),
                    // Out-of-scope / failed-dir fetched item MUST be rejected.
                    SlimapiQuestionEntry(
                        id = "q-b-poison",
                        sessionId = "s2",
                        questions = listOf(QuestionInfo("poison", "h", emptyList())),
                        directory = "/b",
                    ),
                ),
                errors = listOf(
                    cn.vectory.ocdroid.data.model.SlimapiAggregationError(
                        directory = "/b",
                        code = "upstream_timeout",
                    ),
                ),
                authoritativeDirectories = setOf("/a"),
            ),
            permissions = cn.vectory.ocdroid.data.repository.SlimAggregationOutcome.Success(
                items = emptyList(),
                authoritativeDirectories = null,
            ),
            messages = null,
        )

        val c = coordinator()
        val landed = c.applySlimColdStartSnapshot(snapshot)
        assertTrue("token-gated fold must land", landed)

        val qs = slices.sessionList.value.pendingQuestions
        assertEquals(2, qs.size)
        assertEquals("new-a", qs.first { it.id == "q-a" }.questions.first().question)
        assertEquals("old-b", qs.first { it.id == "q-b" }.questions.first().question)
        assertFalse("failed-dir poison item rejected", qs.any { it.id == "q-b-poison" })

        val signal = slices.sessionList.value.questionAggregationSignal
        assertEquals(
            cn.vectory.ocdroid.ui.SlimAggregationCompleteness.INCOMPLETE,
            signal.completeness,
        )
        assertEquals(1, signal.failedSources.size)
        assertEquals("/b", signal.failedSources[0].directory)
        assertEquals("upstream_timeout", signal.failedSources[0].code)
    }

    /**
     * I-2 v2 §4.7: Failure production fold preserves prior, writes FAILED
     * signal + failureMessage, and emits UiEvent.Error.
     */
    @Test
    fun `I2-v2 failure production fold preserves prior emits UiEvent and FAILED signal`() = runTest {
        val priorQ = QuestionRequest(
            id = "q-keep",
            sessionId = "s1",
            questions = listOf(QuestionInfo("keep", "h", emptyList())),
            directory = "/a",
        )
        slices.mutateSessionList {
            it.copy(pendingQuestions = listOf(priorQ), pendingPermissions = emptyList())
        }

        val recorded = mutableListOf<cn.vectory.ocdroid.ui.UiEvent>()
        val collector = scope.launch {
            effects.uiEventsConsumed.toList(recorded)
        }

        val snapshot = SlimColdStartSnapshot(
            sessions = null,
            questions = cn.vectory.ocdroid.data.repository.SlimAggregationOutcome.Failure("HTTP 503"),
            permissions = cn.vectory.ocdroid.data.repository.SlimAggregationOutcome.Success(
                items = emptyList(),
                authoritativeDirectories = null,
            ),
            messages = null,
        )

        val c = coordinator()
        assertTrue(c.applySlimColdStartSnapshot(snapshot))
        scope.testScheduler.advanceUntilIdle()
        collector.cancel()

        assertEquals(listOf(priorQ), slices.sessionList.value.pendingQuestions)
        val signal = slices.sessionList.value.questionAggregationSignal
        assertEquals(
            cn.vectory.ocdroid.ui.SlimAggregationCompleteness.FAILED,
            signal.completeness,
        )
        assertEquals("HTTP 503", signal.failureMessage)
        assertTrue(
            "Failure must emit UiEvent.Error for questions",
            recorded.any {
                it is cn.vectory.ocdroid.ui.UiEvent.Error &&
                    it.resId == cn.vectory.ocdroid.R.string.error_slim_questions_fetch_failed
            },
        )
    }

    /**
     * C-D3 v2 §4.5 / I-2: standalone loadPendingQuestionsSlim host switch
     * with REAL OpenCodeRepository + MockWebServer + configure() rotation.
     *
     * 1. configure A; seed B-side slice that must survive.
     * 2. trigger load; wait until /slimapi/questions request starts.
     * 3. configure(B) rotates marker (real incarnation change).
     * 4. deliver delayed A response.
     * 5. assert B slice/signal/UiEvent untouched.
     *
     * Does NOT hand-force isSlimCommitTokenCurrent / commitIf false.
     * Uses a real CoroutineScope (not UnconfinedTestDispatcher) so OkHttp
     * can resume the launch after the delayed response.
     */
    @Test
    fun `CD3-v2 standalone q-load host switch drops stale commit`() = runBlocking {
        val server = MockWebServer()
        server.start()
        val realScope = kotlinx.coroutines.CoroutineScope(
            Dispatchers.IO + kotlinx.coroutines.SupervisorJob(),
        )
        try {
            every { settingsManager.currentWorkdir } returns "/proj"
            every { settingsManager.getRecentWorkdirs(any()) } returns emptyList()

            val realRepo = OpenCodeRepository(mockk(relaxed = true), mockk(relaxed = true))
            val baseUrl = server.url("/").toString().trimEnd('/')
            realRepo.configure(baseUrl = baseUrl, slim = true)

            val bSeed = QuestionRequest(
                id = "q-b-seed",
                sessionId = "s-b",
                questions = listOf(QuestionInfo("b-seed", "h", emptyList())),
                directory = "/b",
            )
            slices.mutateSessionList {
                it.copy(
                    pendingQuestions = listOf(bSeed),
                    questionAggregationSignal = cn.vectory.ocdroid.ui.SlimAggregationSignal(
                        completeness = cn.vectory.ocdroid.ui.SlimAggregationCompleteness.COMPLETE,
                    ),
                )
            }

            val body = """
                {
                  "items": [
                    {
                      "id": "q-a-stale",
                      "sessionID": "s-a",
                      "questions": [{"question":"stale-a","header":"h","options":[]}],
                      "directory": "/a",
                      "routeToken": "rt-stale"
                    }
                  ],
                  "errors": []
                }
            """.trimIndent()
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(body)
                    .setHeader("Content-Type", "application/json")
                    .setBodyDelay(400, TimeUnit.MILLISECONDS),
            )

            val recorded = mutableListOf<cn.vectory.ocdroid.ui.UiEvent>()
            val collector = realScope.launch {
                effects.uiEventsConsumed.toList(recorded)
            }

            val c = SessionSyncCoordinator(
                scope = realScope,
                slices = slices,
                settingsManager = settingsManager,
                effects = effects,
                currentServerGroupFp = { "test-fp" },
                supportsWatermarkResync = { true },
                repository = realRepo,
            )
            c.loadPendingQuestionsAllWorkdirs(realRepo)

            val started = server.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull("questions request must start under A", started)
            assertTrue(
                "path must be slim questions: ${started!!.path}",
                started.path!!.startsWith("/slimapi/questions"),
            )

            // C-D3 rev-3: production order is beginSlimReconfigure BEFORE
            // HostStatePurged / configure. Rotate marker first so the purge
            // window already rejects old commitIf.
            realRepo.beginSlimReconfigure()
            realRepo.configure(baseUrl = baseUrl, slim = true)

            // Wait for the delayed response + stale gate to finish.
            kotlinx.coroutines.delay(800)
            collector.cancel()

            assertEquals(
                "B seed must be unchanged after stale A response",
                listOf(bSeed),
                slices.sessionList.value.pendingQuestions,
            )
            assertEquals(
                "B signal must stay COMPLETE (not polluted by A)",
                cn.vectory.ocdroid.ui.SlimAggregationCompleteness.COMPLETE,
                slices.sessionList.value.questionAggregationSignal.completeness,
            )
            assertTrue(
                "no q-a-stale write under B",
                slices.sessionList.value.pendingQuestions.none { it.id == "q-a-stale" },
            )
            assertTrue("no UiEvent from stale A aggregation", recorded.isEmpty())
        } finally {
            realScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            server.shutdown()
        }
    }

    /**
     * Control for §4.5: without configure, the same aggregation response
     * updates items + COMPLETE signal under a real repo + MockWebServer.
     */
    @Test
    fun `CD3-v2 standalone q-load control updates items and signal without configure`() = runBlocking {
        val server = MockWebServer()
        server.start()
        val realScope = kotlinx.coroutines.CoroutineScope(
            Dispatchers.IO + kotlinx.coroutines.SupervisorJob(),
        )
        try {
            every { settingsManager.currentWorkdir } returns "/proj"
            every { settingsManager.getRecentWorkdirs(any()) } returns emptyList()

            val realRepo = OpenCodeRepository(mockk(relaxed = true), mockk(relaxed = true))
            realRepo.configure(
                baseUrl = server.url("/").toString().trimEnd('/'),
                slim = true,
            )

            val body = """
                {
                  "items": [
                    {
                      "id": "q-ok",
                      "sessionID": "s1",
                      "questions": [{"question":"hello","header":"h","options":[]}],
                      "directory": "/proj",
                      "routeToken": "rt-ok"
                    }
                  ],
                  "errors": []
                }
            """.trimIndent()
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(body)
                    .setHeader("Content-Type", "application/json"),
            )

            val c = SessionSyncCoordinator(
                scope = realScope,
                slices = slices,
                settingsManager = settingsManager,
                effects = effects,
                currentServerGroupFp = { "test-fp" },
                supportsWatermarkResync = { true },
                repository = realRepo,
            )
            c.loadPendingQuestionsAllWorkdirs(realRepo)
            kotlinx.coroutines.delay(500)

            val qs = slices.sessionList.value.pendingQuestions
            assertTrue("control path writes q-ok: $qs", qs.any { it.id == "q-ok" })
            assertEquals(
                cn.vectory.ocdroid.ui.SlimAggregationCompleteness.COMPLETE,
                slices.sessionList.value.questionAggregationSignal.completeness,
            )
        } finally {
            realScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            server.shutdown()
        }
    }

    // ── T2 (slimapi v0.2.2 client-adapt): scope.directories gating ────────

    /**
     * T2-C3: `serverScope.directories==0 && items==[]` → retain prior
     * (sidecar allowlist not ready; do NOT clear stale local state).
     *
     * Reproduces the narrow-window false-clear bug: pre-fix, the empty
     * Success folded through `applyAggregationOutcome` and clobbered
     * `pendingQuestions` / `pendingPermissions` with `emptyList()`.
     */
    @Test
    fun `T2-C3 scope directories 0 retains prior pending questions and permissions`() = runTest {
        val priorQ = QuestionRequest(
            id = "q-stale",
            sessionId = "s1",
            questions = listOf(QuestionInfo("keep", "h", emptyList())),
            directory = "/a",
        )
        val priorP = PermissionRequest(id = "p-stale", sessionId = "s1")
        slices.mutateSessionList {
            it.copy(pendingQuestions = listOf(priorQ), pendingPermissions = listOf(priorP))
        }

        val snapshot = SlimColdStartSnapshot(
            sessions = null,
            questions = cn.vectory.ocdroid.data.repository.SlimAggregationOutcome.Success(
                items = emptyList(),
                authoritativeDirectories = null,
                serverScope = cn.vectory.ocdroid.data.model.SlimapiScope(directories = 0),
            ),
            permissions = cn.vectory.ocdroid.data.repository.SlimAggregationOutcome.Success(
                items = emptyList(),
                authoritativeDirectories = null,
                serverScope = cn.vectory.ocdroid.data.model.SlimapiScope(directories = 0),
            ),
            messages = null,
        )

        val c = coordinator()
        c.applySlimColdStartSnapshot(snapshot)
        scope.testScheduler.advanceUntilIdle()

        assertEquals(
            "scope==0 MUST retain prior questions (not-ready sidecar)",
            listOf(priorQ),
            slices.sessionList.value.pendingQuestions,
        )
        assertEquals(
            "scope==0 MUST retain prior permissions (not-ready sidecar)",
            listOf(priorP),
            slices.sessionList.value.pendingPermissions,
        )
    }

    /**
     * T2-C3 complement: `serverScope.directories>0 && items==[]` → clear
     * stale (authoritative empty across N ready directories).
     */
    @Test
    fun `T2-C3 scope directories gt 0 with empty items clears stale`() = runTest {
        val priorQ = QuestionRequest(
            id = "q-stale",
            sessionId = "s1",
            questions = listOf(QuestionInfo("keep", "h", emptyList())),
            directory = "/a",
        )
        val priorP = PermissionRequest(id = "p-stale", sessionId = "s1")
        slices.mutateSessionList {
            it.copy(pendingQuestions = listOf(priorQ), pendingPermissions = listOf(priorP))
        }

        val snapshot = SlimColdStartSnapshot(
            sessions = null,
            questions = cn.vectory.ocdroid.data.repository.SlimAggregationOutcome.Success(
                items = emptyList(),
                authoritativeDirectories = null,
                serverScope = cn.vectory.ocdroid.data.model.SlimapiScope(directories = 2),
            ),
            permissions = cn.vectory.ocdroid.data.repository.SlimAggregationOutcome.Success(
                items = emptyList(),
                authoritativeDirectories = null,
                serverScope = cn.vectory.ocdroid.data.model.SlimapiScope(directories = 2),
            ),
            messages = null,
        )

        val c = coordinator()
        c.applySlimColdStartSnapshot(snapshot)
        scope.testScheduler.advanceUntilIdle()

        assertTrue(
            "scope>0 + empty items → authoritative clear (questions)",
            slices.sessionList.value.pendingQuestions.isEmpty(),
        )
        assertTrue(
            "scope>0 + empty items → authoritative clear (permissions)",
            slices.sessionList.value.pendingPermissions.isEmpty(),
        )
    }

    /**
     * T2-C3 backward-compat: `serverScope==null` (old sidecar / pre-0.2.2)
     * preserves the original Success-empty semantics → clear stale. This
     * MUST stay unaffected by the gating addition (scope is additive).
     */
    @Test
    fun `T2-C3 serverScope null preserves original clear behavior`() = runTest {
        val priorQ = QuestionRequest(
            id = "q-stale",
            sessionId = "s1",
            questions = listOf(QuestionInfo("keep", "h", emptyList())),
            directory = "/a",
        )
        slices.mutateSessionList {
            it.copy(pendingQuestions = listOf(priorQ), pendingPermissions = emptyList())
        }

        val snapshot = SlimColdStartSnapshot(
            sessions = null,
            questions = cn.vectory.ocdroid.data.repository.SlimAggregationOutcome.Success(
                items = emptyList(),
                authoritativeDirectories = null,
                serverScope = null,
            ),
            permissions = cn.vectory.ocdroid.data.repository.SlimAggregationOutcome.Success(
                items = emptyList(),
                authoritativeDirectories = null,
                serverScope = null,
            ),
            messages = null,
        )

        val c = coordinator()
        c.applySlimColdStartSnapshot(snapshot)
        scope.testScheduler.advanceUntilIdle()

        assertTrue(
            "null serverScope → original behavior (clear)",
            slices.sessionList.value.pendingQuestions.isEmpty(),
        )
    }

    /**
     * T2-C3b (final review M4): `Partial` + `serverScope.directories==0`
     * + empty items + non-empty errors MUST retain prior. Locks the T2
     * Partial symmetric gate extension (grill-accepted safe superset)
     * against a future "brief said Success only" revert.
     *
     * Scenario: sidecar is not ready yet (allowlist empty → scope=0) AND
     * ships `errors[]` for one directory → response folds to `Partial`
     * with `serverScope.directories==0`. Without the symmetric gate, the
     * empty items + non-empty authoritativeDirectories would clobber
     * prior `pendingQuestions` / `pendingPermissions` with `emptyList()`
     * — the residual false-clear hole the Success path closes (T2-C3).
     * The Partial branch of `applyAggregationOutcome` must therefore
     * apply the SAME `directories==0 → retain prior` rule.
     */
    @Test
    fun `T2-C3b Partial scope directories 0 retains prior pending questions and permissions`() = runTest {
        val priorQ = QuestionRequest(
            id = "q-stale",
            sessionId = "s1",
            questions = listOf(QuestionInfo("keep", "h", emptyList())),
            directory = "/a",
        )
        val priorP = PermissionRequest(id = "p-stale", sessionId = "s1")
        slices.mutateSessionList {
            it.copy(pendingQuestions = listOf(priorQ), pendingPermissions = listOf(priorP))
        }

        val snapshot = SlimColdStartSnapshot(
            sessions = null,
            questions = cn.vectory.ocdroid.data.repository.SlimAggregationOutcome.Partial(
                items = emptyList(),
                errors = listOf(
                    cn.vectory.ocdroid.data.model.SlimapiAggregationError(
                        directory = "/a",
                        code = "upstream_unavailable",
                    ),
                ),
                authoritativeDirectories = setOf("/a"),
                serverScope = cn.vectory.ocdroid.data.model.SlimapiScope(directories = 0),
            ),
            permissions = cn.vectory.ocdroid.data.repository.SlimAggregationOutcome.Partial(
                items = emptyList(),
                errors = listOf(
                    cn.vectory.ocdroid.data.model.SlimapiAggregationError(
                        directory = "/a",
                        code = "upstream_unavailable",
                    ),
                ),
                authoritativeDirectories = setOf("/a"),
                serverScope = cn.vectory.ocdroid.data.model.SlimapiScope(directories = 0),
            ),
            messages = null,
        )

        val c = coordinator()
        c.applySlimColdStartSnapshot(snapshot)
        scope.testScheduler.advanceUntilIdle()

        assertEquals(
            "Partial scope==0 MUST retain prior questions (not-ready sidecar)",
            listOf(priorQ),
            slices.sessionList.value.pendingQuestions,
        )
        assertEquals(
            "Partial scope==0 MUST retain prior permissions (not-ready sidecar)",
            listOf(priorP),
            slices.sessionList.value.pendingPermissions,
        )
    }

    /**
     * T2-C4: `loadPendingQuestionsSlim` inherits the gating via the shared
     * `applyAggregationOutcome`. A scope==0 envelope through the standalone
     * load path MUST retain prior (same as the cold-start path).
     */
    @Test
    fun `T2-C4 loadPendingQuestionsSlim inherits scope gating`() = runTest {
        every { settingsManager.currentWorkdir } returns "/proj"
        every { settingsManager.getRecentWorkdirs(any()) } returns emptyList()
        coEvery { repository.getSlimapiQuestions(any(), any()) } returns Result.success(
            cn.vectory.ocdroid.data.repository.SlimAggregationOutcome.Success(
                items = emptyList(),
                authoritativeDirectories = null,
                serverScope = cn.vectory.ocdroid.data.model.SlimapiScope(directories = 0),
            ),
        )

        val priorQ = QuestionRequest(
            id = "q-prior",
            sessionId = "s1",
            questions = listOf(QuestionInfo("keep", "h", emptyList())),
            directory = "/a",
        )
        slices.mutateSessionList { it.copy(pendingQuestions = listOf(priorQ)) }

        val c = coordinator()
        c.loadPendingQuestionsAllWorkdirs(repository)
        scope.testScheduler.advanceUntilIdle()

        assertEquals(
            "scope==0 through loadPendingQuestionsSlim MUST retain prior",
            listOf(priorQ),
            slices.sessionList.value.pendingQuestions,
        )
    }

    // ── T0 (safety net): slim↔legacy fold-map non-cross-contamination ──────
    //
    // Pairs with LegacyGoldenPathRegressionTest `P0-6` (legacy side). The
    // slim omitted-part affordance lives in `ChatState.partExpandStates`
    // (AppStateSlices.kt:410); the legacy tool/reasoning fold lives in the
    // SEPARATE `StoreState.expandedParts` (StoreState.kt:40). The two MUST
    // never be written by the other mode's flow:
    //   - slim cold-start / digest flows MUST NOT touch `expandedParts`
    //     (the legacy fold map).
    //   - legacy token streaming MUST NOT touch `partExpandStates`
    //     (pinned in LegacyGoldenPathRegressionTest P0-6).
    // These two cases pin the slim→legacy direction so a future reducer
    // migration (T1) cannot accidentally route a slim expand into the legacy
    // fold map or vice-versa.

    /**
     * T0 slim-track: a slim cold-start snapshot fold writes session-list +
     * chat slices but MUST leave the legacy `expandedParts` fold map at its
     * default (empty). Anchor: `applySlimColdStartSnapshot` only calls
     * `mutateSessionList` / `mutateChat` — never `mutateExpandedParts`.
     */
    @Test
    fun `T0 slim cold-start snapshot does NOT pollute legacy expandedParts fold map`() = runTest {
        // Pre-seed the legacy fold map with a tool-card expansion that must
        // survive the slim snapshot fold untouched.
        slices.store.mutateExpandedParts { mapOf("fold:m1:part-1" to true) }
        assertEquals(
            "baseline: legacy expandedParts seeded",
            mapOf("fold:m1:part-1" to true),
            slices.store.expandedParts.value,
        )

        val snapshot = SlimColdStartSnapshot(
            sessions = listOf(Session(id = "sess-1", directory = "/proj", title = "T")),
            questions = cn.vectory.ocdroid.data.repository.SlimAggregationOutcome.Success(
                items = emptyList(),
                authoritativeDirectories = null,
            ),
            permissions = cn.vectory.ocdroid.data.repository.SlimAggregationOutcome.Success(
                items = emptyList(),
                authoritativeDirectories = null,
            ),
            messages = listOf(
                MessageWithParts(
                    info = Message(id = "m1", role = "user", sessionId = "sess-1"),
                    parts = emptyList(),
                ),
            ),
        )

        val c = coordinator()
        assertTrue("slim snapshot fold landed", c.applySlimColdStartSnapshot(snapshot))

        assertEquals(
            "slim cold-start MUST NOT touch the legacy expandedParts fold map",
            mapOf("fold:m1:part-1" to true),
            slices.store.expandedParts.value,
        )
    }

    /**
     * T0 slim-track: a slim digest→reconcile flow writes chat messages +
     * session-list status but MUST leave the legacy `expandedParts` fold map
     * untouched. Anchor: `handleSessionDigest` + reconcile commit through
     * `mutateChat` / `mutateSessionList` — never `mutateExpandedParts`.
     */
    @Test
    fun `T0 slim digest reconcile does NOT write legacy expandedParts fold map`() = runTest {
        slices.mutateChat { it.copy(currentSessionId = "sess-1") }
        slices.store.mutateExpandedParts { mapOf("fold:legacy:part-9" to true) }

        every { repository.applySlimDigest(any(), any()) } returns SlimFetchMessages(
            sessionId = "sess-1",
            since = 0L,
        )
        every { repository.getSlimSessionState("sess-1") } returns SlimSessionState(
            sessionId = "sess-1",
            remoteMessageId = "m1",
            remoteUpdatedAt = 1000L,
            localAppliedMessageId = "m0",
            localAppliedUpdatedAt = 0L,
            dirty = true,
        )
        coEvery { repository.probeLatestSlim("sess-1") } returns ProbeResult(
            ok = true,
            messageID = "m1",
            updatedAt = 1000L,
        )
        coEvery { repository.getSlimapiMessagesSince("sess-1", 0L, any(), any(), any()) } returns Result.success(
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

        assertEquals(
            "slim messages landed via digest→REST (proves the slim flow ran)",
            listOf("m1"),
            slices.chat.value.messages.map { it.id },
        )
        assertEquals(
            "slim digest→reconcile MUST NOT write the legacy expandedParts fold map",
            mapOf("fold:legacy:part-9" to true),
            slices.store.expandedParts.value,
        )
    }
}
