package cn.vectory.ocdroid.ui.controller

import cn.vectory.ocdroid.data.api.CommandInfo
import cn.vectory.ocdroid.data.model.AgentInfo
import cn.vectory.ocdroid.data.model.ComposerImageAttachment
import cn.vectory.ocdroid.data.model.HostProfile
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.model.PermissionRequest
import cn.vectory.ocdroid.data.model.ProvidersResponse
import cn.vectory.ocdroid.data.model.QuestionRequest
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.data.model.TodoItem
import cn.vectory.ocdroid.ui.ConnectionPhase
import cn.vectory.ocdroid.ui.MainViewModelTimings
import cn.vectory.ocdroid.ui.TunnelActivationState
import cn.vectory.ocdroid.util.MarkdownFontSizes
import cn.vectory.ocdroid.util.ThemeMode

/**
 * §R-17 batch2 (AppState retirement): test-only flat fixture used by the
 * controller unit tests ([HostProfileControllerTest] /
 * [SessionSwitcherTest] / [SessionSyncCoordinatorTest]) to seed all nine
 * [cn.vectory.ocdroid.ui.SliceFlows] slices via each test's `seed { ... }`
 * helper. Structurally mirrors the deleted `AppState` data class (same field
 * names + defaults) so `seed { it.copy(field = value) }` call sites read
 * identically; the fixture carries the prior snapshot so successive transforms
 * compose. This is NOT production state — the nine slice `MutableStateFlow`s
 * are the sole authoritative stores.
 */
data class SeedFixture(
    // connection-domain
    val isConnected: Boolean = false,
    val isConnecting: Boolean = false,
    val serverVersion: String? = null,
    val connectionPhase: ConnectionPhase = ConnectionPhase.Idle,
    val tunnelActivationState: TunnelActivationState = TunnelActivationState.Idle,
    // traffic-domain
    val trafficSent: Long = 0L,
    val trafficReceived: Long = 0L,
    // composer-domain
    val inputText: String = "",
    val imageAttachments: List<ComposerImageAttachment> = emptyList(),
    val sendingSessionIds: Set<String> = emptySet(),
    val draftWorkdir: String? = null,
    // file-domain
    val filePathToShowInFiles: String? = null,
    val filePreviewOriginRoute: String? = null,
    val fileBrowserOpen: Boolean = false,
    val fileBrowserWorkdir: String? = null,
    // settings-domain
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val markdownFontSizes: MarkdownFontSizes = MarkdownFontSizes(),
    // §chat-ux-batch T8 (B3): the legacy `selectedAgentName` field was
    // deleted here (mirrors the production SettingsState field removal).
    val agents: List<AgentInfo> = emptyList(),
    val providers: ProvidersResponse? = null,
    val availableCommands: List<CommandInfo> = emptyList(),
    val disabledModels: Set<String> = emptySet(),
    val uiFontScale: Float = 1f,
    val uiContentScale: Float = 1f,
    // chat-domain
    val currentSessionId: String? = null,
    val messages: List<Message> = emptyList(),
    val partsByMessage: Map<String, List<Part>> = emptyMap(),
    val streamingPartTexts: Map<String, String> = emptyMap(),
    val streamingReasoningPart: Part? = null,
    val olderMessagesCursor: String? = null,
    val hasMoreMessages: Boolean = true,
    val isLoadingMessages: Boolean = false,
    val staleNotice: Boolean = false,
    val currentModel: Message.ModelInfo? = null,
    // session-list-domain
    val sessions: List<Session> = emptyList(),
    val sessionStatuses: Map<String, SessionStatus> = emptyMap(),
    val expandedSessionIds: Set<String> = emptySet(),
    val loadedSessionLimit: Int = MainViewModelTimings.sessionPageSize,
    val hasMoreSessions: Boolean = true,
    val isLoadingMoreSessions: Boolean = false,
    val isRefreshingSessions: Boolean = false,
    val pendingPermissions: List<PermissionRequest> = emptyList(),
    val pendingQuestions: List<QuestionRequest> = emptyList(),
    val childSessions: Map<String, List<Session>> = emptyMap(),
    val directorySessions: Map<String, List<Session>> = emptyMap(),
    val openSessionIds: List<String> = emptyList(),
    val sessionTodos: Map<String, List<TodoItem>> = emptyMap(),
    // unread-domain
    val unreadSessions: Set<String> = emptySet(),
    val lastViewedTime: Map<String, Long> = emptyMap(),
    /** §unread-soak: mirror of [cn.vectory.ocdroid.ui.UnreadState.idleSince]. */
    val idleSince: Map<String, Long> = emptyMap(),
    // host-domain
    val hostProfiles: List<HostProfile> = emptyList(),
    val currentHostProfileId: String? = null
)
