package cn.vectory.ocdroid.ui.chat

import cn.vectory.ocdroid.data.model.AgentInfo
import cn.vectory.ocdroid.data.model.HostProfile
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.ProvidersResponse
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.data.model.TodoItem
import cn.vectory.ocdroid.ui.ConnectionPhase
import cn.vectory.ocdroid.ui.ContextUsage
import cn.vectory.ocdroid.ui.TunnelActivationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test


/**
 * R18 Phase 5++ coverage: the [ChatTopBarState] / [ChatTopBarActions] value
 * holders (cn.vectory.ocdroid.ui.chat.ChatTopBar.kt). Coverage gap before this
 * file: 0/30 methods, 0/30 branches, 0/196 instructions — the data classes
 * had never been constructed by a test, so every property accessor + the
 * data-class-generated equals/hashCode/copy/toString/componentX was uncovered.
 *
 * Construction alone (with the defaults exercised) flips the data-class
 * generated methods to covered (kover counts the synthetic accessors).
 */
class ChatTopBarStateTest {

    @Test
    fun `ChatTopBarState default constructor populates every field`() {
        val s = ChatTopBarState(
            sessions = emptyList(),
            currentSessionId = null,
            sessionStatuses = emptyMap(),
            hasMoreSessions = false,
            isLoadingMoreSessions = false,
            agents = emptyList(),
            selectedAgentName = "code",
            contextUsage = null,
        )

        // Verify defaults — these are the bulk of the "uncovered" branches
        // (each default value is a branch in the synthetic constructor).
        assertEquals(false, s.isRefreshingSessions)
        assertEquals(emptySet<String>(), s.expandedSessionIds)
        assertEquals(emptyList<TodoItem>(), s.sessionTodos)
        assertEquals("", s.hostName)
        assertEquals(false, s.isConnected)
        assertEquals(false, s.isConnecting)
        assertEquals(ConnectionPhase.Idle, s.connectionPhase)
        assertEquals(emptyList<HostProfile>(), s.hostProfiles)
        assertEquals(null, s.currentHostProfileId)
        assertEquals(TunnelActivationState.Idle, s.tunnelActivationState)
        assertEquals(false, s.showTunnelAuth)
        assertEquals(emptyList<Session>(), s.openSessions)
        assertEquals(emptySet<String>(), s.unreadSessions)
        assertEquals(null, s.draftWorkdir)
        assertEquals(null, s.parentSessionId)
        assertEquals(null, s.parentSessionTitle)
        assertEquals(0L, s.trafficSent)
        assertEquals(0L, s.trafficReceived)
        assertEquals(null, s.serverVersion)
        assertEquals(null, s.providers)
        assertEquals(emptySet<String>(), s.disabledModels)
        assertEquals(null, s.currentModel)
    }

    @Test
    fun `ChatTopBarState full constructor round-trips every field`() {
        val session = Session(id = "s1", directory = "/x", title = "Title")
        val status = SessionStatus(type = "busy")
        val agent = AgentInfo(name = "code")
        val model = Message.ModelInfo("p", "m")
        val providers = ProvidersResponse()
        val todo = TodoItem(content = "do thing", status = "pending", priority = "high", id = "t1")
        val hostProfile = HostProfile(id = "h1", serverUrl = "http://x", name = "H1")
        val usage = ContextUsage(
            percentage = 0.5f,
            totalTokens = 100,
            contextLimit = 200,
            providerId = "p",
            modelId = "m",
        )

        val s = ChatTopBarState(
            sessions = listOf(session),
            currentSessionId = "s1",
            sessionStatuses = mapOf("s1" to status),
            hasMoreSessions = true,
            isLoadingMoreSessions = true,
            isRefreshingSessions = true,
            expandedSessionIds = setOf("s1"),
            agents = listOf(agent),
            selectedAgentName = "code",
            contextUsage = usage,
            sessionTodos = listOf(todo),
            hostName = "host",
            isConnected = true,
            isConnecting = true,
            connectionPhase = ConnectionPhase.Connected,
            hostProfiles = listOf(hostProfile),
            currentHostProfileId = "h1",
            tunnelActivationState = TunnelActivationState.Loading,
            showTunnelAuth = true,
            openSessions = listOf(session),
            unreadSessions = setOf("s1"),
            draftWorkdir = "/draft",
            parentSessionId = "parent",
            parentSessionTitle = "Parent Title",
            trafficSent = 100L,
            trafficReceived = 200L,
            serverVersion = "1.2.3",
            providers = providers,
            disabledModels = setOf("p/m"),
            currentModel = model,
        )

        assertEquals(listOf(session), s.sessions)
        assertEquals("s1", s.currentSessionId)
        assertEquals(mapOf("s1" to status), s.sessionStatuses)
        assertEquals(true, s.hasMoreSessions)
        assertEquals(true, s.isLoadingMoreSessions)
        assertEquals(true, s.isRefreshingSessions)
        assertEquals(setOf("s1"), s.expandedSessionIds)
        assertEquals(listOf(agent), s.agents)
        assertEquals("code", s.selectedAgentName)
        assertEquals(usage, s.contextUsage)
        assertEquals(listOf(todo), s.sessionTodos)
        assertEquals("host", s.hostName)
        assertEquals(true, s.isConnected)
        assertEquals(true, s.isConnecting)
        assertEquals(ConnectionPhase.Connected, s.connectionPhase)
        assertEquals(listOf(hostProfile), s.hostProfiles)
        assertEquals("h1", s.currentHostProfileId)
        assertEquals(TunnelActivationState.Loading, s.tunnelActivationState)
        assertEquals(true, s.showTunnelAuth)
        assertEquals(listOf(session), s.openSessions)
        assertEquals(setOf("s1"), s.unreadSessions)
        assertEquals("/draft", s.draftWorkdir)
        assertEquals("parent", s.parentSessionId)
        assertEquals("Parent Title", s.parentSessionTitle)
        assertEquals(100L, s.trafficSent)
        assertEquals(200L, s.trafficReceived)
        assertEquals("1.2.3", s.serverVersion)
        assertEquals(providers, s.providers)
        assertEquals(setOf("p/m"), s.disabledModels)
        assertEquals(model, s.currentModel)
    }

    @Test
    fun `ChatTopBarState data-class equals hashCode and copy`() {
        val s1 = ChatTopBarState(
            sessions = emptyList(),
            currentSessionId = "x",
            sessionStatuses = emptyMap(),
            hasMoreSessions = false,
            isLoadingMoreSessions = false,
            agents = emptyList(),
            selectedAgentName = "code",
            contextUsage = null,
        )
        val s2 = s1.copy()
        assertEquals(s1, s2)
        assertEquals(s1.hashCode(), s2.hashCode())
        assertEquals(true, s1.toString().contains("ChatTopBarState"))

        val s3 = s1.copy(currentSessionId = "y")
        assertEquals("y", s3.currentSessionId)
        assertEquals("x", s1.currentSessionId)
    }

    @Test
    fun `ChatTopBarActions default callbacks are no-ops`() {
        // The defaults for the optional callbacks are no-op lambdas — invoking
        // them MUST NOT throw. Required callbacks (onSelectSession /
        // onCloseSession / onSelectAgent) are not defaulted and not invoked
        // here.
        val a = ChatTopBarActions(
            onSelectSession = {},
            onCloseSession = {},
            onSelectAgent = {},
        )

        a.onNavigateToSettings()
        a.onSelectHost("h1")
        a.onActivateTunnel()
        a.onRefreshMessages()
        a.onRefreshTrafficStats()
        a.onNavigateToSessions()
        a.onSwitchModel("p", "m")
    }

    @Test
    fun `ChatTopBarActions callbacks invoke the supplied lambdas`() {
        var selectSessionCalls = mutableListOf<String>()
        var closeSessionCalls = mutableListOf<String>()
        var selectAgentCalls = mutableListOf<String?>()
        var navSettings = 0
        var selectHost = ""
        var activateTunnel = 0
        var refreshMessages = 0
        var refreshTraffic = 0
        var navSessions = 0
        var switchModel = ""

        val a = ChatTopBarActions(
            onSelectSession = { selectSessionCalls += it },
            onCloseSession = { closeSessionCalls += it },
            onSelectAgent = { selectAgentCalls += it },
            onNavigateToSettings = { navSettings += 1 },
            onSelectHost = { selectHost = it },
            onActivateTunnel = { activateTunnel += 1 },
            onRefreshMessages = { refreshMessages += 1 },
            onRefreshTrafficStats = { refreshTraffic += 1 },
            onNavigateToSessions = { navSessions += 1 },
            onSwitchModel = { p, m -> switchModel = "$p/$m" },
        )

        a.onSelectSession("s1")
        a.onCloseSession("s2")
        a.onSelectAgent("code")
        a.onNavigateToSettings()
        a.onSelectHost("h1")
        a.onActivateTunnel()
        a.onRefreshMessages()
        a.onRefreshTrafficStats()
        a.onNavigateToSessions()
        a.onSwitchModel("p", "m")

        assertEquals(listOf("s1"), selectSessionCalls)
        assertEquals(listOf("s2"), closeSessionCalls)
        assertEquals(listOf("code"), selectAgentCalls)
        assertEquals(1, navSettings)
        assertEquals("h1", selectHost)
        assertEquals(1, activateTunnel)
        assertEquals(1, refreshMessages)
        assertEquals(1, refreshTraffic)
        assertEquals(1, navSessions)
        assertEquals("p/m", switchModel)
    }

    @Test
    fun `ChatTopBarActions data-class equals and copy`() {
        val a1 = ChatTopBarActions(
            onSelectSession = {},
            onCloseSession = {},
            onSelectAgent = {},
        )
        // Same required callbacks (different lambda instances, but
        // ChatTopBarActions is a data class so equals is structural — the
        // lambda fields are compared by reference, so a1 != a1.copy() with
        // different lambdas. Verify copy() preserves identity for required
        // callbacks when no override is given).
        val a2 = a1.copy()
        // a1 and a2 share the same lambda references (copy() with no overrides).
        assertEquals(a1, a2)
        assertNotNull(a1.toString())
    }
}
