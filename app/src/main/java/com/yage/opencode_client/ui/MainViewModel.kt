package com.yage.opencode_client.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yage.opencode_client.data.api.CommandInfo
import com.yage.opencode_client.data.model.*
import com.yage.opencode_client.data.repository.HostProfileStore
import com.yage.opencode_client.data.repository.OpenCodeRepository
import com.yage.opencode_client.di.AppLifecycleMonitor
import com.yage.opencode_client.util.SettingsManager
import com.yage.opencode_client.util.DebugLog
import com.yage.opencode_client.util.ThemeMode
import com.yage.opencode_client.util.TrafficTracker
import com.yage.opencode_client.util.MarkdownFontSizes
import com.yage.opencode_client.util.runSuspendCatching
import com.yage.opencode_client.ui.controller.ForegroundCatchUpCallbacks
import com.yage.opencode_client.ui.controller.ForegroundCatchUpController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject

/** Toast message shown on successful tunnel activation. ChatScreen compares
 *  the current `error` against this to pick the Success severity — both the
 *  success and error toasts ride the `error` field, so the message identity
 *  (not the sticky tunnelActivationState) drives severity. */
const val TUNNEL_SUCCESS_TOAST = "隧道激活成功"

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
 * session — the four pieces of message-state that [selectSessionState]
 * otherwise wipes to empty on every switch. Restoring from this snapshot on
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
 * §Phase1C gap (断层) state. Set by [MainViewModel.catchUpAfterDisconnectOrForeground] * when a tail reload detects the pre-reload newest message [anchorNewestId] is
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

data class AppState(
    // §R-17 M2: connection/traffic fields below are deprecated mirrors. The
    // authoritative storage is MainViewModel._connectionFlow / _trafficFlow;
    // this AppState keeps the fields only so legacy consumers and reflection-
    // based tests keep compiling. `viewModel.state` is `_state.asStateFlow()`
    // (a SYNCHRONOUS view — NOT a combine() chain; see the design note in
    // docs/rfc-r17-appstate-split.md §6 「实施偏离记录」 for why combine+
    // stateIn was rejected). These mirror fields are kept in sync with the
    // slices by `MainViewModel.writeConnection` / `writeTraffic`, which in a
    // single synchronous call write the slice AND `_state.value.copy(...)`
    // over these fields — so `state.value` is consistent immediately after
    // every write, with no dispatcher batch reliance (RFC §9.2). They will be
    // removed in M4 together with the rest of AppState; once the mirrors are
    // gone and no test/consumer reads `state.value.<conn/traffic>` directly,
    // `state` can be reconsidered as a combine() of the slices.
    @Deprecated("mirror from connectionFlow; M4 removes AppState", level = DeprecationLevel.WARNING)
    val isConnected: Boolean = false,
    @Deprecated("mirror from connectionFlow; M4 removes AppState", level = DeprecationLevel.WARNING)
    val isConnecting: Boolean = false,
    @Deprecated("mirror from connectionFlow; M4 removes AppState", level = DeprecationLevel.WARNING)
    val serverVersion: String? = null,
    @Deprecated("mirror from M4 slice; M5 removes AppState", level = DeprecationLevel.WARNING)
    val sessions: List<Session> = emptyList(),
    @Deprecated("mirror from M4 slice; M5 removes AppState", level = DeprecationLevel.WARNING)
    val loadedSessionLimit: Int = MainViewModelTimings.sessionPageSize,
    @Deprecated("mirror from M4 slice; M5 removes AppState", level = DeprecationLevel.WARNING)
    val hasMoreSessions: Boolean = true,
    @Deprecated("mirror from M4 slice; M5 removes AppState", level = DeprecationLevel.WARNING)
    val isLoadingMoreSessions: Boolean = false,
    @Deprecated("mirror from M4 slice; M5 removes AppState", level = DeprecationLevel.WARNING)
    val isRefreshingSessions: Boolean = false,
    @Deprecated("mirror from M4 slice; M5 removes AppState", level = DeprecationLevel.WARNING)
    val expandedSessionIds: Set<String> = emptySet(),
    @Deprecated("mirror from M4 slice; M5 removes AppState", level = DeprecationLevel.WARNING)
    val currentSessionId: String? = null,
    // Q6: persisted last-opened phone pager page (0=Chat...3=Settings).
    val lastNavPage: Int = 0,
    @Deprecated("mirror from M4 slice; M5 removes AppState", level = DeprecationLevel.WARNING)
    val sessionStatuses: Map<String, SessionStatus> = emptyMap(),
    // SSE-trust split store (S4): messages hold only Message metadata; parts
    // live separately keyed by message id (populated by API load). Live
    // streaming text is overlaid via streamingPartTexts (keyed by partId).
    @Deprecated("mirror from M4 slice; M5 removes AppState", level = DeprecationLevel.WARNING)
    val messages: List<Message> = emptyList(),
    @Deprecated("mirror from M4 slice; M5 removes AppState", level = DeprecationLevel.WARNING)
    val partsByMessage: Map<String, List<Part>> = emptyMap(),
    // §on-demand: cursor-based history paging. olderMessagesCursor is the opaque
    // V1 cursor for fetching the next older page (null = no more / reset).
    @Deprecated("mirror from M4 slice; M5 removes AppState", level = DeprecationLevel.WARNING)
    val olderMessagesCursor: String? = null,
    @Deprecated("mirror from M4 slice; M5 removes AppState", level = DeprecationLevel.WARNING)
    val hasMoreMessages: Boolean = true,
    @Deprecated("mirror from M4 slice; M5 removes AppState", level = DeprecationLevel.WARNING)
    val isLoadingMessages: Boolean = false,
    @Deprecated("mirror from settingsFlow; M4 removes AppState", level = DeprecationLevel.WARNING)
    val agents: List<AgentInfo> = emptyList(),
    @Deprecated("mirror from settingsFlow; M4 removes AppState", level = DeprecationLevel.WARNING)
    val selectedAgentName: String = "build",
    @Deprecated("mirror from settingsFlow; M4 removes AppState", level = DeprecationLevel.WARNING)
    val providers: ProvidersResponse? = null,
    @Deprecated("mirror from M4 slice; M5 removes AppState", level = DeprecationLevel.WARNING)
    val pendingPermissions: List<PermissionRequest> = emptyList(),
    @Deprecated("mirror from M4 slice; M5 removes AppState", level = DeprecationLevel.WARNING)
    val pendingQuestions: List<QuestionRequest> = emptyList(),
    @Deprecated("mirror from composerFlow; M4 removes AppState", level = DeprecationLevel.WARNING)
    val inputText: String = "",
    val error: String? = null,
    @Deprecated("mirror from settingsFlow; M4 removes AppState", level = DeprecationLevel.WARNING)
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    @Deprecated("mirror from settingsFlow; M4 removes AppState", level = DeprecationLevel.WARNING)
    val markdownFontSizes: MarkdownFontSizes = MarkdownFontSizes(),
    @Deprecated("mirror from fileFlow; M4 removes AppState", level = DeprecationLevel.WARNING)
    val filePathToShowInFiles: String? = null,
    @Deprecated("mirror from fileFlow; M4 removes AppState", level = DeprecationLevel.WARNING)
    val filePreviewOriginRoute: String? = null,
    // Bug3: project-level file browser opened from the Sessions screen. The
    // overlay is rendered at the PhoneLayout ROOT (above the HorizontalPager)
    // and the pager's userScrollEnabled is bound to !fileBrowserOpen, so Chat
    // can't be swiped to / interacted with during a browse. This keeps the
    // global currentDirectory (temporarily re-scoped to fileBrowserWorkdir)
    // unambiguous — no desync.
    @Deprecated("mirror from fileFlow; M4 removes AppState", level = DeprecationLevel.WARNING)
    val fileBrowserOpen: Boolean = false,
    @Deprecated("mirror from fileFlow; M4 removes AppState", level = DeprecationLevel.WARNING)
    val fileBrowserWorkdir: String? = null,
    @Deprecated("mirror from M4 slice; M5 removes AppState", level = DeprecationLevel.WARNING)
    val streamingPartTexts: Map<String, String> = emptyMap(),
    @Deprecated("mirror from M4 slice; M5 removes AppState", level = DeprecationLevel.WARNING)
    val streamingReasoningPart: Part? = null,
    @Deprecated("mirror from M4 slice; M5 removes AppState", level = DeprecationLevel.WARNING)
    val sessionTodos: Map<String, List<TodoItem>> = emptyMap(),
    @Deprecated("mirror from composerFlow; M4 removes AppState", level = DeprecationLevel.WARNING)
    val sendingSessionIds: Set<String> = emptySet(),
    @Deprecated("mirror from composerFlow; M4 removes AppState", level = DeprecationLevel.WARNING)
    val imageAttachments: List<ComposerImageAttachment> = emptyList(),
    @Deprecated("mirror from M4 slice; M5 removes AppState", level = DeprecationLevel.WARNING)
    val hostProfiles: List<HostProfile> = emptyList(),
    @Deprecated("mirror from M4 slice; M5 removes AppState", level = DeprecationLevel.WARNING)
    val currentHostProfileId: String? = null,
    @Deprecated("mirror from connectionFlow; M4 removes AppState", level = DeprecationLevel.WARNING)
    val connectionPhase: String? = null,
    @Deprecated("mirror from connectionFlow; M4 removes AppState", level = DeprecationLevel.WARNING)
    val tunnelActivationState: TunnelActivationState = TunnelActivationState.Idle,
    /**
     * Sub-agent (child) sessions keyed by parent session ID. Populated lazily
     * after a session is selected via [MainViewModel.loadChildSessions]. Used
     * to render sub-agent cards and to support parent->child navigation.
     */
    @Deprecated("mirror from M4 slice; M5 removes AppState", level = DeprecationLevel.WARNING)
    val childSessions: Map<String, List<Session>> = emptyMap(),
    /**
     * Per-directory root sessions, keyed by workdir path. Populated by
     * [MainViewModel.createSessionInWorkdir] via
     * [OpenCodeRepository.getSessionsForDirectory] so the user can see (and
     * pick up) prior conversations when connecting a project. Stored
     * separately from [sessions] so periodic global refreshes do not discard
     * it. [SessionsScreen] merges both for display.
     */
    @Deprecated("mirror from M4 slice; M5 removes AppState", level = DeprecationLevel.WARNING)
    val directorySessions: Map<String, List<Session>> = emptyMap(),
    /**
     * Mirror of [SettingsManager.openSessionIds] (browser-tab style list of
     * "open" session IDs in open-order, most recently opened first). Kept in
     * AppState so the Compose UI recomposes when the list changes.
     * [SettingsManager] remains the persistent source of truth — this field
     * is the observable projection.
     */
    @Deprecated("mirror from M4 slice; M5 removes AppState", level = DeprecationLevel.WARNING)
    val openSessionIds: List<String> = emptyList(),
    /**
     * Non-null when the user has entered "draft" (deferred-create) mode by
     * invoking [MainViewModel.createSessionInWorkdir]. The repository's
     * current directory has been set to this workdir, but no POST /session
     * has been issued. The first [MainViewModel.sendMessage] will create the
     * session on demand and clear this field. Selecting/creating any
     * other session or switching host discards the draft.
     */
    @Deprecated("mirror from composerFlow; M4 removes AppState", level = DeprecationLevel.WARNING)
    val draftWorkdir: String? = null,
    /**
     * Per-session last-viewed epoch millis. Updated whenever the user
     * selects/opens a session. Used as the basis for [unreadSessions].
     * Not currently persisted across restarts.
     */
    @Deprecated("mirror from M4 slice; M5 removes AppState", level = DeprecationLevel.WARNING)
    val lastViewedTime: Map<String, Long> = emptyMap(),
    /**
     * Session IDs that have received SSE message updates more recently than
     * their [lastViewedTime] (and are not the currently-open session).
     * Drives the unread badge in the session lists. Cleared on open.
     */
    @Deprecated("mirror from M4 slice; M5 removes AppState", level = DeprecationLevel.WARNING)
    val unreadSessions: Set<String> = emptySet(),
    /**
     * Sessions the user has opened (cleared the unread badge) and that are
     * still eligible to be *re-marked* unread if they remain busy when the
     * user navigates away, or if a new out-of-band message arrives for them
     * while they are not the current session (server 1.17.11+ surfaces new
     * messages via `message.updated`; the message.created branch is retained
     * only for forward-compat).
     *
     * A session leaves this set when the server reports it going idle
     * (busy -> idle) — at that point the in-flight task is complete and there
     * is nothing further to surface. This implements the "swipe away from a
     * busy session = re-mark unread; session completes cleanly = do not"
     * behaviour.
     */
    @Deprecated("mirror from M4 slice; M5 removes AppState", level = DeprecationLevel.WARNING)
    val tempClearedUnread: Set<String> = emptySet(),
    /**
     * Slash commands available in the composer. Merges a small set of
     * client-side commands (/clear, /compact, /undo, /redo) with the
     * server-published list (GET /command). Populated by
     * [MainViewModel.loadCommands] on connect.
     */
    @Deprecated("mirror from settingsFlow; M4 removes AppState", level = DeprecationLevel.WARNING)
    val availableCommands: List<CommandInfo> = emptyList(),
    /**
     * Cumulative HTTP traffic counters mirrored from [TrafficTracker] for the
     * Settings page. Refreshed on-demand by [MainViewModel.refreshTrafficStats]
     * (e.g. when Settings opens) rather than on every request, to avoid
     * recomposing the whole app on each network call. [totalTrafficBytes] is
     * derived so it can never drift out of sync.
     *
     * §R-17 M2: these are deprecated mirrors; authoritative storage is
     * [MainViewModel._trafficFlow]. M4 removes AppState.
     */
    @Deprecated("mirror from trafficFlow; M4 removes AppState", level = DeprecationLevel.WARNING)
    val trafficSent: Long = 0L,
    @Deprecated("mirror from trafficFlow; M4 removes AppState", level = DeprecationLevel.WARNING)
    val trafficReceived: Long = 0L,
    /**
     * §Phase1C: non-null when a tail reload detected messages may have arrived
     * during a disconnect that fall outside the 5-message tail window. Drives
     * the gap divider UI + user-triggered closure. Cleared on session switch,
     * manual refresh (global cold-start), and cold start. See [GapInfo].
     */
    @Deprecated("mirror from M4 slice; M5 removes AppState", level = DeprecationLevel.WARNING)
    val gapInfo: GapInfo? = null,
    /**
     * §Phase1E: shown as a top banner after a long (>5min) background absence
     * or process rebuild — "长时间未查看，仅显示最新内容 [点击重新加载全部会话]".
     * Tapping the action triggers [MainViewModel.refreshCurrentSession] (global
     * cold-start). Cleared once a normal load completes.
     */
    @Deprecated("mirror from M4 slice; M5 removes AppState", level = DeprecationLevel.WARNING)
    val staleNotice: Boolean = false
) {
    /** Combined sent + received traffic, derived so it never drifts. */
    val totalTrafficBytes: Long
        get() = trafficSent + trafficReceived

    data class ContextUsage(
        val percentage: Float,
        val totalTokens: Int,
        val contextLimit: Int,
        val providerId: String? = null,
        val modelId: String? = null,
        val inputTokens: Int? = null,
        val outputTokens: Int? = null,
        val reasoningTokens: Int? = null,
        val cachedReadTokens: Int? = null,
        val cachedWriteTokens: Int? = null,
        val cost: Double? = null
    )

    data class ConnectionState(
        val isConnected: Boolean = false,
        val isConnecting: Boolean = false,
        val serverVersion: String? = null
    )

    data class SessionState(
        val sessions: List<Session> = emptyList(),
    val currentSessionId: String? = null,
        val sessionStatuses: Map<String, SessionStatus> = emptyMap(),
        val expandedSessionIds: Set<String> = emptySet(),
        val loadedSessionLimit: Int = MainViewModelTimings.sessionPageSize,
        val hasMoreSessions: Boolean = true,
    val isLoadingMoreSessions: Boolean = false,
    val isRefreshingSessions: Boolean = false,
        val pendingPermissions: List<PermissionRequest> = emptyList(),
        val pendingQuestions: List<QuestionRequest> = emptyList()
    ) {
        val currentSession: Session?
            get() = sessions.find { it.id == currentSessionId }

        val currentSessionStatus: SessionStatus?
            get() = currentSessionId?.let { sessionStatuses[it] }

        val isCurrentSessionBusy: Boolean
            get() = currentSessionStatus?.isBusy == true

        val canLoadMoreSessions: Boolean
            get() = hasMoreSessions && !isLoadingMoreSessions
    }

    data class ChatState(
        val messages: List<Message> = emptyList(),
        val partsByMessage: Map<String, List<Part>> = emptyMap(),
        val streamingPartTexts: Map<String, String> = emptyMap(),
        val streamingReasoningPart: Part? = null,
        val isLoadingMessages: Boolean = false,
        val inputText: String = "",
        val imageAttachments: List<ComposerImageAttachment> = emptyList()
    )

    data class FileUiState(
        val filePathToShowInFiles: String? = null,
        val filePreviewOriginRoute: String? = null
    )

    data class SettingsState(
        val error: String? = null,
        val themeMode: ThemeMode = ThemeMode.SYSTEM,
        val selectedAgentName: String = "build",
        val contextUsage: ContextUsage? = null,
        val agents: List<AgentInfo> = emptyList(),
        val providers: ProvidersResponse? = null
    )

    val connectionState: ConnectionState
        get() = ConnectionState(
            isConnected = isConnected,
            isConnecting = isConnecting,
            serverVersion = serverVersion
        )

    val sessionState: SessionState
        get() = SessionState(
            sessions = sessions,
            currentSessionId = currentSessionId,
            sessionStatuses = sessionStatuses,
            expandedSessionIds = expandedSessionIds,
            loadedSessionLimit = loadedSessionLimit,
            hasMoreSessions = hasMoreSessions,
            isLoadingMoreSessions = isLoadingMoreSessions,
            isRefreshingSessions = isRefreshingSessions,
            pendingPermissions = pendingPermissions,
            pendingQuestions = pendingQuestions
        )

    val chatState: ChatState
        get() = ChatState(
            messages = visibleMessages,
            partsByMessage = partsByMessage,
            streamingPartTexts = streamingPartTexts,
            streamingReasoningPart = streamingReasoningPart,
            isLoadingMessages = isLoadingMessages,
            inputText = inputText,
            imageAttachments = imageAttachments
        )

    val visibleMessages: List<Message>
        get() {
            val revertMessageId = currentSession?.revert?.messageId
            val reverted = if (revertMessageId == null) {
                messages
            } else {
                messages.filter { message -> message.id < revertMessageId }
            }
            // Filter out system / tool / environment messages — only user and
            // assistant messages are shown in the chat transcript.
            return reverted.filter { !it.isToolRole }
        }

    val fileUiState: FileUiState
        get() = FileUiState(
            filePathToShowInFiles = filePathToShowInFiles,
            filePreviewOriginRoute = filePreviewOriginRoute
        )

    val settingsState: SettingsState
        get() = SettingsState(
            error = error,
            themeMode = themeMode,
            selectedAgentName = selectedAgentName,
            contextUsage = contextUsage,
            agents = agents,
            providers = providers
        )

    val currentSession: Session?
        get() = sessions.find { it.id == currentSessionId }

    val currentHostProfile: HostProfile?
        get() = hostProfiles.find { it.id == currentHostProfileId }

    val currentSessionStatus: SessionStatus?
        get() = currentSessionId?.let { sessionStatuses[it] }

    val isCurrentSessionBusy: Boolean
        get() = currentSessionStatus?.isBusy == true

    val canLoadMoreSessions: Boolean
        get() = hasMoreSessions && !isLoadingMoreSessions

    val visibleAgents: List<AgentInfo>
        get() = agents.filter { it.isVisible }

    val contextUsage: ContextUsage?
        get() {
            val lastAssistant = messages.lastOrNull { it.isAssistant && tokenTotal(it.tokens) != null }
                ?: return logContextUsageUnavailable("no assistant message with usable tokens; messages=${messages.size}")
            val tokens = lastAssistant.tokens
                ?: return logContextUsageUnavailable("latest assistant has no tokens; messages=${messages.size}")
            val total = tokenTotal(tokens)
                ?: return logContextUsageUnavailable("assistant tokens have no usable totals; tokens=$tokens")
            val model = lastAssistant.resolvedModel
                ?: return logContextUsageUnavailable("assistant message has no resolved model; message=${lastAssistant.id}")
            val key = "${model.providerId}/${model.modelId}"
            val index = providers?.providers?.flatMap { provider ->
                provider.models.flatMap { (modelKey, providerModel) ->
                    listOfNotNull(
                        "${provider.id}/$modelKey" to providerModel,
                        providerModel.id.takeIf { it.isNotEmpty() }?.let { "${provider.id}/$it" to providerModel },
                        providerModel.resolvedProviderId?.let { resolvedProvider ->
                            providerModel.id.takeIf { it.isNotEmpty() }?.let { modelId -> "$resolvedProvider/$modelId" to providerModel }
                        }
                    )
                }
            }?.toMap() ?: emptyMap()
            val providerModel = index[key] ?: index.entries
                .filter { it.key.substringAfter('/') == model.modelId }
                .takeIf { it.size == 1 }
                ?.first()
                ?.value
            val limit = providerModel?.limit?.context
                ?: return logContextUsageUnavailable("no context limit for $key; providerModelKeys=${index.keys.take(12)}")
            if (limit <= 0) return logContextUsageUnavailable("non-positive context limit for $key: $limit")
            return ContextUsage(
                percentage = (total.toFloat() / limit.toFloat()).coerceIn(0f, 1f),
                totalTokens = total,
                contextLimit = limit,
                providerId = model.providerId,
                modelId = model.modelId,
                inputTokens = tokens.input,
                outputTokens = tokens.output,
                reasoningTokens = tokens.reasoning,
                cachedReadTokens = tokens.cache?.read,
                cachedWriteTokens = tokens.cache?.write,
                cost = lastAssistant.cost
            )
        }

    private fun logContextUsageUnavailable(reason: String): ContextUsage? {
        runCatching { Log.d("AppState", "contextUsage unavailable: $reason") }
        return null
    }

    private fun tokenTotal(tokens: Message.TokenInfo?): Int? {
        if (tokens == null) return null
        tokens.total?.takeIf { it > 0 }?.let { return it }
        return listOfNotNull(
            tokens.input,
            tokens.output,
            tokens.reasoning,
            tokens.cache?.read,
            tokens.cache?.write
        ).sum().takeIf { it > 0 }
    }
}

@HiltViewModel
@OptIn(FlowPreview::class)
class MainViewModel @Inject constructor(
    internal val repository: OpenCodeRepository,
    internal val settingsManager: SettingsManager,
    private val hostProfileStore: HostProfileStore,
    internal val trafficTracker: TrafficTracker,
    private val appLifecycleMonitor: AppLifecycleMonitor
) : ViewModel(), ForegroundCatchUpCallbacks {

    // §R-17 M2: _state keeps carrying every AppState field (the connection/
    // traffic ones are deprecated mirrors). The authoritative storage for
    // the connection/traffic domains lives in _connectionFlow / _trafficFlow
    // (independent MutableStateFlows so M4 consumers can subscribe to them
    // directly and skip the AppState-level recompositions). `state` stays a
    // synchronous view over `_state` (NOT a combine()/stateIn chain) so that
    // every `_state` / slice write is immediately observable on
    // `state.value` — this preserves the synchronous read-after-write
    // semantics the existing test-suite (and a few non-coroutine call sites)
    // rely on. combine()+stateIn() would propagate changes asynchronously
    // (only advanced on the test dispatcher) and broke ~60 tests that read
    // `state.value` right after `updateState { ... }`. The slices are kept
    // in sync with the mirrors via the [writeConnection] / [writeTraffic]
    // helpers, which mutate both atomically from the caller's thread.
    private val _state = MutableStateFlow(AppState())

    /** §R-17 M2: connection-domain slice (RFC §2.1). Authoritative storage. */
    private val _connectionFlow = MutableStateFlow(ConnectionState())
    /** §R-17 M2: traffic-domain slice (RFC §2.9). Authoritative storage. */
    private val _trafficFlow = MutableStateFlow(TrafficState())
    /** §R-17 M3: composer-domain slice (RFC §2.5). Authoritative storage. */
    private val _composerFlow = MutableStateFlow(ComposerState())
    /** §R-17 M3: file-browser slice (RFC §2.6). Authoritative storage. */
    private val _fileFlow = MutableStateFlow(FileState())
    /** §R-17 M3: settings slice (RFC §2.4, minus cross-domain error). Authoritative storage. */
    private val _settingsFlow = MutableStateFlow(SettingsState())
    /** §R-17 M4: chat slice (RFC §2.2). Authoritative storage. */
    private val _chatFlow = MutableStateFlow(ChatState())
    /** §R-17 M4: session-list slice (RFC §2.3). Authoritative storage. */
    private val _sessionListFlow = MutableStateFlow(SessionListState())
    /** §R-17 M4: unread slice (RFC §2.7). Authoritative storage. */
    private val _unreadFlow = MutableStateFlow(UnreadState())
    /** §R-17 M4: host slice (RFC §2.8). Authoritative storage. */
    private val _hostFlow = MutableStateFlow(HostState())

    /** Read-only connection slice for direct subscription (M4 consumers). */
    val connectionFlow: StateFlow<ConnectionState> = _connectionFlow.asStateFlow()
    /** Read-only traffic slice for direct subscription (M4 consumers). */
    val trafficFlow: StateFlow<TrafficState> = _trafficFlow.asStateFlow()
    /** Read-only composer slice for direct subscription (M4 consumers). */
    val composerFlow: StateFlow<ComposerState> = _composerFlow.asStateFlow()
    /** Read-only file slice for direct subscription (M4 consumers). */
    val fileFlow: StateFlow<FileState> = _fileFlow.asStateFlow()
    /** Read-only settings slice for direct subscription (M4 consumers). */
    val settingsFlow: StateFlow<SettingsState> = _settingsFlow.asStateFlow()
    /** Read-only chat slice for direct subscription (M4 consumers). */
    val chatFlow: StateFlow<ChatState> = _chatFlow.asStateFlow()
    /** Read-only session-list slice for direct subscription (M4 consumers). */
    val sessionListFlow: StateFlow<SessionListState> = _sessionListFlow.asStateFlow()
    /** Read-only unread slice for direct subscription (M4 consumers). */
    val unreadFlow: StateFlow<UnreadState> = _unreadFlow.asStateFlow()
    /** Read-only host slice for direct subscription (M4 consumers). */
    val hostFlow: StateFlow<HostState> = _hostFlow.asStateFlow()

    /**
     * §R-17 Stage 1: bundle of all nine slice flows, passed to free helpers
     * (`launch*` / `handle*` / `applySavedSettings`) so their `updateAndSync`
     * calls keep every slice in sync with the AppState mirror. Built once;
     * the underlying MutableStateFlows are the same `_xxxFlow` fields above.
     */
    private val sliceFlows: SliceFlows = SliceFlows(
        connection = _connectionFlow,
        traffic = _trafficFlow,
        composer = _composerFlow,
        file = _fileFlow,
        settings = _settingsFlow,
        chat = _chatFlow,
        sessionList = _sessionListFlow,
        unread = _unreadFlow,
        host = _hostFlow
    )

    /**
     * R-16 M1: foreground/background three-tier catch-up state machine, extracted
     * from MainViewModel. Owns the 5 previously-@Volatile fields + the tier
     * thresholds; side effects (testConnection / cold-start reload / SSE cancel /
     * draft discard / catch-up probe) flow back through [ForegroundCatchUpCallbacks]
     * which MainViewModel implements below — so the controller never holds a
     * MainViewModel reference (R-16 §7.3 circular-dependency avoidance).
     */
    private val foregroundCatchUpController: ForegroundCatchUpController =
        ForegroundCatchUpController(
            appLifecycleMonitor = appLifecycleMonitor,
            scope = viewModelScope,
            callbacks = this
        )

    val state: StateFlow<AppState> = _state.asStateFlow()

    /**
     * §R-17 M2: write the connection slice atomically and mirror it onto the
     * deprecated AppState fields in `_state`, so `state.value` stays
     * consistent synchronously (RFC §4 strategy A — single-threaded,
     * Main.immediate call sites; no dispatcher batch reliance). ALWAYS use
     * this instead of `_connectionFlow.update { ... }` on its own, otherwise
     * the AppState mirror drifts out of sync until a consumer re-reads the
     * slice directly.
     */
    @Suppress("DEPRECATION")
    private fun writeConnection(transform: (ConnectionState) -> ConnectionState) {
        val next = transform(_connectionFlow.value)
        _connectionFlow.value = next
        _state.value = _state.value.copy(
            isConnected = next.isConnected,
            isConnecting = next.isConnecting,
            serverVersion = next.serverVersion,
            connectionPhase = next.connectionPhase,
            tunnelActivationState = next.tunnelActivationState
        )
    }

    /**
     * §R-17 M2: write the traffic slice atomically and mirror it onto the
     * deprecated AppState fields in `_state`. See [writeConnection].
     */
    @Suppress("DEPRECATION")
    private fun writeTraffic(transform: (TrafficState) -> TrafficState) {
        val next = transform(_trafficFlow.value)
        _trafficFlow.value = next
        _state.value = _state.value.copy(
            trafficSent = next.trafficSent,
            trafficReceived = next.trafficReceived
        )
    }

    /**
     * §R-17 M3: write the composer slice atomically and mirror it onto the
     * deprecated AppState fields in `_state`. See [writeConnection] for the
     * synchronous-mirror rationale (RFC §4 strategy A, RFC §9.2). ALWAYS use
     * this instead of `_composerFlow.update { ... }` on its own.
     */
    @Suppress("DEPRECATION")
    private fun writeComposer(transform: (ComposerState) -> ComposerState) {
        val next = transform(_composerFlow.value)
        _composerFlow.value = next
        _state.value = _state.value.copy(
            inputText = next.inputText,
            imageAttachments = next.imageAttachments,
            sendingSessionIds = next.sendingSessionIds,
            draftWorkdir = next.draftWorkdir
        )
    }

    /**
     * §R-17 M3: write the file slice atomically and mirror it onto the
     * deprecated AppState fields in `_state`. See [writeConnection].
     */
    @Suppress("DEPRECATION")
    private fun writeFile(transform: (FileState) -> FileState) {
        val next = transform(_fileFlow.value)
        _fileFlow.value = next
        _state.value = _state.value.copy(
            filePathToShowInFiles = next.filePathToShowInFiles,
            filePreviewOriginRoute = next.filePreviewOriginRoute,
            fileBrowserOpen = next.fileBrowserOpen,
            fileBrowserWorkdir = next.fileBrowserWorkdir
        )
    }

    /**
     * §R-17 M3: write the settings slice atomically and mirror it onto the
     * deprecated AppState fields in `_state`. `error` is NOT here — it stays
     * on `_state` (cross-domain, RFC §3.3). See [writeConnection].
     */
    @Suppress("DEPRECATION")
    private fun writeSettings(transform: (SettingsState) -> SettingsState) {
        val next = transform(_settingsFlow.value)
        _settingsFlow.value = next
        _state.value = _state.value.copy(
            themeMode = next.themeMode,
            markdownFontSizes = next.markdownFontSizes,
            selectedAgentName = next.selectedAgentName,
            agents = next.agents,
            providers = next.providers,
            availableCommands = next.availableCommands
        )
    }

    /** §R-17 M4: write the chat slice + mirror. See [writeConnection]. `error` stays on `_state`. */
    @Suppress("DEPRECATION")
    private fun writeChat(transform: (ChatState) -> ChatState) {
        val next = transform(_chatFlow.value)
        _chatFlow.value = next
        _state.value = _state.value.copy(
            currentSessionId = next.currentSessionId,
            messages = next.messages,
            partsByMessage = next.partsByMessage,
            streamingPartTexts = next.streamingPartTexts,
            streamingReasoningPart = next.streamingReasoningPart,
            olderMessagesCursor = next.olderMessagesCursor,
            hasMoreMessages = next.hasMoreMessages,
            isLoadingMessages = next.isLoadingMessages,
            gapInfo = next.gapInfo,
            staleNotice = next.staleNotice
        )
    }

    /** §R-17 M4: write the session-list slice + mirror. See [writeConnection]. */
    @Suppress("DEPRECATION")
    private fun writeSessionList(transform: (SessionListState) -> SessionListState) {
        val next = transform(_sessionListFlow.value)
        _sessionListFlow.value = next
        _state.value = _state.value.copy(
            sessions = next.sessions,
            sessionStatuses = next.sessionStatuses,
            expandedSessionIds = next.expandedSessionIds,
            loadedSessionLimit = next.loadedSessionLimit,
            hasMoreSessions = next.hasMoreSessions,
            isLoadingMoreSessions = next.isLoadingMoreSessions,
            isRefreshingSessions = next.isRefreshingSessions,
            pendingPermissions = next.pendingPermissions,
            pendingQuestions = next.pendingQuestions,
            childSessions = next.childSessions,
            directorySessions = next.directorySessions,
            openSessionIds = next.openSessionIds,
            sessionTodos = next.sessionTodos
        )
    }

    /** §R-17 M4: write the unread slice + mirror. See [writeConnection]. */
    @Suppress("DEPRECATION")
    private fun writeUnread(transform: (UnreadState) -> UnreadState) {
        val next = transform(_unreadFlow.value)
        _unreadFlow.value = next
        _state.value = _state.value.copy(
            unreadSessions = next.unreadSessions,
            tempClearedUnread = next.tempClearedUnread,
            lastViewedTime = next.lastViewedTime
        )
    }

    /** §R-17 M4: write the host slice + mirror. See [writeConnection]. */
    @Suppress("DEPRECATION")
    private fun writeHost(transform: (HostState) -> HostState) {
        val next = transform(_hostFlow.value)
        _hostFlow.value = next
        _state.value = _state.value.copy(
            hostProfiles = next.hostProfiles,
            currentHostProfileId = next.currentHostProfileId
        )
    }

    /**
     * §R-17 M4: unified state write that propagates to EVERY slice.
     *
     * Replaces the legacy `updateState { ... }` for the many mixed-domain
     * update sites (chat/session/unread/host and the cross-domain `error`).
     * Writes `_state` (the AppState mirror) then synchronises ALL nine slices
     * from the new mirror values.
     *
     * Isolation still works because `MutableStateFlow` only emits on
     * `!equals` — a slice whose fields did not change is reassigned an
     * structurally-equal value and its subscribers are NOT notified. So a
     * `updateState { it.copy(isLoadingMessages = true) }` only emits on
     * `_chatFlow` (and `_state`); `_sessionListFlow`/`_connectionFlow`/etc.
     * reassigned equal values stay silent. This is what lets the legacy
     * `updateState` sites drive the slices without per-site split-up, while
     * still giving M4 consumers that subscribe to a single slice the same
     * isolation they would get from per-slice writeXxx calls.
     *
     * The M2/M3 per-slice helpers (writeConnection/writeComposer/etc.) remain
     * valid and PREFERRED for sites already using them; `updateState` is the
     * catch-all for the remaining mixed/chat/session/unread/host updates and
     * for any new code. M5 may later split these into per-slice writes for
     * finer granularity, but functionally this is equivalent.
     */
    @Suppress("DEPRECATION")
    private fun updateState(transform: (AppState) -> AppState) {
        val next = transform(_state.value)
        _state.value = next
        // Sync every slice from the new AppState mirror. Each MutableStateFlow
        // suppresses equal-value emissions, so only actually-changed slices
        // notify their subscribers.
        _connectionFlow.value = ConnectionState(
            isConnected = next.isConnected,
            isConnecting = next.isConnecting,
            serverVersion = next.serverVersion,
            connectionPhase = next.connectionPhase,
            tunnelActivationState = next.tunnelActivationState
        )
        _trafficFlow.value = TrafficState(
            trafficSent = next.trafficSent,
            trafficReceived = next.trafficReceived
        )
        _composerFlow.value = ComposerState(
            inputText = next.inputText,
            imageAttachments = next.imageAttachments,
            sendingSessionIds = next.sendingSessionIds,
            draftWorkdir = next.draftWorkdir
        )
        _fileFlow.value = FileState(
            filePathToShowInFiles = next.filePathToShowInFiles,
            filePreviewOriginRoute = next.filePreviewOriginRoute,
            fileBrowserOpen = next.fileBrowserOpen,
            fileBrowserWorkdir = next.fileBrowserWorkdir
        )
        _settingsFlow.value = SettingsState(
            themeMode = next.themeMode,
            markdownFontSizes = next.markdownFontSizes,
            selectedAgentName = next.selectedAgentName,
            agents = next.agents,
            providers = next.providers,
            availableCommands = next.availableCommands
        )
        _chatFlow.value = ChatState(
            currentSessionId = next.currentSessionId,
            messages = next.messages,
            partsByMessage = next.partsByMessage,
            streamingPartTexts = next.streamingPartTexts,
            streamingReasoningPart = next.streamingReasoningPart,
            olderMessagesCursor = next.olderMessagesCursor,
            hasMoreMessages = next.hasMoreMessages,
            isLoadingMessages = next.isLoadingMessages,
            gapInfo = next.gapInfo,
            staleNotice = next.staleNotice
        )
        _sessionListFlow.value = SessionListState(
            sessions = next.sessions,
            sessionStatuses = next.sessionStatuses,
            expandedSessionIds = next.expandedSessionIds,
            loadedSessionLimit = next.loadedSessionLimit,
            hasMoreSessions = next.hasMoreSessions,
            isLoadingMoreSessions = next.isLoadingMoreSessions,
            isRefreshingSessions = next.isRefreshingSessions,
            pendingPermissions = next.pendingPermissions,
            pendingQuestions = next.pendingQuestions,
            childSessions = next.childSessions,
            directorySessions = next.directorySessions,
            openSessionIds = next.openSessionIds,
            sessionTodos = next.sessionTodos
        )
        _unreadFlow.value = UnreadState(
            unreadSessions = next.unreadSessions,
            tempClearedUnread = next.tempClearedUnread,
            lastViewedTime = next.lastViewedTime
        )
        _hostFlow.value = HostState(
            hostProfiles = next.hostProfiles,
            currentHostProfileId = next.currentHostProfileId
        )
    }

    /**
     * Expansion state for the four collapsible card types (ToolCallsRow,
     * ReasoningCard, ToolCard, PatchCard). Kept in a dedicated StateFlow
     * (NOT inside [AppState]) so toggling a card only recomposes the
     * subscribers of this flow (the individual cards), not the whole
     * ChatScreen. Key format: `"${messageId}|${partKey}"` (constructed by
     * the card layer; this VM does not parse keys).
     *
     * Cleared on session switch via [clearExpandedParts] (called from
     * [selectSession]) so switching conversations resets all cards to their
     * default collapsed state. loadMore / loadMessages deliberately do NOT
     * clear it — the user's in-progress expand state must survive history
     * pagination.
     */
    private val _expandedParts = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val expandedParts: StateFlow<Map<String, Boolean>> = _expandedParts.asStateFlow()

    // currentValue is the card's *actually displayed* expansion state
    // (expandedParts[key] ?: per-card-default), supplied by the caller. The
    // previous signature read current[key] ?: false here, which mismatched the
    // cards' display default (running tool default=true): the first tap on a
    // default-expanded card wrote true → no visible change (#13/#16). Passing
    // the displayed value makes the toggle always invert what the user sees.
    fun togglePartExpand(key: String, currentValue: Boolean) {
        _expandedParts.update { current -> current + (key to !currentValue) }
    }

    internal fun clearExpandedParts() {
        _expandedParts.value = emptyMap()
    }

    /**
     * §Per-session message cache (LRU): maps sessionId → the loaded message
     * window ([CachedSessionWindow]) so switching A→B→A restores A's already-
     * loaded history + cursor instantly instead of wiping to empty and re-
     * fetching only the latest 5. Bounded to [SESSION_WINDOW_CACHE_CAPACITY]
     * entries; overflow evicts the least-recently-used. Eviction just means
     * "fall back to cold-load behavior" (empty + resetLimit=true) on next
     * open — no data loss.
     *
     * Main-thread confined: all writers (`launchLoadMessages`,
     * `launchLoadMoreMessages`, the switch-away capture in [selectSession])
     * run on `viewModelScope` (Dispatchers.Main.immediate). LinkedHashMap is
     * not thread-safe but every access here is on the main dispatcher, so no
     * synchronization is needed. `accessOrder = true` makes both `get` and
     * `put` promote the entry to MRU, which is what gives us LRU semantics
     * (restore-on-switch counts as a "use" → cached sessions the user
     * revisits stay warm).
     */
    private val sessionWindowCache: MutableMap<String, CachedSessionWindow> =
        object : LinkedHashMap<String, CachedSessionWindow>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedSessionWindow>?): Boolean =
                size > SESSION_WINDOW_CACHE_CAPACITY
        }

    /** Test-only visibility into the cache size (for assertions). */
    internal fun sessionWindowCacheSize(): Int = sessionWindowCache.size

    /**
     * Test-only visibility: returns the cached window for [sessionId] if any.
     * TRUE read-only — iterates entries instead of `get` so it does NOT
     * promote the entry to most-recently-used (the cache is access-ordered,
     * so a plain `sessionWindowCache[sessionId]` would skew eviction order).
     */
    internal fun peekSessionWindow(sessionId: String): CachedSessionWindow? =
        sessionWindowCache.entries.firstOrNull { it.key == sessionId }?.value

    /**
     * Writes [window] for [sessionId] into the LRU cache (overwriting any
     * prior entry, promoting to MRU). Called from `launchLoadMessages` /
     * `launchLoadMoreMessages` after a successful fetch+merge via the
     * `onCacheWindow` callback.
     */
    private fun writeSessionWindow(sessionId: String, window: CachedSessionWindow) {
        sessionWindowCache[sessionId] = window
    }

    /**
     * Captures the CURRENT message state into the cache under [sessionId].
     * Used by [selectSession] to write-back the outgoing session's latest
     * view before switching away — this is what keeps the cache fresh vs
     * SSE-driven message.updated / streaming mutations that bypassed the
     * load callbacks. Reads only; never mutates [_state].
     */
    private fun captureCurrentSessionWindow(sessionId: String) {
        val current = _state.value
        sessionWindowCache[sessionId] = CachedSessionWindow(
            messages = current.messages,
            partsByMessage = current.partsByMessage,
            olderMessagesCursor = current.olderMessagesCursor,
            hasMoreMessages = current.hasMoreMessages
        )
    }

    /** Drops the entire cache. Called on host switch / host delete (active). */
    private fun clearSessionWindowCache() {
        sessionWindowCache.clear()
    }

    private var sseJob: Job? = null
    private var lastHealthCheckTime = 0L

    // §R-16 M1: the five @Volatile foreground catch-up fields
    // (hasObservedForegroundState / lastLoadAtMs / sseHasConnectedOnce /
    // backgroundedAtMs / suppressNextConnectCatchUp) and the
    // onForegroundChanged three-tier state machine moved to
    // [foregroundCatchUpController]. MainViewModel now delegates via
    // controller.onForegroundChanged / onServerConnected / onHostReconfigured
    // and implements [ForegroundCatchUpCallbacks] for the side effects.

    init {
        loadSettings()
        // §15.2: foreground/background hook now lives in
        // [foregroundCatchUpController] (it subscribes to
        // appLifecycleMonitor.isInForeground in its own init, launched into
        // viewModelScope). §18.1 error-notification mirror stays here.

        // §18.1: mirror state.error into the notification module so it can
        // surface a system notification when we are in the background.
        // distinctUntilChanged() so we only fire on transitions; the monitor
        // applies its own "skip while in foreground" rule. UNDISPATCHED for
        // the same reason as the refresh trigger — we want the subscription
        // established before init returns.
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            _state
                .map { it.error }
                .distinctUntilChanged()
                .collect { appLifecycleMonitor.onAppError(it) }
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // R-16 M1: ForegroundCatchUpCallbacks implementation.
    // The three-tier state machine itself lives in [foregroundCatchUpController];
    // these are the side-effect hooks it calls back into. Each maps 1:1 to the
    // private helper the inlined onForegroundChanged used to call directly.
    // ──────────────────────────────────────────────────────────────────────

    override fun forceReconnect() = testConnection(force = true)

    override fun globalColdStartRefresh(currentId: String) = performGlobalColdStartRefresh(currentId = currentId)

    override fun setStaleNotice() {
        updateState { it.copy(staleNotice = true) }
    }

    override fun clearDraft() = clearDraftIfActive()

    override fun cancelSse() {
        sseJob?.cancel()
        sseJob = null
    }

    override fun catchUpAfterDisconnect(sessionId: String) =
        catchUpAfterDisconnectOrForeground(sessionId)

    override fun currentSessionId(): String? = _state.value.currentSessionId

    /**
     * §Stage D (gpter 阻塞 #1): tear down any in-flight SSE feed BEFORE the
     * repository is reconfigured for a host / profile switch. Without this,
     * the SSE job bound to the PREVIOUS host keeps delivering events into
     * AppState while the new host's health probe is still in flight — those
     * stale events (session/status/message/permission/question) would pollute
     * the freshly-cleared state for the new profile.
     */
    private fun cancelSseForReconfigure() {
        DebugLog.i("SSE", "cancelSse (reconfigure)")
        sseJob?.cancel()
        sseJob = null
        // §Phase1E: a host/profile switch is a fresh server — treat the next
        // connect as a cold start (skip catch-up, the reconfigure path loads
        // sessions/messages itself). R-16 M1: delegated to the controller.
        foregroundCatchUpController.onHostReconfigured()
    }

    /**
     * §18.1 deep-link entry point invoked by [com.yage.opencode_client.MainActivity]
     * when a notification tap carries [MainActivity.EXTRA_SESSION_ID]. If the
     * session is already cached locally we just [selectSession]; otherwise we
     * try a direct `getSession` fetch (works for sub-agent sessions too) and
     * upsert before selecting. Failures are surfaced via [AppState.error]
     * rather than silently dropping the navigation.
     */
    fun openSessionFromDeepLink(sessionId: String) {
        viewModelScope.launch {
            if (_state.value.sessions.none { it.id == sessionId }) {
                val fetched = withContext(Dispatchers.IO) {
                    runSuspendCatching { repository.getSession(sessionId).getOrNull() }.getOrNull()
                }
                if (fetched != null) {
                    updateState { st ->
                        st.copy(sessions = upsertSession(st.sessions, fetched))
                    }
                }
            }
            selectSession(sessionId)
        }
    }

    private fun loadSettings() {
        applySavedSettings(repository, settingsManager, hostProfileStore, _state, _connectionFlow, _settingsFlow, sliceFlows)
    }

    /** Q6: persist the phone pager page the user navigated to, so cold start lands there. */
    fun setLastNavPage(page: Int) {
        val clamped = page.coerceIn(0, 2)
        if (_state.value.lastNavPage == clamped) return
        settingsManager.lastNavPage = clamped
        updateState { it.copy(lastNavPage = clamped) }
    }

    fun configureServer(url: String, username: String? = null, password: String? = null) {
        // §Stage D (gpter 阻塞 #1): cancel any in-flight SSE feed BEFORE
        // repository.configure, otherwise events from the previous
        // credential/host keep landing in AppState during the new probe.
        cancelSseForReconfigure()
        settingsManager.serverUrl = url
        settingsManager.username = username
        settingsManager.password = password
        repository.configure(url, username, password, allowInsecureConnections = currentHostProfile().allowInsecureConnections)
    }

    fun getHostProfiles(): List<HostProfile> = hostProfileStore.profiles()

    fun currentHostProfile(): HostProfile = hostProfileStore.currentProfile()

    /**
     * Persists [profile] and conditionally writes/clears the Basic Auth and
     * tunnel passwords according to the explicit three-state contract:
     *
     *  - [basicAuthEdited] = true  → write [basicAuthPassword] (blank removes
     *    the stored value, matching [SettingsManager.setBasicAuthPassword]'s
     *    blank→remove semantics).
     *  - [basicAuthEdited] = false → skip the Basic Auth branch entirely
     *    (preserve whatever is currently stored). This is the fix for #5:
     *    previously any save with a blank editor password field wiped the
     *    stored credential.
     *  - [tunnelEdited] / [tunnelPassword] follow the same rule for the
     *    tunnel credential.
     *
     * When the editor clears the Basic Auth username ([HostProfile.basicAuth]
     * becomes null) it passes [basicAuthEdited] = true + [basicAuthPassword]
     * = "" so the orphaned password is removed — no special-case branch here.
     *
     * @see SettingsManager.setBasicAuthPassword
     * @see SettingsManager.setTunnelPassword
     */
    fun saveHostProfile(
        profile: HostProfile,
        basicAuthPassword: String = "",
        basicAuthEdited: Boolean = false,
        tunnelPassword: String = "",
        tunnelEdited: Boolean = false
    ) {
        val normalized = if (profile.basicAuth != null) {
            profile.copy(basicAuth = profile.basicAuth.copy(passwordId = profile.id))
        } else {
            profile
        }
        if (basicAuthEdited) {
            settingsManager.setBasicAuthPassword(normalized.id, basicAuthPassword)
        }
        if (tunnelEdited) {
            settingsManager.setTunnelPassword(normalized.id, tunnelPassword)
        }
        // Defense-in-depth (#5): when basicAuth is null on the saved profile
        // and the editor did not explicitly touch the password field, the
        // three-state contract above intentionally leaves any stored password
        // alone. But a profile with no basicAuth config should never retain an
        // orphaned password, so clear it here regardless. (Safe even when no
        // password was stored — setBasicAuthPassword(blank) is a no-op remove.)
        if (normalized.basicAuth == null) {
            settingsManager.setBasicAuthPassword(normalized.id, "")
        }
        hostProfileStore.save(normalized)
        refreshHostProfileState()
    }

    fun selectHostProfile(profileId: String) {
        viewModelScope.launch {
            val profile = hostProfileStore.select(profileId)
            // §Stage D (gpter 阻塞 #1): SSE cancellation is handled
            // inside configureRepositoryForProfile() below (single authoritative
            // point), so it fires before repository.configure runs.
            updateState { it.copy(
                currentSessionId = null,
                messages = emptyList(),
                partsByMessage = emptyMap(),
                sessionStatuses = emptyMap(),
                streamingPartTexts = emptyMap(),
                streamingReasoningPart = null,
                sessionTodos = emptyMap(),
                // Switching host fully resets the per-host session/unread/draft
                // state: the new host's sessions are unrelated to the previous
                // one, so stale open tabs, unread markers and draft workdirs
                // must not leak across hosts. [loadSessions] will repopulate
                // [sessions]/[openSessionIds] for the new host.
                openSessionIds = emptyList(),
                unreadSessions = emptySet(),
                tempClearedUnread = emptySet(),
                lastViewedTime = emptyMap(),
                sessions = emptyList(),
                directorySessions = emptyMap()
                // §R-17 M2: serverVersion moved to writeConnection below.
                // §R-17 M3: draftWorkdir → writeComposer, availableCommands →
                // writeSettings (both reset to defaults, mirrors kept in sync).
            ) }
            // §R-17 M3 (RFC §4 strategy A): reset the composer + settings slices
            // that belong to the previous host. Intermediate state is legal
            // (empty sessions list; draft/commands briefly stale until these
            // run, but updateState above already flipped currentSessionId=null).
            writeComposer { it.copy(draftWorkdir = null) }
            writeSettings { it.copy(availableCommands = emptyList()) }
            // §R-17 M2 (RFC §4 strategy A): clear the (previous host's) probed
            // server version so it is not shown under the new host before the
            // new health check repopulates it (or, if the new connection fails,
            // no stale version lingers). Intermediate state here is legal: an
            // empty `_state` paired with the old host's isConnected/Phase only
            // briefly renders the empty-state spinner before testConnection
            // rewrites the slice.
            writeConnection { it.copy(serverVersion = null) }
            // §Per-session message cache: cached windows belong to the previous
            // host's sessions — they must not be restored onto the new host's
            // unrelated sessions (different server, different message IDs).
            clearSessionWindowCache()
            settingsManager.currentSessionId = null
            settingsManager.openSessionIds = emptyList()
            // Clear the persisted session-metadata cache too: the previous
            // host's sessions would otherwise re-seed AppState.sessions on the
            // next cold start and pollute the new host's tab/title/workdir
            // groups. loadSessions will repopulate the cache for the new host.
            settingsManager.sessionCache = emptyList()
            // §H3: clear the persisted workdir too — a path from host A is
            // meaningless on host B (different machine/filesystem), and leaving
            // it would let configureRepositoryForProfile restore it onto the new
            // host. configureRepositoryForProfile (below) re-scopes to the
            // (now-null) workdir, which is correct for a fresh host.
            settingsManager.currentWorkdir = null
            configureRepositoryForProfile(profile)
            refreshHostProfileState()
            testConnection(force = true)
        }
    }

    fun duplicateHostProfile(profileId: String) {
        hostProfileStore.duplicate(profileId)
        refreshHostProfileState()
    }

    fun deleteHostProfile(profileId: String) {
        // Detect deletion of the ACTIVE host: the new current host is then
        // unrelated, so we must purge all per-host session/workdir state
        // (mirrors selectHostProfile) — otherwise the old host's sessions/tabs/
        // workdir leak into the new host (gpter review MAJOR).
        val wasCurrent = profileId == _state.value.currentHostProfileId
        hostProfileStore.delete(profileId)
        val current = hostProfileStore.currentProfile()
        configureRepositoryForProfile(current)
        refreshHostProfileState()
        if (wasCurrent) {
            updateState {
                it.copy(
                    currentSessionId = null,
                    messages = emptyList(),
                    partsByMessage = emptyMap(),
                    sessionStatuses = emptyMap(),
                    streamingPartTexts = emptyMap(),
                    streamingReasoningPart = null,
                    sessionTodos = emptyMap(),
                    openSessionIds = emptyList(),
                    unreadSessions = emptySet(),
                    tempClearedUnread = emptySet(),
                    lastViewedTime = emptyMap(),
                    sessions = emptyList(),
                    directorySessions = emptyMap()
                    // §R-17 M2: serverVersion moved to writeConnection below.
                    // §R-17 M3: draftWorkdir → writeComposer, availableCommands →
                    // writeSettings (below).
                )
            }
            // §R-17 M3 (RFC §4 strategy A): reset composer + settings slices.
            writeComposer { it.copy(draftWorkdir = null) }
            writeSettings { it.copy(availableCommands = emptyList()) }
            // §R-17 M2 (RFC §4 strategy A): clear the deleted (active) host's
            // probed server version so the replacement host doesn't display a
            // stale version before its own health check (or, on failed
            // reconnect, at all). Intermediate state is legal (see selectHostProfile).
            writeConnection { it.copy(serverVersion = null) }
            // §Per-session message cache: ditto — cached windows belong to the
            // deleted (active) host and must not survive into its replacement.
            clearSessionWindowCache()
            settingsManager.currentSessionId = null
            settingsManager.openSessionIds = emptyList()
            settingsManager.sessionCache = emptyList()
            settingsManager.currentWorkdir = null
            testConnection(force = true)
        }
    }

    fun importHostProfile(payload: String): Result<HostProfile> = runCatching {
        hostProfileStore.importJson(payload).also { refreshHostProfileState() }
    }

    fun exportHostProfile(profile: HostProfile): String = hostProfileStore.exportJson(profile)

    private fun refreshHostProfileState() {
        updateState {
            it.copy(
                hostProfiles = hostProfileStore.profiles(),
                currentHostProfileId = hostProfileStore.currentProfile().id
            )
        }
    }

    private fun configureRepositoryForProfile(profile: HostProfile) {
        // §Stage D (gpter 阻塞 #1, centralized): cancel any in-flight SSE feed
        // BEFORE repository.configure. This is the single authoritative
        // cancellation point for all profile-based reconfigure paths
        // (selectHostProfile / deleteHostProfile / testConnection), so a stale
        // SSE job from the previous host cannot keep delivering events into
        // AppState while the new host's health probe is in flight.
        // configureServer() keeps its own call because it invokes
        // repository.configure(...) directly rather than via this helper.
        // Safe during init: sseJob is null-initialized before the init block
        // runs, and cancel() is null-safe.
        cancelSseForReconfigure()
        val password = profile.basicAuth?.passwordId?.let { settingsManager.basicAuthPassword(it) }
        repository.configure(profile.serverUrl, profile.basicAuth?.username, password, allowInsecureConnections = profile.allowInsecureConnections)
        // §H2: repository.configure() resets currentDirectory to null. Every
        // reconfigure path (testConnection on cold start + each ON_START
        // catch-up, selectHostProfile, deleteHostProfile) flows through here, so
        // without restoring the persisted workdir afterwards, the connected
        // project's directory context is lost and directory-scoped requests
        // (file ops, slash commands) silently fall back to the server's default
        // cwd. selectHostProfile clears currentWorkdir first (H3) so a host
        // switch doesn't carry the previous host's path across.
        settingsManager.currentWorkdir?.let { repository.setCurrentDirectory(it) }
    }

    fun getSavedConnectionSettings(): ConnectionFormSettings = ConnectionFormSettings(
        serverUrl = settingsManager.serverUrl,
        username = settingsManager.username ?: "",
        password = settingsManager.password ?: ""
    )

    fun testConnection(force: Boolean = false, retries: Int = 0) {
        val now = System.currentTimeMillis()
        if (!force && now - lastHealthCheckTime < 30_000) return
        lastHealthCheckTime = now
        viewModelScope.launch {
            // §R-17 M2: error stays on _state (consumed app-wide);
            // connectionPhase/isConnecting moved to _connectionFlow. Atomicity
            // (RFC §4 strategy A): the two updates run back-to-back on the same
            // dispatcher; the intermediate state (error cleared, phase not yet
            // "connecting") is legal and transient.
            updateState { it.copy(error = null) }
            writeConnection { it.copy(isConnecting = true, connectionPhase = "connecting") }
            val profile = hostProfileStore.currentProfile()
            configureRepositoryForProfile(profile)
            // Retry loop: attempt 1 is always made; up to `retries` extra
            // attempts follow on failure/unhealthy with exponential backoff
            // (1s, 2s, 4s, ...). Default callers pass retries=0 (one-shot),
            // preserving the original single-attempt semantics; only
            // coldStartReconnect() opts into retries. isActive is checked so
            // ViewModel cancellation aborts cleanly mid-backoff.
            val maxAttempts = 1 + retries.coerceAtLeast(0)
            var attempt = 0
            var backoffMs = 1000L
            while (isActive) {
                attempt++
                if (attempt > 1) {
                    writeConnection {
                        it.copy(connectionPhase = "reconnecting (attempt $attempt/$maxAttempts)")
                    }
                }
                val healthResult = repository.checkHealth()
                if (healthResult.isSuccess) {
                    val health = healthResult.getOrNull()
                    if (health != null && health.healthy) {
                        writeConnection {
                            it.copy(
                                isConnected = true,
                                serverVersion = health.version,
                                isConnecting = false,
                                connectionPhase = "connected"
                            )
                        }
                        loadInitialData()
                        startSSE()
                        return@launch
                    }
                    // Healthy=false: surface the version if present but keep
                    // retrying (server may still be coming up on cold start).
                    if (health != null) {
                        writeConnection { it.copy(serverVersion = health.version) }
                    }
                }
                if (attempt >= maxAttempts || !isActive) {
                    // §R-17 M2: error on _state; connection fields on
                    // _connectionFlow. Intermediate state legal (error set
                    // before phase flips to "disconnected" — both still
                    // describe the same failure).
                    updateState {
                        it.copy(
                            error = healthResult.exceptionOrNull()?.let { e ->
                                errorMessageOrFallback(e, "Connection failed")
                            }
                        )
                    }
                    writeConnection {
                        it.copy(
                            isConnected = false,
                            isConnecting = false,
                            connectionPhase = "disconnected"
                        )
                    }
                    return@launch
                }
                delay(backoffMs)
                backoffMs *= 2
            }
        }
    }

    /**
     * Cold-start entry point: force a connection check with up to 3 retries
     * (exponential backoff 1s/2s/4s) so a slow-to-wake server (common when
     * the OpenCode server itself is bootstrapping) still comes up instead of
     * stranding the user on the disconnected empty state. Used exclusively
     * from [com.yage.opencode_client.MainActivity]'s cold-start LaunchedEffect.
     */
    fun coldStartReconnect() {
        testConnection(force = true, retries = 3)
    }

    private fun loadInitialData() {
        loadSessions()
        loadAgents()
        loadProviders()
        loadPendingQuestions()
        loadCommands()
        // Re-fetch the directory-scoped sessions for the restored workdir so the
        // connected project's sessions reappear after restart (directorySessions
        // is in-memory and otherwise empty until the user re-connects).
        settingsManager.currentWorkdir?.let { workdir ->
            viewModelScope.launch {
                repository.getSessionsForDirectory(workdir)
                    .onSuccess { sessions ->
                        updateState { it.copy(directorySessions = it.directorySessions + (workdir to sessions)) }
                    }
            }
        }
    }

    /**
     * Best-effort fetch of the server-published slash commands. Merges the
     * server list with a small set of client-side commands (/clear, /compact,
     * /undo, /redo) so the composer's `/`-autocomplete surfaces both. Failures
     * (older servers without GET /command, transient network errors) are
     * swallowed: only the client-side commands remain available.
     */
    private fun loadCommands() {
        viewModelScope.launch {
            repository.getCommands()
                .onSuccess { serverCommands ->
                    writeSettings {
                        it.copy(availableCommands = mergeCommands(localCommands(), serverCommands))
                    }
                }
                .onFailure { error ->
                    reportNonFatalIssue(TAG, "Failed to load commands", error)
                    writeSettings { it.copy(availableCommands = localCommands()) }
                }
        }
    }

    private fun localCommands(): List<CommandInfo> = listOf(
        CommandInfo(name = "clear", description = "Start a new session"),
        CommandInfo(name = "compact", description = "Compact conversation history"),
        CommandInfo(name = "undo", description = "Undo the last change"),
        CommandInfo(name = "redo", description = "Redo the last undone change")
    )

    private fun mergeCommands(
        local: List<CommandInfo>,
        server: List<CommandInfo>
    ): List<CommandInfo> {
        // Server takes precedence on duplicates (its descriptions/hints are
        // authoritative); local commands are appended only when the server did
        // not also expose the same name.
        val serverNames = server.mapTo(mutableSetOf()) { it.name.lowercase(Locale.getDefault()) }
        val localOnly = local.filter { it.name.lowercase() !in serverNames }
        return server + localOnly
    }

    /**
     * Routes a `/command arguments` invocation. Local commands that have
     * client-side semantics are handled here:
     *   - `/clear`            -> start a new session in the current workdir.
     *
     * All other commands (including /compact, /undo, /redo when the server
     * did not publish them, plus every server-defined command such as /init,
     * /review) are forwarded to POST /session/{id}/command. The current
     * workdir is injected by the OkHttp interceptor.
     *
     * [arguments] is the raw text the user typed after the command name. It is
     * packed into the request as a single "text" argument for the server.
     */
    fun executeCommand(command: String, arguments: String) {
        val cmd = command.removePrefix("/").trim().lowercase(Locale.getDefault())
        if (cmd.isEmpty()) return
        when (cmd) {
            "clear" -> {
                setInputText("")
                // Start a new session in the current workdir (or fall back to
                // a host-default createSession when no workdir is bound).
                val workdir = repository.getCurrentDirectory()
                    ?: _state.value.currentSession?.directory
                if (workdir != null) {
                    createSessionInWorkdir(workdir)
                } else {
                    createSession()
                }
            }
            else -> {
                val sessionId = _state.value.currentSessionId ?: run {
                    updateState {
                        it.copy(error = "Open or create a session before running /$cmd")
                    }
                    return
                }
                setInputText("")
                viewModelScope.launch {
                    // §fix(command-400): server 1.17.11 wants `arguments` as the
                    // raw string, not a {"text": ...} object. Pass it directly.
                    repository.executeCommand(sessionId, cmd, arguments)
                        .onFailure { error ->
                            updateState {
                                it.copy(error = errorMessageOrFallback(error, "Command /$cmd failed"))
                            }
                        }
                }
            }
        }
    }

    fun loadSessions() {
        launchLoadSessions(
            scope = viewModelScope,
            repository = repository,
            state = _state,
            settingsManager = settingsManager,
            onSelectSession = ::selectSession,
            onLoadSessionStatus = ::loadSessionStatus,
            onLoadMessages = { sessionId -> loadMessages(sessionId) },
            slices = sliceFlows,
        )
    }

    fun loadMoreSessions() {
        launchLoadMoreSessions(
            scope = viewModelScope,
            repository = repository,
            state = _state,
            onSelectSession = ::selectSession,
            slices = sliceFlows,
        )
    }

    private fun loadSessionStatus() {
        launchLoadSessionStatus(
            scope = viewModelScope,
            repository = repository,
            state = _state,
            slices = sliceFlows,
        )
    }

    fun selectSession(sessionId: String) {
        // Capture the previously-selected session BEFORE selectSessionState
        // overwrites currentSessionId. Used below to decide whether the
        // session the user is leaving should be re-marked unread: if it was
        // temp-cleared (user had viewed it) and is still busy, background
        // activity may still produce output the user cares about — re-mark it.
        val previousSessionId = _state.value.currentSessionId
        val previousWasBusyAndCleared = previousSessionId != null &&
            previousSessionId != sessionId &&
            _state.value.tempClearedUnread.contains(previousSessionId) &&
            _state.value.sessionStatuses[previousSessionId]?.isBusy == true

        // §Per-session message cache (write-back): snapshot the OUTGOING
        // session's currently-loaded view into the LRU before selectSessionState
        // clears it. This is what makes the cache stay fresh vs SSE-driven
        // message.updated / streaming mutations that bypass the load path —
        // any state changes since the last load are captured here, so a return
        // trip restores the up-to-the-second view (then the post-restore tail
        // fetch merges any brand-new messages).
        if (previousSessionId != null && previousSessionId != sessionId) {
            captureCurrentSessionWindow(previousSessionId)
        }

        selectSessionState(_state, settingsManager, sessionId, _composerFlow, sliceFlows)

        // §Per-session message cache (restore): if the new session has a
        // cached window, seed messages/parts/cursor/hasMore from it INSTEAD
        // of leaving the empty list set by selectSessionState. The
        // subsequent loadMessages below uses resetLimit=false on cache hit
        // so the existing §preserveUnfetched merge logic keeps older loaded
        // pages and merges the fresh tail non-destructively. On cache MISS
        // we keep the prior behavior (empty + resetLimit=true cold load).
        val cachedWindow = sessionWindowCache[sessionId]
        if (cachedWindow != null) {
            updateState {
                it.copy(
                    messages = cachedWindow.messages,
                    partsByMessage = cachedWindow.partsByMessage,
                    olderMessagesCursor = cachedWindow.olderMessagesCursor,
                    hasMoreMessages = cachedWindow.hasMoreMessages
                )
            }
        }
        // Look up the target session in the union of the cached sessions list
        // and directorySessions (#10: a session surfaced for a connected
        // workdir may not yet be in the global list) so the parentId check
        // below is unambiguous, and so a directory-only session can be upserted
        // into state.sessions before syncCurrentDirectoryForSession reads it.
        // openSubAgent upserts children into sessions first so this lookup also
        // succeeds for sub-agents.
        val targetSession = (_state.value.sessions + _state.value.directorySessions.values.flatten())
            .firstOrNull { it.id == sessionId }
        // #10: if the session is currently only in directorySessions (not yet
        // in the global sessions list), upsert it now. Without this,
        // currentSession (which finds by id in sessions) would be null and
        // syncCurrentDirectoryForSession would drop the workdir.
        if (targetSession != null && _state.value.sessions.none { it.id == sessionId }) {
            updateState { it.copy(sessions = upsertSession(it.sessions, targetSession)) }
        }
        // Reset the collapsible-card expansion state (#13): switching sessions
        // collapses all cards. Done here (not in selectSessionState / loadMessages)
        // so history pagination (loadMore) preserves the user's in-progress
        // expand state within the same session.
        clearExpandedParts()
        // Sync the repository's workdir context to the selected session so that
        // directory-scoped requests (files, prompt, create) target the right cwd.
        syncCurrentDirectoryForSession(_state, repository, sessionId)
        // §Per-session message cache: resetLimit=false on a cache hit so we
        // don't wipe the messages we just restored — the existing
        // §preserveUnfetched merge keeps older loaded pages and prepends the
        // fresh latest-5 tail. On cache MISS, resetLimit=true cold-loads the
        // latest 5 and seeds the cursor (the legacy behavior).
        loadMessages(sessionId, resetLimit = cachedWindow == null)
        loadSessionStatus()
        loadChildSessions(sessionId)
        // Selecting a concrete session discards any in-progress draft.
        // Update openSessionIds + unread tracking + lastViewedTime. Skip the
        // openSessionIds prepend for sub-agents (parentId != null): they are
        // transient navigations from within a parent conversation and must not
        // pollute the open-tabs list. Sub-agents are only reachable via SubAgentCard.
        val now = System.currentTimeMillis()
        updateState {
            // Re-mark the previous session as unread if it was busy when the
            // user navigated away (its temp-cleared badge should resurface so
            // the user knows there is still in-flight activity to come back to).
            val withReMark = if (previousWasBusyAndCleared) {
                it.copy(unreadSessions = it.unreadSessions + previousSessionId!!)
            } else {
                it
            }
            withReMark.copy(
                unreadSessions = withReMark.unreadSessions - sessionId,
                lastViewedTime = withReMark.lastViewedTime + (sessionId to now),
                // Track the newly-selected session as "temp-cleared" so a
                // subsequent switch away (while busy) or a new out-of-band
                // message can re-mark it. The previous session stays in the
                // set until the server reports it going idle (handled in
                // SyncActions).
                tempClearedUnread = withReMark.tempClearedUnread + sessionId
            )
        }
        // §R-17 M3 (RFC §4 strategy A): draftWorkdir cleared via the composer
        // slice. Selecting a real session discards any in-progress draft.
        // Intermediate state legal (unread updated, draft briefly non-null).
        writeComposer { it.copy(draftWorkdir = null) }
        // Browser-tab semantics: a click on an already-open tab must NOT
        // reorder it (keeps insertion order stable). The prepend only happens
        // when the selected session is NOT yet in the list (e.g. a cold-start
        // restoration of a persisted currentSessionId that has no open tab).
        // New-session prepend (draft-send) is handled separately in sendMessage.
        if (targetSession?.parentId == null && sessionId !in settingsManager.openSessionIds) {
            val updated = (listOf(sessionId) + settingsManager.openSessionIds).take(8)
            settingsManager.openSessionIds = updated
            updateState { it.copy(openSessionIds = updated) }
            // Fix #5: persist the newly-opened session's metadata into
            // sessionCache so its tab survives a restart even when no message
            // was ever sent. The cache is otherwise written only by
            // launchLoadSessions.onSuccess, which does not re-run on a plain
            // open — so without this, the tab's Session metadata was missing
            // on cold start and ChatScreen's mapNotNull dropped the id.
            // The session may currently live only in directorySessions (not yet
            // promoted into the global list), so merge both sources (dedup by
            // id) before persisting. Same plain prefs write as loadSessions.
            val postState = _state.value
            val sourceSessions = (postState.sessions + postState.directorySessions.values.flatten())
                .distinctBy { it.id }
            persistSessionCache(
                settingsManager = settingsManager,
                sessions = sourceSessions,
                openIds = updated,
                currentId = postState.currentSessionId,
                currentWorkdir = settingsManager.currentWorkdir
            )
        }
    }

    /**
     * Fetches the sub-agent (child) sessions for [sessionId] and stores them in
     * [AppState.childSessions]. Best-effort: failures (older servers without the
     * `children` endpoint, test mocks) are swallowed silently — the sub-agent
     * cards simply fall back to the in-stream tool state.
     */
    fun loadChildSessions(sessionId: String) {
        viewModelScope.launch {
            try {
                repository.getChildren(sessionId)
                    .onSuccess { children ->
                        updateState { it.copy(childSessions = it.childSessions + (sessionId to children)) }
                    }
                    .onFailure { error ->
                        reportNonFatalIssue(TAG, "Failed to load child sessions for $sessionId", error)
                    }
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // Swallow: child sessions are a progressive-enhancement feature.
            }
        }
    }

    /**
     * Opens a sub-agent conversation in-place: resolves the child session from
     * any available cache (sessions list, parent's [AppState.childSessions] or
     * a single-shot API fetch), upserts it into the sessions list (so workdir
     * sync + session lookup work), then selects it.
     *
     * Caller passes the child session ID obtained from a `task` part's metadata.
     * Without the upsert, [selectSession] would set `currentSession` to null
     * (the session is not in the cached list), which breaks the parent-back
     * affordance and lets [selectSession]'s openSessionIds branch mistake the
     * unknown session for a root session.
     */
    fun openSubAgent(childSessionId: String) {
        val parentId = _state.value.currentSessionId
        viewModelScope.launch {
            // Try every local cache first; fall back to a single GET so we
            // always have the child session in `state.sessions` before select.
            val child = _state.value.sessions.firstOrNull { it.id == childSessionId }
                ?: parentId?.let { pid -> _state.value.childSessions[pid]?.find { it.id == childSessionId } }
                ?: _state.value.childSessions.values.flatten().firstOrNull { it.id == childSessionId }
                ?: runSuspendCatching { repository.getSession(childSessionId).getOrNull() }.getOrNull()
            // #14: only upsert + select when the child actually resolved.
            // Previously a null child still triggered selectSession(childSessionId),
            // which would set currentSessionId to a non-existent session and
            // leave currentSession null. Surface the failure via the error
            // channel instead (same mechanism as testConnection failures).
            if (child != null) {
                updateState { state -> state.copy(sessions = upsertSession(state.sessions, child)) }
                selectSession(childSessionId)
            } else {
                updateState { it.copy(error = "子任务会话不可用") }
            }
        }
    }

    /**
     * Remove a session from the "open" tabs list (browser-tab close). Only
     * removes it from [AppState.openSessionIds] / [SettingsManager.openSessionIds];
     * the session itself is not deleted from the server. If the closed session
     * was the currently selected one, the next open session (if any) is
     * selected automatically, otherwise the chat view returns to the empty
     * state and the persisted [currentSessionId] is cleared.
     */
    fun closeSession(sessionId: String) {
        val isCurrent = _state.value.currentSessionId == sessionId
        // Preserve the draft of the session being closed. selectSessionState
        // would otherwise mis-target the save once currentSessionId has moved
        // (it reads oldSessionId AFTER we've changed it), writing the closed
        // session's draft into the next session (#10). Save it explicitly here
        // so selectSession(nextId) only restores the next session's own draft.
        if (isCurrent) {
            settingsManager.setDraftText(sessionId, _state.value.inputText)
        }
        val updated = settingsManager.openSessionIds.filter { it != sessionId }
        settingsManager.openSessionIds = updated
        val nextId = updated.firstOrNull()
        updateState { currentState ->
            val next = currentState.copy(
                openSessionIds = updated,
                unreadSessions = currentState.unreadSessions - sessionId
            )
            if (isCurrent && nextId == null) {
                settingsManager.currentSessionId = null
                next.copy(currentSessionId = null, messages = emptyList(), partsByMessage = emptyMap())
            } else {
                // Keep currentSessionId pointing at the closed session for now;
                // selectSession(nextId) below performs the switch (and saves the
                // closed session's draft via the explicit setDraftText above).
                next
            }
        }
        // §R-17 M3 (RFC §4 strategy A): when the last open session is closed,
        // clear the composer inputText via the composer slice. Runs only in the
        // same `isCurrent && nextId == null` branch above; intermediate state
        // legal (inputText briefly retains its value until this line).
        if (isCurrent && nextId == null) {
            writeComposer { it.copy(inputText = "") }
        }
        if (isCurrent && nextId != null) {
            selectSession(nextId)
        }
    }

    fun loadMessages(sessionId: String, resetLimit: Boolean = true) {
        launchLoadMessages(
            scope = viewModelScope,
            repository = repository,
            state = _state,
            settingsFlow = _settingsFlow,
            sessionId = sessionId,
            resetLimit = resetLimit,
            settingsManager = settingsManager,
            onCacheWindow = ::writeSessionWindow,
            slices = sliceFlows,
        )
    }

    /** Load messages with delay when triggered by SSE/send (server may need time to persist). */
    private fun loadMessagesWithRetry(sessionId: String, resetLimit: Boolean = true) {
        launchLoadMessagesWithRetry(viewModelScope, sessionId, _state, resetLimit, ::loadMessages)
    }

    fun loadMoreMessages() {
        val sessionId = _state.value.currentSessionId ?: return
        launchLoadMoreMessages(
            scope = viewModelScope,
            repository = repository,
            state = _state,
            sessionId = sessionId,
            onCacheWindow = ::writeSessionWindow,
            slices = sliceFlows,
        )
    }

    /**
     * §Phase1D manual refresh = GLOBAL cold-start + lazy reload.
     *
     * Drops the entire in-memory message window cache ([sessionWindowCache]),
     * clears streaming overlays + any open gap, then freshly loads the CURRENT
     * session's latest-5 (authoritative snapshot). Other sessions are NOT
     * re-fetched now — they lazy-load via the existing [selectSession]
     * cache-miss path when the user next opens them. This is the user's escape
     * hatch when they distrust the live SSE state: a deliberate "forget
     * everything, start fresh" that only costs traffic for sessions actually
     * revisited.
     */
    fun refreshCurrentSession() {
        val sessionId = _state.value.currentSessionId ?: return
        if (_state.value.isLoadingMessages) return
        performGlobalColdStartRefresh(currentId = sessionId)
    }

    /**
     * Shared global cold-start body used by both [refreshCurrentSession] and
     * the long-absence foreground tier. Clears all per-session message state
     * (cache + current view + gap + streaming) then cold-loads [currentId].
     */
    private fun performGlobalColdStartRefresh(currentId: String) {
        // §Phase1E (glm-1 🟠-2): bail if a message load (e.g. an in-flight
        // loadMore) is running. Without this guard the clear+loadMessages
        // below would wipe messages, then loadMessages would no-op on the
        // shared isLoadingMessages guard, leaving an empty list. The caller
        // (refresh button or >5min tier) is user/initiated and idempotent —
        // skipping here is safe; the banner/action remains for a retry.
        if (_state.value.isLoadingMessages) return
        clearSessionWindowCache()
        updateState {
            it.copy(
                streamingPartTexts = emptyMap(),
                streamingReasoningPart = null,
                gapInfo = null,
                staleNotice = false,
                messages = emptyList(),
                partsByMessage = emptyMap(),
                olderMessagesCursor = null,
                hasMoreMessages = true
            )
        }
        loadMessages(currentId, resetLimit = true)
    }

    /**
     * §Phase1B/1C catch-up wrapper: probe the server's newest message id and
     * only reload the 5-message tail if it changed; on reload, detect whether
     * a gap (断层) opened and surface it via [AppState.gapInfo]. Used on SSE
     * reconnect (not the first connect) and the 15s–5min foreground tier.
     */
    private fun catchUpAfterDisconnectOrForeground(sessionId: String) {
        launchCatchUp(
            scope = viewModelScope,
            repository = repository,
            state = _state,
            settingsFlow = _settingsFlow,
            sessionId = sessionId,
            settingsManager = settingsManager,
            onCacheWindow = ::writeSessionWindow,
            slices = sliceFlows,
        )
    }

    /**
     * §Phase1C user-triggered gap closure: pages older from the gap boundary
     * until the pre-reload newest id reappears (gap bridged) or history is
     * exhausted. Bound to the gap divider's tap action.
     */
    fun closeGap() {
        val sessionId = _state.value.currentSessionId ?: return
        launchCloseGap(
            scope = viewModelScope,
            repository = repository,
            state = _state,
            sessionId = sessionId,
            onCacheWindow = ::writeSessionWindow,
            slices = sliceFlows,
        )
    }

    private fun loadAgents() {
        viewModelScope.launch {
            repository.getAgents()
                .onSuccess { agents ->
                    val currentAgent = _settingsFlow.value.selectedAgentName
                    val validAgent = if (agents.none { it.name == currentAgent }) {
                        "build"
                    } else {
                        currentAgent
                    }
                    writeSettings { it.copy(agents = agents, selectedAgentName = validAgent) }
                    if (validAgent != currentAgent) {
                        settingsManager.selectedAgentName = validAgent
                    }
                }
                .onFailure { error ->
                    reportNonFatalIssue(TAG, "Failed to load agents", error)
                }
        }
    }

    private fun loadProviders() {
        launchLoadProviders(viewModelScope, repository, _state, _settingsFlow) { message, error ->
            reportNonFatalIssue(TAG, message, error)
        }
    }

    fun createSession(title: String? = null) {
        launchCreateSession(viewModelScope, repository, _state, title, ::selectSession, sliceFlows)
    }

    /**
     * Deferred ("draft") session creation: sets the repository's working
     * directory and enters a draft state ([AppState.draftWorkdir]) without
     * issuing POST /session. The session is created lazily by [sendMessage]
     * on the first user prompt. If the user navigates away (selects another
     * session or switches host), the draft is discarded.
     */
    fun createSessionInWorkdir(workdir: String) {
        repository.setCurrentDirectory(workdir)
        // Clear any previously selected session: draft mode shows an empty
        // chat page until the first message materialises the session.
        settingsManager.currentSessionId = null
        updateState {
            it.copy(
                currentSessionId = null,
                messages = emptyList(),
                partsByMessage = emptyMap(),
                sessionTodos = emptyMap(),
                streamingPartTexts = emptyMap(),
                streamingReasoningPart = null
            )
        }
        // §R-17 M3 (RFC §4 strategy A): composer slice — enter draft mode
        // (draftWorkdir=workdir) and reset input/attachments. Intermediate state
        // legal (chat fields cleared above; composer flipped below).
        writeComposer {
            it.copy(
                inputText = "",
                imageAttachments = emptyList(),
                draftWorkdir = workdir
            )
        }
        // Persist the connected workdir so a restart re-scopes the repository
        // to this project (currentDirectory is otherwise in-memory only).
        settingsManager.currentWorkdir = workdir
        // Best-effort: fetch the existing root sessions for this workdir (#10)
        // so the user can discover / resume prior conversations in the project
        // they just connected. Stored in [AppState.directorySessions] (a map
        // keyed by workdir), separate from the global sessions list, so the
        // periodic global getSessions refresh does not discard it. Failures
        // (older servers, transient network) are silently ignored.
        viewModelScope.launch {
            repository.getSessionsForDirectory(workdir)
                .onSuccess { sessions ->
                    updateState { it.copy(directorySessions = it.directorySessions + (workdir to sessions)) }
                }
        }
    }

    /**
     * Discard an in-progress draft session (deferred-create mode entered via
     * [createSessionInWorkdir]) whenever the user leaves the Chat screen or
     * the app goes to background. No-op when there is no active draft, so it
     * is safe to call from any navigation / lifecycle hook without disturbing
     * real (already-created) sessions.
     *
     * The guard mirrors [sendMessage]'s draft branch precondition:
     * `draftWorkdir != null && currentSessionId == null`. Once the draft has
     * materialised into a real session (first send), `draftWorkdir` is null
     * and this call does nothing.
     */
    fun clearDraftIfActive() {
        val current = _state.value
        if (current.draftWorkdir == null || current.currentSessionId != null) return
        // Clear the persisted workdir too so a discarded draft doesn't leave
        // the repository re-scoped to an abandoned project on resume/cold start
        // (createSessionInWorkdir is the only writer of currentWorkdir). Real
        // sessions are unaffected — the guard above is a no-op for them.
        settingsManager.currentWorkdir = null
        writeComposer {
            it.copy(
                draftWorkdir = null,
                inputText = "",
                imageAttachments = emptyList()
            )
        }
    }

    fun forkSession(sessionId: String, messageId: String?) {
        launchForkSession(viewModelScope, repository, _state, sessionId, messageId, ::selectSession, sliceFlows)
    }

    fun archiveSession(sessionId: String) {
        launchSetSessionArchived(viewModelScope, repository, _state, sessionId, archived = true, sliceFlows)
    }

    fun restoreSession(sessionId: String) {
        launchSetSessionArchived(viewModelScope, repository, _state, sessionId, archived = false, sliceFlows)
    }

    fun deleteSession(sessionId: String) {
        launchDeleteSession(viewModelScope, repository, _state, settingsManager, sessionId, ::selectSession, sliceFlows)
    }

    fun sendMessage() {
        val draftWorkdir = _state.value.draftWorkdir
        val existingSessionId = _state.value.currentSessionId
        val text = _state.value.inputText.trim()
        val attachments = _state.value.imageAttachments
        if (text.isEmpty() && attachments.isEmpty()) return

        // Draft mode: lazily create the session on the first send. The draft
        // is tied to the workdir set by createSessionInWorkdir; we materialise
        // the session via POST /session, switch currentSessionId to it, clear
        // the draft, prepend to openSessionIds, then dispatch the message.
        if (draftWorkdir != null && existingSessionId == null) {
            // #10: synchronously clear draftWorkdir BEFORE launching the
            // network call so a double-tap (race) cannot enter this branch a
            // second time and create duplicate sessions. The local draftWorkdir
            // captured above is used inside the coroutine. A second invoke now
            // reads draftWorkdir=null → existingSessionId=null → early return.
            writeComposer { it.copy(draftWorkdir = null) }
            viewModelScope.launch {
                repository.createSession(title = null)
                    .onSuccess { session ->
                        updateState { state ->
                            val openIds = (listOf(session.id) + settingsManager.openSessionIds).distinct().take(8)
                            settingsManager.openSessionIds = openIds
                            val now = System.currentTimeMillis()
                            state.copy(
                                sessions = upsertSession(state.sessions, session),
                                currentSessionId = session.id,
                                openSessionIds = openIds,
                                unreadSessions = state.unreadSessions - session.id,
                                lastViewedTime = state.lastViewedTime + (session.id to now)
                            )
                        }
                        // §R-17 M3: draftWorkdir mirror was cleared above; keep
                        // the slice consistent (no-op semantically, but avoids
                        // slice/mirror drift if the slice was touched elsewhere).
                        writeComposer { it.copy(draftWorkdir = null) }
                        settingsManager.currentSessionId = session.id
                        // Fix #5: persist the freshly-created session's metadata
                        // into sessionCache so its tab survives restart (mirrors
                        // selectSession). The upsert above already added `session`
                        // to state.sessions; loadSessions will later refresh the
                        // cache authoritatively, but this guarantees the tab's
                        // metadata is durable immediately after creation.
                        val postState = _state.value
                        persistSessionCache(
                            settingsManager = settingsManager,
                            sessions = postState.sessions,
                            openIds = postState.openSessionIds,
                            currentId = session.id,
                            currentWorkdir = settingsManager.currentWorkdir
                        )
                        dispatchSendMessage(session.id)
                    }
                    .onFailure { error ->
                        // §R-17 M3: draftWorkdir → writeComposer, error → _state.
                        // Restore draft mode so the user can retry. draftWorkdir
                        // was synchronously cleared before launch to guard
                        // against double-tap; on failure we put it back (the
                        // captured local above) so the composer stays in draft
                        // mode and the untouched inputText remains editable. A
                        // retry is a fresh invoke that re-clears + re-launches.
                        writeComposer { it.copy(draftWorkdir = draftWorkdir) }
                        updateState {
                            it.copy(
                                error = "Failed to create session in $draftWorkdir: ${error.message ?: "unknown error"}"
                            )
                        }
                    }
            }
            return
        }

        val sessionId = existingSessionId ?: return
        if (_state.value.sendingSessionIds.contains(sessionId)) return
        dispatchSendMessage(sessionId)
    }

    private fun dispatchSendMessage(sessionId: String) {
        // §R-17 M3: read composer slice directly (authoritative; the AppState
        // mirror is kept in sync by writeComposer but reading the slice avoids
        // the @Deprecated mirror warning inside the VM).
        val composer = _composerFlow.value
        if (composer.sendingSessionIds.contains(sessionId)) return
        val text = composer.inputText.trim()
        val attachments = composer.imageAttachments
        if (text.isEmpty() && attachments.isEmpty()) return

        writeComposer { state -> state.copy(sendingSessionIds = state.sendingSessionIds + sessionId) }

        // §append-safe (glmer MAJOR-1): clear the composer synchronously on
        // dispatch so a follow-up typed during the in-flight prompt_async
        // window is not wiped by a late onSuccess. The captured `text` is
        // restored on failure (in launchSendMessage) only if the user has not
        // typed something new in the meantime.
        settingsManager.setDraftText(sessionId, "")
        writeComposer { it.copy(inputText = "") }

        val agent = _settingsFlow.value.selectedAgentName
        val model: Message.ModelInfo? = null
        val currentSession = _state.value.currentSession

        fun dispatchSend() {
            launchSendMessage(
                scope = viewModelScope,
                repository = repository,
                state = _state,
                composerFlow = _composerFlow,
                sessionId = sessionId,
                text = text,
                attachments = attachments,
                agent = agent,
                model = model,
                onRefreshMessages = ::loadMessagesWithRetry,
                onRefreshSessions = ::loadSessions,
                onSuccess = {
                    settingsManager.setDraftText(sessionId, "")
                    writeComposer { it.copy(imageAttachments = emptyList()) }
                },
                onComplete = {
                    writeComposer { state -> state.copy(sendingSessionIds = state.sendingSessionIds - sessionId) }
                },
                slices = sliceFlows,
            )
        }

        if (currentSession?.isArchived == true) {
            viewModelScope.launch {
                repository.updateSessionArchived(sessionId, -1L)
                    .onSuccess { updated ->
                        updateState { state ->
                            state.copy(sessions = state.sessions.map { session -> if (session.id == sessionId) updated else session })
                        }
                        dispatchSend()
                    }
                    .onFailure { error ->
                        // §append-safe (gpter MAJOR): the composer was cleared
                        // synchronously at dispatch and sendingSessionIds was
                        // locked. If the pre-send unarchive fails, NO send is
                        // dispatched, so mirror the send-failure cleanup: unlock,
                        // restore the captured prompt (only if the user hasn't
                        // typed something new), and restore the draft — otherwise
                        // the session would be permanently send-locked and the
                        // text lost.
                        // §R-17 M3: read composer slice for the restore-decision;
                        // dispatch error → _state, composer fields → writeComposer.
                        val currentInput = _composerFlow.value.inputText
                        val restored = if (currentInput.isBlank()) text else currentInput
                        if (restored != currentInput) settingsManager.setDraftText(sessionId, restored)
                        updateState { s ->
                            s.copy(
                                error = "Failed to restore session: ${errorMessageOrFallback(error, "unknown error")}"
                            )
                        }
                        writeComposer { c ->
                            c.copy(
                                sendingSessionIds = c.sendingSessionIds - sessionId,
                                inputText = restored
                            )
                        }
                    }
            }
            return
        }

        dispatchSend()
    }

    fun abortSession() {
        val sessionId = _state.value.currentSessionId ?: return
        viewModelScope.launch {
            repository.abortSession(sessionId)
                .onFailure { error ->
                    updateState { it.copy(error = errorMessageOrFallback(error, "Failed to abort session")) }
                }
        }
    }

    fun setInputText(text: String) {
        writeComposer { it.copy(inputText = text) }
        _state.value.currentSessionId?.let { settingsManager.setDraftText(it, text) }
    }

    fun addImageAttachments(attachments: List<ComposerImageAttachment>) {
        if (attachments.isEmpty()) return
        writeComposer { state ->
            state.copy(imageAttachments = (state.imageAttachments + attachments).take(4))
        }
    }

    fun removeImageAttachment(id: String) {
        writeComposer { state ->
            state.copy(imageAttachments = state.imageAttachments.filterNot { it.id == id })
        }
    }

    fun editFromMessage(messageId: String) {
        val sessionId = _state.value.currentSessionId ?: return
        val message = _state.value.messages.firstOrNull { it.id == messageId && it.isUser } ?: return
        val draft = (_state.value.partsByMessage[messageId] ?: emptyList()).firstOrNull { it.isText }?.text?.trim().orEmpty()
        if (draft.isBlank()) return

        viewModelScope.launch {
            repository.revertSession(sessionId, messageId)
                .onSuccess { updatedSession ->
                    // §R-17 M3: sessions/error → _state; inputText/imageAttachments
                    // → writeComposer. Intermediate state legal (sessions updated,
                    // composer not yet reset).
                    updateState { state ->
                        state.copy(
                            sessions = state.sessions.map { session -> if (session.id == sessionId) updatedSession else session },
                            error = null
                        )
                    }
                    writeComposer { c ->
                        c.copy(
                            inputText = draft,
                            imageAttachments = emptyList()
                        )
                    }
                    settingsManager.setDraftText(sessionId, draft)
                    loadMessages(sessionId)
                    loadSessions()
                }
                .onFailure { error ->
                    updateState { it.copy(error = "Failed to edit message: ${errorMessageOrFallback(error, "unknown error")}") }
                }
        }
    }

    fun selectAgent(agentName: String) {
        settingsManager.selectedAgentName = agentName
        writeSettings { it.copy(selectedAgentName = agentName) }
        _state.value.currentSessionId?.let { settingsManager.setAgentForSession(it, agentName) }
    }

    fun toggleSessionExpanded(sessionId: String) {
        updateState { state ->
            val next = if (state.expandedSessionIds.contains(sessionId)) {
                state.expandedSessionIds - sessionId
            } else {
                state.expandedSessionIds + sessionId
            }
            state.copy(expandedSessionIds = next)
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        settingsManager.themeMode = mode
        writeSettings { it.copy(themeMode = mode) }
    }

    fun setMarkdownFontSizes(sizes: MarkdownFontSizes) {
        settingsManager.markdownFontSizes = sizes
        writeSettings { it.copy(markdownFontSizes = sizes) }
    }

    fun respondPermission(sessionId: String, permissionId: String, response: PermissionResponse) {
        viewModelScope.launch {
            repository.respondPermission(sessionId, permissionId, response)
                .onSuccess {
                    updateState { it.copy(
                        pendingPermissions = it.pendingPermissions.filter { p -> p.id != permissionId }
                    )}
                }
                .onFailure { error ->
                    updateState { it.copy(error = errorMessageOrFallback(error, "Failed to respond to permission")) }
                }
        }
    }

    fun loadPendingPermissions() {
        viewModelScope.launch {
            repository.getPendingPermissions()
                .onSuccess { permissions ->
                    updateState { it.copy(pendingPermissions = permissions) }
                }
                .onFailure { error ->
                    Log.w(TAG, "Failed to load permissions: ${error.message}")
                }
        }
    }

    fun loadPendingQuestions() {
        viewModelScope.launch {
            repository.getPendingQuestions()
                .onSuccess { questions ->
                    updateState { it.copy(pendingQuestions = questions) }
                }
                .onFailure { error ->
                    Log.w(TAG, "Failed to load questions: ${error.message}")
                }
        }
    }

    fun replyQuestion(requestId: String, answers: List<List<String>>, onError: () -> Unit = {}) {
        viewModelScope.launch {
            repository.replyQuestion(requestId, answers)
                .onSuccess {
                    updateState { currentState ->
                        currentState.copy(pendingQuestions = currentState.pendingQuestions.filter { it.id != requestId })
                    }
                }
                .onFailure { error ->
                    Log.w(TAG, "Failed to reply question: ${error.message}")
                    onError()
                }
        }
    }

    fun rejectQuestion(requestId: String) {
        viewModelScope.launch {
            repository.rejectQuestion(requestId)
                .onSuccess {
                    updateState { currentState ->
                        currentState.copy(pendingQuestions = currentState.pendingQuestions.filter { it.id != requestId })
                    }
                }
                .onFailure { error ->
                    Log.w(TAG, "Failed to reject question: ${error.message}")
                }
        }
    }

    fun clearError() {
        updateState { it.copy(error = null) }
    }

    /**
     * Mirrors the latest [TrafficTracker] totals into [_trafficFlow] so the
     * Settings page (and any other consumer) renders current figures. Called
     * on-demand (e.g. when Settings opens) rather than on every request, to
     * avoid global recomposition per network call — the counter itself
     * accumulates continuously in the background.
     *
     * §R-17 M2: writes the dedicated [_trafficFlow] slice instead of AppState.
     */
    fun refreshTrafficStats() {
        writeTraffic {
            it.copy(
                trafficSent = trafficTracker.totalBytesSent,
                trafficReceived = trafficTracker.totalBytesReceived
            )
        }
    }

    /** Zeros the cumulative traffic counters (in-memory + persisted). */
    fun resetTrafficStats() {
        trafficTracker.reset()
        refreshTrafficStats()
    }

    /**
     * Hard reset of ALL local data, then reconnect + re-fetch from the server.
     *
     * Wipes everything persisted by [SettingsManager] EXCEPT server connection
     * info (server URL, basic-auth username/password, host profiles, current
     * host id) and tunnel passwords — see [SettingsManager.clearAllLocalData]
     * for the exact preservation contract. Then resets the in-memory
     * [AppState] to a clean slate, preserving only the host-profile list +
     * current id so the UI stays in a "reconnecting" state against the SAME
     * server (no silent host switch), tears down in-flight SSE, and finally
     * reconnects via [coldStartReconnect] which re-runs [loadInitialData] on a
     * healthy connection.
     *
     * After this: theme/font revert to defaults, open tabs / session cache /
     * drafts / current session+workdir are cleared, traffic counters are zeroed.
     * The connected-project directory sessions are NOT re-fetched here
     * (currentWorkdir is now null) — the user re-connects projects via the
     * Sessions screen; the global server session list still loads fresh.
     */
    fun resetLocalDataAndResync() {
        // 1. Wipe persisted local data (preserves connection + tunnel creds).
        settingsManager.clearAllLocalData()
        // 2. Zero the in-memory traffic tracker so its stale cumulative total
        //    is not re-persisted on the next counted request (the persisted
        //    traffic keys were already removed by clearAllLocalData above).
        trafficTracker.reset()
        // 3. Drop the per-session message-window cache (it belongs to the
        //    wiped sessions and must not be restored onto fresh ones).
        clearSessionWindowCache()
        // 4. Tear down any in-flight SSE feed + reset reconnect flags so the
        //    upcoming reconnect is treated as a genuine cold start (mirrors
        //    selectHostProfile / reconfigure). Safe: cancel() is null-safe.
        cancelSseForReconfigure()
        // 5. Reset in-memory AppState to defaults, preserving only the host
        //    profile list + current id. AppState() defaults clear: sessions,
        //    directorySessions, messages, partsByMessage, openSessionIds,
        //    currentSessionId, currentWorkdir, sessionTodos, childSessions,
        //    drafts (inputText), unread/tempCleared, draftWorkdir, themeMode,
        //    markdownFontSizes, selectedAgentName, traffic stats, streaming
        //    state, file browser state, etc.
        val keptHostProfiles = _state.value.hostProfiles
        val keptHostProfileId = _state.value.currentHostProfileId
        @Suppress("DEPRECATION")
        updateState {
            // §R-17 M2: connection/traffic fields left at their AppState
            // defaults — they are deprecated mirrors and the combine() in
            // `state` overwrites them from _connectionFlow / _trafficFlow
            // (reset just below). Keeping them out of the constructor call
            // avoids touching deprecated parameters.
            AppState(
                hostProfiles = keptHostProfiles,
                currentHostProfileId = keptHostProfileId
            )
        }
        // §R-17 M2: reset the connection + traffic slices to match the
        // "isConnecting / reconnecting, traffic zeroed" semantics the old
        // monolithic AppState() constructor provided. Atomicity (RFC §4
        // strategy A): each intermediate state is legal (empty sessions list
        // with a "reconnecting" badge). trafficTracker.reset() already ran
        // above, so mirroring 0/0 here keeps the slice consistent with the
        // tracker (otherwise combine would keep showing the stale pre-reset
        // totals until the next refreshTrafficStats call — a behaviour
        // regression vs. the old constructor-default zeroing).
        writeConnection {
            ConnectionState(isConnecting = true, connectionPhase = "reconnecting")
        }
        writeTraffic { TrafficState() }
        // §R-17 M3: also reset the composer/file/settings slices — the old
        // AppState() constructor zeroed their mirror fields, so without these
        // the slices would keep their pre-reset values (input text, file
        // browser, agent/theme/commands) while the mirrors flipped to defaults,
        // i.e. slice/mirror drift. Intermediate state legal (mirrors already
        // defaulted above; slices reset here).
        writeComposer { ComposerState() }
        writeFile { FileState() }
        writeSettings { SettingsState() }
        // 6. Reconnect to the (preserved) current host profile and re-fetch.
        //    coldStartReconnect → testConnection(force=true, retries=3); on
        //    health success it calls configureRepositoryForProfile (currentWorkdir
        //    is null so no project is re-scoped) → loadInitialData + startSSE.
        coldStartReconnect()
    }

    fun activateTunnelForCurrentHost() {
        val profile = hostProfileStore.currentProfile()
        // The tunnel password is POSTed to the opencode server URL itself (the
        // server acts as the tunnel gateway). Surface WHY activation can't
        // proceed instead of silently returning — otherwise the user taps
        // "Activate Tunnel" and nothing happens with no clue.
        val passwordId = profile.tunnelPasswordId
        if (passwordId == null) {
            // §R-17 M2: tunnelActivationState moved to _connectionFlow; error
            // stays on _state. Intermediate state legal.
            updateState {
                it.copy(error = "隧道激活失败：未设置隧道认证密码。请在「服务器」设置中填写隧道密码并保存后再试。")
            }
            writeConnection {
                it.copy(tunnelActivationState = TunnelActivationState.Error("未设置隧道密码"))
            }
            return
        }
        val password = settingsManager.getTunnelPassword(passwordId)
        if (password.isNullOrBlank()) {
            updateState {
                it.copy(error = "隧道激活失败：已配置密码标识但存储为空（可能保存时未输入）。请重新输入隧道密码并保存。")
            }
            writeConnection {
                it.copy(tunnelActivationState = TunnelActivationState.Error("隧道密码为空"))
            }
            return
        }

        writeConnection { it.copy(tunnelActivationState = TunnelActivationState.Loading) }
        viewModelScope.launch {
            repository.activateTunnel(profile.serverUrl, password, allowInsecure = profile.allowInsecureConnections)
                .onSuccess {
                    updateState {
                        it.copy(error = TUNNEL_SUCCESS_TOAST)
                    }
                    writeConnection {
                        it.copy(tunnelActivationState = TunnelActivationState.Success)
                    }
                    Log.d(TAG, "Tunnel activated successfully for ${profile.serverUrl}")
                }
                .onFailure { error ->
                    // The repository now enriches the exception with type+message
                    // (e.g. "HTTP 401: ...", "UnknownHostException: ...",
                    // "SSLPeerUnverifiedException: ..."). Surface that directly —
                    // no generic prefix that would double up the fallback string.
                    val msg = errorMessageOrFallback(error, "未知错误（无异常信息）")
                    updateState {
                        it.copy(error = "隧道激活失败：$msg")
                    }
                    writeConnection {
                        it.copy(tunnelActivationState = TunnelActivationState.Error(msg))
                    }
                    Log.e(TAG, "Tunnel activation failed", error)
                }
        }
    }

    fun showFileInFiles(path: String, originRoute: String? = null) {
        writeFile { it.copy(filePathToShowInFiles = path, filePreviewOriginRoute = originRoute) }
    }

    fun clearFileToShow() {
        writeFile { it.copy(filePathToShowInFiles = null, filePreviewOriginRoute = null) }
    }

    /**
     * Bug3: open the file browser for a specific connected project (workdir)
     * from the Sessions screen. Scopes the repository to [workdir] (so
     * getFileTree/getFileContent list that project) WITHOUT the side effects of
     * [createSessionInWorkdir] (no currentSessionId/draft/currentWorkdir reset),
     * then seeds the preview path. The Sessions screen toggles its own overlay.
     *
     * Directory coupling (gpter review): the file-content API has no per-call
     * directory variant, so the browse must use the GLOBAL repository
     * currentDirectory. To avoid desyncing an open Chat session (project A) when
     * browsing project B, we SAVE the pre-browse directory here and RESTORE it
     * in [restoreDirectoryAfterBrowse] when the overlay closes. The Sessions
     * file overlay is full-screen, so Chat is not interactable during the browse
     * (no concurrent access); on close the global directory is restored to A.
     */
    private var browseSavedDirectory: String? = null
    private var browseActive: Boolean = false

    fun browseFilesInWorkdir(workdir: String) {
        if (!browseActive) {
            browseSavedDirectory = repository.getCurrentDirectory()
            browseActive = true
        }
        repository.setCurrentDirectory(workdir)
        writeFile {
            it.copy(
                // Fix #4: null here so FilesViewModel.syncPathToShow(null, ...)
                // calls closePreview() and the normal loadFiles("") flow (scoped
                // by the X-Opencode-Directory header set above) renders the
                // clickable FileBrowserPane. Setting it to `workdir` misrouted
                // the directory through the file-preview pipeline and rendered a
                // plain-text directory dump instead of the clickable list.
                filePathToShowInFiles = null,
                filePreviewOriginRoute = "sessions",
                fileBrowserOpen = true,
                fileBrowserWorkdir = workdir
            )
        }
    }

    /** Close the project file browser: restore the global currentDirectory to
     *  its pre-browse value and hide the overlay. Called from PhoneLayout's
     *  overlay close (onCloseFile / BackHandler). */
    fun closeFileBrowser() {
        if (browseActive) {
            repository.setCurrentDirectory(browseSavedDirectory)
            browseSavedDirectory = null
            browseActive = false
        }
        writeFile {
            it.copy(
                fileBrowserOpen = false,
                fileBrowserWorkdir = null,
                filePathToShowInFiles = null,
                filePreviewOriginRoute = null
            )
        }
    }

    /**
     * On-demand re-fetch of a connected project's directory-scoped sessions.
     *
     * [loadInitialData] only refreshes [AppState.directorySessions] for the
     * *current* workdir, so a non-current connected project's session list goes
     * stale. Combined with missed `session.created` SSE events while the phone
     * was backgrounded, a conversation created on the web would not appear
     * under its project until a full reconnect. The Sessions screen calls this
     * when the user EXPANDS a workdir group, so the freshest server list is
     * rendered. Fire-and-forget: on success it REPLACES the workdir's entry in
     * [AppState.directorySessions] (same semantics as loadInitialData's
     * directory fetch); on failure it silently keeps the existing list
     * (onSuccess only) — acceptable for a user-initiated refresh.
     */
    fun refreshDirectorySessions(workdir: String) {
        if (workdir.isBlank()) return
        viewModelScope.launch {
            repository.getSessionsForDirectory(workdir)
                .onSuccess { sessions ->
                    updateState { it.copy(directorySessions = it.directorySessions + (workdir to sessions)) }
                }
        }
    }

    private fun startSSE() {
        DebugLog.i("SSE", "startSSE")
        sseJob?.cancel()
        sseJob = launchSseCollection(viewModelScope, repository, _state, ::handleSSEEvent)
    }

    private fun handleSSEEvent(event: SSEEvent) {
        // §Phase1E: every (re)connect's first frame is `server.connected`.
        // Catch-up runs on every connect EXCEPT the very first process-time
        // connect (cold start has no local history). The three-tier suppress /
        // sseHasConnectedOnce state machine now lives in
        // [foregroundCatchUpController] (R-16 M1); it calls back into
        // [catchUpAfterDisconnectOrForeground] via [ForegroundCatchUpCallbacks]
        // when a probe is actually warranted.
        if (event.payload.type == "server.connected") {
            foregroundCatchUpController.onServerConnected()
        }
        handleIncomingSseEvent(
            state = _state,
            event = event,
            onRefreshMessages = ::loadMessagesWithRetry,
            onRefreshSessions = ::loadSessions,
            onLoadPendingPermissions = ::loadPendingPermissions,
            onNonFatalIssue = { message -> reportNonFatalIssue(TAG, message) },
            slices = sliceFlows,
        )
    }

    override fun onCleared() {
        sseJob?.cancel()
        super.onCleared()
    }

    private companion object {
        private const val TAG = "MainViewModel"

        // R-16 M1: FOREGROUND_RELOAD_MIN_INTERVAL_MS (15s) and
        // LONG_ABSENCE_THRESHOLD_MS (5min) moved to
        // ForegroundCatchUpController.companion.


        /**
         * §Per-session message cache capacity: max number of session windows
         * kept in memory. ~12 covers the typical "open tabs" working set
         * ([SettingsManager.openSessionIds] is itself capped at 8) plus a
         * few recently-evicted tabs. Overflow evicts LRU; the evicted
         * session simply cold-loads on next open (current pre-cache behavior).
         */
        private const val SESSION_WINDOW_CACHE_CAPACITY = 12
    }
}
