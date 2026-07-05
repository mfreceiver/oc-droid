package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.data.model.AgentInfo
import cn.vectory.ocdroid.data.model.ComposerImageAttachment
import cn.vectory.ocdroid.data.api.CommandInfo
import cn.vectory.ocdroid.data.model.HostProfile
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.model.PermissionRequest
import cn.vectory.ocdroid.data.model.ProvidersResponse
import cn.vectory.ocdroid.data.model.QuestionRequest
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.data.model.TodoItem
import cn.vectory.ocdroid.util.MarkdownFontSizes
import cn.vectory.ocdroid.util.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow

sealed class TunnelActivationState {
    data object Idle : TunnelActivationState()
    data object Loading : TunnelActivationState()
    data object Success : TunnelActivationState()
    data class Error(val message: String) : TunnelActivationState()
}

/**
 * §R-17 batch2: connection-domain state slice. Authoritative storage; no
 * AppState mirror. Field set strictly follows RFC R-17 §2.1.
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
 * §R-17 batch2: composer-domain state slice. Authoritative storage; writes
 * via _composerFlow.update. Field set strictly follows RFC R-17 §2.5.
 *
 * This is the highest-frequency slice (`inputText` mutates on every keystroke)
 * and the primary reason the slice exists: consumers subscribe to
 * `composerFlow` directly, so keystrokes no longer recompose ChatTopBar.
 *
 * Write atomicity (RFC §4 strategy A): same model as [ConnectionState] —
 * every mutation goes through a single `writeComposer { ... }`. No dispatcher
 * batch reliance (RFC §9.2).
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
 * §R-17 batch2: settings/global-preference state slice. Authoritative storage
 * via _settingsFlow.update. Field set strictly follows RFC R-17 §2.4 (error
 * is NOT here — it is a one-shot UiEvent on _uiEvents).
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
    val availableCommands: List<CommandInfo> = emptyList(),
    /**
     * §model-selection: per-baseUrl disabled-model entries (format
     * `"$providerId/$modelId"`), projected from
     * [cn.vectory.ocdroid.util.SettingsManager.getDisabledModels] for the
     * current host. Used by Settings → Model management and the chat
     * quick-switch picker to hide unchecked models.
     */
    val disabledModels: Set<String> = emptySet(),
    /**
     * §ui-scale: user-adjustable UI scale factors (M3 LocalDensity override
     * pattern). [uiFontScale] multiplies fontScale (text only);
     * [uiContentScale] multiplies density (dp dimensions + sp text together).
     * Both default 1.0; clamped to SettingsManager.UI_SCALE_MIN–MAX. Seeded
     * from SettingsManager on connect; persisted via the setters in
     * MainViewModel. Read by MainActivity → OpenCodeTheme → LocalDensity.
     */
    val uiFontScale: Float = 1f,
    val uiContentScale: Float = 1f
)

/**
 * §R-17 batch2: chat-domain state slice (RFC §2.2). Authoritative storage via
 * _chatFlow.update. The highest-frequency domain (SSE streaming deltas mutate
 * streamingPartTexts/messages many times per second). §R-17 batch2: error/success
 * events migrated to SharedFlow<UiEvent>.
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
    val staleNotice: Boolean = false,
    /**
     * §model-selection (V1-per-prompt): the intended-next-model for the active
     * session — sent with the next outgoing prompt's PromptRequest.model.
     * Surfaced in the chat top-bar context menu + the model picker dialog.
     * Updated synchronously by `switchSessionModel` (local persist + state write)
     * and after each message load. The per-session stored value (persisted via
     * [cn.vectory.ocdroid.util.SettingsManager.getModelForSession]) wins over
     * inference; inference from the latest assistant message's
     * [Message.resolvedModel] is the fallback for sessions first opened on
     * another client (no local stored choice yet).
     */
     val currentModel: Message.ModelInfo? = null,
     /**
      * §compact: true while a context compaction is in progress for the active
      * session. Set by [MainViewModel.compactSession], cleared when the session
      * transitions from busy → idle (compaction done) or on immediate failure.
      * While true, the compacting capsule is shown (no abort button) and chat
      * input is disabled.
      */
     val isCompacting: Boolean = false,
     /** §compact: System.currentTimeMillis when compaction started, for the
      * capsule timer and the idle-clear guard floor. */
     val compactStartedAt: Long = 0L,
     /**
     * §3-scroll-memory: monotonically incremented by
     * [MainViewModel.performGlobalColdStartRefresh] so the ChatScreen layer
     * observes a change and clears its hoisted per-session scroll-position
     * cache. Only consumed by ChatScreen via
     * [MainViewModel.chatFlow]; follows the same write-only-to-slice
     * pattern as [isCompacting] / [compactStartedAt].
     */
     val refreshNonce: Long = 0L,
     /**
      * §R-17 batch5: SSE delta coalescing buffers. Moved out of
      * [cn.vectory.ocdroid.ui.controller.SessionSyncCoordinator]'s private
      * mutableMapOf hidden state so the coalesce-window state is observable
      * (e.g. an idle reload can detect "deltas still buffered" before deciding
      * the overlay is empty).
      *
      * - [deltaBuffer]: accumulated delta text per partId (APPEND semantics;
      *   the previous StringBuilder → String conversion makes each entry
      *   immutable so CAS `update { }` is safe).
      * - [fullTextBuffer]: latest authoritative full text per partId (REPLACE
      *   semantics; fullText supersedes any concurrent delta accumulation).
      * - [pendingFlushPartIds]: partIds whose DELTA_COALESCE_MS flush window
      *   is still open. The actual `Job` references stay on the coordinator
      *   (a Job is neither serializable nor a value type — it is tied to the
      *   coordinator's CoroutineScope); this set is the observable mirror.
      */
     val deltaBuffer: Map<String, String> = emptyMap(),
     val fullTextBuffer: Map<String, String> = emptyMap(),
     val pendingFlushPartIds: Set<String> = emptySet()
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

/**
 * §R-17 batch2: navigation-domain state slice. Replaces the former
 * AppState.lastNavPage. Source of truth is SettingsManager (persisted); this
 * slice is the in-memory view. Not part of the 9 SliceFlows bundle.
 */
data class NavState(val lastNavPage: Int = 0)

/**
 * §R-17 Stage 1 (consumer-migration enabler): container holding all nine slice
 * `MutableStateFlow`s so that free helpers (`launch*` / `handle*`) can write
 * EVERY slice directly — without each helper taking nine separate flow params.
 * Built once in MainViewModel and passed (as `slices`) to every free helper.
 *
 * §R-17 batch2 step e final: the slice↔AppState sync helpers
 * (`aggregateFromSlices` / `syncSlicesFromAppState` / `updateAndSync` /
 * `applyComposerSlice` / `applySettingsSlice`) that used to live alongside
 * this data class were removed — the slices are now the sole authoritative
 * store.
 *
 * §R-17 batch2: container holding all nine slice MutableStateFlows. Passed to
 * Actions free functions and controllers. All writes via .update { } (CAS)
 * MUST run on Dispatchers.Main.immediate (caller convention) to preserve
 * cross-slice consistency within a single frame.
 */
data class SliceFlows(
    val connection: MutableStateFlow<ConnectionState>,
    val traffic: MutableStateFlow<TrafficState>,
    val composer: MutableStateFlow<ComposerState>,
    val file: MutableStateFlow<FileState>,
    val settings: MutableStateFlow<SettingsState>,
    val chat: MutableStateFlow<ChatState>,
    val sessionList: MutableStateFlow<SessionListState>,
    val unread: MutableStateFlow<UnreadState>,
    val host: MutableStateFlow<HostState>
)
