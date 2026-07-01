package cn.vectory.ocdroid

import cn.vectory.ocdroid.ui.AppState
import cn.vectory.ocdroid.ui.computeContextUsage
import cn.vectory.ocdroid.ui.currentSession
import cn.vectory.ocdroid.ui.currentSessionStatus
import cn.vectory.ocdroid.data.model.*
import cn.vectory.ocdroid.util.ThemeMode
import org.junit.Assert.*
import org.junit.Test

/**
 * §R-17 M5.1 (glmer 🟠#1): the AppState-derived getters
 * (`visibleMessages` / `contextUsage` / `currentSession` / `currentSessionStatus`
 * / `isCurrentSessionBusy` / `canLoadMoreSessions` / `visibleAgents`) were a
 * verbatim duplicate of the top-level functions in [AppStateDerived] and are
 * being deleted. These tests now exercise the top-level functions directly
 * (constructing their inputs from an AppState fixture) — semantically
 * equivalent, but they cover the code path production actually uses.
 *
 * The AppState data-class field defaults / setters (mirror fields retained as a
 * synced cache, per M5) are still covered by the `AppState default values` /
 * `filePathToShowInFiles` / `filePreviewOriginRoute` cases.
 */
class AppStateTest {

    @Test
    fun `AppState default values`() {
        val state = AppState()

        assertFalse(state.isConnected)
        assertFalse(state.isConnecting)
        assertNull(state.serverVersion)
        assertTrue(state.sessions.isEmpty())
        assertEquals(10, state.loadedSessionLimit)
        assertTrue(state.hasMoreSessions)
        assertFalse(state.isLoadingMoreSessions)
        assertNull(state.currentSessionId)
        assertTrue(state.sessionStatuses.isEmpty())
        assertTrue(state.messages.isEmpty())
        assertFalse(state.isLoadingMessages)
        assertTrue(state.agents.isEmpty())
        assertEquals("build", state.selectedAgentName)
        assertNull(state.providers)
        assertTrue(state.pendingPermissions.isEmpty())
        assertEquals("", state.inputText)
        assertNull(state.error)
        assertEquals(ThemeMode.SYSTEM, state.themeMode)
        assertNull(state.filePathToShowInFiles)
        assertNull(state.filePreviewOriginRoute)
        // canLoadMoreSessions is now an inline derivation (was an AppState getter).
        assertTrue(state.hasMoreSessions && !state.isLoadingMoreSessions)
    }

    @Test
    fun `filePathToShowInFiles can be set and read`() {
        val state = AppState(filePathToShowInFiles = "src/main.kt")
        assertEquals("src/main.kt", state.filePathToShowInFiles)
    }

    @Test
    fun `filePreviewOriginRoute can be set and read`() {
        val state = AppState(
            filePathToShowInFiles = "src/main.kt",
            filePreviewOriginRoute = "chat"
        )
        assertEquals("chat", state.filePreviewOriginRoute)
    }

    @Test
    fun `filePreviewOriginRoute defaults to null`() {
        val state = AppState(filePathToShowInFiles = "src/main.kt")
        assertNull(state.filePreviewOriginRoute)
    }

    @Test
    fun `currentSession returns correct session`() {
        val session1 = Session(id = "s1", directory = "/project1")
        val session2 = Session(id = "s2", directory = "/project2")

        val state = AppState(
            sessions = listOf(session1, session2),
            currentSessionId = "s2"
        )

        assertEquals(session2, currentSession(state.sessions, state.currentSessionId))
    }

    @Test
    fun `currentSession returns null when no session selected`() {
        val session1 = Session(id = "s1", directory = "/project1")

        val state = AppState(
            sessions = listOf(session1),
            currentSessionId = null
        )

        assertNull(currentSession(state.sessions, state.currentSessionId))
    }

    @Test
    fun `currentSessionStatus returns correct status`() {
        val status1 = SessionStatus(type = "idle")
        val status2 = SessionStatus(type = "busy")

        val state = AppState(
            sessionStatuses = mapOf("s1" to status1, "s2" to status2),
            currentSessionId = "s2"
        )

        assertEquals(status2, currentSessionStatus(state.sessionStatuses, state.currentSessionId))
    }

    @Test
    fun `isCurrentSessionBusy returns true when busy`() {
        val state = AppState(
            sessionStatuses = mapOf("s1" to SessionStatus(type = "busy")),
            currentSessionId = "s1"
        )

        assertTrue(currentSessionStatus(state.sessionStatuses, state.currentSessionId)?.isBusy == true)
    }

    @Test
    fun `isCurrentSessionBusy returns false when idle`() {
        val state = AppState(
            sessionStatuses = mapOf("s1" to SessionStatus(type = "idle")),
            currentSessionId = "s1"
        )

        assertFalse(currentSessionStatus(state.sessionStatuses, state.currentSessionId)?.isBusy == true)
    }

    @Test
    fun `isCurrentSessionBusy returns false when no status`() {
        val state = AppState(currentSessionId = "s1")

        assertFalse(currentSessionStatus(state.sessionStatuses, state.currentSessionId)?.isBusy == true)
    }

    @Test
    fun `visibleAgents filters correctly`() {
        val agents = listOf(
            AgentInfo(name = "Visible1", mode = "primary", hidden = false),
            AgentInfo(name = "Hidden", mode = "primary", hidden = true),
            AgentInfo(name = "SubAgent", mode = "subagent", hidden = false),
            AgentInfo(name = "Visible2", mode = "all", hidden = false)
        )

        val state = AppState(agents = agents)
        // visibleAgents is now an inline derivation (was an AppState getter);
        // ChatScreen uses settings.agents.filter { it.isVisible } directly.
        val visible = state.agents.filter { it.isVisible }

        assertEquals(2, visible.size)
        assertEquals("Visible1", visible[0].name)
        assertEquals("Visible2", visible[1].name)
    }

    private fun makeProviders(vararg models: Triple<String, String, String?>): ProvidersResponse {
        val providers = models.groupBy { it.first }.map { (providerId, group) ->
            ConfigProvider(
                id = providerId,
                models = group.associate { (_, modelId, name) ->
                    modelId to ProviderModel(id = modelId, name = name)
                }
            )
        }
        return ProvidersResponse(providers = providers)
    }

    private fun makeContextUsageState(
        totalTokens: Int?,
        providerId: String = "openai",
        modelId: String = "gpt-4",
        contextLimit: Int? = 128000
    ): AppState {
        val message = Message(
            id = "msg-1",
            role = "assistant",
            model = Message.ModelInfo(providerId = providerId, modelId = modelId),
            tokens = Message.TokenInfo(total = totalTokens)
        )
        val providerModel = ProviderModel(
            id = modelId,
            limit = if (contextLimit != null) ProviderModelLimit(context = contextLimit) else null
        )
        val providers = ProvidersResponse(
            providers = listOf(
                ConfigProvider(
                    id = providerId,
                    models = mapOf(modelId to providerModel)
                )
            )
        )
        return AppState(messages = listOf(message), providers = providers)
    }

    @Test
    fun `contextUsage returns null when no messages`() {
        val state = AppState()
        assertNull(computeContextUsage(state.messages, state.providers))
    }

    @Test
    fun `contextUsage returns null when no assistant messages`() {
        val userMessage = Message(id = "msg-1", role = "user")
        val state = AppState(messages = listOf(userMessage))
        assertNull(computeContextUsage(state.messages, state.providers))
    }

    @Test
    fun `contextUsage returns null when assistant has no tokens`() {
        val message = Message(id = "msg-1", role = "assistant", tokens = null)
        val state = AppState(messages = listOf(message))
        assertNull(computeContextUsage(state.messages, state.providers))
    }

    @Test
    fun `contextUsage returns null when total tokens is null`() {
        val message = Message(
            id = "msg-1",
            role = "assistant",
            tokens = Message.TokenInfo(total = null),
            model = Message.ModelInfo("openai", "gpt-4")
        )
        val state = AppState(messages = listOf(message))
        assertNull(computeContextUsage(state.messages, state.providers))
    }

    @Test
    fun `contextUsage returns null when no resolvedModel`() {
        val message = Message(
            id = "msg-1",
            role = "assistant",
            tokens = Message.TokenInfo(total = 50000)
        )
        val state = AppState(messages = listOf(message))
        assertNull(computeContextUsage(state.messages, state.providers))
    }

    @Test
    fun `contextUsage returns null when provider model not found`() {
        val message = Message(
                id = "msg-1",
                role = "assistant",
                model = Message.ModelInfo(providerId = "unknown", modelId = "missing"),
                tokens = Message.TokenInfo(total = 50000)
            )
        val providers = ProvidersResponse(
            providers = listOf(
                ConfigProvider(
                    id = "openai",
                    models = mapOf(
                        "gpt-4" to ProviderModel(
                            id = "gpt-4",
                            limit = ProviderModelLimit(context = 128000)
                        )
                    )
                )
            )
        )
        val state = AppState(messages = listOf(message), providers = providers)
        assertNull(computeContextUsage(state.messages, state.providers))
    }

    @Test
    fun `contextUsage returns null when context limit is zero`() {
        val state = makeContextUsageState(totalTokens = 50000, contextLimit = 0)
        assertNull(computeContextUsage(state.messages, state.providers))
    }

    @Test
    fun `contextUsage returns null when context limit is null`() {
        val state = makeContextUsageState(totalTokens = 50000, contextLimit = null)
        assertNull(computeContextUsage(state.messages, state.providers))
    }

    @Test
    fun `contextUsage calculates correct percentage`() {
        val state = makeContextUsageState(totalTokens = 64000, contextLimit = 128000)
        val usage = computeContextUsage(state.messages, state.providers)

        assertNotNull(usage)
        assertEquals(0.5f, usage!!.percentage, 0.001f)
        assertEquals(64000, usage.totalTokens)
        assertEquals(128000, usage.contextLimit)
    }

    @Test
    fun `contextUsage matches provider model map key when provider model id differs`() {
        val message = Message(
            id = "msg-1",
            role = "assistant",
            model = Message.ModelInfo(providerId = "ollama-cloud", modelId = "deepseek-v4-pro"),
            tokens = Message.TokenInfo(total = 64000)
        )
        val providers = ProvidersResponse(
            providers = listOf(
                ConfigProvider(
                    id = "ollama-cloud",
                    models = mapOf(
                        "deepseek-v4-pro" to ProviderModel(
                            id = "deepseek-v4-pro:latest",
                            limit = ProviderModelLimit(context = 128000)
                        )
                    )
                )
            )
        )
        val usage = computeContextUsage(listOf(message), providers)

        assertNotNull(usage)
        assertEquals(0.5f, usage!!.percentage, 0.001f)
    }

    @Test
    fun `contextUsage clamps percentage to 1f`() {
        val state = makeContextUsageState(totalTokens = 200000, contextLimit = 128000)
        val usage = computeContextUsage(state.messages, state.providers)

        assertNotNull(usage)
        assertEquals(1.0f, usage!!.percentage, 0.001f)
    }

    @Test
    fun `contextUsage uses last assistant message with tokens`() {
        val oldAssistant = Message(
            id = "msg-1",
            role = "assistant",
            model = Message.ModelInfo("openai", "gpt-4"),
            tokens = Message.TokenInfo(total = 10000)
        )
        val userMsg = Message(id = "msg-2", role = "user")
        val newAssistant = Message(
            id = "msg-3",
            role = "assistant",
            model = Message.ModelInfo("openai", "gpt-4"),
            tokens = Message.TokenInfo(total = 90000)
        )
        val providers = ProvidersResponse(
            providers = listOf(
                ConfigProvider(
                    id = "openai",
                    models = mapOf(
                        "gpt-4" to ProviderModel(
                            id = "gpt-4",
                            limit = ProviderModelLimit(context = 128000)
                        )
                    )
                )
            )
        )
        val state = AppState(
            messages = listOf(oldAssistant, userMsg, newAssistant),
            providers = providers
        )
        val usage = computeContextUsage(state.messages, state.providers)

        assertNotNull(usage)
        assertEquals(90000, usage!!.totalTokens)
    }

    @Test
    fun `contextUsage skips latest assistant when tokens are empty`() {
        val usableAssistant = Message(
            id = "msg-1",
            role = "assistant",
            model = Message.ModelInfo("openai", "gpt-4"),
            tokens = Message.TokenInfo(total = 90000)
        )
        val emptyTokenAssistant = Message(
            id = "msg-2",
            role = "assistant",
            model = Message.ModelInfo("openai", "gpt-4"),
            tokens = Message.TokenInfo(
                total = null,
                input = 0,
                output = 0,
                reasoning = 0,
                cache = Message.TokenInfo.CacheInfo(read = 0, write = 0)
            )
        )
        val providers = ProvidersResponse(
            providers = listOf(
                ConfigProvider(
                    id = "openai",
                    models = mapOf(
                        "gpt-4" to ProviderModel(
                            id = "gpt-4",
                            limit = ProviderModelLimit(context = 128000)
                        )
                    )
                )
            )
        )

        val usage = computeContextUsage(listOf(usableAssistant, emptyTokenAssistant), providers)

        assertNotNull(usage)
        assertEquals(90000, usage!!.totalTokens)
    }

    @Test
    fun `contextUsage near thresholds`() {
        val lowUsage = makeContextUsageState(totalTokens = 60000, contextLimit = 128000)
        assertTrue(computeContextUsage(lowUsage.messages, lowUsage.providers)!!.percentage < 0.7f)

        val midUsage = makeContextUsageState(totalTokens = 100000, contextLimit = 128000)
        val midPct = computeContextUsage(midUsage.messages, midUsage.providers)!!.percentage
        assertTrue(midPct >= 0.7f && midPct < 0.9f)

        val highUsage = makeContextUsageState(totalTokens = 120000, contextLimit = 128000)
        assertTrue(computeContextUsage(highUsage.messages, highUsage.providers)!!.percentage >= 0.9f)
    }
}
