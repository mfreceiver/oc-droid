package cn.vectory.ocdroid.ui.controller

import android.util.Log
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.model.QuestionRequest
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.data.model.SSEEvent
import cn.vectory.ocdroid.data.model.SSEPayload
import cn.vectory.ocdroid.ui.ChatState
import cn.vectory.ocdroid.ui.ComposerState
import cn.vectory.ocdroid.ui.ConnectionState
import cn.vectory.ocdroid.ui.FileState
import cn.vectory.ocdroid.ui.HostState
import cn.vectory.ocdroid.ui.SessionListState
import cn.vectory.ocdroid.ui.SettingsState
import cn.vectory.ocdroid.ui.SharedEffectBus
import cn.vectory.ocdroid.ui.SliceFlows
import cn.vectory.ocdroid.ui.TrafficState
import cn.vectory.ocdroid.ui.UnreadState
import cn.vectory.ocdroid.util.SettingsManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
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
 * R-16 M4 → R-17 batch3b: independent unit test for [SessionSyncCoordinator].
 *
 * Zero reflection — the coordinator is driven entirely through its public
 * [SessionSyncCoordinator.handleEvent] API and asserted via direct slice
 * StateFlow reads + the captured [ControllerEffect]s on a real
 * [SharedEffectBus]. A collector launched in [setUp] drains every emitted
 * effect into [collectedEffects] so the test bodies can filter by effect
 * type. non-fatal issues now go through the inlined
 * [cn.vectory.ocdroid.ui.reportNonFatalIssue] helper (which calls
 * `android.util.Log.w`) — these tests `mockkStatic(Log::class)` in setUp and
 * use `verify { Log.w(...) }` for the assertions that used to count
 * `onNonFatalIssue` callback invocations.
 *
 * The dispatch body is a verbatim move of the pre-extraction
 * `handleIncomingSseEvent`, so these cases are the behaviour-equivalence
 * proof that the SSE fold (message patch/insert, session upsert, status
 * badge, part streaming overlay, permission/question/todo, server.connected
 * catch-up trigger) is byte-for-byte preserved. SliceFlows are real so the
 * coordinator's state writes are observable.
 */
@Suppress("DEPRECATION")
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SessionSyncCoordinatorTest {

    private lateinit var slices: SliceFlows
    private lateinit var effects: SharedEffectBus
    private lateinit var collectedEffects: MutableList<ControllerEffect>
    private lateinit var recordedUiEvents: MutableList<cn.vectory.ocdroid.ui.UiEvent>
    private lateinit var settingsManager: SettingsManager
    private lateinit var scope: TestScope
    private lateinit var coordinator: SessionSyncCoordinator
    /** CP1 (notify Phase-0): single connection-identity store. */
    private lateinit var identityStore: cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
    /**
     * R-20 Phase 1 (C4): persistent cache mock. Relaxed by default (returns
     * Unit / null); individual tests re-stub to verify the
     * appendMessageIfSessionCached call on message.updated new-insert.
     */
    private lateinit var cacheRepository: cn.vectory.ocdroid.data.cache.CacheRepository
    /**
     * §R-17 batch2 step e final: in-test fixture carrying the prior snapshot
     * so successive `seed { ... }` calls compose against the prior state.
     * Production no longer has an AppState mirror; this fixture exists only
     * to drive the seed() transform chain.
     */
    private var appStateFixture: SeedFixture = SeedFixture()

    @Before
    fun setUp() {
        // §batch 3b: reportNonFatalIssue now runs inline in the coordinator and
        // calls android.util.Log.w. Stub it so the JVM test harness doesn't
        // throw on the android.util.Log statics.
        mockkStatic(Log::class)
        io.mockk.every { Log.w(any<String>(), any<String>()) } returns 0
        io.mockk.every { Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0
        appStateFixture = SeedFixture()
        // §R18 Phase 4 (P0-9): SliceFlows is built via a SharedStateStore; the
        // bundle exposes read-only StateFlow views + per-slice mutateXxx.
        val store = cn.vectory.ocdroid.ui.SharedStateStore()
        slices = store.slices
        settingsManager = mockk(relaxed = true)
        effects = SharedEffectBus()
        collectedEffects = mutableListOf()
        recordedUiEvents = mutableListOf()
        scope = TestScope(UnconfinedTestDispatcher())
        identityStore = cn.vectory.ocdroid.service.identity.ConnectionIdentityStore()
        cacheRepository = io.mockk.mockk(relaxed = true)
        // Drain every emitted effect into collectedEffects so the test bodies
        // can filter by type. Launched in scope so it auto-cancels at test end.
        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { effects.effectsConsumed.toList(collectedEffects) }
        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { effects.uiEventsConsumed.toList(recordedUiEvents) }
        coordinator = SessionSyncCoordinator(
            scope, slices, settingsManager, effects,
            currentServerGroupFp = { "test-fp" },
            cacheRepository = cacheRepository,
            identityStore = identityStore,
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    /**
     * §R-17 batch2 step e final: seed slices directly from a SeedFixture.
     * The fixture carries the prior snapshot so successive `seed { ... }` calls
     * compose against the prior state. The coordinator reads slices only.
     *
     * §R18 Phase 4 (P0-9): per-slice StateFlow views are read-only; funnel
     * every seed write through the matching mutateXxx helper.
     */
    private fun seed(transform: (SeedFixture) -> SeedFixture) {
        appStateFixture = transform(appStateFixture)
        val s = appStateFixture
        slices.mutateConnection {
            ConnectionState(
                isConnected = s.isConnected,
                isConnecting = s.isConnecting,
                serverVersion = s.serverVersion,
                connectionPhase = s.connectionPhase,
                tunnelActivationState = s.tunnelActivationState
            )
        }
        slices.mutateTraffic {
            TrafficState(
                trafficSent = s.trafficSent,
                trafficReceived = s.trafficReceived
            )
        }
        slices.mutateComposer {
            ComposerState(
                inputText = s.inputText,
                imageAttachments = s.imageAttachments,
                sendingSessionIds = s.sendingSessionIds,
                draftWorkdir = s.draftWorkdir
            )
        }
        slices.mutateFile {
            FileState(
                filePathToShowInFiles = s.filePathToShowInFiles,
                filePreviewOriginRoute = s.filePreviewOriginRoute,
                fileBrowserOpen = s.fileBrowserOpen,
                fileBrowserWorkdir = s.fileBrowserWorkdir
            )
        }
        slices.mutateSettings {
            SettingsState(
                themeMode = s.themeMode,
                markdownFontSizes = s.markdownFontSizes,
                selectedAgentName = s.selectedAgentName,
                agents = s.agents,
                providers = s.providers,
                availableCommands = s.availableCommands,
                disabledModels = s.disabledModels,
                uiFontScale = s.uiFontScale,
                uiContentScale = s.uiContentScale
            )
        }
        slices.mutateChat {
            it.copy(
                currentSessionId = s.currentSessionId,
                messages = s.messages,
                partsByMessage = s.partsByMessage,
                streamingPartTexts = s.streamingPartTexts,
                streamingReasoningPart = s.streamingReasoningPart,
                olderMessagesCursor = s.olderMessagesCursor,
                hasMoreMessages = s.hasMoreMessages,
                isLoadingMessages = s.isLoadingMessages,
                gapMarkers = s.gapMarkers,
                staleNotice = s.staleNotice,
                currentModel = s.currentModel
            )
        }
        slices.mutateSessionList {
            SessionListState(
                sessions = s.sessions,
                sessionStatuses = s.sessionStatuses,
                expandedSessionIds = s.expandedSessionIds,
                loadedSessionLimit = s.loadedSessionLimit,
                hasMoreSessions = s.hasMoreSessions,
                isLoadingMoreSessions = s.isLoadingMoreSessions,
                isRefreshingSessions = s.isRefreshingSessions,
                pendingPermissions = s.pendingPermissions,
                pendingQuestions = s.pendingQuestions,
                childSessions = s.childSessions,
                directorySessions = s.directorySessions,
                openSessionIds = s.openSessionIds,
                sessionTodos = s.sessionTodos
            )
        }
        slices.mutateUnread {
            UnreadState(
                unreadSessions = s.unreadSessions,
                lastViewedTime = s.lastViewedTime,
                idleSince = s.idleSince
            )
        }
        slices.mutateHost {
            HostState(
                hostProfiles = s.hostProfiles,
                currentHostProfileId = s.currentHostProfileId
            )
        }
    }

    /** Current-session guard: many folds only touch the current session's view. */
    private fun setCurrentSession(sessionId: String?) {
        seed { it.copy(currentSessionId = sessionId) }
    }

    private fun event(type: String, block: JsonObjectBuilder.() -> Unit): SSEEvent =
        SSEEvent(payload = SSEPayload(type = type, properties = buildJsonObject(block)))

    // ── server.connected ───────────────────────────────────────────────────

    @Test
    fun `server connected routes to onServerConnected and is a no-op in the dispatch table`() {
        coordinator.handleEvent(event("server.connected") {})

        assertEquals(1, collectedEffects.filterIsInstance<ControllerEffect.ServerConnected>().size)
        // No state mutation, no other callback.
        assertTrue(collectedEffects.filterIsInstance<ControllerEffect.LoadMessages>().isEmpty())
        verify(exactly = 0) { Log.w(any<String>(), any<String>()) }
    }

    // ── session.created / session.updated (upsert) ─────────────────────────

    @Test
    fun `session created prepends the parsed session via upsert`() {
        seed {
            it.copy(
            sessions = listOf(Session(id = "session-1", directory = "/tmp/old"))
            )
        }

        coordinator.handleEvent(event("session.created") {
            put("session", buildJsonObject {
                put("id", JsonPrimitive("session-2"))
                put("directory", JsonPrimitive("/tmp/project"))
                put("title", JsonPrimitive("New Session"))
            })
        })

        assertEquals(listOf("session-2", "session-1"), slices.sessionList.value.sessions.map { it.id })
    }

    @Test
    fun `session created with unparseable payload fires onNonFatalIssue without mutating state`() {
        coordinator.handleEvent(event("session.created") {
            put("session", JsonPrimitive("not-an-object"))
        })

        verify(exactly = 1) { Log.w(any<String>(), match<String> { it.contains("session.created") }) }
        assertTrue(slices.sessionList.value.sessions.isEmpty())
    }

    @Test
    fun `session updated replaces the existing session title from info`() {
        seed {
            it.copy(
            sessions = listOf(Session(id = "session-1", directory = "/tmp/project", title = null))
            )
        }

        coordinator.handleEvent(event("session.updated") {
            put("info", buildJsonObject {
                put("id", JsonPrimitive("session-1"))
                put("directory", JsonPrimitive("/tmp/project"))
                put("title", JsonPrimitive("Refactor auth module"))
            })
        })

        val sessions = slices.sessionList.value.sessions
        assertEquals(1, sessions.size)
        assertEquals("Refactor auth module", sessions[0].title)
    }

    @Test
    fun `session updated inserts an unknown session from the session key`() {
        seed {
            it.copy(
            sessions = listOf(Session(id = "session-1", directory = "/tmp/old"))
            )
        }

        coordinator.handleEvent(event("session.updated") {
            put("session", buildJsonObject {
                put("id", JsonPrimitive("session-new"))
                put("directory", JsonPrimitive("/tmp/new"))
                put("title", JsonPrimitive("New Feature"))
            })
        })

        val ids = slices.sessionList.value.sessions.map { it.id }
        assertEquals(listOf("session-new", "session-1"), ids)
    }

    @Test
    fun `session updated archived evicts the id from openSessionIds and persists`() {
        // Archived-by-another-client path: the SSE frame flips the session to
        // archived. The handler must drop it from openSessionIds (so the tab
        // strip drops it) and persist the cleaned list.
        seed {
            it.copy(
                sessions = listOf(Session(id = "session-1", directory = "/tmp/project")),
                openSessionIds = listOf("session-1", "session-2")
            )
        }

        coordinator.handleEvent(event("session.updated") {
            put("info", buildJsonObject {
                put("id", JsonPrimitive("session-1"))
                put("directory", JsonPrimitive("/tmp/project"))
                put("time", buildJsonObject { put("archived", JsonPrimitive(1_700_000_000_000)) })
            })
        })

        // Upsert still happened (authoritative record kept), but the tab list
        // dropped the now-archived id.
        assertTrue(slices.sessionList.value.sessions.any { it.id == "session-1" && it.isArchived })
        assertEquals(listOf("session-2"), slices.sessionList.value.openSessionIds)
        verify { settingsManager.openSessionIds = listOf("session-2") }
    }

    @Test
    fun `session updated archived clears currentSessionId and messages when it is the open session`() {
        // Cross-client archive of the currently-open session: the SSE handler
        // must not only drop the tab (openSessionIds) but also clear
        // currentSessionId + messages so the chat view falls back to empty,
        // aligning with the user-triggered archive path.
        seed {
            it.copy(
                sessions = listOf(Session(id = "session-1", directory = "/tmp/project")),
                openSessionIds = listOf("session-1"),
                currentSessionId = "session-1",
                messages = listOf(Message(id = "msg-1", role = "assistant")),
                partsByMessage = mapOf("msg-1" to emptyList())
            )
        }

        coordinator.handleEvent(event("session.updated") {
            put("info", buildJsonObject {
                put("id", JsonPrimitive("session-1"))
                put("directory", JsonPrimitive("/tmp/project"))
                put("time", buildJsonObject { put("archived", JsonPrimitive(1_700_000_000_000)) })
            })
        })

        assertNull(slices.chat.value.currentSessionId)
        assertTrue(slices.chat.value.messages.isEmpty())
        assertTrue(slices.chat.value.partsByMessage.isEmpty())
        assertEquals(emptyList<String>(), slices.sessionList.value.openSessionIds)
        // §R18 Phase 2-F: SettingsManager is no longer written directly here —
        // chatFlow.currentSessionId (asserted null above) is the runtime source;
        // the AppCore collector persists non-null changes only.
    }

    @Test
    fun `session updated non-archived keeps openSessionIds untouched and does not persist`() {
        seed {
            it.copy(
                sessions = listOf(Session(id = "session-1", directory = "/tmp/project")),
                openSessionIds = listOf("session-1", "session-2")
            )
        }

        coordinator.handleEvent(event("session.updated") {
            put("info", buildJsonObject {
                put("id", JsonPrimitive("session-1"))
                put("directory", JsonPrimitive("/tmp/project"))
                put("title", JsonPrimitive("Edited title"))
            })
        })

        assertEquals(listOf("session-1", "session-2"), slices.sessionList.value.openSessionIds)
        verify(exactly = 0) { settingsManager.openSessionIds = any() }
    }

    // ── session.status (busy / idle) ───────────────────────────────────────

    @Test
    fun `session status busy updates the badge and triggers a resetLimit reload for the current session`() {
        setCurrentSession("session-1")

        coordinator.handleEvent(event("session.status") {
            put("sessionID", JsonPrimitive("session-1"))
            put("status", buildJsonObject { put("type", JsonPrimitive("busy")) })
        })

        assertNotNull(slices.sessionList.value.sessionStatuses["session-1"])
        assertTrue(slices.sessionList.value.sessionStatuses["session-1"]!!.isBusy)
        assertEquals(
            listOf("session-1" to true),
            collectedEffects.filterIsInstance<ControllerEffect.LoadMessages>()
                .map { it.sessionId to it.resetLimit }
        )
    }

    @Test
    fun `session status busy for a non-current session only updates the badge`() {
        setCurrentSession("session-1")

        coordinator.handleEvent(event("session.status") {
            put("sessionID", JsonPrimitive("session-2"))
            put("status", buildJsonObject { put("type", JsonPrimitive("busy")) })
        })

        assertTrue(slices.sessionList.value.sessionStatuses["session-2"]!!.isBusy)
        assertTrue(collectedEffects.filterIsInstance<ControllerEffect.LoadMessages>().isEmpty())
    }

    @Test
    fun `session status idle on current with a non-empty streaming overlay triggers a resetLimit reload`() {
        setCurrentSession("session-1")
        seed {
            it.copy(
            streamingPartTexts = mapOf("part-1" to "partial"),
            streamingReasoningPart = Part(id = "part-1", messageId = "m1", sessionId = "session-1", type = "reasoning")
            )
        }

        coordinator.handleEvent(event("session.status") {
            put("sessionID", JsonPrimitive("session-1"))
            put("status", buildJsonObject { put("type", JsonPrimitive("idle")) })
        })

        assertFalse(slices.sessionList.value.sessionStatuses["session-1"]!!.isBusy)
        assertEquals(
            listOf("session-1" to true),
            collectedEffects.filterIsInstance<ControllerEffect.LoadMessages>()
                .map { it.sessionId to it.resetLimit }
        )
    }

    @Test
    fun `session status idle on current with an empty overlay skips the reload`() {
        setCurrentSession("session-1")

        coordinator.handleEvent(event("session.status") {
            put("sessionID", JsonPrimitive("session-1"))
            put("status", buildJsonObject { put("type", JsonPrimitive("idle")) })
        })

        assertFalse(slices.sessionList.value.sessionStatuses["session-1"]!!.isBusy)
        assertTrue(collectedEffects.filterIsInstance<ControllerEffect.LoadMessages>().isEmpty())
    }

    @Test
    fun `session status with an unparseable payload fires onNonFatalIssue`() {
        coordinator.handleEvent(event("session.status") {
            put("sessionID", JsonPrimitive("session-1"))
            put("status", JsonPrimitive("not-an-object"))
        })

        verify(exactly = 1) { Log.w(any<String>(), match<String> { it.contains("session.status") }) }
    }

    // ── §unread-soak: SSE no longer marks unread (sweep owns it) ──────────

    @Test
    fun `session status busy to idle on a non-current root session does not mark unread (sweep owns marking)`() {
        // §unread-soak: the instant busy→idle marker in the session.status
        // SSE handler is REMOVED. The [UnreadSoakController] sweep + pure
        // [evaluateUnread] evaluator now own the population logic (all-idle +
        // ≥10s soak + not-current + not-viewed). The SSE handler still folds
        // the status into sessionStatuses (so the evaluator sees it) but never
        // marks unread itself. The sweep-driven marking is covered by
        // [UnreadSoakTest] / [UnreadSoakControllerTest].
        seed {
            it.copy(
                currentSessionId = "CUR",
                sessions = listOf(
                    Session(id = "A", directory = "/tmp", parentId = null),
                    Session(id = "CUR", directory = "/tmp", parentId = null),
                ),
                sessionStatuses = mapOf("A" to SessionStatus(type = "busy")),
            )
        }

        coordinator.handleEvent(event("session.status") {
            put("sessionID", JsonPrimitive("A"))
            put("status", buildJsonObject { put("type", JsonPrimitive("idle")) })
        })

        // The status IS folded in (evaluator reads it on the next sweep tick)…
        assertTrue(slices.sessionList.value.sessionStatuses["A"]!!.isIdle)
        // …but unread is NOT populated by the SSE handler.
        assertFalse(slices.unread.value.unreadSessions.contains("A"))
    }

    @Test
    fun `session status busy to idle on the current session does not mark unread`() {
        seed {
            it.copy(
                currentSessionId = "A",
                sessions = listOf(Session(id = "A", directory = "/tmp", parentId = null)),
                sessionStatuses = mapOf("A" to SessionStatus(type = "busy")),
            )
        }

        coordinator.handleEvent(event("session.status") {
            put("sessionID", JsonPrimitive("A"))
            put("status", buildJsonObject { put("type", JsonPrimitive("idle")) })
        })

        assertFalse(slices.unread.value.unreadSessions.contains("A"))
    }

    @Test
    fun `session status busy to idle on a child session does not mark unread`() {
        seed {
            it.copy(
                currentSessionId = "CUR",
                sessions = listOf(
                    Session(id = "CHILD", directory = "/tmp", parentId = "A"),
                    Session(id = "A", directory = "/tmp", parentId = null),
                    Session(id = "CUR", directory = "/tmp", parentId = null),
                ),
                sessionStatuses = mapOf("CHILD" to SessionStatus(type = "busy")),
            )
        }

        coordinator.handleEvent(event("session.status") {
            put("sessionID", JsonPrimitive("CHILD"))
            put("status", buildJsonObject { put("type", JsonPrimitive("idle")) })
        })

        assertFalse(slices.unread.value.unreadSessions.contains("CHILD"))
    }

    @Test
    fun `session status busy to idle on an unknown session does not mark unread`() {
        seed {
            it.copy(
                currentSessionId = "CUR",
                sessions = listOf(Session(id = "CUR", directory = "/tmp", parentId = null)),
                sessionStatuses = mapOf("GHOST" to SessionStatus(type = "busy")),
            )
        }

        coordinator.handleEvent(event("session.status") {
            put("sessionID", JsonPrimitive("GHOST"))
            put("status", buildJsonObject { put("type", JsonPrimitive("idle")) })
        })

        assertFalse(slices.unread.value.unreadSessions.contains("GHOST"))
    }

    @Test
    fun `session status idle to idle does not mark unread`() {
        seed {
            it.copy(
                currentSessionId = "CUR",
                sessions = listOf(Session(id = "A", directory = "/tmp", parentId = null)),
                sessionStatuses = mapOf("A" to SessionStatus(type = "idle")),
            )
        }

        coordinator.handleEvent(event("session.status") {
            put("sessionID", JsonPrimitive("A"))
            put("status", buildJsonObject { put("type", JsonPrimitive("idle")) })
        })

        assertFalse(slices.unread.value.unreadSessions.contains("A"))
    }

    // ── message.created (forward-compat branch) ────────────────────────────

    @Test
    fun `message created for the current session triggers a resetLimit reload`() {
        setCurrentSession("session-1")

        coordinator.handleEvent(event("message.created") {
            put("sessionID", JsonPrimitive("session-1"))
        })

        assertEquals(
            listOf("session-1" to true),
            collectedEffects.filterIsInstance<ControllerEffect.LoadMessages>()
                .map { it.sessionId to it.resetLimit }
        )
    }

    @Test
    fun `message created for a non-current session does not mark unread`() {
        // §unread-lifecycle Task 3: message.created no longer produces unread.
        // The sole producer is root session busy→idle (lifecycle completion).
        setCurrentSession("session-1")

        coordinator.handleEvent(event("message.created") {
            put("sessionID", JsonPrimitive("session-2"))
        })

        assertFalse(slices.unread.value.unreadSessions.contains("session-2"))
        assertTrue(collectedEffects.filterIsInstance<ControllerEffect.LoadMessages>().isEmpty())
    }

    @Test
    fun `message created for the current session does not mark it unread`() {
        setCurrentSession("session-1")

        coordinator.handleEvent(event("message.created") {
            put("sessionID", JsonPrimitive("session-1"))
        })

        assertFalse(slices.unread.value.unreadSessions.contains("session-1"))
    }

    // ── message.updated (patch-if-found + insert-if-absent) ─────────────────

    @Test
    fun `message updated patches an existing message in place without reloading`() {
        setCurrentSession("session-1")
        seed {
            it.copy(
            messages = listOf(Message(id = "m1", role = "assistant"), Message(id = "m2", role = "user"))
            )
        }

        coordinator.handleEvent(event("message.updated") {
            put("sessionID", JsonPrimitive("session-1"))
            put("info", buildJsonObject {
                put("id", JsonPrimitive("m1"))
                put("role", JsonPrimitive("assistant"))
                put("cost", JsonPrimitive(0.5))
            })
        })

        assertEquals(listOf("m1", "m2"), slices.chat.value.messages.map { it.id })
        // No reload issued — patch is in-place.
        assertTrue(collectedEffects.filterIsInstance<ControllerEffect.LoadMessages>().isEmpty())
    }

    @Test
    fun `message updated inserts a new message when its id is absent from the local list`() {
        setCurrentSession("session-1")
        seed { it.copy(messages = listOf(Message(id = "m1", role = "user"))) }

        coordinator.handleEvent(event("message.updated") {
            put("sessionID", JsonPrimitive("session-1"))
            put("info", buildJsonObject {
                put("id", JsonPrimitive("m2"))
                put("role", JsonPrimitive("assistant"))
            })
        })

        // Inserted at the tail (oldest-first list).
        assertEquals(listOf("m1", "m2"), slices.chat.value.messages.map { it.id })
    }

    @Test
    fun `message updated is ignored when the event targets a non-current session`() {
        setCurrentSession("session-1")
        seed { it.copy(messages = listOf(Message(id = "m1", role = "user"))) }

        coordinator.handleEvent(event("message.updated") {
            put("sessionID", JsonPrimitive("session-other"))
            put("info", buildJsonObject {
                put("id", JsonPrimitive("m2"))
                put("role", JsonPrimitive("assistant"))
            })
        })

        // Defensive session guard: list untouched.
        assertEquals(listOf("m1"), slices.chat.value.messages.map { it.id })
    }

    // ── R-20 Phase 1 (C4, maxer I11): message.updated new-insert writes DB ──

    @Test
    fun `C4 message updated new insert appends to persistent cache`() {
        // found=false (id absent from local list) → the message was just
        // inserted into the slice. The persistent cache must mirror it via
        // appendMessageIfSessionCached so a subsequent switch-back sees it.
        // The coordinator's scope uses UnconfinedTestDispatcher, so the
        // scope.launch append runs eagerly before the assertion below.
        setCurrentSession("session-1")
        seed { it.copy(messages = listOf(Message(id = "m1", role = "user"))) }

        coordinator.handleEvent(event("message.updated") {
            put("sessionID", JsonPrimitive("session-1"))
            put("info", buildJsonObject {
                put("id", JsonPrimitive("m2"))
                put("role", JsonPrimitive("assistant"))
            })
        })

        // Verify appendMessageIfSessionCached was called once for the new id.
        io.mockk.coVerify(exactly = 1) {
            cacheRepository.appendMessageIfSessionCached(
                "test-fp",
                "session-1",
                match { it.id == "m2" },
                emptyList(),
            )
        }
    }

    @Test
    fun `C4 message updated patch does NOT append to persistent cache`() {
        // found=true (id already in local list) → the message was patched in
        // place. The cache must NOT be re-appended (the existing entry is
        // already correct; appending would be a redundant write + would
        // overwrite the cached parts with emptyList).
        setCurrentSession("session-1")
        seed {
            it.copy(
                messages = listOf(
                    Message(id = "m1", role = "user"),
                    Message(id = "m2", role = "assistant"),
                )
            )
        }

        coordinator.handleEvent(event("message.updated") {
            put("sessionID", JsonPrimitive("session-1"))
            put("info", buildJsonObject {
                put("id", JsonPrimitive("m1"))
                put("role", JsonPrimitive("user"))
                put("cost", JsonPrimitive(0.5))
            })
        })

        // Patch path: appendMessageIfSessionCached MUST NOT fire.
        io.mockk.coVerify(exactly = 0) {
            cacheRepository.appendMessageIfSessionCached(any(), any(), any(), any())
        }
    }

    @Test
    fun `C4 message updated non-current session does NOT append to cache`() {
        // Defensive session guard: append (like the slice insert) only fires
        // for the CURRENT session. A non-current session's insert is ignored
        // entirely (no slice write, no cache write).
        setCurrentSession("session-1")
        seed { it.copy(messages = listOf(Message(id = "m1", role = "user"))) }

        coordinator.handleEvent(event("message.updated") {
            put("sessionID", JsonPrimitive("session-other"))
            put("info", buildJsonObject {
                put("id", JsonPrimitive("m2"))
                put("role", JsonPrimitive("assistant"))
            })
        })

        io.mockk.coVerify(exactly = 0) {
            cacheRepository.appendMessageIfSessionCached(any(), any(), any(), any())
        }
    }

    // ── §recent-sort-by-message: bump session.time.updated on message events ──

    @Test
    fun `message updated bumps the owning session time updated to message time created`() {
        // §recent-sort-by-message: a message.updated event must bump the owning
        // session's time.updated to max(current, message.time.created) so the
        // recent-sessions surfaces (sortedByDescending { time.updated })
        // reflect actual message activity. The bump fires for the CURRENT
        // session, even when the message is patched in place (found=true).
        setCurrentSession("session-1")
        seed {
            it.copy(
                sessions = listOf(
                    Session(
                        id = "session-1",
                        directory = "/tmp",
                        time = Session.TimeInfo(created = 1_000L, updated = 1_500L)
                    )
                )
            )
        }

        coordinator.handleEvent(event("message.updated") {
            put("sessionID", JsonPrimitive("session-1"))
            put("info", buildJsonObject {
                put("id", JsonPrimitive("m1"))
                put("role", JsonPrimitive("assistant"))
                put("time", buildJsonObject {
                    put("created", JsonPrimitive(2_000L))
                })
            })
        })

        val updated = slices.sessionList.value.sessions.first { it.id == "session-1" }
        assertEquals(2_000L, updated.time?.updated)
    }

    @Test
    fun `message updated bumps time updated for a non-current session too`() {
        // §recent-sort-by-message: the bump fires for NON-current sessions as
        // well so cross-client activity in another session promotes it to the
        // top of the recent list (the chat-slice patch is the only thing that
        // short-circuits for non-current sessions — the session-list bump
        // happens BEFORE the current-session guard).
        setCurrentSession("session-1")
        seed {
            it.copy(
                sessions = listOf(
                    Session(id = "session-1", directory = "/a"),
                    Session(
                        id = "session-2",
                        directory = "/b",
                        time = Session.TimeInfo(created = 1_000L, updated = 1_000L)
                    )
                )
            )
        }

        coordinator.handleEvent(event("message.updated") {
            put("sessionID", JsonPrimitive("session-2"))
            put("info", buildJsonObject {
                put("id", JsonPrimitive("mX"))
                put("role", JsonPrimitive("assistant"))
                put("time", buildJsonObject {
                    put("created", JsonPrimitive(5_000L))
                })
            })
        })

        val s2 = slices.sessionList.value.sessions.first { it.id == "session-2" }
        assertEquals(
            "non-current session's time.updated must bump to the message's created timestamp",
            5_000L,
            s2.time?.updated
        )
    }

    @Test
    fun `message updated time bump is monotonic and never goes backwards`() {
        // §recent-sort-by-message: withUpdatedAtLeast is monotonic — an older
        // message.updated event (replay / out-of-order) MUST NOT move the
        // session's time.updated backwards.
        setCurrentSession("session-1")
        seed {
            it.copy(
                sessions = listOf(
                    Session(
                        id = "session-1",
                        directory = "/a",
                        time = Session.TimeInfo(created = 1_000L, updated = 10_000L)
                    )
                )
            )
        }

        coordinator.handleEvent(event("message.updated") {
            put("sessionID", JsonPrimitive("session-1"))
            put("info", buildJsonObject {
                put("id", JsonPrimitive("m1"))
                put("role", JsonPrimitive("assistant"))
                put("time", buildJsonObject {
                    // older than the current updated — must be ignored.
                    put("created", JsonPrimitive(2_000L))
                })
            })
        })

        val s = slices.sessionList.value.sessions.first { it.id == "session-1" }
        assertEquals(
            "time.updated must NOT go backwards",
            10_000L,
            s.time?.updated
        )
    }

    @Test
    fun `message updated with no message time created leaves session time updated untouched`() {
        // Defensive: if the payload lacks info.time.created, the bump is a
        // no-op (does not synthesize a System.currentTimeMillis() stamp that
        // could race the server's authoritative time.updated later).
        setCurrentSession("session-1")
        seed {
            it.copy(
                sessions = listOf(
                    Session(
                        id = "session-1",
                        directory = "/a",
                        time = Session.TimeInfo(created = 1_000L, updated = 1_500L)
                    )
                )
            )
        }

        coordinator.handleEvent(event("message.updated") {
            put("sessionID", JsonPrimitive("session-1"))
            put("info", buildJsonObject {
                put("id", JsonPrimitive("m1"))
                put("role", JsonPrimitive("assistant"))
                // no time.created
            })
        })

        val s = slices.sessionList.value.sessions.first { it.id == "session-1" }
        assertEquals(1_500L, s.time?.updated)
    }

    @Test
    fun `applyMessageTimestampBump bumps sessions in BOTH sessions and directorySessions stores`() {
        // §recent-sort-by-message: a session can be present in EITHER the
        // top-level sessions list OR a directorySessions bucket (or both — the
        // recent derivation merges + dedupes by id). The bump must touch BOTH
        // stores so a session that only surfaces via a directory fetch
        // (SessionsScreen.directorySessions) also reorders correctly.
        val dirSession = Session(
            id = "dir-only",
            directory = "/proj",
            time = Session.TimeInfo(created = 100L, updated = 100L)
        )
        val state = SessionListState(
            sessions = listOf(
                Session(
                    id = "top",
                    directory = "/proj",
                    time = Session.TimeInfo(created = 100L, updated = 100L)
                )
            ),
            directorySessions = mapOf("/proj" to listOf(dirSession))
        )

        val (next, effects) = state.applyMessageTimestampBump("dir-only", 5_000L)

        assertTrue("pure transform returns no side effects", effects.isEmpty())
        // directorySessions copy bumped.
        val dirBumped = next.directorySessions["/proj"]?.firstOrNull { it.id == "dir-only" }
        assertNotNull("directory-only session still present", dirBumped)
        assertEquals(5_000L, dirBumped!!.time?.updated)
        // Untouched top-level session stays unchanged.
        val topUntouched = next.sessions.first { it.id == "top" }
        assertEquals(100L, topUntouched.time?.updated)
    }

    @Test
    fun `applyMessageTimestampBump with non-positive timestamp is a no-op`() {
        // Defensive: an invalid (zero / negative) timestamp must not corrupt
        // the time.updated field (e.g. overwrite a real stamp with 0).
        val state = SessionListState(
            sessions = listOf(
                Session(
                    id = "s1",
                    directory = "/p",
                    time = Session.TimeInfo(created = 100L, updated = 1_000L)
                )
            )
        )
        val (next0, _) = state.applyMessageTimestampBump("s1", 0L)
        assertEquals(1_000L, next0.sessions.first { it.id == "s1" }.time?.updated)
        val (nextNeg, _) = state.applyMessageTimestampBump("s1", -5L)
        assertEquals(1_000L, nextNeg.sessions.first { it.id == "s1" }.time?.updated)
    }

    // ── message.part.updated (full text / delta / part.created) ─────────────

    @Test
    fun `message part updated full text replaces the streaming value as a sync point`() {
        setCurrentSession("session-1")

        coordinator.handleEvent(event("message.part.updated") {
            put("sessionID", JsonPrimitive("session-1"))
            put("part", buildJsonObject {
                put("messageID", JsonPrimitive("message-1"))
                put("id", JsonPrimitive("part-1"))
                put("type", JsonPrimitive("text"))
                put("text", JsonPrimitive("Hello world"))
            })
        })

        assertEquals("Hello world", slices.chat.value.streamingPartTexts["part-1"])
    }

    @Test
    fun `message part updated delta accumulates into streamingPartTexts and sets a reasoning overlay`() {
        setCurrentSession("session-1")

        coordinator.handleEvent(event("message.part.updated") {
            put("sessionID", JsonPrimitive("session-1"))
            put("part", buildJsonObject {
                put("messageID", JsonPrimitive("message-1"))
                put("id", JsonPrimitive("part-1"))
                put("type", JsonPrimitive("reasoning"))
            })
            put("delta", JsonPrimitive("thinking"))
        })

        assertEquals("thinking", slices.chat.value.streamingPartTexts["part-1"])
        assertEquals("part-1", slices.chat.value.streamingReasoningPart?.id)
    }

    @Test
    fun `message part updated blank reasoning creation records type and survives a text-field delta`() {
        // §reasoning-routing-fix: the server creates a reasoning part via
        // part.updated with type=reasoning but BLANK text (full=0) before
        // streaming content via part.delta. This blank creation must record the
        // true type + set streamingReasoningPart, otherwise the subsequent
        // part.delta (field="text" — server quirk) injects a type=text
        // placeholder and reasoning renders as 正文 (no thinking card).
        setCurrentSession("session-1")

        // Phase 1: blank creation event (no text, no delta).
        coordinator.handleEvent(event("message.part.updated") {
            put("sessionID", JsonPrimitive("session-1"))
            put("part", buildJsonObject {
                put("messageID", JsonPrimitive("message-1"))
                put("id", JsonPrimitive("part-1"))
                put("type", JsonPrimitive("reasoning"))
            })
        })

        assertEquals("part-1", slices.chat.value.streamingReasoningPart?.id)
        assertEquals("message-1", slices.chat.value.streamingReasoningPart?.messageId)
        val partsAfterCreate = slices.chat.value.partsByMessage["message-1"]
        assertNotNull(partsAfterCreate)
        assertEquals("reasoning", partsAfterCreate!!.first { it.id == "part-1" }.type)

        // Phase 2: idempotent — re-sending the creation event does not duplicate
        // the placeholder.
        coordinator.handleEvent(event("message.part.updated") {
            put("sessionID", JsonPrimitive("session-1"))
            put("part", buildJsonObject {
                put("messageID", JsonPrimitive("message-1"))
                put("id", JsonPrimitive("part-1"))
                put("type", JsonPrimitive("reasoning"))
            })
        })
        assertEquals(1, slices.chat.value.partsByMessage["message-1"]?.count { it.id == "part-1" })

        // Phase 3: a part.delta with field="text" resolves to the KNOWN
        // reasoning type (not field) and accumulates into the reasoning overlay.
        coordinator.handleEvent(event("message.part.delta") {
            put("sessionID", JsonPrimitive("session-1"))
            put("messageID", JsonPrimitive("message-1"))
            put("partID", JsonPrimitive("part-1"))
            put("field", JsonPrimitive("text"))
            put("delta", JsonPrimitive("step "))
        })
        assertEquals(
            "reasoning",
            slices.chat.value.partsByMessage["message-1"]!!.first { it.id == "part-1" }.type
        )
        assertEquals("part-1", slices.chat.value.streamingReasoningPart?.id)
        assertEquals("step ", slices.chat.value.streamingPartTexts["part-1"])
    }

    @Test
    fun `message part updated with null ids preserves the overlay and triggers a resetLimit=false reload`() {
        setCurrentSession("session-1")
        val seeded = mapOf("part-1" to "stale")
        val seededReasoning = Part(id = "part-1", messageId = "m1", sessionId = "session-1", type = "text")
        seed {
            it.copy(
            streamingPartTexts = seeded,
            streamingReasoningPart = seededReasoning
            )
        }

        // part.created: part object present but no messageID / id yet.
        coordinator.handleEvent(event("message.part.updated") {
            put("sessionID", JsonPrimitive("session-1"))
            put("part", buildJsonObject { put("type", JsonPrimitive("text")) })
        })

        // 闪屏修复：part.created（无 id）不再清空 overlay —— 同回合内每个新 part
        // 都发此信号，清空会导致所有流式气泡反复坍缩/充填闪烁。overlay 保留，
        // 仅触发 reload(resetLimit=false) 刷新权威快照。
        assertEquals(seeded, slices.chat.value.streamingPartTexts)
        assertEquals(seededReasoning, slices.chat.value.streamingReasoningPart)
        assertEquals(
            listOf("session-1" to false),
            collectedEffects.filterIsInstance<ControllerEffect.LoadMessages>()
                .map { it.sessionId to it.resetLimit }
        )
    }

    @Test
    fun `message part updated is ignored for a non-current session`() {
        setCurrentSession("session-1")

        coordinator.handleEvent(event("message.part.updated") {
            put("sessionID", JsonPrimitive("session-other"))
            put("part", buildJsonObject {
                put("messageID", JsonPrimitive("message-1"))
                put("id", JsonPrimitive("part-1"))
                put("type", JsonPrimitive("text"))
            })
            put("delta", JsonPrimitive("ignored"))
        })

        assertTrue(slices.chat.value.streamingPartTexts.isEmpty())
        assertTrue(collectedEffects.filterIsInstance<ControllerEffect.LoadMessages>().isEmpty())
    }

    // ── message.part.delta (web independent event) ──────────────────────────

    @Test
    fun `message part delta leading edge writes immediately and trailing deltas coalesce into a 100ms batch`() {
        setCurrentSession("session-1")

        fun delta(d: String) = coordinator.handleEvent(event("message.part.delta") {
            put("sessionID", JsonPrimitive("session-1"))
            put("messageID", JsonPrimitive("message-1"))
            put("partID", JsonPrimitive("part-1"))
            put("field", JsonPrimitive("text"))
            put("delta", JsonPrimitive(d))
        })

        delta("Hello")
        // §M5 leading edge: first delta renders immediately (zero-latency first token).
        assertEquals("Hello", slices.chat.value.streamingPartTexts["part-1"])

        delta(", world")
        delta("!")
        // §M5 trailing coalesce: subsequent deltas buffer within the 100ms window
        // — they do NOT each trigger a state write / Compose recomposition.
        assertEquals("Hello", slices.chat.value.streamingPartTexts["part-1"])

        // Advance virtual time past the DELTA_COALESCE_MS window → one batched flush.
        scope.testScheduler.advanceUntilIdle()

        assertEquals("Hello, world!", slices.chat.value.streamingPartTexts["part-1"])
        assertTrue(collectedEffects.filterIsInstance<ControllerEffect.LoadMessages>().isEmpty())
    }

    @Test
    fun `message part delta opens a fresh leading edge after the coalesce window flushes`() {
        setCurrentSession("session-1")

        fun delta(d: String) = coordinator.handleEvent(event("message.part.delta") {
            put("sessionID", JsonPrimitive("session-1"))
            put("messageID", JsonPrimitive("message-1"))
            put("partID", JsonPrimitive("part-1"))
            put("delta", JsonPrimitive(d))
        })

        delta("A")
        delta("B")
        scope.testScheduler.advanceUntilIdle()
        assertEquals("AB", slices.chat.value.streamingPartTexts["part-1"])

        // After the window closed, the next delta starts a new leading edge
        // (writes immediately), then a new 100ms window opens.
        delta("C")
        assertEquals("ABC", slices.chat.value.streamingPartTexts["part-1"])
        delta("D")
        assertEquals("ABC", slices.chat.value.streamingPartTexts["part-1"])

        scope.testScheduler.advanceUntilIdle()
        assertEquals("ABCD", slices.chat.value.streamingPartTexts["part-1"])
    }

    @Test
    fun `message part updated full text coalesces and replaces pending deltas so the snapshot stays authoritative`() {
        setCurrentSession("session-1")

        fun delta(d: String) = coordinator.handleEvent(event("message.part.delta") {
            put("sessionID", JsonPrimitive("session-1"))
            put("messageID", JsonPrimitive("message-1"))
            put("partID", JsonPrimitive("part-1"))
            put("delta", JsonPrimitive(d))
        })

        delta("partial")
        // Buffered delta that would corrupt the authoritative snapshot if flushed.
        delta(" STALE")
        assertEquals("partial", slices.chat.value.streamingPartTexts["part-1"])

        // Authoritative full text arrives while the delta coalesce window is
        // still open. §Site1 coalescing: it is buffered into the REPLACE
        // fullTextBuffer (not written synchronously), so the overlay still
        // shows the leading-edge value until the window flushes.
        coordinator.handleEvent(event("message.part.updated") {
            put("sessionID", JsonPrimitive("session-1"))
            put("part", buildJsonObject {
                put("messageID", JsonPrimitive("message-1"))
                put("id", JsonPrimitive("part-1"))
                put("type", JsonPrimitive("text"))
                put("text", JsonPrimitive("AUTHORITATIVE"))
            })
        })

        // Flushing the coalesce window must apply the authoritative fullText as
        // a REPLACE (fullTextBuffer wins over the deltaBuffer's STALE append),
        // so the stale delta never corrupts the snapshot.
        scope.testScheduler.advanceUntilIdle()
        assertEquals("AUTHORITATIVE", slices.chat.value.streamingPartTexts["part-1"])
    }

    @Test
    fun `message part created with null ids drops pending delta buffers but preserves overlay`() {
        setCurrentSession("session-1")
        seed { it.copy(streamingPartTexts = mapOf("part-1" to "partial")) }

        // Leading edge: first delta writes immediately + opens the 100ms window.
        coordinator.handleEvent(event("message.part.delta") {
            put("sessionID", JsonPrimitive("session-1"))
            put("messageID", JsonPrimitive("message-1"))
            put("partID", JsonPrimitive("part-1"))
            put("delta", JsonPrimitive(" MORE"))
        })
        assertEquals("partial MORE", slices.chat.value.streamingPartTexts["part-1"])

        // Trailing delta buffers (window still open) — not yet written.
        coordinator.handleEvent(event("message.part.delta") {
            put("sessionID", JsonPrimitive("session-1"))
            put("messageID", JsonPrimitive("message-1"))
            put("partID", JsonPrimitive("part-1"))
            put("delta", JsonPrimitive(" BUFFERED"))
        })
        // Still "partial MORE" — trailing delta is buffered, not written.
        assertEquals("partial MORE", slices.chat.value.streamingPartTexts["part-1"])

        // part.created (null ids): clearDeltaBuffers() drops the pending
        // " BUFFERED" buffer, but the overlay is PRESERVED (闪屏修复).
        coordinator.handleEvent(event("message.part.updated") {
            put("sessionID", JsonPrimitive("session-1"))
            put("part", buildJsonObject { put("type", JsonPrimitive("text")) })
        })

        // Overlay preserved — not cleared.
        assertEquals("partial MORE", slices.chat.value.streamingPartTexts["part-1"])
        // Advancing time must not resurrect the buffered delta (clearDeltaBuffers
        // cancelled the flush job + dropped the buffer).
        scope.testScheduler.advanceUntilIdle()
        assertEquals("partial MORE", slices.chat.value.streamingPartTexts["part-1"])
    }

    @Test
    fun `part-created preserves overlay then idle finalization clears it via resetLimit reload`() {
        setCurrentSession("session-1")
        seed {
            it.copy(
            streamingPartTexts = mapOf("part-1" to "streaming"),
            streamingReasoningPart = Part(id = "part-1", messageId = "m1", sessionId = "session-1", type = "reasoning")
            )
        }

        // part.created (null ids): overlay preserved (闪屏修复), reload(resetLimit=false).
        coordinator.handleEvent(event("message.part.updated") {
            put("sessionID", JsonPrimitive("session-1"))
            put("part", buildJsonObject { put("type", JsonPrimitive("text")) })
        })
        assertEquals("streaming", slices.chat.value.streamingPartTexts["part-1"])
        assertEquals(
            listOf("session-1" to false),
            collectedEffects.filterIsInstance<ControllerEffect.LoadMessages>()
                .map { it.sessionId to it.resetLimit }
        )

        // session.status idle: overlay still non-empty → idle finalization fires
        // reload(resetLimit=true), which is the safety net that ultimately clears
        // the overlay (MainViewModelMessageActions streamingFinalized branch).
        coordinator.handleEvent(event("session.status") {
            put("sessionID", JsonPrimitive("session-1"))
            put("status", buildJsonObject { put("type", JsonPrimitive("idle")) })
        })
        assertFalse(slices.sessionList.value.sessionStatuses["session-1"]!!.isBusy)
        assertEquals(
            listOf("session-1" to false, "session-1" to true),
            collectedEffects.filterIsInstance<ControllerEffect.LoadMessages>()
                .map { it.sessionId to it.resetLimit }
        )
    }

    @Test
    fun `message part delta is ignored for a non-current session`() {
        setCurrentSession("session-1")

        coordinator.handleEvent(event("message.part.delta") {
            put("sessionID", JsonPrimitive("session-other"))
            put("messageID", JsonPrimitive("message-1"))
            put("partID", JsonPrimitive("part-1"))
            put("delta", JsonPrimitive("ignored"))
        })

        assertTrue(slices.chat.value.streamingPartTexts.isEmpty())
    }

    // ── permission.asked / question.* / todo.updated ────────────────────────

    @Test
    fun `permission asked refreshes pending permissions via the callback`() {
        coordinator.handleEvent(event("permission.asked") {})
        assertEquals(1, collectedEffects.filterIsInstance<ControllerEffect.LoadPendingPermissions>().size)
    }

    @Test
    fun `question asked appends a pending question`() {
        coordinator.handleEvent(event("question.asked") {
            put("id", JsonPrimitive("question-1"))
            put("sessionID", JsonPrimitive("session-1"))
            put("questions", buildJsonArray {
                add(buildJsonObject {
                    put("question", JsonPrimitive("Which framework?"))
                    put("header", JsonPrimitive("Framework Choice"))
                    put("options", buildJsonArray {
                        add(buildJsonObject {
                            put("label", JsonPrimitive("React"))
                            put("description", JsonPrimitive("Popular UI library"))
                        })
                    })
                    put("multiple", JsonPrimitive(false))
                    put("custom", JsonPrimitive(true))
                })
            })
        })

        assertEquals(listOf("question-1"), slices.sessionList.value.pendingQuestions.map { it.id })
        assertEquals("session-1", slices.sessionList.value.pendingQuestions.single().sessionId)
    }

    @Test
    fun `question asked is idempotent when the id already exists`() {
        seed {
            it.copy(
            pendingQuestions = listOf(QuestionRequest(id = "question-1", sessionId = "session-1", questions = emptyList()))
            )
        }

        coordinator.handleEvent(event("question.asked") {
            put("id", JsonPrimitive("question-1"))
            put("sessionID", JsonPrimitive("session-1"))
            put("questions", buildJsonArray {})
        })

        assertEquals(1, slices.sessionList.value.pendingQuestions.size)
    }

    @Test
    fun `question rejected removes the pending question by requestID`() {
        seed {
            it.copy(
            pendingQuestions = listOf(
                QuestionRequest(id = "question-1", sessionId = "session-1", questions = emptyList()),
                QuestionRequest(id = "question-2", sessionId = "session-2", questions = emptyList())
            )
            )
        }

        coordinator.handleEvent(event("question.rejected") {
            put("requestID", JsonPrimitive("question-1"))
        })

        assertEquals(listOf("question-2"), slices.sessionList.value.pendingQuestions.map { it.id })
    }

    @Test
    fun `question replied removes the pending question by id fallback`() {
        seed {
            it.copy(
            pendingQuestions = listOf(QuestionRequest(id = "question-1", sessionId = "session-1", questions = emptyList()))
            )
        }

        coordinator.handleEvent(event("question.replied") {
            put("id", JsonPrimitive("question-1"))
        })

        assertTrue(slices.sessionList.value.pendingQuestions.isEmpty())
    }

    @Test
    fun `todo updated writes the parsed todos keyed by sessionID`() {
        coordinator.handleEvent(event("todo.updated") {
            put("sessionID", JsonPrimitive("session-1"))
            put("todos", buildJsonArray {
                add(buildJsonObject {
                    put("id", JsonPrimitive("todo-1"))
                    put("content", JsonPrimitive("Write tests"))
                    put("status", JsonPrimitive("pending"))
                    put("activeForm", buildJsonObject { put("pastTense", JsonPrimitive("wrote")) })
                })
            })
        })

        val todos = slices.sessionList.value.sessionTodos["session-1"]
        assertNotNull(todos)
        assertEquals(1, todos!!.size)
        assertEquals("todo-1", todos[0].id)
    }

    @Test
    fun `todo updated with a malformed todos array is a no-op`() {
        coordinator.handleEvent(event("todo.updated") {
            put("sessionID", JsonPrimitive("session-1"))
            put("todos", JsonPrimitive("not-an-array"))
        })

        assertNull(slices.sessionList.value.sessionTodos["session-1"])
    }

    // ── §issue-1(1)(2)(3): session.diff dispatch + sync/session.idle 识别 ─

    @Test
    fun `session diff writes the parsed diffs keyed by sessionID`() {
        coordinator.handleEvent(event("session.diff") {
            put("sessionID", JsonPrimitive("session-1"))
            put("diff", buildJsonArray {
                add(buildJsonObject {
                    put("file", JsonPrimitive("src/Main.kt"))
                    put("additions", JsonPrimitive(5))
                    put("deletions", JsonPrimitive(2))
                    put("status", JsonPrimitive("modified"))
                    put("patch", JsonPrimitive("@@ -1,2 +1,3 @@\n ctx\n+added"))
                    // lenientJson（ignoreUnknownKeys=true）必须容忍未知字段而不丢弃整条。
                    put("unexpectedFutureField", JsonPrimitive("ignored"))
                })
            })
        })

        val diffs = slices.sessionList.value.sessionDiffs["session-1"]
        assertNotNull(diffs)
        assertEquals(1, diffs!!.size)
        assertEquals("src/Main.kt", diffs[0].file)
        assertEquals(5, diffs[0].additions)
        assertEquals("modified", diffs[0].status)
        assertNotNull(diffs[0].patch)
    }

    @Test
    fun `session diff with a malformed diff array is a no-op`() {
        coordinator.handleEvent(event("session.diff") {
            put("sessionID", JsonPrimitive("session-1"))
            put("diff", JsonPrimitive("not-an-array"))
        })

        assertNull(slices.sessionList.value.sessionDiffs["session-1"])
    }

    @Test
    fun `sync and session idle are recognized without warning or unknown-counter bump`() {
        // §issue-1(2)(3): 显式 case 不落入 else——无 DebugLog.w 告警、unknownEventCounters 不增。
        coordinator.handleEvent(event("sync") {})
        coordinator.handleEvent(event("session.idle") { put("sessionID", JsonPrimitive("s1")) })

        verify(exactly = 0) { Log.w(any<String>(), any<String>()) }
        assertTrue(coordinator.unknownEventCountsSnapshot().isEmpty())
    }

    @Test
    fun `an unknown event type is silently ignored`() {
        coordinator.handleEvent(event("plugin.added") { put("sessionID", JsonPrimitive("session-1")) })
        verify(exactly = 0) { Log.w(any<String>(), any<String>()) }
        assertTrue(collectedEffects.filterIsInstance<ControllerEffect.LoadMessages>().isEmpty())
    }

    // ── §R18 Phase 3 Wave 1 (P0-7): unknown SSE event warning + counter ────

    @Test
    fun `a noisy unknown event type skips the log warning but still bumps the counter`() {
        // plugin.added is in NOISY_SSE_LOG_EVENTS → no DebugLog.w (preserves
        // the original test contract: no Log.w on noisy types).
        coordinator.handleEvent(event("plugin.added") { put("sessionID", JsonPrimitive("s1")) })

        verify(exactly = 0) { Log.w(any<String>(), any<String>()) }
        // P0-7: the counter still increments so diagnostics can spot recurring
        // unknown types even when they happen to be on the noisy-skip list.
        assertEquals(mapOf("plugin.added" to 1), coordinator.unknownEventCountsSnapshot())
    }

    @Test
    fun `a genuinely unknown event type logs a warning and bumps the counter`() {
        // §P0-7: a non-noisy type that fell through the dispatch table must
        // (a) be logged via DebugLog.w (= Log.w under the mockkStatic stub),
        // (b) bump the unknownEventCounters entry, (c) in DEBUG builds, emit
        // a UiEvent.Debug so the in-app snackbar surfaces the surprise.
        coordinator.handleEvent(event("totally.unknown") {
            put("sessionID", JsonPrimitive("s1"))
            put("weird", JsonPrimitive("field"))
        })

        verify(atLeast = 1) { Log.w(any<String>(), match<String> { it.contains("totally.unknown") }) }
        assertEquals(mapOf("totally.unknown" to 1), coordinator.unknownEventCountsSnapshot())
    }

    @Test
    fun `unknownEventCounters accumulates per type across events`() {
        coordinator.handleEvent(event("unknown.a") {})
        coordinator.handleEvent(event("unknown.a") {})
        coordinator.handleEvent(event("unknown.b") {})

        val snapshot = coordinator.unknownEventCountsSnapshot()
        assertEquals(2, snapshot["unknown.a"])
        assertEquals(1, snapshot["unknown.b"])
    }

    // ── §R18 Phase 3 Wave 1 (P1-9): multi-workdir pending questions fan-out ──

    // §issue-1 Phase 2a Fix B: fan-out site (1) now INCLUDES per-fp recent_workdirs
    // (flipped green from the Phase 1b characterization that asserted their absence).
    // §badge-stale-fix: behavior changed from optimistic keep-existing merge to
    // AUTHORITATIVE reconcile — the fan-out covers every known workdir at once, so
    // its union is the server's source of truth; a question the server no longer
    // reports (resolved without the client receiving the resolve event) is DROPPED
    // instead of lingering as a ghost that keeps the Sessions nav badge lit.
    @Test
    fun `loadPendingQuestionsAllWorkdirs fans out across directorySessions keys plus currentWorkdir plus recent_workdirs and reconciles authoritatively`() {
        // §P1-9: the single-workdir AppCore dispatch path polls only
        // currentWorkdir; background workdirs' questions vanish. The fan-out
        // here queries EVERY known directory + currentWorkdir + recent_workdirs
        // (per-fp) and reconciles authoritatively.
        seed {
            it.copy(
                directorySessions = mapOf(
                    "/proj-a" to listOf(Session(id = "sa", directory = "/proj-a")),
                    "/proj-b" to listOf(Session(id = "sb", directory = "/proj-b"))
                ),
                pendingQuestions = listOf(
                    QuestionRequest(id = "existing-not-on-server", sessionId = "s0", questions = emptyList())
                )
            )
        }
        every { settingsManager.currentWorkdir } returns "/current"
        // §issue-1 Fix B: recent_workdirs (per-fp) are now part of the fan-out.
        // Seed a fake list for the test fp; the coordinator's currentServerGroupFp
        // is "test-fp" (see setUp), so getRecentWorkdirs("test-fp") returns these.
        every { settingsManager.getRecentWorkdirs("test-fp") } returns listOf("/recent-1", "/recent-2")
        // Capture the EXACT workdir set fanned out (stronger than per-path coVerify).
        val queriedDirs = mutableListOf<String>()
        val repository = mockk<cn.vectory.ocdroid.data.repository.OpenCodeRepository>(relaxed = true)
        coEvery { repository.getPendingQuestions(any()) } answers {
            val dir = firstArg<String>()
            queriedDirs += dir
            when (dir) {
                "/proj-a" -> Result.success(
                    listOf(QuestionRequest(id = "qa", sessionId = "sa", questions = emptyList()))
                )
                "/proj-b" -> Result.success(
                    listOf(QuestionRequest(id = "qb", sessionId = "sb", questions = emptyList()))
                )
                "/current" -> Result.success(
                    listOf(QuestionRequest(id = "qc", sessionId = "sc", questions = emptyList()))
                )
                else -> Result.success(emptyList())
            }
        }

        coordinator.loadPendingQuestionsAllWorkdirs(repository)
        scope.testScheduler.advanceUntilIdle()

        val ids = slices.sessionList.value.pendingQuestions.map { it.id }.toSet()
        // Authoritative reconcile: server result wins; the locally-held ghost
        // (present at fan-out start, absent from every server response) is dropped.
        assertTrue("qa from /proj-a", "qa" in ids)
        assertTrue("qb from /proj-b", "qb" in ids)
        assertTrue("qc from /current", "qc" in ids)
        assertFalse("existing-not-on-server dropped (ghost no longer reported by server)", "existing-not-on-server" in ids)
        // §issue-1 Fix B: exact workdir SET = directorySessions.keys + currentWorkdir
        // + recent_workdirs (per-fp). Each queried exactly once (distinct). The
        // recent workdirs contribute no questions here (else-branch → empty) but
        // ARE queried — proving a question on a recently-used-but-disconnected
        // workdir is no longer missed.
        assertEquals(5, queriedDirs.size)
        assertEquals(
            "fan-out set must be directorySessions.keys + currentWorkdir + recent_workdirs",
            setOf("/proj-a", "/proj-b", "/current", "/recent-1", "/recent-2"),
            queriedDirs.toSet(),
        )
    }

    @Test
    fun `loadPendingQuestionsAllWorkdirs dedupes workdirs that appear in both directorySessions and currentWorkdir`() {
        seed {
            it.copy(directorySessions = mapOf("/dup" to listOf(Session(id = "sx", directory = "/dup"))))
        }
        every { settingsManager.currentWorkdir } returns "/dup"
        val repository = mockk<cn.vectory.ocdroid.data.repository.OpenCodeRepository>(relaxed = true)
        coEvery { repository.getPendingQuestions("/dup") } returns Result.success(emptyList())

        coordinator.loadPendingQuestionsAllWorkdirs(repository)
        scope.testScheduler.advanceUntilIdle()

        coVerify(exactly = 1) { repository.getPendingQuestions("/dup") }
    }

    @Test
    fun `loadPendingQuestionsAllWorkdirs is a no-op when no workdirs are known`() {
        seed { it.copy(directorySessions = emptyMap()) }
        every { settingsManager.currentWorkdir } returns null
        val repository = mockk<cn.vectory.ocdroid.data.repository.OpenCodeRepository>(relaxed = true)

        coordinator.loadPendingQuestionsAllWorkdirs(repository)
        scope.testScheduler.advanceUntilIdle()

        coVerify(exactly = 0) { repository.getPendingQuestions(any()) }
    }

    @Test
    fun `loadPendingQuestionsAllWorkdirs swallows per-directory failures and keeps the slice unchanged for that dir`() {
        seed { it.copy(directorySessions = mapOf("/bad" to listOf(Session(id = "s", directory = "/bad")))) }
        every { settingsManager.currentWorkdir } returns null
        val repository = mockk<cn.vectory.ocdroid.data.repository.OpenCodeRepository>(relaxed = true)
        coEvery { repository.getPendingQuestions("/bad") } returns Result.failure(java.io.IOException("network down"))

        coordinator.loadPendingQuestionsAllWorkdirs(repository)
        scope.testScheduler.advanceUntilIdle()

        // Failure path doesn't wipe the slice — pendingQuestions stays empty
        // (its initial state) rather than being mutated by the failed fetch.
        assertTrue(slices.sessionList.value.pendingQuestions.isEmpty())
    }

    // ── §task5-ghost (final-review fix 2): archived-session question guard ──

    @Test
    fun `loadPendingQuestionsAllWorkdirs drops questions whose session is locally archived even when the server still returns them`() {
        // §task5-ghost: an archived session's questions were cleared from the
        // presentation domain by the archive reducer. The authoritative sweep
        // must NOT let them ghost back when the server still includes them in
        // its pending-questions response (e.g. race between the archive REST
        // call and the pending-questions snapshot on the server). Unknown
        // session ids are kept (conservative).
        seed {
            it.copy(
                sessions = listOf(
                    Session(id = "live", directory = "/p"),
                    Session(id = "archived", directory = "/p", time = Session.TimeInfo(archived = 1L)),
                ),
                directorySessions = mapOf("/p" to listOf(Session(id = "live", directory = "/p"))),
            )
        }
        every { settingsManager.currentWorkdir } returns "/p"
        every { settingsManager.getRecentWorkdirs("test-fp") } returns emptyList()
        val repository = mockk<cn.vectory.ocdroid.data.repository.OpenCodeRepository>(relaxed = true)
        coEvery { repository.getPendingQuestions(any()) } returns Result.success(
            listOf(
                QuestionRequest(id = "q-live", sessionId = "live", questions = emptyList()),
                QuestionRequest(id = "q-archived", sessionId = "archived", questions = emptyList()),
                QuestionRequest(id = "q-unknown", sessionId = "no-such-session", questions = emptyList()),
            )
        )

        coordinator.loadPendingQuestionsAllWorkdirs(repository)
        scope.testScheduler.advanceUntilIdle()

        val ids = slices.sessionList.value.pendingQuestions.map { it.id }.toSet()
        assertTrue("live session question kept", "q-live" in ids)
        assertFalse(
            "archived session question NOT resurrected (ghost guard)",
            "q-archived" in ids,
        )
        assertTrue(
            "unknown session question kept (conservative)",
            "q-unknown" in ids,
        )
    }

    @Test
    fun `loadPendingQuestionsAllWorkdirs drops questions whose ancestor session is archived - root-only archive path`() {
        // §task5-ghost-r2 (final-fix round 2): the server archived only the
        // root (root-only SSE archive event); the child lives in sessionsById
        // as still-UNARCHIVED, and the server's pending-questions response
        // still includes the child's question. The Fix-1 reducer cleared the
        // child question locally; without the ancestor walk this reconcile
        // would only inspect the (still-unarchived) child and let the question
        // ghost back. The filter must walk parentId chains so an archived root
        // drops the child's question too.
        seed {
            it.copy(
                sessions = listOf(
                    Session(id = "archived-root", directory = "/p", time = Session.TimeInfo(archived = 1L)),
                    Session(id = "child", directory = "/p", parentId = "archived-root"),
                ),
            )
        }
        every { settingsManager.currentWorkdir } returns "/p"
        every { settingsManager.getRecentWorkdirs("test-fp") } returns emptyList()
        val repository = mockk<cn.vectory.ocdroid.data.repository.OpenCodeRepository>(relaxed = true)
        coEvery { repository.getPendingQuestions(any()) } returns Result.success(
            listOf(
                QuestionRequest(id = "q-child", sessionId = "child", questions = emptyList()),
            )
        )

        coordinator.loadPendingQuestionsAllWorkdirs(repository)
        scope.testScheduler.advanceUntilIdle()

        val ids = slices.sessionList.value.pendingQuestions.map { it.id }.toSet()
        assertFalse(
            "descendant of archived root question NOT resurrected (ancestor ghost guard)",
            "q-child" in ids,
        )
    }

    // ── §task7-coverage: session.error event handler ────────────────────────
    // The session.error SSE event (rate-limit / quota / provider failures) was
    // entirely untested — every branch in its handler (UiEvent.Error emission
    // + chat-slice error attachment) was missed. These tests exercise both the
    // named and unnamed error shapes, plus the current/non-current chat mutation.

    @Test
    fun `session error with name emits a named UiEvent Error`() {
        coordinator.handleEvent(event("session.error") {
            put("sessionID", JsonPrimitive("s1"))
            put("error", buildJsonObject {
                put("name", JsonPrimitive("RateLimitError"))
                put("data", buildJsonObject {
                    put("message", JsonPrimitive("slow down"))
                })
            })
        })

        val err = recordedUiEvents.filterIsInstance<cn.vectory.ocdroid.ui.UiEvent.Error>().single()
        assertEquals(cn.vectory.ocdroid.R.string.error_session_sse_named, err.resId)
        assertEquals("RateLimitError", err.args[0])
        assertEquals("slow down", err.args[1])
    }

    @Test
    fun `session error without name emits an unnamed UiEvent Error`() {
        coordinator.handleEvent(event("session.error") {
            put("sessionID", JsonPrimitive("s1"))
            put("error", buildJsonObject {
                put("data", buildJsonObject {
                    put("message", JsonPrimitive("generic failure"))
                })
            })
        })

        val err = recordedUiEvents.filterIsInstance<cn.vectory.ocdroid.ui.UiEvent.Error>().single()
        assertEquals(cn.vectory.ocdroid.R.string.error_session_sse_unnamed, err.resId)
        assertEquals("generic failure", err.args[0])
    }

    @Test
    fun `session error for the current session attaches error to last assistant message`() {
        setCurrentSession("s1")
        seed {
            it.copy(messages = listOf(
                Message(id = "m-user", role = "user"),
                Message(id = "m-bot", role = "assistant"),
            ))
        }

        coordinator.handleEvent(event("session.error") {
            put("sessionID", JsonPrimitive("s1"))
            put("error", buildJsonObject {
                put("name", JsonPrimitive("ProviderError"))
                put("data", buildJsonObject {
                    put("message", JsonPrimitive("failed"))
                })
            })
        })

        val bot = slices.chat.value.messages.first { it.id == "m-bot" }
        assertNotNull(bot.error)
        assertEquals("ProviderError", bot.error!!.name)
    }

    @Test
    fun `session error for current session with no assistant message does not mutate chat`() {
        setCurrentSession("s1")
        seed {
            it.copy(messages = listOf(Message(id = "m-user", role = "user")))
        }

        coordinator.handleEvent(event("session.error") {
            put("sessionID", JsonPrimitive("s1"))
            put("error", buildJsonObject {
                put("name", JsonPrimitive("E"))
                put("data", buildJsonObject { put("message", JsonPrimitive("m")) })
            })
        })

        assertTrue(slices.chat.value.messages.none { it.error != null })
    }

    @Test
    fun `session error for current session where last assistant already has error is a no-op on chat`() {
        setCurrentSession("s1")
        seed {
            it.copy(messages = listOf(
                Message(id = "m-bot", role = "assistant", error = Message.MessageError(name = "old", data = null)),
            ))
        }

        coordinator.handleEvent(event("session.error") {
            put("sessionID", JsonPrimitive("s1"))
            put("error", buildJsonObject {
                put("name", JsonPrimitive("New"))
                put("data", buildJsonObject { put("message", JsonPrimitive("m")) })
            })
        })

        val bot = slices.chat.value.messages.first { it.id == "m-bot" }
        assertEquals("old", bot.error!!.name)
    }

    @Test
    fun `session error for a non-current session does not mutate chat`() {
        setCurrentSession("s1")
        seed {
            it.copy(messages = listOf(Message(id = "m-bot", role = "assistant")))
        }

        coordinator.handleEvent(event("session.error") {
            put("sessionID", JsonPrimitive("s2"))
            put("error", buildJsonObject {
                put("name", JsonPrimitive("E"))
                put("data", buildJsonObject { put("message", JsonPrimitive("m")) })
            })
        })

        assertTrue(slices.chat.value.messages.none { it.error != null })
    }

    // ── §task7-coverage: session.status busy→busy branch ────────────────────

    @Test
    fun `session status busy to busy does not mark unread`() {
        // Covers the nowIdle=false branch of the compound condition at line 534.
        seed {
            it.copy(
                currentSessionId = "CUR",
                sessions = listOf(Session(id = "A", directory = "/tmp", parentId = null)),
                sessionStatuses = mapOf("A" to SessionStatus(type = "busy")),
            )
        }

        coordinator.handleEvent(event("session.status") {
            put("sessionID", JsonPrimitive("A"))
            put("status", buildJsonObject { put("type", JsonPrimitive("busy")) })
        })

        assertFalse(slices.unread.value.unreadSessions.contains("A"))
    }

    @Test
    fun `session status idle with no prior status entry does not mark unread`() {
        // Covers the oldStatus=null → wasBusy=false branch.
        seed {
            it.copy(
                currentSessionId = "CUR",
                sessions = listOf(
                    Session(id = "A", directory = "/tmp", parentId = null),
                    Session(id = "CUR", directory = "/tmp", parentId = null),
                ),
                sessionStatuses = emptyMap(),
            )
        }

        coordinator.handleEvent(event("session.status") {
            put("sessionID", JsonPrimitive("A"))
            put("status", buildJsonObject { put("type", JsonPrimitive("idle")) })
        })

        assertFalse(slices.unread.value.unreadSessions.contains("A"))
    }

    // ── §files-events-noop (task 7): file.watcher.updated / file.edited ─────

    @Test
    fun `file watcher updated is a no-op - no warning, no state change`() {
        // §files-events-noop: location-scoped filesystem event with NO sessionID
        // (session=- is normal). Explicit empty case ⇒ NOT routed to the
        // unrecognized-event warning branch. No sessionList / unread mutation.
        val sessionsBefore = slices.sessionList.value
        val unreadBefore = slices.unread.value

        coordinator.handleEvent(event("file.watcher.updated") {
            put("path", JsonPrimitive("/tmp/project/src/Main.kt"))
        })

        // No unrecognized-event warning (the !noisy gate would fire Log.w).
        verify(exactly = 0) {
            Log.w(any<String>(), match<String> { it.contains("unrecognized event") })
        }
        assertEquals(sessionsBefore, slices.sessionList.value)
        assertEquals(unreadBefore, slices.unread.value)
    }

    @Test
    fun `file edited is a no-op - no warning, no state change`() {
        val sessionsBefore = slices.sessionList.value
        val unreadBefore = slices.unread.value

        coordinator.handleEvent(event("file.edited") {
            put("path", JsonPrimitive("/tmp/project/README.md"))
        })

        verify(exactly = 0) {
            Log.w(any<String>(), match<String> { it.contains("unrecognized event") })
        }
        assertEquals(sessionsBefore, slices.sessionList.value)
        assertEquals(unreadBefore, slices.unread.value)
    }

    @Test
    fun `file events do not increment unknownEventCounters`() {
        // §files-events-noop: explicit empty cases are semantically correct —
        // they must NOT bump the unknown-event counter (that's for genuinely
        // unrecognized types routed to the else branch).
        coordinator.handleEvent(event("file.watcher.updated") {})
        coordinator.handleEvent(event("file.edited") {})

        val counts = coordinator.unknownEventCountsSnapshot()
        assertFalse("file.watcher.updated" in counts)
        assertFalse("file.edited" in counts)
    }

    // ── §task7-coverage: invalid payload ReportNonFatal paths ───────────────

    @Test
    fun `session created with invalid payload fires ReportNonFatal`() {
        coordinator.handleEvent(event("session.created") {
            put("session", JsonPrimitive("not-an-object"))
        })

        io.mockk.verify { Log.w(any<String>(), match<String> { it.contains("invalid session.created") }) }
    }
    @Test
    fun `session updated with invalid payload fires ReportNonFatal`() {
        coordinator.handleEvent(event("session.updated") {
            put("info", JsonPrimitive("garbage"))
        })

        io.mockk.verify { Log.w(any<String>(), match<String> { it.contains("invalid session.updated") }) }
    }

    @Test
    fun `session diff with invalid diff array is silently dropped`() {
        coordinator.handleEvent(event("session.diff") {
            put("sessionID", JsonPrimitive("s1"))
            put("diff", JsonPrimitive("not-an-array"))
        })

        assertTrue(slices.sessionList.value.sessionDiffs.isEmpty())
    }

    @Test
    fun `todo updated with malformed todos array is silently dropped`() {
        coordinator.handleEvent(event("todo.updated") {
            put("sessionID", JsonPrimitive("s1"))
            put("todos", JsonPrimitive("not-an-array"))
        })

        assertTrue(slices.sessionList.value.sessionTodos.isEmpty())
    }

    // ── §task7-coverage: message.created forward-compat timestamp bump ─────

    @Test
    fun `message created with info time-created bumps the session timestamp`() {
        seed {
            it.copy(
                currentSessionId = "s1",
                sessions = listOf(Session(id = "s1", directory = "/tmp")),
            )
        }

        coordinator.handleEvent(event("message.created") {
            put("sessionID", JsonPrimitive("s1"))
            put("info", buildJsonObject {
                put("id", "m-new")
                put("role", "assistant")
                put("time", buildJsonObject {
                    put("created", 99999L)
                })
            })
        })

        val session = slices.sessionList.value.sessions.first { it.id == "s1" }
        assertEquals(99999L, session.time?.updated)
    }

    @Test
    fun `message created for current session triggers reload`() {
        setCurrentSession("session-1")

        coordinator.handleEvent(event("message.created") {
            put("sessionID", JsonPrimitive("session-1"))
        })

        assertEquals(
            listOf("session-1" to true),
            collectedEffects.filterIsInstance<ControllerEffect.LoadMessages>()
                .map { it.sessionId to it.resetLimit }
        )
    }

    // ── §task7-coverage: session.error data parsing branches ────────────────

    @Test
    fun `session error with data error field instead of message falls back correctly`() {
        coordinator.handleEvent(event("session.error") {
            put("sessionID", JsonPrimitive("s1"))
            put("error", buildJsonObject {
                put("name", JsonPrimitive("Err"))
                put("data", buildJsonObject {
                    put("error", JsonPrimitive("from-error-field"))
                })
            })
        })

        val err = recordedUiEvents.filterIsInstance<cn.vectory.ocdroid.ui.UiEvent.Error>().single()
        assertEquals("from-error-field", err.args[1])
    }

    @Test
    fun `session error with null data uses default message`() {
        coordinator.handleEvent(event("session.error") {
            put("sessionID", JsonPrimitive("s1"))
            put("error", buildJsonObject {
                put("name", JsonPrimitive("Named"))
            })
        })

        val err = recordedUiEvents.filterIsInstance<cn.vectory.ocdroid.ui.UiEvent.Error>().single()
        assertEquals(R.string.error_session_sse_named, err.resId)
        assertEquals("Named", err.args[0])
        assertEquals("Server session error", err.args[1])
    }

    @Test
    fun `session error with null error object uses all defaults`() {
        coordinator.handleEvent(event("session.error") {
            put("sessionID", JsonPrimitive("s1"))
        })

        val err = recordedUiEvents.filterIsInstance<cn.vectory.ocdroid.ui.UiEvent.Error>().single()
        assertEquals(R.string.error_session_sse_unnamed, err.resId)
        assertEquals("Server session error", err.args[0])
    }

    @Test
    fun `session error with blank name is treated as unnamed`() {
        coordinator.handleEvent(event("session.error") {
            put("sessionID", JsonPrimitive("s1"))
            put("error", buildJsonObject {
                put("name", JsonPrimitive("   "))
                put("data", buildJsonObject { put("message", JsonPrimitive("m")) })
            })
        })

        val err = recordedUiEvents.filterIsInstance<cn.vectory.ocdroid.ui.UiEvent.Error>().single()
        assertEquals(R.string.error_session_sse_unnamed, err.resId)
    }

    // ── §task7-coverage: markSessionColdSnapshotted branches ────────────────

    @Test
    fun `markSessionColdSnapshotted with blank id is a no-op`() {
        coordinator.markSessionColdSnapshotted("   ")
        assertTrue(coordinator.sseSyncStateSnapshot().sessionsEverColdSnapshotted.isEmpty())
    }

    @Test
    fun `markSessionColdSnapshotted is idempotent`() {
        coordinator.markSessionColdSnapshotted("s1")
        val afterFirst = coordinator.sseSyncStateSnapshot().sessionsEverColdSnapshotted
        assertEquals(setOf("s1"), afterFirst)

        coordinator.markSessionColdSnapshotted("s1")
        val afterSecond = coordinator.sseSyncStateSnapshot().sessionsEverColdSnapshotted
        assertEquals(afterFirst, afterSecond)
    }

    @Test
    fun `markSessionColdSnapshotted accumulates distinct ids`() {
        coordinator.markSessionColdSnapshotted("s1")
        coordinator.markSessionColdSnapshotted("s2")
        assertEquals(
            setOf("s1", "s2"),
            coordinator.sseSyncStateSnapshot().sessionsEverColdSnapshotted
        )
    }

    // ── §task7-coverage: user-part guard in message.part.updated ────────────

    @Test
    fun `message part updated for a user message is silently dropped`() {
        setCurrentSession("s1")
        seed {
            it.copy(messages = listOf(Message(id = "m-user", role = "user")))
        }

        coordinator.handleEvent(event("message.part.updated") {
            put("sessionID", JsonPrimitive("s1"))
            put("part", buildJsonObject {
                put("messageID", JsonPrimitive("m-user"))
                put("id", JsonPrimitive("p1"))
                put("type", JsonPrimitive("text"))
            })
            put("delta", JsonPrimitive("hello"))
        })

        assertTrue(slices.chat.value.streamingPartTexts.isEmpty())
        assertTrue(slices.chat.value.partsByMessage.isEmpty())
    }

    // ── §task7-coverage: message.updated non-current session early return ───

    @Test
    fun `message updated for a non-current session skips chat mutation but bumps time`() {
        setCurrentSession("s1")
        seed {
            it.copy(
                sessions = listOf(
                    Session(id = "s1", directory = "/tmp"),
                    Session(id = "s2", directory = "/tmp"),
                ),
            )
        }

        coordinator.handleEvent(event("message.updated") {
            put("sessionID", JsonPrimitive("s2"))
            put("info", buildJsonObject {
                put("id", "m-external")
                put("role", "user")
                put("time", buildJsonObject {
                    put("created", 50000L)
                })
            })
        })

        assertTrue(slices.chat.value.messages.none { it.id == "m-external" })
        val s2 = slices.sessionList.value.sessions.first { it.id == "s2" }
        assertEquals(50000L, s2.time?.updated)
    }

    @Test
    fun `message updated with null info is a no-op on chat`() {
        setCurrentSession("s1")

        coordinator.handleEvent(event("message.updated") {
            put("sessionID", JsonPrimitive("s1"))
        })

        assertTrue(slices.chat.value.messages.isEmpty())
    }

    // ── §task7-coverage: session.diff valid write path ──────────────────────

    @Test
    fun `session diff with valid payload writes diffs keyed by session id`() {
        coordinator.handleEvent(event("session.diff") {
            put("sessionID", JsonPrimitive("s1"))
            put("diff", buildJsonArray {
                add(buildJsonObject {
                    put("file", "a.kt")
                    put("additions", 3)
                    put("deletions", 1)
                    put("status", "modified")
                })
            })
        })

        assertNotNull(slices.sessionList.value.sessionDiffs["s1"])
        assertEquals(1, slices.sessionList.value.sessionDiffs["s1"]!!.size)
    }

    // ── §task7-coverage: todo.updated valid write path ──────────────────────

    @Test
    fun `todo updated with valid payload writes todos keyed by session id`() {
        coordinator.handleEvent(event("todo.updated") {
            put("sessionID", JsonPrimitive("s1"))
            put("todos", buildJsonArray {
                add(buildJsonObject {
                    put("content", "task")
                    put("status", "pending")
                    put("priority", "high")
                })
            })
        })

        assertNotNull(slices.sessionList.value.sessionTodos["s1"])
        assertEquals(1, slices.sessionList.value.sessionTodos["s1"]!!.size)
    }

    // ── §task7-coverage: message.part.delta edge cases ──────────────────────

    @Test
    fun `message part delta for non-current session is dropped`() {
        setCurrentSession("s1")
        coordinator.handleEvent(event("message.part.delta") {
            put("sessionID", JsonPrimitive("s2"))
            put("messageID", JsonPrimitive("m1"))
            put("partID", JsonPrimitive("p1"))
            put("delta", JsonPrimitive("hello"))
        })
        assertTrue(slices.chat.value.streamingPartTexts.isEmpty())
    }

    @Test
    fun `message part delta with null messageID is dropped`() {
        setCurrentSession("s1")
        coordinator.handleEvent(event("message.part.delta") {
            put("sessionID", JsonPrimitive("s1"))
            put("partID", JsonPrimitive("p1"))
            put("delta", JsonPrimitive("hello"))
        })
        assertTrue(slices.chat.value.streamingPartTexts.isEmpty())
    }

    @Test
    fun `message part delta with null partID is dropped`() {
        setCurrentSession("s1")
        coordinator.handleEvent(event("message.part.delta") {
            put("sessionID", JsonPrimitive("s1"))
            put("messageID", JsonPrimitive("m1"))
            put("delta", JsonPrimitive("hello"))
        })
        assertTrue(slices.chat.value.streamingPartTexts.isEmpty())
    }

    @Test
    fun `message part delta for a user message is dropped`() {
        setCurrentSession("s1")
        seed { it.copy(messages = listOf(Message(id = "m-user", role = "user"))) }
        coordinator.handleEvent(event("message.part.delta") {
            put("sessionID", JsonPrimitive("s1"))
            put("messageID", JsonPrimitive("m-user"))
            put("partID", JsonPrimitive("p1"))
            put("delta", JsonPrimitive("hello"))
        })
        assertTrue(slices.chat.value.streamingPartTexts.isEmpty())
    }

    @Test
    fun `message part delta with null or empty delta string is a no-op`() {
        setCurrentSession("s1")
        coordinator.handleEvent(event("message.part.delta") {
            put("sessionID", JsonPrimitive("s1"))
            put("messageID", JsonPrimitive("m1"))
            put("partID", JsonPrimitive("p1"))
        })
        assertTrue(slices.chat.value.streamingPartTexts.isEmpty())
    }

    @Test
    fun `message part delta with non-streamable known type is dropped`() {
        setCurrentSession("s1")
        seed {
            it.copy(
                messages = listOf(Message(id = "m1", role = "assistant")),
                partsByMessage = mapOf("m1" to listOf(Part(id = "p1", messageId = "m1", sessionId = "s1", type = "tool"))),
            )
        }
        coordinator.handleEvent(event("message.part.delta") {
            put("sessionID", JsonPrimitive("s1"))
            put("messageID", JsonPrimitive("m1"))
            put("partID", JsonPrimitive("p1"))
            put("delta", JsonPrimitive("hello"))
        })
        assertNull(slices.chat.value.streamingPartTexts["p1"])
    }

    @Test
    fun `message part delta trailing delta coalesces into buffer when flush is pending`() {
        setCurrentSession("s1")
        seed { it.copy(messages = listOf(Message(id = "m1", role = "assistant"))) }

        // First delta: leading edge
        coordinator.handleEvent(event("message.part.delta") {
            put("sessionID", JsonPrimitive("s1"))
            put("messageID", JsonPrimitive("m1"))
            put("partID", JsonPrimitive("p1"))
            put("delta", JsonPrimitive("first"))
        })
        assertEquals("first", slices.chat.value.streamingPartTexts["p1"])

        // Second delta without advancing the clock: trailing coalesce
        coordinator.handleEvent(event("message.part.delta") {
            put("sessionID", JsonPrimitive("s1"))
            put("messageID", JsonPrimitive("m1"))
            put("partID", JsonPrimitive("p1"))
            put("delta", JsonPrimitive(" second"))
        })
        // Leading edge value stays; the trailing delta is buffered
        assertEquals("first", slices.chat.value.streamingPartTexts["p1"])
        assertEquals(" second", slices.chat.value.deltaBuffer["p1"])
    }

    // ── §task7-coverage: message.part.updated coalesce + status-flip paths ──

    @Test
    fun `message part updated trailing fullText coalesces into buffer when flush is pending`() {
        setCurrentSession("s1")
        seed { it.copy(messages = listOf(Message(id = "m1", role = "assistant"))) }

        // First fullText: leading edge
        coordinator.handleEvent(event("message.part.updated") {
            put("sessionID", JsonPrimitive("s1"))
            put("part", buildJsonObject {
                put("messageID", JsonPrimitive("m1"))
                put("id", JsonPrimitive("p1"))
                put("type", JsonPrimitive("text"))
                put("text", JsonPrimitive("first-full"))
            })
        })
        assertEquals("first-full", slices.chat.value.streamingPartTexts["p1"])

        // Second fullText without advancing: trailing coalesce → replaceFullTextBuffer
        coordinator.handleEvent(event("message.part.updated") {
            put("sessionID", JsonPrimitive("s1"))
            put("part", buildJsonObject {
                put("messageID", JsonPrimitive("m1"))
                put("id", JsonPrimitive("p1"))
                put("type", JsonPrimitive("text"))
                put("text", JsonPrimitive("second-full"))
            })
        })
        assertEquals("first-full", slices.chat.value.streamingPartTexts["p1"])
        assertEquals("second-full", slices.chat.value.fullTextBuffer["p1"])
    }

    @Test
    fun `message part updated trailing delta coalesces into buffer when flush is pending`() {
        setCurrentSession("s1")
        seed { it.copy(messages = listOf(Message(id = "m1", role = "assistant"))) }

        // First delta: leading edge
        coordinator.handleEvent(event("message.part.updated") {
            put("sessionID", JsonPrimitive("s1"))
            put("part", buildJsonObject {
                put("messageID", JsonPrimitive("m1"))
                put("id", JsonPrimitive("p1"))
                put("type", JsonPrimitive("text"))
            })
            put("delta", JsonPrimitive("leading"))
        })
        assertEquals("leading", slices.chat.value.streamingPartTexts["p1"])

        // Second delta without advancing: trailing coalesce → appendDeltaBuffer
        coordinator.handleEvent(event("message.part.updated") {
            put("sessionID", JsonPrimitive("s1"))
            put("part", buildJsonObject {
                put("messageID", JsonPrimitive("m1"))
                put("id", JsonPrimitive("p1"))
                put("type", JsonPrimitive("text"))
            })
            put("delta", JsonPrimitive(" trailing"))
        })
        assertEquals("leading", slices.chat.value.streamingPartTexts["p1"])
        assertEquals(" trailing", slices.chat.value.deltaBuffer["p1"])
    }

    @Test
    fun `message part updated with both text and delta null is a status-flip no-op`() {
        setCurrentSession("s1")
        seed { it.copy(messages = listOf(Message(id = "m1", role = "assistant"))) }

        coordinator.handleEvent(event("message.part.updated") {
            put("sessionID", JsonPrimitive("s1"))
            put("part", buildJsonObject {
                put("messageID", JsonPrimitive("m1"))
                put("id", JsonPrimitive("p1"))
                put("type", JsonPrimitive("text"))
            })
        })

        // No streaming text, no reload effect — pure status flip
        assertTrue(slices.chat.value.streamingPartTexts.isEmpty())
        assertTrue(collectedEffects.filterIsInstance<ControllerEffect.LoadMessages>().isEmpty())
    }

    @Test
    fun `message part updated for non-current session is dropped`() {
        setCurrentSession("s1")
        coordinator.handleEvent(event("message.part.updated") {
            put("sessionID", JsonPrimitive("s2"))
            put("part", buildJsonObject {
                put("messageID", JsonPrimitive("m1"))
                put("id", JsonPrimitive("p1"))
                put("type", JsonPrimitive("text"))
            })
            put("delta", JsonPrimitive("hello"))
        })
        assertTrue(slices.chat.value.streamingPartTexts.isEmpty())
    }

    @Test
    fun `message part delta with null sessionID is dropped`() {
        setCurrentSession("s1")
        coordinator.handleEvent(event("message.part.delta") {
            put("messageID", JsonPrimitive("m1"))
            put("partID", JsonPrimitive("p1"))
            put("delta", JsonPrimitive("hello"))
        })
        assertTrue(slices.chat.value.streamingPartTexts.isEmpty())
    }

    // ── §task7-coverage: remaining edge cases in message.created/updated ────

    @Test
    fun `message created with info but zero time-created skips timestamp bump`() {
        seed {
            it.copy(
                currentSessionId = "s1",
                sessions = listOf(Session(id = "s1", directory = "/tmp")),
            )
        }

        coordinator.handleEvent(event("message.created") {
            put("sessionID", JsonPrimitive("s1"))
            put("info", buildJsonObject {
                put("id", "m-new")
                put("role", "assistant")
            })
        })

        val session = slices.sessionList.value.sessions.first { it.id == "s1" }
        assertNull(session.time?.updated)
    }

    @Test
    fun `message updated with zero time-created does not bump session`() {
        setCurrentSession("s1")
        seed {
            it.copy(sessions = listOf(Session(id = "s1", directory = "/tmp")))
        }

        coordinator.handleEvent(event("message.updated") {
            put("sessionID", JsonPrimitive("s1"))
            put("info", buildJsonObject {
                put("id", "m1")
                put("role", "user")
            })
        })

        val session = slices.sessionList.value.sessions.first { it.id == "s1" }
        assertNull(session.time?.updated)
    }

    @Test
    fun `session updated archived when session is not in openSessionIds does not persist`() {
        seed {
            it.copy(
                sessions = listOf(Session(id = "keep", directory = "/tmp")),
                openSessionIds = listOf("keep"),
            )
        }

        coordinator.handleEvent(event("session.updated") {
            put("info", buildJsonObject {
                put("id", "other")
                put("directory", "/tmp")
                put("time", buildJsonObject {
                    put("archived", 99999L)
                })
            })
        })

        io.mockk.verify(exactly = 0) { settingsManager.openSessionIds = any() }
    }

    @Test
    fun `message part updated with already correct type skips placeholder re-injection`() {
        setCurrentSession("s1")
        seed {
            it.copy(
                messages = listOf(Message(id = "m1", role = "assistant")),
                partsByMessage = mapOf("m1" to listOf(
                    Part(id = "p1", messageId = "m1", sessionId = "s1", type = "text")
                )),
            )
        }

        coordinator.handleEvent(event("message.part.updated") {
            put("sessionID", JsonPrimitive("s1"))
            put("part", buildJsonObject {
                put("messageID", JsonPrimitive("m1"))
                put("id", JsonPrimitive("p1"))
                put("type", JsonPrimitive("text"))
            })
            put("delta", JsonPrimitive("more"))
        })

        val parts = slices.chat.value.partsByMessage["m1"]!!
        assertEquals(1, parts.size)
        assertEquals("text", parts[0].type)
    }

    @Test
    fun `message updated with empty message id does not mutate chat`() {
        setCurrentSession("s1")

        coordinator.handleEvent(event("message.updated") {
            put("sessionID", JsonPrimitive("s1"))
            put("info", buildJsonObject {
                put("id", "")
                put("role", "assistant")
            })
        })

        assertTrue(slices.chat.value.messages.isEmpty())
    }

    // ── CP1 (notify Phase-0): identity-checked entry point ───────────────────

    /**
     * CP1 spec test: old-identity collector frame emitted AFTER
     * [cn.vectory.ocdroid.service.identity.ConnectionIdentityStore.beginReconfigure]
     * is dropped BEFORE the SSC fold (no state mutation).
     */
    @Test
    fun `CP1 stale-identity SSE frame is dropped before SSC fold`() {
        // Bind the initial identity (epoch 0).
        val initialIdentity = identityStore.bind("test-fp", "/proj", "test-endpoint")
        setCurrentSession("s1")

        // A reconfigure invalidates the initial identity (epoch bumps to 1,
        // currentIdentity is nulled).
        identityStore.beginReconfigure()

        // A stale-identity frame arrives (captured under the OLD identity).
        val staleEvent = event("session.status") {
            put("sessionID", JsonPrimitive("s1"))
            put("status", buildJsonObject { put("type", JsonPrimitive("busy")) })
        }
        coordinator.handleEvent(
            cn.vectory.ocdroid.service.events.IdentifiedSseEvent(initialIdentity, staleEvent)
        )

        // The stale-identity frame MUST NOT mutate state — the badge is
        // absent because the fold never ran.
        assertNull(
            "stale-identity frame must not write sessionStatuses",
            slices.sessionList.value.sessionStatuses["s1"]
        )
        assertTrue(
            "stale-identity frame must not emit LoadMessages",
            collectedEffects.filterIsInstance<ControllerEffect.LoadMessages>().isEmpty()
        )
    }

    /**
     * CP1 spec test: [ControllerEffect.HostReconfigured] resets SSC's overlay
     * to the EXACT epoch carried on the effect.
     */
    @Test
    fun `CP1 HostReconfigured resets SSC overlay to the carried epoch`() {
        // Establish some overlay state under epoch 0.
        setCurrentSession("s1")
        coordinator.handleEvent(event("server.connected") {})  // cold-start: connectedOnce=true
        assertTrue("baseline connectedOnce=true", coordinator.sseSyncStateSnapshot().connectedOnce)

        // Reconfigure: bump the store epoch + emit HostReconfigured carrying it.
        val newEpoch = identityStore.beginReconfigure()
        effects.tryEmitEffect(ControllerEffect.HostReconfigured(epoch = newEpoch))

        val snap = coordinator.sseSyncStateSnapshot()
        assertEquals(
            "SSC overlay hostGeneration must match the effect's epoch",
            newEpoch,
            snap.hostGeneration,
        )
        assertFalse("connectedOnce reset by HostReconfigured", snap.connectedOnce)
        assertTrue("sessionsDirty cleared", snap.sessionsDirty.isEmpty())
        assertNull("lastDisconnectAt cleared", snap.lastDisconnectAt)
    }

    /**
     * CP1 spec test: the current-identity frame (matching the store's bound
     * identity + epoch) passes through the identity gate and folds normally.
     */
    @Test
    fun `CP1 current-identity SSE frame passes the gate and folds`() {
        val identity = identityStore.bind("test-fp", "/proj", "test-endpoint")
        setCurrentSession("s1")

        coordinator.handleEvent(
            cn.vectory.ocdroid.service.events.IdentifiedSseEvent(identity, event("session.status") {
                put("sessionID", JsonPrimitive("s1"))
                put("status", buildJsonObject { put("type", JsonPrimitive("busy")) })
            })
        )

        assertNotNull(slices.sessionList.value.sessionStatuses["s1"])
        assertTrue(slices.sessionList.value.sessionStatuses["s1"]!!.isBusy)
    }
}
