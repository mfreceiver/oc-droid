package com.yage.opencode_client.ui

import com.yage.opencode_client.data.model.AgentInfo
import com.yage.opencode_client.data.model.ComposerImageAttachment
import com.yage.opencode_client.data.api.CommandInfo
import com.yage.opencode_client.data.model.HostProfile
import com.yage.opencode_client.data.model.Message
import com.yage.opencode_client.data.model.Part
import com.yage.opencode_client.data.model.PermissionRequest
import com.yage.opencode_client.data.model.ProvidersResponse
import com.yage.opencode_client.data.model.QuestionRequest
import com.yage.opencode_client.data.model.Session
import com.yage.opencode_client.data.model.SessionStatus
import com.yage.opencode_client.data.model.TodoItem
import com.yage.opencode_client.util.MarkdownFontSizes
import com.yage.opencode_client.util.ThemeMode

sealed class TunnelActivationState {
    data object Idle : TunnelActivationState()
    data object Loading : TunnelActivationState()
    data object Success : TunnelActivationState()
    data class Error(val message: String) : TunnelActivationState()
}

/**
 * §R-17 M2: connection-domain state slice. Authoritative storage lives in
 * [MainViewModel._connectionFlow] (a dedicated `MutableStateFlow`); the
 * overlapping fields on [AppState] are deprecated mirrors kept only for
 * source/test compatibility until M4 retires them. Field set strictly
 * follows RFC R-17 §2.1.
 *
 * Write atomicity (RFC §4, strategy A): every mutation goes through a single
 * `writeConnection { ... }` (or a sequence of them where each
 * intermediate state is a legal UI state — never a `isConnected=true` paired
 * with a null `connectionPhase`). Do NOT rely on `Dispatchers.Main.immediate`
 * batching across separate `update` calls.
 */
data class ConnectionState(
    val isConnected: Boolean = false,
    val isConnecting: Boolean = false,
    val serverVersion: String? = null,
    val connectionPhase: String? = null,
    val tunnelActivationState: TunnelActivationState = TunnelActivationState.Idle
)

/**
 * §R-17 M2: traffic-domain state slice. Authoritative storage lives in
 * [MainViewModel._trafficFlow]. Field set strictly follows RFC R-17 §2.9.
 * Only written by [MainViewModel.refreshTrafficStats] and read by the server
 * management dialog (ChatTopBar) — isolating it avoids recomposing unrelated
 * subscribers each time the counter is refreshed.
 */
data class TrafficState(
    val trafficSent: Long = 0L,
    val trafficReceived: Long = 0L
) {
    /** Combined sent + received traffic, derived so it never drifts. */
    val totalTrafficBytes: Long
        get() = trafficSent + trafficReceived
}

/**
 * §R-17 M3: composer-domain state slice. Authoritative storage lives in
 * [MainViewModel._composerFlow]. Field set strictly follows RFC R-17 §2.5.
 *
 * This is the highest-frequency slice (`inputText` mutates on every keystroke)
 * and the primary reason M3 exists: once M4 consumers subscribe to
 * `composerFlow` directly instead of reading `state.inputText`, keystrokes no
 * longer recompose ChatTopBar.
 *
 * Write atomicity (RFC §4 strategy A): same model as [ConnectionState] —
 * every mutation goes through [MainViewModel.writeComposer], which writes the
 * slice AND the `_state` mirror in one synchronous call. No dispatcher batch
 * reliance (RFC §9.2).
 */
data class ComposerState(
    val inputText: String = "",
    val imageAttachments: List<ComposerImageAttachment> = emptyList(),
    val sendingSessionIds: Set<String> = emptySet(),
    val draftWorkdir: String? = null
)

/**
 * §R-17 M3: file-browser-domain state slice. Authoritative storage lives in
 * [MainViewModel._fileFlow]. Field set strictly follows RFC R-17 §2.6.
 * Consumed only by FilesScreen + PhoneLayout overlay; isolating it prevents
 * file-open/close from recomposing unrelated subscribers.
 */
data class FileState(
    val filePathToShowInFiles: String? = null,
    val filePreviewOriginRoute: String? = null,
    val fileBrowserOpen: Boolean = false,
    val fileBrowserWorkdir: String? = null
)

/**
 * §R-17 M3: settings/global-preference state slice. Authoritative storage
 * lives in [MainViewModel._settingsFlow]. Field set strictly follows RFC
 * R-17 §2.4 EXCEPT `error`, which is cross-domain and stays on `_state` (see
 * RFC §3.3 — same call as M2 keeping `error` on `_state`).
 *
 * `availableCommands` is a connect-time / host-switch config (not live state)
 * but RFC §2.4 groups it here rather than under composer.
 */
data class SettingsState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val markdownFontSizes: MarkdownFontSizes = MarkdownFontSizes(),
    val selectedAgentName: String = "build",
    val agents: List<AgentInfo> = emptyList(),
    val providers: ProvidersResponse? = null,
    val availableCommands: List<CommandInfo> = emptyList()
)

/**
 * §R-17 M4: chat-domain state slice (RFC §2.2). Authoritative storage lives in
 * [MainViewModel._chatFlow]. The highest-frequency domain (SSE streaming deltas
 * mutate streamingPartTexts/messages many times per second). `error` is
 * intentionally NOT here — it is cross-domain and stays on `_state` (RFC §3.3,
 * same call as M2/M3).
 */
data class ChatState(
    val currentSessionId: String? = null,
    val messages: List<Message> = emptyList(),
    val partsByMessage: Map<String, List<Part>> = emptyMap(),
    val streamingPartTexts: Map<String, String> = emptyMap(),
    val streamingReasoningPart: Part? = null,
    val olderMessagesCursor: String? = null,
    val hasMoreMessages: Boolean = true,
    val isLoadingMessages: Boolean = false,
    val gapInfo: GapInfo? = null,
    val staleNotice: Boolean = false
)

/**
 * §R-17 M4: session-list-domain state slice (RFC §2.3). Authoritative storage
 * lives in [MainViewModel._sessionListFlow]. Low-frequency (loadSessions /
 * loadMore / SSE session.created/updated); isolating it stops SSE chat deltas
 * from recomposing SessionsScreen.
 */
data class SessionListState(
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
    val sessionTodos: Map<String, List<TodoItem>> = emptyMap()
)

/**
 * §R-17 M4: unread-domain state slice (RFC §2.7). Authoritative storage lives
 * in [MainViewModel._unreadFlow]. Drives the unread badge; depends on session
 * status + chat currentSessionId + foreground (cross-domain, see RFC §4).
 */
data class UnreadState(
    val unreadSessions: Set<String> = emptySet(),
    val tempClearedUnread: Set<String> = emptySet(),
    val lastViewedTime: Map<String, Long> = emptyMap()
)

/**
 * §R-17 M4: host-profile-domain state slice (RFC §2.8). Authoritative storage
 * lives in [MainViewModel._hostFlow]. Very low write frequency (save/switch/
 * import); consumed by ChatTopBar (server dialog) + SettingsScreen.
 */
data class HostState(
    val hostProfiles: List<HostProfile> = emptyList(),
    val currentHostProfileId: String? = null
)

/**
 * §Per-session message cache: a snapshot of the loaded window for a single
 * session — the four pieces of message-state that the session-switch flow
 * (inlined in SessionSwitcher.switchTo()) otherwise wipes to empty on every switch. Restoring from this snapshot on
 * return avoids the visible flicker + history re-fetch (the latest 5 only)
 * that previously hit users on every A→B→A hop. Bounded LRU lives in
 * [MainViewModel.sessionWindowCache]; the post-restore tail fetch
 * (`loadMessages(resetLimit = false)`) still runs to merge fresh messages
 * non-destructively, so a stale snapshot never hides new content.
 */
internal data class CachedSessionWindow(
    val messages: List<Message>,
    val partsByMessage: Map<String, List<Part>>,
    val olderMessagesCursor: String?,
    val hasMoreMessages: Boolean
)

/**
 * §Phase1C gap (断层) state. Set by [MainViewModel.catchUpAfterDisconnectOrForeground]
 * when a tail reload detects the pre-reload newest message [anchorNewestId] is
 * NOT in the freshly-fetched latest window — meaning messages arrived during
 * the disconnect that fall outside the 5-message tail. The user can then tap
 * the gap divider to page older (loadMore-style) from [tailOldestCursor] until
 * [anchorNewestId] reappears (gap closed, [open] = false).
 *
 * - [anchorNewestId]: pre-reload local newest message id (by time.created).
 * - [tailOldestId]: the oldest id in the fetched tail window — the visual
 *   seam where the gap divider is rendered (between this and older history).
 * - [tailOldestCursor]: server cursor for paging OLDER from the tail's oldest
 *   (the gap-closure direction). Updated as the user pages.
 * - [open]: true while the gap is still unresolved.
 */
data class GapInfo(
    val anchorNewestId: String,
    val tailOldestId: String,
    val tailOldestCursor: String?,
    val open: Boolean = true
)

data class ConnectionFormSettings(
    val serverUrl: String,
    val username: String,
    val password: String
)
