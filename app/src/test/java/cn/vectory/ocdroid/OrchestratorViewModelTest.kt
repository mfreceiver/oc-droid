package cn.vectory.ocdroid

import android.util.Log
import cn.vectory.ocdroid.data.model.BasicAuthConfig
import cn.vectory.ocdroid.data.model.HealthResponse
import cn.vectory.ocdroid.data.model.HostProfile
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.MessageWithParts
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.model.PermissionRequest
import cn.vectory.ocdroid.data.model.PermissionResponse
import cn.vectory.ocdroid.data.model.QuestionRequest
import cn.vectory.ocdroid.data.model.ProvidersResponse
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.data.model.SSEEvent
import cn.vectory.ocdroid.data.model.SSEPayload
import cn.vectory.ocdroid.data.repository.MessagesPage
import cn.vectory.ocdroid.ui.AppCore
import cn.vectory.ocdroid.ui.ChatViewModel
import cn.vectory.ocdroid.ui.ComposerViewModel
import cn.vectory.ocdroid.ui.ConnectionViewModel
import cn.vectory.ocdroid.ui.HostViewModel
import cn.vectory.ocdroid.ui.OrchestratorViewModel
import cn.vectory.ocdroid.ui.SessionViewModel
import cn.vectory.ocdroid.ui.SharedEffectBus
import cn.vectory.ocdroid.ui.SharedStateStore
import cn.vectory.ocdroid.ui.TunnelActivationState
import cn.vectory.ocdroid.ui.UiEvent
import cn.vectory.ocdroid.ui.currentSession
import cn.vectory.ocdroid.ui.loadSessionsForEffect
import cn.vectory.ocdroid.ui.session.buildSessionTree
import cn.vectory.ocdroid.ui.visibleMessages
import cn.vectory.ocdroid.util.ThemeMode
import app.cash.turbine.test as turbineTest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §R-17 batch3e: domain tests split out of the former [MainViewModelTest].
 *
 * Each test constructs an [AppCore] (via [MainViewModelTestBase.createCore])
 * and wraps it in ALL 6 domain VMs. Since the VMs share the same AppCore
 * singleton, slice reads return the same flows regardless of which VM is
 * asked. Tests call VM-owned methods via the matching VM variable
 * (`chatVM.sendMessage()`, `sessionVM.selectSession(...)`, etc.) — no legacy
 * `AppCore` shim extensions remain.
 *
 * §R18 Phase 4 (P2-3): state setup writes slices directly through the
 * AppCore `writeXxx { it.copy(...) }` helpers (former `updateState {}`
 * AppState shim removed). AppCore internals that have no VM equivalent
 * (`handleSSEEvent`, `store`, `peekSessionWindow`) are reached through
 * `core` directly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OrchestratorViewModelTest : MainViewModelTestBase() {

    @Test
    fun `session updated SSE upserts session without server refresh`() = runTest {
        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = OrchestratorViewModel(core)  // primary VM under test
        core.writeChat { it.copy(currentSessionId = "session-1") }
        core.writeSessionList {
            it.copy(
                sessions = listOf(cn.vectory.ocdroid.data.model.Session(id = "session-1", directory = "/tmp/project", title = "Old"))
            )
        }

        handleSse(core,
            SSEEvent(
                payload = SSEPayload(
                    type = "session.updated",
                    properties = buildJsonObject {
                        put(
                            "session",
                            buildJsonObject {
                                put("id", JsonPrimitive("session-1"))
                                put("directory", JsonPrimitive("/tmp/project"))
                                put("title", JsonPrimitive("SSE Only"))
                            }
                        )
                    }
                )
            )
        )
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.getSessions(any()) }
        assertEquals("SSE Only", sessionVM.sessionListFlow.value.sessions.single().title)
    }

    @Test
    fun `session updated SSE applies fresh payload title`() = runTest {
        // The server's session.updated event carries the generated title with a fresh
        // timestamp. The handler upserts the payload directly into state.sessions without
        // triggering a full server refresh, so the freshly received title must be visible
        // immediately (not clobbered by any stale concurrent snapshot).
        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = OrchestratorViewModel(core)  // primary VM under test
        core.writeChat { it.copy(currentSessionId = "session-1") }
        core.writeSessionList {
            it.copy(
                sessions = listOf(
                    cn.vectory.ocdroid.data.model.Session(
                        id = "session-1",
                        directory = "/tmp/project",
                        title = "New session - 1700000000",
                        time = cn.vectory.ocdroid.data.model.Session.TimeInfo(updated = 1_000)
                    )
                )
            )
        }

        handleSse(core,
            SSEEvent(
                payload = SSEPayload(
                    type = "session.updated",
                    properties = buildJsonObject {
                        put(
                            "info",
                            buildJsonObject {
                                put("id", JsonPrimitive("session-1"))
                                put("directory", JsonPrimitive("/tmp/project"))
                                put("title", JsonPrimitive("Pythagorean theorem: history, proof, engineering"))
                                put(
                                    "time",
                                    buildJsonObject { put("updated", JsonPrimitive(2_000)) }
                                )
                            }
                        )
                    }
                )
            )
        )
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.getSessions(any()) }
        assertEquals(
            "Pythagorean theorem: history, proof, engineering",
            sessionVM.sessionListFlow.value.sessions.single { it.id == "session-1" }.title
        )
    }

    @Test
    fun `handleSSEEvent appends streaming reasoning delta for current session`() = runTest {
        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = OrchestratorViewModel(core)  // primary VM under test
        core.writeChat { it.copy(currentSessionId = "session-1") }

        handleSse(core,
            SSEEvent(
                payload = SSEPayload(
                    type = "message.part.updated",
                    properties = buildJsonObject {
                        put("sessionID", JsonPrimitive("session-1"))
                        put(
                            "part",
                            buildJsonObject {
                                put("messageID", JsonPrimitive("message-1"))
                                put("id", JsonPrimitive("part-1"))
                                put("type", JsonPrimitive("reasoning"))
                            }
                        )
                        put("delta", JsonPrimitive("thinking"))
                    }
                )
            )
        )

        assertEquals("thinking", chatVM.chatFlow.value.streamingPartTexts["part-1"])
        assertEquals("part-1", chatVM.chatFlow.value.streamingReasoningPart?.id)
    }

    @Test
    fun `handleSSEEvent message part delta accumulates into streamingPartTexts`() = runTest {
        // message.part.delta is a distinct web event with top-level ids and a
        // field-level delta. S2/S4 accumulate into streamingPartTexts keyed by
        // bare partId (the UI contract after the split-store rekey). It must NOT
        // reload and must ignore non-current sessions.
        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = OrchestratorViewModel(core)  // primary VM under test
        core.writeChat { it.copy(currentSessionId = "session-1") }

        fun delta(d: String) = handleSse(core,
            SSEEvent(
                payload = SSEPayload(
                    type = "message.part.delta",
                    properties = buildJsonObject {
                        put("sessionID", JsonPrimitive("session-1"))
                        put("messageID", JsonPrimitive("message-1"))
                        put("partID", JsonPrimitive("part-1"))
                        put("field", JsonPrimitive("text"))
                        put("delta", JsonPrimitive(d))
                    }
                )
            )
        )

        delta("Hello")
        // §M5 leading edge: the first delta writes immediately (zero-latency
        // first token). The trailing-coalesce window is now open.
        assertEquals("Hello", chatVM.chatFlow.value.streamingPartTexts["part-1"])

        delta(", world")
        // §M5 trailing coalesce: subsequent deltas buffer within the 100ms
        // window — they do NOT each trigger a state write.
        assertEquals("Hello", chatVM.chatFlow.value.streamingPartTexts["part-1"])

        // Advance the virtual clock past DELTA_COALESCE_MS so the batched flush
        // appends the buffered delta in one state write.
        advanceUntilIdle()

        assertEquals("Hello, world", chatVM.chatFlow.value.streamingPartTexts["part-1"])
        // No reload issued.
        coVerify(exactly = 0) { repository.getMessagesPaged(any(), any(), any()) }
    }

    @Test
    fun `handleSSEEvent message part delta is ignored for other sessions`() = runTest {
        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = OrchestratorViewModel(core)  // primary VM under test
        core.writeChat { it.copy(currentSessionId = "session-1") }

        handleSse(core,
            SSEEvent(
                payload = SSEPayload(
                    type = "message.part.delta",
                    properties = buildJsonObject {
                        put("sessionID", JsonPrimitive("session-other"))
                        put("messageID", JsonPrimitive("message-1"))
                        put("partID", JsonPrimitive("part-1"))
                        put("delta", JsonPrimitive("ignored"))
                    }
                )
            )
        )

        assertTrue(chatVM.chatFlow.value.streamingPartTexts.isEmpty())
    }

    @Test
    fun `handleSSEEvent session created prepends parsed session`() = runTest {
        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = OrchestratorViewModel(core)  // primary VM under test
        core.writeSessionList {
            it.copy(sessions = listOf(cn.vectory.ocdroid.data.model.Session(id = "session-1", directory = "/tmp/old")))
        }

        handleSse(core,
            SSEEvent(
                payload = SSEPayload(
                    type = "session.created",
                    properties = buildJsonObject {
                        put(
                            "session",
                            buildJsonObject {
                                put("id", JsonPrimitive("session-2"))
                                put("directory", JsonPrimitive("/tmp/project"))
                                put("title", JsonPrimitive("New Session"))
                            }
                        )
                    }
                )
            )
        )

        assertEquals(listOf("session-2", "session-1"), sessionVM.sessionListFlow.value.sessions.map { it.id })
    }

    @Test
    fun `handleSSEEvent session updated replaces existing session title`() = runTest {
        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = OrchestratorViewModel(core)  // primary VM under test
        core.writeSessionList {
            it.copy(sessions = listOf(
                cn.vectory.ocdroid.data.model.Session(id = "session-1", directory = "/tmp/project", title = null)
            ))
        }

        handleSse(core,
            SSEEvent(
                payload = SSEPayload(
                    type = "session.updated",
                    properties = buildJsonObject {
                        put(
                            "info",
                            buildJsonObject {
                                put("id", JsonPrimitive("session-1"))
                                put("directory", JsonPrimitive("/tmp/project"))
                                put("title", JsonPrimitive("Refactor auth module"))
                            }
                        )
                    }
                )
            )
        )

        val sessions = sessionVM.sessionListFlow.value.sessions
        assertEquals(1, sessions.size)
        assertEquals("session-1", sessions[0].id)
        assertEquals("Refactor auth module", sessions[0].title)
    }

    @Test
    fun `handleSSEEvent session updated inserts unknown session`() = runTest {
        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = OrchestratorViewModel(core)  // primary VM under test
        core.writeSessionList {
            it.copy(sessions = listOf(
                cn.vectory.ocdroid.data.model.Session(id = "session-1", directory = "/tmp/old")
            ))
        }

        handleSse(core,
            SSEEvent(
                payload = SSEPayload(
                    type = "session.updated",
                    properties = buildJsonObject {
                        put(
                            "session",
                            buildJsonObject {
                                put("id", JsonPrimitive("session-new"))
                                put("directory", JsonPrimitive("/tmp/new"))
                                put("title", JsonPrimitive("New Feature"))
                            }
                        )
                    }
                )
            )
        )

        val sessions = sessionVM.sessionListFlow.value.sessions
        assertEquals(2, sessions.size)
        assertEquals("session-new", sessions[0].id)
        assertEquals("New Feature", sessions[0].title)
        assertEquals("session-1", sessions[1].id)
    }

    @Test
    fun `handleSSEEvent part-created preserves streaming overlay and refreshes messages`() = runTest {
        val messages = listOf(MessageWithParts(info = Message(id = "a2", role = "assistant")))
        coEvery { repository.getMessagesPaged("session-1", any(), any()) } returns Result.success(MessagesPage(messages, null))
        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = OrchestratorViewModel(core)  // primary VM under test
        val initialStreaming = mapOf("part-1" to "partial")
        val initialReasoning = Part(id = "part-1", messageId = "message-1", sessionId = "session-1", type = "reasoning")
        core.writeChat {
            it.copy(
                currentSessionId = "session-1",
                streamingPartTexts = initialStreaming,
                streamingReasoningPart = initialReasoning
            )
        }

        handleSse(core,
            SSEEvent(
                payload = SSEPayload(
                    type = "message.part.updated",
                    properties = buildJsonObject {
                        put("sessionID", JsonPrimitive("session-1"))
                        put("part", buildJsonObject { put("type", JsonPrimitive("reasoning")) })
                    }
                )
            )
        )
        advanceTimeBy(1000)
        advanceUntilIdle()

        // 闪屏修复：part.created（part 对象只有 type 无 messageID/id）不再清空
        // overlay —— 同回合内每个新 part（reasoning→text→tool…）都会发此信号，
        // 清空会导致所有流式气泡反复坍缩/充填闪烁。reload(resetLimit=false) 保留
        // streamingPartTexts/streamingReasoningPart（§append-safe）。
        assertEquals(initialStreaming, chatVM.chatFlow.value.streamingPartTexts)
        assertEquals(initialReasoning, chatVM.chatFlow.value.streamingReasoningPart)
        assertEquals(messages.map { it.info }, chatVM.chatFlow.value.messages)
    }

    @Test
    fun `handleSSEEvent ignores message updates when no current session is selected`() = runTest {
        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = OrchestratorViewModel(core)  // primary VM under test

        handleSse(core,
            SSEEvent(
                payload = SSEPayload(
                    type = "message.part.updated",
                    properties = buildJsonObject {
                        put("part", buildJsonObject { put("type", JsonPrimitive("reasoning")) })
                        put("delta", JsonPrimitive("ignored"))
                    }
                )
            )
        )
        advanceUntilIdle()

        assertTrue(chatVM.chatFlow.value.streamingPartTexts.isEmpty())
        assertNull(chatVM.chatFlow.value.streamingReasoningPart)
    }

    @Test
    fun `handleSSEEvent idle status finalizes streaming overlay via reload when overlay non-empty`() = runTest {
        // §append-safe finalization (gpter MAJOR): the overlay-clear in
        // launchLoadMessages is gated on !busy, so a reload that ran while busy
        // preserves the overlay. When the CURRENT session then settles to idle
        // with a still-live overlay, session.status idle triggers a resetLimit
        // reload that reconciles against the now-persisted authoritative window
        // and clears the overlay (status is now idle, so the gated clear runs).
        val messages = listOf(MessageWithParts(info = Message(id = "a1", role = "assistant")))
        coEvery { repository.getMessagesPaged("session-1", any(), any()) } returns Result.success(MessagesPage(messages, null))
        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = OrchestratorViewModel(core)  // primary VM under test
        val streaming = mapOf("part-1" to "partial")
        val reasoning = Part(id = "part-1", messageId = "message-1", sessionId = "session-1", type = "reasoning")
        core.writeChat {
            it.copy(
                currentSessionId = "session-1",
                streamingPartTexts = streaming,
                streamingReasoningPart = reasoning
            )
        }

        handleSse(core,
            SSEEvent(
                payload = SSEPayload(
                    type = "session.status",
                    properties = buildJsonObject {
                        put("sessionID", JsonPrimitive("session-1"))
                        put(
                            "status",
                            buildJsonObject {
                                put("type", JsonPrimitive("idle"))
                            }
                        )
                    }
                )
            )
        )
        advanceTimeBy(1000)
        advanceUntilIdle()

        // Status badge updated to idle...
        val status = sessionVM.sessionListFlow.value.sessionStatuses["session-1"]
        assertNotNull(status)
        assertFalse(status!!.isBusy)
        // ...overlay cleared by the finalization reload...
        assertTrue(chatVM.chatFlow.value.streamingPartTexts.isEmpty())
        assertNull(chatVM.chatFlow.value.streamingReasoningPart)
        // ...and a resetLimit reload was issued.
        coVerify(atLeast = 1) { repository.getMessagesPaged("session-1", any(), any()) }
    }

    @Test
    fun `handleSSEEvent idle status skips reload when streaming overlay already empty`() = runTest {
        // §append-safe finalization: the idle reload is gated on a non-empty
        // overlay to avoid redundant reloads. With no live overlay, idle only
        // updates the status badge (SSE-trust model preserved).
        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = OrchestratorViewModel(core)  // primary VM under test
        core.writeChat {
            it.copy(
                currentSessionId = "session-1",
                streamingPartTexts = emptyMap(),
                streamingReasoningPart = null
            )
        }

        handleSse(core,
            SSEEvent(
                payload = SSEPayload(
                    type = "session.status",
                    properties = buildJsonObject {
                        put("sessionID", JsonPrimitive("session-1"))
                        put(
                            "status",
                            buildJsonObject {
                                put("type", JsonPrimitive("idle"))
                            }
                        )
                    }
                )
            )
        )
        advanceTimeBy(1000)
        advanceUntilIdle()

        val status = sessionVM.sessionListFlow.value.sessionStatuses["session-1"]
        assertNotNull(status)
        assertFalse(status!!.isBusy)
        coVerify(exactly = 0) { repository.getMessagesPaged(any(), any(), any()) }
    }

    @Test
    fun `handleSSEEvent permission asked refreshes pending permissions`() = runTest {
        val permissions = listOf(
            PermissionRequest(id = "perm-1", sessionId = "session-1", permission = "file.read")
        )
        coEvery { repository.getPendingPermissions() } returns Result.success(permissions)
        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = OrchestratorViewModel(core)  // primary VM under test

        handleSse(core,
            SSEEvent(payload = SSEPayload(type = "permission.asked"))
        )
        advanceUntilIdle()

        assertEquals(permissions, sessionVM.sessionListFlow.value.pendingPermissions)
    }

    @Test
    fun `respondPermission calls repository and removes pending permission`() = runTest {
        coEvery {
            repository.respondPermission("session-1", "perm-1", PermissionResponse.ALWAYS)
        } returns Result.success(Unit)

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = OrchestratorViewModel(core)  // primary VM under test
        core.writeSessionList {
            it.copy(
                pendingPermissions = listOf(
                    PermissionRequest(id = "perm-1", sessionId = "session-1", permission = "file.write"),
                    PermissionRequest(id = "perm-2", sessionId = "session-2", permission = "file.read")
                )
            )
        }

        orchestratorVM.respondPermission("session-1", "perm-1", PermissionResponse.ALWAYS)
        advanceUntilIdle()

        coVerify(exactly = 1) {
            repository.respondPermission("session-1", "perm-1", PermissionResponse.ALWAYS)
        }
        assertEquals(listOf("perm-2"), sessionVM.sessionListFlow.value.pendingPermissions.map { it.id })
    }

    @Test
    fun `loadPendingPermissions loads permissions into state`() = runTest {
        val permissions = listOf(
            PermissionRequest(id = "perm-1", sessionId = "session-1", permission = "file.read"),
            PermissionRequest(id = "perm-2", sessionId = "session-2", permission = "command.exec")
        )
        coEvery { repository.getPendingPermissions() } returns Result.success(permissions)

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = OrchestratorViewModel(core)  // primary VM under test

        sessionVM.loadPendingPermissions()
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.getPendingPermissions() }
        assertEquals(permissions, sessionVM.sessionListFlow.value.pendingPermissions)
    }

    @Test
    fun `replyQuestion calls repository and removes answered question`() = runTest {
        val answers = listOf(listOf("React"), listOf("Custom"))
        // §R18 Phase 2-E step 2: directory now threaded explicitly. The
        // question's session is not in the test state, so the resolver falls
        // back to settingsManager.currentWorkdir (mocked null here).
        coEvery { repository.replyQuestion("question-1", answers, any()) } returns Result.success(Unit)

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = OrchestratorViewModel(core)  // primary VM under test
        core.writeSessionList {
            it.copy(
                pendingQuestions = listOf(
                    QuestionRequest(
                        id = "question-1",
                        sessionId = "session-1",
                        questions = emptyList()
                    ),
                    QuestionRequest(
                        id = "question-2",
                        sessionId = "session-2",
                        questions = emptyList()
                    )
                )
            )
        }

        orchestratorVM.replyQuestion("question-1", answers)
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.replyQuestion("question-1", answers, any()) }
        assertEquals(listOf("question-2"), sessionVM.sessionListFlow.value.pendingQuestions.map { it.id })
    }

    @Test
    fun `rejectQuestion calls repository and removes rejected question`() = runTest {
        coEvery { repository.rejectQuestion("question-1", any()) } returns Result.success(Unit)

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = OrchestratorViewModel(core)  // primary VM under test
        core.writeSessionList {
            it.copy(
                pendingQuestions = listOf(
                    QuestionRequest(
                        id = "question-1",
                        sessionId = "session-1",
                        questions = emptyList()
                    ),
                    QuestionRequest(
                        id = "question-2",
                        sessionId = "session-2",
                        questions = emptyList()
                    )
                )
            )
        }

        orchestratorVM.rejectQuestion("question-1")
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.rejectQuestion("question-1", any()) }
        assertEquals(listOf("question-2"), sessionVM.sessionListFlow.value.pendingQuestions.map { it.id })
    }

    // ── §issue-1 Phase 1/2 seam test: resolver→repository wiring ──────────────
    //
    // GAP this closes (gpter Phase-1 gate gap #2): Group A tests
    // `resolveQuestionDirectory` directly; Group B tests the repository header
    // on the wire directly; the legacy `replyQuestion calls repository…` test
    // above uses `any()` for the directory arg. NOTHING pins that the directory
    // the resolver COMPUTES is the directory actually PASSED to
    // repository.replyQuestion / .rejectQuestion — a future caller could
    // mis-pass / drop / hardcode the resolver result and A+B+legacy would all
    // still pass. This test pins that SEAM by asserting the EXACT directory
    // value on the repository call.
    //
    // Phase 2a Fix A IS applied (resolveQuestionDirectory is now `suspend`,
    // fetches the parent session via repository.getSession when it is absent
    // locally, and DROPS the old settingsManager.currentWorkdir fallback).
    // This test seeds the bug-triggering scenario (parent session absent from
    // local state + currentWorkdir="/wrong-Y") and asserts the FETCHED
    // "/real-X" reaches the repository — proving resolver→repo wiring through
    // the suspend fetch. currentWorkdir="/wrong-Y" is set specifically so this
    // test would FAIL if anyone re-introduced the old currentWorkdir fallback.
    //
    // §issue-1 Phase 1/2 seam test: proves resolver→repository wiring (A tests
    // resolver, B tests wire; this pins the directory arg actually passed).
    // Would have failed pre-Fix-A (resolver returned currentWorkdir /wrong-Y).
    @Test
    fun `replyQuestion seam passes resolveQuestionDirectory output as the directory arg to the repository`() = runTest {
        val answers = listOf(listOf("React"), listOf("Custom"))
        // The WRONG value the pre-fix branch-2 fallback used to send. Fix A
        // drops this path; set it so a regression (re-adding the fallback)
        // would make this test fail.
        every { settingsManager.currentWorkdir } returns "/wrong-Y"
        // The REAL session directory Fix A fetches via GET /session/{id}.
        coEvery { repository.getSession("session-S") } returns Result.success(Session(id = "session-S", directory = "/real-X"))
        coEvery { repository.replyQuestion(any(), any(), any()) } returns Result.success(Unit)

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = OrchestratorViewModel(core)
        // session-S is deliberately NOT in `sessions` nor `directorySessions`
        // (the bug-triggering scenario: parent session absent from local state
        // → resolver must fetch).
        core.writeSessionList {
            it.copy(
                sessions = emptyList(),
                directorySessions = emptyMap(),
                pendingQuestions = listOf(
                    QuestionRequest(id = "q-1", sessionId = "session-S", questions = emptyList()),
                ),
            )
        }

        orchestratorVM.replyQuestion("q-1", answers)
        advanceUntilIdle()

        // SEAM PROOF: the EXACT directory the resolver fetched (`/real-X`, NOT
        // currentWorkdir `/wrong-Y`) is the directory passed to
        // repository.replyQuestion. NOT `any()` — the literal value, end-to-end
        // from fetch → resolver → repository arg → (per B1-B4) wire header.
        coVerify(exactly = 1) { repository.replyQuestion("q-1", answers, "/real-X") }
        // Fix A fetch: the resolver hit the local-miss → GET /session/{id} path.
        coVerify(exactly = 1) { repository.getSession("session-S") }
        // Question consumed from pending on success.
        assertTrue(sessionVM.sessionListFlow.value.pendingQuestions.none { it.id == "q-1" })
    }

    // §issue-1 Phase 1/2 seam test (reject companion): same resolver→repo
    // wiring pinned for rejectQuestion. See the reply seam test above.
    @Test
    fun `rejectQuestion seam passes resolveQuestionDirectory output as the directory arg to the repository`() = runTest {
        every { settingsManager.currentWorkdir } returns "/wrong-Y"
        coEvery { repository.getSession("session-S") } returns Result.success(Session(id = "session-S", directory = "/real-X"))
        coEvery { repository.rejectQuestion(any(), any()) } returns Result.success(Unit)

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = OrchestratorViewModel(core)
        core.writeSessionList {
            it.copy(
                sessions = emptyList(),
                directorySessions = emptyMap(),
                pendingQuestions = listOf(
                    QuestionRequest(id = "q-1", sessionId = "session-S", questions = emptyList()),
                ),
            )
        }

        orchestratorVM.rejectQuestion("q-1")
        advanceUntilIdle()

        // SEAM PROOF: the resolver's fetched `/real-X` (not currentWorkdir
        // `/wrong-Y`) is the directory passed to repository.rejectQuestion.
        coVerify(exactly = 1) { repository.rejectQuestion("q-1", "/real-X") }
        coVerify(exactly = 1) { repository.getSession("session-S") }
        assertTrue(sessionVM.sessionListFlow.value.pendingQuestions.none { it.id == "q-1" })
    }

    @Test
    fun `handleSSEEvent message created refreshes messages for current session`() = runTest {
        val messages = listOf(MessageWithParts(info = Message(id = "m1", role = "assistant")))
        coEvery { repository.getMessagesPaged("session-1", any(), any()) } returns Result.success(MessagesPage(messages, null))
        coEvery { repository.getSessions(100) } returns Result.success(
            listOf(cn.vectory.ocdroid.data.model.Session(id = "session-1", directory = "/tmp/project"))
        )

        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = OrchestratorViewModel(core)  // primary VM under test
        core.writeChat { it.copy(currentSessionId = "session-1") }

        handleSse(core,
            SSEEvent(
                payload = SSEPayload(
                    type = "message.created",
                    properties = buildJsonObject {
                        put("sessionID", JsonPrimitive("session-1"))
                    }
                )
            )
        )
        advanceTimeBy(400)
        advanceUntilIdle()

        assertEquals(messages.map { it.info }, chatVM.chatFlow.value.messages)
    }

    @Test
    fun `handleSSEEvent question asked appends pending question`() = runTest {
        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = OrchestratorViewModel(core)  // primary VM under test

        handleSse(core,
            SSEEvent(
                payload = SSEPayload(
                    type = "question.asked",
                    properties = buildJsonObject {
                        put("id", JsonPrimitive("question-1"))
                        put("sessionID", JsonPrimitive("session-1"))
                        put(
                            "questions",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("question", JsonPrimitive("What framework do you use?"))
                                        put("header", JsonPrimitive("Framework Choice"))
                                        put(
                                            "options",
                                            buildJsonArray {
                                                add(
                                                    buildJsonObject {
                                                        put("label", JsonPrimitive("React"))
                                                        put("description", JsonPrimitive("Popular UI library"))
                                                    }
                                                )
                                            }
                                        )
                                        put("multiple", JsonPrimitive(false))
                                        put("custom", JsonPrimitive(true))
                                    }
                                )
                            }
                        )
                    }
                )
            )
        )

        assertEquals(listOf("question-1"), sessionVM.sessionListFlow.value.pendingQuestions.map { it.id })
        assertEquals("session-1", sessionVM.sessionListFlow.value.pendingQuestions.single().sessionId)
    }

    @Test
    fun `handleSSEEvent question rejected removes pending question`() = runTest {
        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = OrchestratorViewModel(core)  // primary VM under test
        core.writeSessionList {
            it.copy(
                pendingQuestions = listOf(
                    QuestionRequest(id = "question-1", sessionId = "session-1", questions = emptyList()),
                    QuestionRequest(id = "question-2", sessionId = "session-2", questions = emptyList())
                )
            )
        }

        handleSse(core,
            SSEEvent(
                payload = SSEPayload(
                    type = "question.rejected",
                    properties = buildJsonObject {
                        put("requestID", JsonPrimitive("question-1"))
                    }
                )
            )
        )

        assertEquals(listOf("question-2"), sessionVM.sessionListFlow.value.pendingQuestions.map { it.id })
    }

    @Test
    fun `Stage1 free helper syncs slice - selectSession mirrors chatFlow`() = runTest {
        // §R-17 Stage 1 verification: free helpers (selectSessionState via
        // selectSession) now write AppState through updateAndSync(sliceFlows),
        // so the chatFlow slice must mirror the AppState chat fields. This is
        // the hard prerequisite for consumer migration (Stage 2) — without it
        // a consumer reading chatFlow would see stale values after selectSession.
        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = OrchestratorViewModel(core)  // primary VM under test
        core.writeSessionList { it.copy(sessions = listOf(Session(id = "s1", directory = "/tmp"))) }
        sessionVM.selectSession("s1")
        advanceUntilIdle()
        assertEquals("s1", chatVM.chatFlow.value.currentSessionId)
        assertEquals(
            "chatFlow slice must mirror AppState after free-helper write",
            chatVM.chatFlow.value.currentSessionId,
            chatVM.chatFlow.value.currentSessionId
        )
    }

    @Test
    fun `Stage1 free helper syncs slice - loadSessions mirrors sessionListFlow`() = runTest {
        // §R-17 Stage 1: launchLoadSessions (free helper) writes AppState via
        // updateAndSync; sessionListFlow must mirror the sessions field so a
        // future consumer subscribing to sessionListFlow sees the loaded list.
        coEvery { repository.getSessions(any()) } returns Result.success(
            listOf(Session(id = "loaded-1", directory = "/tmp"))
        )
        val core = createCore()
        val chatVM = cn.vectory.ocdroid.ui.ChatViewModel(core)
        val sessionVM = cn.vectory.ocdroid.ui.SessionViewModel(core)
        val connectionVM = cn.vectory.ocdroid.ui.ConnectionViewModel(core)
        val hostVM = cn.vectory.ocdroid.ui.HostViewModel(core)
        val composerVM = cn.vectory.ocdroid.ui.ComposerViewModel(core)
        val orchestratorVM = cn.vectory.ocdroid.ui.OrchestratorViewModel(core)
        val viewModel = OrchestratorViewModel(core)  // primary VM under test
        core.loadSessionsForEffect()
        advanceUntilIdle()
        assertTrue(
            "sessionListFlow must contain the loaded session",
            sessionVM.sessionListFlow.value.sessions.any { it.id == "loaded-1" }
        )
        assertEquals(
            "sessionListFlow.sessions must mirror AppState.sessions",
            sessionVM.sessionListFlow.value.sessions.size,
            sessionVM.sessionListFlow.value.sessions.size
        )
    }

}
