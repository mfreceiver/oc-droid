package cn.vectory.ocdroid.ui.controller

import android.util.Log
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SSEEvent
import cn.vectory.ocdroid.data.model.SSEPayload
import cn.vectory.ocdroid.service.identity.ConnectionIdentityStore
import cn.vectory.ocdroid.service.status.SessionBusyStatus
import cn.vectory.ocdroid.service.status.SessionStatusKey
import cn.vectory.ocdroid.service.status.StatusAggregatorInput
import cn.vectory.ocdroid.ui.SessionListState
import cn.vectory.ocdroid.ui.SharedEffectBus
import cn.vectory.ocdroid.ui.SliceFlows
import cn.vectory.ocdroid.util.SettingsManager
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * CP4 (notify Phase-0) — focused unit coverage for the `session.status` SSE
 * branch's feed into the authoritative [StatusAggregatorInput].
 *
 * Verifies that on every `session.status` event:
 *  - the sessionId is resolved to its workdir via `SessionTree.allSessionsById`
 *    (sessions + directorySessions + childSessions);
 *  - the composite [SessionStatusKey] is built with the current host's
 *    `serverGroupFp` (the provider) and that workdir;
 *  - the SSE status is mapped to [SessionBusyStatus] via
 *    `SessionBusyStatusMapping.toSessionBusyStatus`;
 *  - `applySseStatus` is called with the resolved key + the clock's arrival time;
 *  - unknown sessionIds (not in the merged map) are skipped silently;
 *  - the existing fold (badge / unread / reload) is unchanged.
 *
 * The aggregator input is a fake that records every `applySseStatus` call.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionSyncCoordinatorStatusFeedTest {

    private val serverGroupFp = "test-fp"
    private lateinit var slices: SliceFlows
    private lateinit var effects: SharedEffectBus
    private lateinit var scope: TestScope
    private lateinit var identityStore: ConnectionIdentityStore
    private lateinit var aggregatorInput: RecordingStatusAggregatorInput
    private lateinit var coordinator: SessionSyncCoordinator
    private var clockNow: Long = 1_000L

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        io.mockk.every { Log.w(any<String>(), any<String>()) } returns 0
        io.mockk.every { Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0
        val store = cn.vectory.ocdroid.ui.SharedStateStore()
        slices = store.slices
        effects = SharedEffectBus()
        scope = TestScope(UnconfinedTestDispatcher())
        identityStore = ConnectionIdentityStore()
        aggregatorInput = RecordingStatusAggregatorInput()
        coordinator = SessionSyncCoordinator(
            scope = scope,
            slices = slices,
            settingsManager = mockk(relaxed = true),
            effects = effects,
            currentServerGroupFp = { serverGroupFp },
            cacheRepository = io.mockk.mockk(relaxed = true),
            identityStore = identityStore,
            statusAggregatorInput = aggregatorInput,
            clock = { clockNow },
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun event(type: String, block: JsonObjectBuilder.() -> Unit): SSEEvent =
        SSEEvent(payload = SSEPayload(type = type, properties = buildJsonObject(block)))

    private fun seedSessions(sessions: List<Session>) {
        slices.mutateSessionList {
            SessionListState(
                sessions = sessions,
                sessionStatuses = emptyMap(),
                expandedSessionIds = emptySet(),
                loadedSessionLimit = 0,
                hasMoreSessions = false,
                isLoadingMoreSessions = false,
                isRefreshingSessions = false,
                pendingPermissions = emptyList(),
                pendingQuestions = emptyList(),
                childSessions = emptyMap(),
                directorySessions = emptyMap(),
                openSessionIds = emptyList(),
                sessionTodos = emptyMap(),
            )
        }
    }

    @Test
    fun `session status busy feeds aggregator with resolved workdir and mapped status`() {
        seedSessions(listOf(Session(id = "s1", directory = "/work-a")))

        coordinator.handleEvent(event("session.status") {
            put("sessionID", JsonPrimitive("s1"))
            put("status", buildJsonObject { put("type", JsonPrimitive("busy")) })
        })

        assertEquals(1, aggregatorInput.applyCalls.size)
        val call = aggregatorInput.applyCalls.single()
        assertEquals(SessionStatusKey(serverGroupFp, "/work-a", "s1"), call.key)
        assertEquals(SessionBusyStatus.Busy, call.status)
        assertEquals(1_000L, call.sourceTimeMs)
    }

    @Test
    fun `session status idle maps to Idle status`() {
        seedSessions(listOf(Session(id = "s1", directory = "/work-a")))

        coordinator.handleEvent(event("session.status") {
            put("sessionID", JsonPrimitive("s1"))
            put("status", buildJsonObject { put("type", JsonPrimitive("idle")) })
        })

        val call = aggregatorInput.applyCalls.single()
        assertEquals(SessionBusyStatus.Idle, call.status)
    }

    @Test
    fun `session status retry maps to Retry status`() {
        seedSessions(listOf(Session(id = "s1", directory = "/work-a")))

        coordinator.handleEvent(event("session.status") {
            put("sessionID", JsonPrimitive("s1"))
            put("status", buildJsonObject { put("type", JsonPrimitive("retry")) })
        })

        val call = aggregatorInput.applyCalls.single()
        assertEquals(SessionBusyStatus.Retry, call.status)
    }

    @Test
    fun `session status for a directory-resident session resolves via directorySessions`() {
        // The session is NOT in the flat sessions list — it lives in directorySessions.
        // SessionTree.allSessionsById merges all three sources, so the aggregator feed
        // must still resolve it.
        slices.mutateSessionList {
            SessionListState(
                sessions = emptyList(),
                sessionStatuses = emptyMap(),
                expandedSessionIds = emptySet(),
                loadedSessionLimit = 0,
                hasMoreSessions = false,
                isLoadingMoreSessions = false,
                isRefreshingSessions = false,
                pendingPermissions = emptyList(),
                pendingQuestions = emptyList(),
                childSessions = emptyMap(),
                directorySessions = mapOf(
                    "/work-x" to listOf(Session(id = "dir-1", directory = "/work-x")),
                ),
                openSessionIds = emptyList(),
                sessionTodos = emptyMap(),
            )
        }

        coordinator.handleEvent(event("session.status") {
            put("sessionID", JsonPrimitive("dir-1"))
            put("status", buildJsonObject { put("type", JsonPrimitive("busy")) })
        })

        val call = aggregatorInput.applyCalls.single()
        assertEquals(SessionStatusKey(serverGroupFp, "/work-x", "dir-1"), call.key)
    }

    @Test
    fun `session status for an unknown sessionId is skipped silently (no applySseStatus)`() {
        seedSessions(listOf(Session(id = "known", directory = "/work")))

        coordinator.handleEvent(event("session.status") {
            put("sessionID", JsonPrimitive("ghost"))
            put("status", buildJsonObject { put("type", JsonPrimitive("busy")) })
        })

        assertTrue(
            "unknown sessionId must NOT reach the aggregator (no composite key without workdir)",
            aggregatorInput.applyCalls.isEmpty(),
        )
    }

    @Test
    fun `session status clock advances on successive events`() {
        seedSessions(listOf(Session(id = "s1", directory = "/work")))
        clockNow = 5_000L

        coordinator.handleEvent(event("session.status") {
            put("sessionID", JsonPrimitive("s1"))
            put("status", buildJsonObject { put("type", JsonPrimitive("busy")) })
        })
        clockNow = 6_000L
        coordinator.handleEvent(event("session.status") {
            put("sessionID", JsonPrimitive("s1"))
            put("status", buildJsonObject { put("type", JsonPrimitive("idle")) })
        })

        assertEquals(listOf(5_000L, 6_000L), aggregatorInput.applyCalls.map { it.sourceTimeMs })
    }

    @Test
    fun `existing badge fold is unchanged when aggregator input is present`() {
        // Belt-and-suspenders: feeding the aggregator must NOT regress the existing
        // sessionStatuses slice mutation (badge / reload trigger).
        seedSessions(listOf(Session(id = "s1", directory = "/work-a")))

        coordinator.handleEvent(event("session.status") {
            put("sessionID", JsonPrimitive("s1"))
            put("status", buildJsonObject { put("type", JsonPrimitive("busy")) })
        })

        assertTrue(slices.sessionList.value.sessionStatuses["s1"]?.isBusy == true)
    }

    @Test
    fun `no statusAggregatorInput wired - session status branch skips feed without breaking`() {
        // Legacy / test construction with statusAggregatorInput = null — the session.status
        // branch must simply skip the aggregator feed (no NPE, badge fold unchanged).
        val legacyCoordinator = SessionSyncCoordinator(
            scope = scope,
            slices = slices,
            settingsManager = mockk(relaxed = true),
            effects = effects,
            currentServerGroupFp = { serverGroupFp },
            cacheRepository = io.mockk.mockk(relaxed = true),
            identityStore = identityStore,
            statusAggregatorInput = null,
            clock = { clockNow },
        )
        seedSessions(listOf(Session(id = "s1", directory = "/work-a")))

        legacyCoordinator.handleEvent(event("session.status") {
            put("sessionID", JsonPrimitive("s1"))
            put("status", buildJsonObject { put("type", JsonPrimitive("busy")) })
        })

        assertTrue(slices.sessionList.value.sessionStatuses["s1"]?.isBusy == true)
        assertNull(aggregatorInput.lastApply())
    }

    private data class ApplyCall(
        val key: SessionStatusKey,
        val status: SessionBusyStatus,
        val sourceTimeMs: Long,
    )

    private class RecordingStatusAggregatorInput : StatusAggregatorInput {
        val applyCalls = mutableListOf<ApplyCall>()

        fun lastApply(): ApplyCall? = applyCalls.lastOrNull()

        override suspend fun refresh(
            identity: cn.vectory.ocdroid.service.identity.ConnectionIdentity,
            snapshot: cn.vectory.ocdroid.service.status.StatusSnapshot,
        ) {
            // Unused by these tests (they exercise the SSE feed path only).
        }

        override fun applySseStatus(key: SessionStatusKey, status: SessionBusyStatus, sourceTimeMs: Long) {
            applyCalls.add(ApplyCall(key, status, sourceTimeMs))
        }

        override fun markRequestFailed(
            identity: cn.vectory.ocdroid.service.identity.ConnectionIdentity,
            snapshot: cn.vectory.ocdroid.service.status.StatusSnapshot,
            sourceTimeMs: Long,
        ) {
            // Unused by these tests.
        }
    }
}
