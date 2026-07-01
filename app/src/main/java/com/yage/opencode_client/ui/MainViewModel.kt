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
import com.yage.opencode_client.ui.controller.ComposerCallbacks
import com.yage.opencode_client.ui.controller.ComposerController
import com.yage.opencode_client.ui.controller.ConnectionCoordinator
import com.yage.opencode_client.ui.controller.ConnectionCoordinatorCallbacks
import com.yage.opencode_client.ui.controller.ForegroundCatchUpCallbacks
import com.yage.opencode_client.ui.controller.ForegroundCatchUpController
import com.yage.opencode_client.ui.controller.HostProfileCallbacks
import com.yage.opencode_client.ui.controller.HostProfileController
import com.yage.opencode_client.ui.controller.LowTrafficPoller
import com.yage.opencode_client.ui.controller.LowTrafficPollerCallbacks
import com.yage.opencode_client.ui.controller.SessionSyncCoordinator
import com.yage.opencode_client.ui.controller.SessionSyncCoordinatorCallbacks
import com.yage.opencode_client.ui.controller.SessionSwitcher
import com.yage.opencode_client.ui.controller.SessionSwitcherCallbacks
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
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
)

@HiltViewModel
@OptIn(FlowPreview::class)
class MainViewModel @Inject constructor(
    internal val repository: OpenCodeRepository,
    internal val settingsManager: SettingsManager,
    private val hostProfileStore: HostProfileStore,
    internal val trafficTracker: TrafficTracker,
    private val appLifecycleMonitor: AppLifecycleMonitor
) : ViewModel(), ForegroundCatchUpCallbacks, ComposerCallbacks, SessionSwitcherCallbacks, HostProfileCallbacks, ConnectionCoordinatorCallbacks, SessionSyncCoordinatorCallbacks, LowTrafficPollerCallbacks {

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
     *
     * Moved up from its previous position (was after _state builder) so it is
     * initialized before [composerController] which receives it by reference
     * (R-16 M2).
     */
    private val _expandedParts = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val expandedParts: StateFlow<Map<String, Boolean>> = _expandedParts.asStateFlow()
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
            callbacks = this,
            // R2: 省流守卫 — 省流模式下让位给 lowTrafficPoller (§5.1)。
            isLowTrafficMode = { settingsManager.lowTrafficMode }
        )

    /**
     * M1 (docs/省流模式设计.md §1): 省流模式轮询状态机。仅当
     * [SettingsManager.lowTrafficMode] 开启时激活（[onLowTrafficModeChanged]
     * 调 [LowTrafficPoller.start]/[LowTrafficPoller.stop]）。构造即订阅
     * [AppLifecycleMonitor.isInForeground]，但 active=false 时所有回调 no-op。
     * 与 [foregroundCatchUpController] 互斥并行（§5.1）：省流模式 SSE 未启，
     * ForegroundCatchUpController 的回调天然 no-op，前台同步由本 poller 独占。
     * Side effects (catchUp / stale hint / permission-question 补足) flow back
     * through [LowTrafficPollerCallbacks] which MainViewModel implements below.
     */
    private val lowTrafficPoller: LowTrafficPoller =
        LowTrafficPoller(
            scope = viewModelScope,
            state = _state,
            slices = sliceFlows,
            repository = repository,
            settingsManager = settingsManager,
            appLifecycleMonitor = appLifecycleMonitor,
            callbacks = this
        )

    /**
     * R-16 M2: composer-domain state mutation controller. Owns the write path
     * for inputText / imageAttachments / draftWorkdir on [_composerFlow], and
     * for collapsible-card expansion state on [_expandedParts]. Side effects
     * (SettingsManager draft/workdir persistence) flow through
     * [ComposerCallbacks] which MainViewModel implements below.
     */
    private val composerController: ComposerController =
        ComposerController(
            state = _state,
            composerFlow = _composerFlow,
            chatFlow = _chatFlow,
            expandedParts = _expandedParts,
            callbacks = this
        )

    /**
     * R-16 M2b: session-switching state machine controller. Owns the
     * [selectSession] flow (8 steps) + the per-session message-window LRU
     * cache. Side effects (SettingsManager draft/openSessionIds persistence,
     * repository directory sync / message / status / child-session loads,
     * sessionCache persistence) flow through [SessionSwitcherCallbacks] which
     * MainViewModel implements below.
     */
    private val sessionSwitcher: SessionSwitcher =
        SessionSwitcher(
            state = _state,
            composerFlow = _composerFlow,
            expandedParts = _expandedParts,
            slices = sliceFlows,
            callbacks = this
        )

    /**
     * R-16 M3: Host Profile CRUD + repository reconfiguration + tunnel
     * activation + full local-data reset controller. Owns all host-profile
     * management logic (selectHostProfile / deleteHostProfile / saveHostProfile
     * / configureServer / configureRepositoryForProfile /
     * activateTunnelForCurrentHost / resetLocalDataAndResync). Side effects
     * (SSE cancel/reconnect, testConnection, sessionWindowCache clear,
     * trafficTracker reset) flow through [HostProfileCallbacks] which
     * MainViewModel implements below.
     */
    private val hostProfileController: HostProfileController =
        HostProfileController(
            scope = viewModelScope,
            state = _state,
            slices = sliceFlows,
            hostProfileStore = hostProfileStore,
            repository = repository,
            settingsManager = settingsManager,
            callbacks = this
        )

    /**
     * R-16 M4: server connection lifecycle controller. Owns the SSE feed Job +
     * the 30s health-check throttle + the testConnection/coldStartReconnect
     * state machine + initial-data load orchestration + startSSE/cancelSse.
     * Side effects (repository reconfigure, initial-data loaders, SSE event
     * dispatch → SessionSyncCoordinator, catch-up reset) flow through
     * [ConnectionCoordinatorCallbacks] which MainViewModel implements below.
     */
    private val connectionCoordinator: ConnectionCoordinator =
        ConnectionCoordinator(
            scope = viewModelScope,
            state = _state,
            connectionFlow = _connectionFlow,
            settingsFlow = _settingsFlow,
            slices = sliceFlows,
            repository = repository,
            settingsManager = settingsManager,
            callbacks = this
        )

    /**
     * R-16 M4: SSE event → AppState fold controller. Owns the handleSSEEvent
     * dispatch (server.connected catch-up trigger + the message/session/status/
     * part/permission/question/todo fold). Side effects (authoritative reload,
     * permission refresh, catch-up probe, non-fatal logging) flow through
     * [SessionSyncCoordinatorCallbacks] which MainViewModel implements below.
     */
    private val sessionSyncCoordinator: SessionSyncCoordinator =
        SessionSyncCoordinator(
            scope = viewModelScope,
            state = _state,
            slices = sliceFlows,
            callbacks = this
        )

    val state: StateFlow<AppState> = _state.asStateFlow()

    /**
     * §R-17 M2→M5: write the connection slice AND mirror it onto AppState.
     * The slice is the authoritative read path (all consumers read slices); the
     * mirror write is retained so the free helpers in MainViewModelSessionActions
     * that still read `state.value.<field>` (and CatchUpGapTest) see consistent
     * values without a stale window. A follow-up that migrates those reads to
     * slices can drop the mirror write.
     *
     * §R-17 M5.1 (kimo 🟠#2) THREAD-SAFETY: the slice-write + AppState-mirror-write
     * is only consistent because every call site runs on `Dispatchers.Main.immediate`
     * (single-threaded). The same constraint applies to every `writeXxx` helper
     * (writeTraffic/writeComposer/writeFile/writeSettings/writeChat/writeSessionList/
     * writeUnread/writeHost — "See [writeConnection]") and to [updateState] /
     * `updateAndSync`. MUST be invoked on `Dispatchers.Main.immediate`; a
     * background-thread call would let a concurrent writer interleave between the
     * slice write and the mirror write, breaking slice↔mirror consistency.
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

    /** §R-17 M2→M5: write the traffic slice + mirror. See [writeConnection]. */
    @Suppress("DEPRECATION")
    private fun writeTraffic(transform: (TrafficState) -> TrafficState) {
        val next = transform(_trafficFlow.value)
        _trafficFlow.value = next
        _state.value = _state.value.copy(trafficSent = next.trafficSent, trafficReceived = next.trafficReceived)
    }

    /** §R-17 M3→M5: write the composer slice + mirror. See [writeConnection]. */
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

    /** §R-17 M3→M5: write the file slice + mirror. See [writeConnection]. */
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

    /** §R-17 M3→M5: write the settings slice + mirror. `error` stays on AppState. */
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

    /** §R-17 M4→M5: write the chat slice + mirror. See [writeConnection]. */
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

    /** §R-17 M4→M5: write the session-list slice + mirror. See [writeConnection]. */
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

    /** §R-17 M4→M5: write the unread slice + mirror. See [writeConnection]. */
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

    /** §R-17 M4→M5: write the host slice + mirror. See [writeConnection]. */
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
     * §R-17 M4→M5: unified state write that propagates to EVERY slice.
     *
     * Catch-all for the many mixed-domain update sites
     * (chat/session/unread/host and the cross-domain `error`). Builds a
     * TRANSIENT [AppState] aggregate from the authoritative slice values
     * (see [aggregateFromSlices]), applies the legacy `{ it.copy(...) }`
     * transform against it, then writes the result back to `_state` AND
     * re-syncs every slice from it via [syncSlicesFromAppState]. `_state` thus
     * stays a synchronised mirror of the slices (retained because the free
     * helpers in MainViewModelSessionActions still read `state.value.<field>`,
     * and CatchUpGapTest's null-slices path — see M5.2 tech debt).
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
     * §R-17 M5.1 (kimo 🟠#2) THREAD-SAFETY: the aggregate→transform→write→sync
     * sequence is NOT atomic. It is only safe because every call site runs on
     * `Dispatchers.Main.immediate` (single-threaded). Calling this (or
     * [writeConnection]/[writeComposer]/.../`updateAndSync`) from a background
     * thread would let a concurrent writer interleave between the aggregate
     * read and the slice/mirror write, breaking slice↔mirror consistency. MUST
     * be invoked on `Dispatchers.Main.immediate`.
     */
    @Suppress("DEPRECATION")
    private fun updateState(transform: (AppState) -> AppState) {
        val before = aggregateFromSlices(_state.value)
        val after = transform(before)
        _state.value = after
        syncSlicesFromAppState(after, sliceFlows)
    }

    /**
     * §R-17 M5→M5.1: builds a transient [AppState] snapshot from the authoritative
     * slice values, carrying over the persisted [AppState.error] /
     * [AppState.lastNavPage] from [seed]. Used by [updateState] so the legacy
     * `(AppState) -> AppState` transforms keep compiling/working against the
     * slice-derived values without each call site needing a per-slice rewrite.
     *
     * §R-17 M5.1 (glmer 🟠#2): delegates to the single field list in the
     * top-level [com.yage.opencode_client.ui.aggregateFromSlices] (the same one
     * the free helpers' `updateAndSync` uses), so the slice→AppState mapping
     * lives in exactly one place instead of being hand-synced here.
     */
    private fun aggregateFromSlices(seed: AppState): AppState =
        aggregateFromSlices(sliceFlows, seed)

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

    // R-16 M2: delegated to [composerController].
    fun togglePartExpand(key: String, currentValue: Boolean) {
        composerController.togglePartExpand(key, currentValue)
    }

    // R-16 M2: delegated to [composerController].
    internal fun clearExpandedParts() {
        composerController.clearExpandedParts()
    }

    // R-16 M2b: sessionWindowCache + all cache helpers (captureCurrentSessionWindow,
    // writeSessionWindow, peekSessionWindow, clearSessionWindowCache,
    // sessionWindowCacheSize) moved to [sessionSwitcher]. The thin wrappers
    // below delegate to the controller so existing callers (loadMessages,
    // loadMoreMessages, catchUp, closeGap via onCacheWindow; host switch /
    // delete / reset via clearSessionWindowCache; tests via peekSessionWindow /
    // sessionWindowCacheSize) keep working unchanged.

    /** Test-only visibility into the cache size (for assertions). */
    internal fun sessionWindowCacheSize(): Int = sessionSwitcher.sessionWindowCacheSize()

    /**
     * Test-only visibility: returns the cached window for [sessionId] if any.
     * Delegates to [sessionSwitcher] — TRUE read-only (does NOT promote to MRU).
     */
    internal fun peekSessionWindow(sessionId: String): CachedSessionWindow? =
        sessionSwitcher.peekSessionWindow(sessionId)

    /**
     * Writes [window] for [sessionId] into the LRU cache. Called from
     * launchLoadMessages / launchLoadMoreMessages via the `onCacheWindow` callback.
     */
    private fun writeSessionWindow(sessionId: String, window: CachedSessionWindow) {
        sessionSwitcher.writeSessionWindow(sessionId, window)
    }

    /** Drops the entire cache. Called on host switch / host delete / reset. */
    override fun clearSessionWindowCache() {
        sessionSwitcher.clearSessionWindowCache()
    }

    // §R-16 M4: sseJob + lastHealthCheckTime moved to [connectionCoordinator].
    // The SSE feed Job and the 30s health-check throttle anchor are now owned
    // by the connection lifecycle controller; MainViewModel delegates via
    // connectionCoordinator.startSSE / cancelSse / cancelSseForReconfigure /
    // testConnection / coldStartReconnect.

    // §R-16 M1: the five @Volatile foreground catch-up fields
    // (hasObservedForegroundState / lastLoadAtMs / sseHasConnectedOnce /
    // backgroundedAtMs / suppressNextConnectCatchUp) and the
    // onForegroundChanged three-tier state machine moved to
    // [foregroundCatchUpController]. MainViewModel now delegates via
    // controller.onForegroundChanged / onServerConnected / onHostReconfigured
    // and implements [ForegroundCatchUpCallbacks] for the side effects.

    init {
        loadSettings()
        // §F1 (3/3 评审一致确认致命): 持久化 lowTrafficMode=true 后进程重建冷启动时，
        // M3 守卫跳过 SSE、M1 又从未 start → 界面静止。loadSettings 读取最新持久化
        // 值后，若省流模式开启则立即激活 M1 轮询。start() 自身幂等；foreground 守卫
        // 由 LowTrafficPoller 内部处理。
        if (settingsManager.lowTrafficMode) lowTrafficPoller.start()
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

    override fun forceReconnect() = connectionCoordinator.testConnection(force = true)

    override fun globalColdStartRefresh(currentId: String) = performGlobalColdStartRefresh(currentId = currentId)

    override fun setStaleNotice() {
        updateState { it.copy(staleNotice = true) }
    }

    override fun clearDraft() = clearDraftIfActive()

    // R-16 M4: SSE feed cancellation delegated to [connectionCoordinator]
    // (the sseJob now lives there). M5 跟进 (§4.2): also drop pending delta
    // buffers — a stopped SSE feed won't deliver the part.updated that would
    // have flushed them, so leaving them buffered risks a stale flush on the
    // next connect. clearDeltaBuffers is idempotent.
    override fun cancelSse() {
        connectionCoordinator.cancelSse()
        sessionSyncCoordinator.clearDeltaBuffers()
    }

    override fun catchUpAfterDisconnect(sessionId: String) =
        catchUpAfterDisconnectOrForeground(sessionId)

    override fun currentSessionId(): String? = _chatFlow.value.currentSessionId

    // ──────────────────────────────────────────────────────────────────────
    // M1 (docs/省流模式设计.md §1): LowTrafficPollerCallbacks implementation.
    // The poller state machine lives in [lowTrafficPoller]; these are the
    // side-effect hooks it calls back into. triggerCatchUp 复用现有
    // catchUpAfterDisconnectOrForeground (= launchCatchUp, M2 已 sentinel=4)，
    // 不重写 reload 逻辑。permission/question 复用现有 pending* 写入模式。
    // ──────────────────────────────────────────────────────────────────────

    override fun triggerCatchUp(sessionId: String) {
        // §D (kimo 重要): triggerCatchUp 是恢复信号 (active→idle / probe hit /
        // foreground return / session switch 都会触发) → 清除 onRetryError 设的
        // retry error。瞬态：若仍异常，下次 retry ≥3 tick 会重新设。
        clearTransientLowTrafficError(retryOnly = true)
        catchUpAfterDisconnectOrForeground(sessionId)
    }

    override fun onStaleHint() {
        // §1.5: 复用 staleNotice banner ("省流模式下可能不是最新，点击检查"
        // 由 ChatScreen 渲染 — 现有 staleNotice UI 文案在省流模式下语义一致)。
        updateState { it.copy(staleNotice = true) }
    }

    override fun onRetryError(sessionId: String) {
        // §1.2 (gpter 致命#3): retry 持续 ≥3 tick → 顶部提示"运行出错"。
        // 复用 error 通道 (瞬态，下次 triggerCatchUp / onConnectionRecovered 由
        // §D clearTransientLowTrafficError 清除)。
        updateState { it.copy(error = RETRY_ERROR_MSG) }
    }

    override fun onConnectionError() {
        // §1.1 B4: status 连续失败 ≥3 → 连接错误态。复用 error 通道；
        // onConnectionRecovered 在下一次成功 tick 清除。
        updateState { it.copy(error = "省流模式：连接服务器失败，正在重试") }
    }

    override fun onConnectionRecovered() {
        // §D (kimo 重要): 恢复连接 → 清除省流模式设的所有瞬态 error (连接错误 +
        // retry 错误)。仅当确实处于任一瞬态时才写，避免无谓 state 抖动。
        clearTransientLowTrafficError(retryOnly = false)
    }

    /**
     * §D (kimo 重要): 清除省流模式写过的瞬态 error 字符串。
     *  - `retryOnly = true`: 只清 onRetryError 设的 retry error (triggerCatchUp 路径)。
     *  - `retryOnly = false`: 同时清 onConnectionError 设的连接 error (onConnectionRecovered)。
     * 复用现有"按字符串前缀匹配"的 error 通道（与 onConnectionRecovered 旧实现一致）。
     */
    private fun clearTransientLowTrafficError(retryOnly: Boolean) {
        val current = _state.value.error ?: return
        val isRetryError = current == RETRY_ERROR_MSG
        val isConnectionError = current?.startsWith("省流模式：连接") == true
        val shouldClear = if (retryOnly) isRetryError else (isRetryError || isConnectionError)
        if (shouldClear) {
            updateState { it.copy(error = null) }
        }
    }

    override fun onPendingPermissionsLoaded(list: List<com.yage.opencode_client.data.model.PermissionRequest>) {
        // 复用 loadPendingPermissions 的写入模式（dser 🔴-3 前台补足）。
        @Suppress("DEPRECATION")
        updateState { it.copy(pendingPermissions = list) }
    }

    override fun onPendingQuestionsLoaded(list: List<com.yage.opencode_client.data.model.QuestionRequest>) {
        @Suppress("DEPRECATION")
        updateState { it.copy(pendingQuestions = list) }
    }

    // ──────────────────────────────────────────────────────────────────────
    // R-16 M2: ComposerCallbacks implementation.
    // The composer state mutation logic lives in [composerController]; these
    // are the side-effect hooks it calls back into for SettingsManager writes.
    // ──────────────────────────────────────────────────────────────────────

    override fun saveDraft(sessionId: String, text: String) {
        settingsManager.setDraftText(sessionId, text)
    }

    override fun clearPersistedWorkdir() {
        settingsManager.currentWorkdir = null
    }

    // ──────────────────────────────────────────────────────────────────────
    // R-16 M2b: SessionSwitcherCallbacks implementation.
    // The session-switch flow lives in [sessionSwitcher.switchTo]; these are
    // the side-effect hooks it calls back into for SettingsManager / repository
    // / persistence operations. Each maps 1:1 to the original inline call in
    // the pre-extraction selectSession.
    // ──────────────────────────────────────────────────────────────────────

    // Note: saveDraft(sessionId, text) is shared with ComposerCallbacks —
    // both interfaces declare the same method, so a single override satisfies both.

    override fun getDraft(sessionId: String): String = settingsManager.getDraftText(sessionId)

    override fun setCurrentSessionId(sessionId: String?) {
        settingsManager.currentSessionId = sessionId
    }

    override fun setOpenSessionIds(ids: List<String>) {
        settingsManager.openSessionIds = ids
    }

    override fun persistSessionCache(sessions: List<Session>, openIds: List<String>, currentId: String?) {
        persistSessionCache(
            settingsManager = settingsManager,
            sessions = sessions,
            openIds = openIds,
            currentId = currentId,
            currentWorkdir = settingsManager.currentWorkdir
        )
    }

    override fun syncCurrentDirectory(directory: String?) {
        repository.setCurrentDirectory(directory)
    }

    // M5 跟进 (§4.2): SessionSwitcher calls this when LEAVING the outgoing
    // session so its pending delta flush jobs can't write into the new
    // session's state. Delegates to SessionSyncCoordinator.clearDeltaBuffers
    // (idempotent: cancels all flushJobs + clears deltaBuffer).
    override fun onClearDeltaBuffers() = sessionSyncCoordinator.clearDeltaBuffers()

    // Note: loadChildSessions, loadMessages, and loadSessionStatus are
    // implemented as override methods on the existing MainViewModel functions
    // (see their declarations below) — adding `override` to the existing
    // method satisfies the interface without creating a separate delegation.

    /**
     * §Stage D (gpter 阻塞 #1): tear down any in-flight SSE feed BEFORE the
     * repository is reconfigured for a host / profile switch. Without this,
     * the SSE job bound to the PREVIOUS host keeps delivering events into
     * AppState while the new host's health probe is still in flight — those
     * stale events (session/status/message/permission/question) would pollute
     * the freshly-cleared state for the new profile.
     *
     * R-16 M4: delegated to [connectionCoordinator] (cancels the SSE feed +
     * resets the catch-up state machine via the coordinator's callbacks, which
     * route back to foregroundCatchUpController.onHostReconfigured).
     */
    override fun cancelSseForReconfigure() = connectionCoordinator.cancelSseForReconfigure()

    // ──────────────────────────────────────────────────────────────────────
    // R-16 M3: HostProfileCallbacks implementation.
    // The host-profile management logic lives in [hostProfileController];
    // these are the orchestration hooks it calls back into for SSE lifecycle,
    // connection testing, and cross-controller coordination.
    //
    // Several of these (cancelSseForReconfigure, startSse, coldStartReconnect,
    // loadInitialData, clearSessionWindowCache) were previously private/internal
    // methods on MainViewModel and are now `override` (public) to satisfy the
    // interface. forceReconnect is shared with ForegroundCatchUpCallbacks.
    // ──────────────────────────────────────────────────────────────────────

    // Note: forceReconnect() is shared with ForegroundCatchUpCallbacks —
    // a single override satisfies both interfaces.

    override fun resetTrafficTracker() {
        trafficTracker.reset()
    }

    // ──────────────────────────────────────────────────────────────────────
    // R-16 M4: ConnectionCoordinatorCallbacks implementation.
    // The connection lifecycle (testConnection / coldStartReconnect / SSE
    // start-stop / initial-data orchestration) lives in [connectionCoordinator];
    // these are the orchestration hooks it calls back into for repository
    // reconfiguration, the initial-data loaders it does NOT own, SSE event
    // dispatch → SessionSyncCoordinator, and the catch-up state-machine reset.
    //
    // loadSessions / loadAgents / loadProviders / loadPendingQuestions satisfy
    // the interface via their own `override` declarations (the existing
    // loader implementations — MainViewModel still owns them). forceReconnect
    // / cancelSse / cancelSseForReconfigure are shared with the Foreground /
    // HostProfile callback interfaces (single override each).
    // ──────────────────────────────────────────────────────────────────────

    override fun configureRepositoryForCurrentProfile() {
        hostProfileController.configureRepositoryForProfile(hostProfileStore.currentProfile())
    }

    override fun onSseEvent(event: SSEEvent) {
        sessionSyncCoordinator.handleEvent(event)
    }

    override fun onHostReconfigured() {
        foregroundCatchUpController.onHostReconfigured()
    }

    // ──────────────────────────────────────────────────────────────────────
    // R-16 M4: SessionSyncCoordinatorCallbacks implementation.
    // The SSE event → AppState fold lives in [sessionSyncCoordinator]; these
    // are the side-effect hooks it calls back into for the foreground catch-up
    // trigger (server.connected), authoritative message reloads, session-list
    // refresh, permission refresh, and non-fatal payload logging.
    // ──────────────────────────────────────────────────────────────────────

    override fun onServerConnected() {
        foregroundCatchUpController.onServerConnected()
    }

    override fun onRefreshMessages(sessionId: String, resetLimit: Boolean) {
        loadMessagesWithRetry(sessionId, resetLimit)
    }

    override fun onRefreshSessions() {
        loadSessions()
    }

    override fun onLoadPendingPermissions() {
        loadPendingPermissions()
    }

    override fun onNonFatalIssue(message: String) {
        reportNonFatalIssue(TAG, message)
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
            if (_sessionListFlow.value.sessions.none { it.id == sessionId }) {
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

    // R-16 M3: delegated to [hostProfileController].
    fun configureServer(url: String, username: String? = null, password: String? = null) {
        hostProfileController.configureServer(url, username, password)
    }

    fun getHostProfiles(): List<HostProfile> = hostProfileController.getHostProfiles()

    fun currentHostProfile(): HostProfile = hostProfileController.currentHostProfile()

    fun saveHostProfile(
        profile: HostProfile,
        basicAuthPassword: String = "",
        basicAuthEdited: Boolean = false,
        tunnelPassword: String = "",
        tunnelEdited: Boolean = false
    ) {
        hostProfileController.saveHostProfile(profile, basicAuthPassword, basicAuthEdited, tunnelPassword, tunnelEdited)
    }

    fun selectHostProfile(profileId: String) {
        hostProfileController.selectHostProfile(profileId)
    }

    fun duplicateHostProfile(profileId: String) {
        hostProfileController.duplicateHostProfile(profileId)
    }

    fun deleteHostProfile(profileId: String) {
        hostProfileController.deleteHostProfile(profileId)
    }

    fun importHostProfile(payload: String): Result<HostProfile> =
        hostProfileController.importHostProfile(payload)

    fun exportHostProfile(profile: HostProfile): String =
        hostProfileController.exportHostProfile(profile)

    fun getSavedConnectionSettings(): ConnectionFormSettings =
        hostProfileController.getSavedConnectionSettings()

    // R-16 M4: delegated to [connectionCoordinator]. The full state machine
    // (30s throttle, exponential-backoff retry, health probe, on-success
    // loadInitialData + startSSE, on-failure error surface) now lives there.
    fun testConnection(force: Boolean = false, retries: Int = 0) {
        connectionCoordinator.testConnection(force = force, retries = retries)
    }

    /**
     * Cold-start entry point: force a connection check with up to 3 retries
     * (exponential backoff 1s/2s/4s) so a slow-to-wake server (common when
     * the OpenCode server itself is bootstrapping) still comes up instead of
     * stranding the user on the disconnected empty state. Used exclusively
     * from [com.yage.opencode_client.MainActivity]'s cold-start LaunchedEffect.
     */
    override fun coldStartReconnect() = connectionCoordinator.coldStartReconnect()

    // R-16 M4: delegated to [connectionCoordinator] (loadCommands / localCommands
    // / mergeCommands moved there too; initial-data loaders route back through
    // ConnectionCoordinatorCallbacks which MainViewModel implements below).
    override fun loadInitialData() = connectionCoordinator.loadInitialData()

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
                    ?: currentSession(_sessionListFlow.value.sessions, _chatFlow.value.currentSessionId)?.directory
                if (workdir != null) {
                    createSessionInWorkdir(workdir)
                } else {
                    createSession()
                }
            }
            else -> {
                val sessionId = _chatFlow.value.currentSessionId ?: run {
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

    // R-16 M4: now also satisfies ConnectionCoordinatorCallbacks.loadSessions.
    override fun loadSessions() {
        launchLoadSessions(
            scope = viewModelScope,
            repository = repository,
            state = _state,
            settingsManager = settingsManager,
            onSelectSession = ::selectSession,
            onLoadSessionStatus = ::loadSessionStatus,
            onLoadMessages = { sessionId -> loadMessages(sessionId, resetLimit = true) },
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

    override fun loadSessionStatus() {
        launchLoadSessionStatus(
            scope = viewModelScope,
            repository = repository,
            state = _state,
            slices = sliceFlows,
        )
    }

    /**
     * R-16 M2b: the full 8-step session-switch flow now lives in
     * [sessionSwitcher.switchTo]. This is a thin public wrapper that preserves
     * the existing API for all callers (loadSessions callback, openSubAgent,
     * openSessionFromDeepLink, sendMessage draft creation, tests, etc.).
     */
    fun selectSession(sessionId: String) {
        sessionSwitcher.switchTo(sessionId)
        // M4 简化版 (§1.7 G): 省流模式切 tab 时重置 M1 计数器 + 立即 catchUp。
        // 正式的 R1 callback 接口留到 M4；先在此直接转发（R2 callback 已在
        // lowTrafficPoller.onSessionSwitched 内幂等处理 active=false no-op）。
        if (settingsManager.lowTrafficMode) {
            lowTrafficPoller.onSessionSwitched(sessionId)
        }
    }

    /**
     * Fetches the sub-agent (child) sessions for [sessionId] and stores them in
     * [AppState.childSessions]. Best-effort: failures (older servers without the
     * `children` endpoint, test mocks) are swallowed silently — the sub-agent
     * cards simply fall back to the in-stream tool state.
     */
    override fun loadChildSessions(sessionId: String) {
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
        val parentId = _chatFlow.value.currentSessionId
        viewModelScope.launch {
            // Try every local cache first; fall back to a single GET so we
            // always have the child session in `state.sessions` before select.
            val child = _sessionListFlow.value.sessions.firstOrNull { it.id == childSessionId }
                ?: parentId?.let { pid -> _sessionListFlow.value.childSessions[pid]?.find { it.id == childSessionId } }
                ?: _sessionListFlow.value.childSessions.values.flatten().firstOrNull { it.id == childSessionId }
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
        val isCurrent = _chatFlow.value.currentSessionId == sessionId
        // Preserve the draft of the session being closed. selectSessionState
        // would otherwise mis-target the save once currentSessionId has moved
        // (it reads oldSessionId AFTER we've changed it), writing the closed
        // session's draft into the next session (#10). Save it explicitly here
        // so selectSession(nextId) only restores the next session's own draft.
        if (isCurrent) {
            settingsManager.setDraftText(sessionId, _composerFlow.value.inputText)
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

    override fun loadMessages(sessionId: String, resetLimit: Boolean) {
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

    /**
     * Convenience overload preserving the pre-M2b single-arg call convention
     * (`loadMessages(sessionId)`). Kotlin does not allow default parameter
     * values on override methods, so this thin wrapper supplies the default.
     */
    fun loadMessages(sessionId: String) = loadMessages(sessionId, resetLimit = true)

    /** Load messages with delay when triggered by SSE/send (server may need time to persist). */
    private fun loadMessagesWithRetry(sessionId: String, resetLimit: Boolean = true) {
        launchLoadMessagesWithRetry(viewModelScope, sessionId, _state, resetLimit, ::loadMessages)
    }

    fun loadMoreMessages() {
        val sessionId = _chatFlow.value.currentSessionId ?: return
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
        val sessionId = _chatFlow.value.currentSessionId ?: return
        if (_chatFlow.value.isLoadingMessages) return
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
        if (_chatFlow.value.isLoadingMessages) return
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
        val sessionId = _chatFlow.value.currentSessionId ?: return
        launchCloseGap(
            scope = viewModelScope,
            repository = repository,
            state = _state,
            sessionId = sessionId,
            onCacheWindow = ::writeSessionWindow,
            slices = sliceFlows,
        )
    }

    // R-16 M4: now satisfies ConnectionCoordinatorCallbacks.loadAgents.
    override fun loadAgents() {
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

    // R-16 M4: now satisfies ConnectionCoordinatorCallbacks.loadProviders.
    override fun loadProviders() {
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
    // R-16 M2: delegated to [composerController].
    fun clearDraftIfActive() {
        composerController.clearDraftIfActive()
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
        val draftWorkdir = _composerFlow.value.draftWorkdir
        val existingSessionId = _chatFlow.value.currentSessionId
        val text = _composerFlow.value.inputText.trim()
        val attachments = _composerFlow.value.imageAttachments
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
        if (_composerFlow.value.sendingSessionIds.contains(sessionId)) return
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

        // §1.9 (gpter 可选#3): 省流模式下发送消息 → 本地标 busy + 重置 tick，
        // 让后续轮询观测到 active→idle 做权威 reload。放在去重守卫之后、网络
        // 发起之前（active=false 时 onMessageSent 幂等 no-op）。
        if (settingsManager.lowTrafficMode) {
            lowTrafficPoller.onMessageSent(sessionId)
        }

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
        val currentSession = currentSession(_sessionListFlow.value.sessions, _chatFlow.value.currentSessionId)

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
        val sessionId = _chatFlow.value.currentSessionId ?: return
        viewModelScope.launch {
            repository.abortSession(sessionId)
                .onFailure { error ->
                    updateState { it.copy(error = errorMessageOrFallback(error, "Failed to abort session")) }
                }
        }
    }

    // R-16 M2: delegated to [composerController].
    fun setInputText(text: String) {
        composerController.setInputText(text)
    }

    // R-16 M2: delegated to [composerController].
    fun addImageAttachments(attachments: List<ComposerImageAttachment>) {
        composerController.addImageAttachments(attachments)
    }

    // R-16 M2: delegated to [composerController].
    fun removeImageAttachment(id: String) {
        composerController.removeImageAttachment(id)
    }

    fun editFromMessage(messageId: String) {
        val sessionId = _chatFlow.value.currentSessionId ?: return
        val message = _chatFlow.value.messages.firstOrNull { it.id == messageId && it.isUser } ?: return
        val draft = (_chatFlow.value.partsByMessage[messageId] ?: emptyList()).firstOrNull { it.isText }?.text?.trim().orEmpty()
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
                    loadMessages(sessionId, resetLimit = true)
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
        _chatFlow.value.currentSessionId?.let { settingsManager.setAgentForSession(it, agentName) }
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

    /**
     * 省流模式当前值（`docs/省流模式设计.md` §3 M0）。供设置页 Switch 读取初始态。
     */
    fun isLowTrafficMode(): Boolean = settingsManager.lowTrafficMode

    /**
     * 省流模式切换入口（`docs/省流模式设计.md` §1.8 H / §3 M0）。
     *
     * 行为：
     *  1. 立即持久化新值到 [settingsManager]（M3 的 `ConnectionCoordinator
     *     .startSSE` 守卫、后续 M1 轮询都会读最新值，即时生效）。
     *  2. **F2 (gpter 致命#2)**: 开省流时先取消已有 SSE firehose + 清 delta buffer，
     *     再启动 M1。否则已运行的 SSE (69MB/min) 不会被 M3 守卫自动停，变成双通道。
     *  3. **F3 (gpter 致命#3)**: 关省流时停 M1 后恢复 SSE 实时同步。已连接则直接
     *     startSSE；未连接走 forceReconnect (成功后 testConnection 内部会 startSSE)。
     *  4. 触发当前 session 重载（`resetLimit=true`），让用户立即看到省流/实时模式的
     *     同步结果——但**不清 `sessionWindowCache` 中其他 session 的缓存**（dser 🟠-3）。
     *  5. 加 3s 防抖（`LOW_TRAFFIC_MODE_DEBOUNCE_MS`）避免快速来回切反噬省流。
     */
    private var lowTrafficModeReloadJob: Job? = null

    fun onLowTrafficModeChanged(enabled: Boolean) {
        settingsManager.lowTrafficMode = enabled
        if (enabled) {
            // §F2 (gpter 致命#2): 取消已有 SSE firehose。M3 守卫只防新 startSSE，
            // 不会停已在跑的 collector，必须显式 cancelSse。同时清 delta buffer 防
            // 下次连接 stale flush (cancelSse override 已含 clearDeltaBuffers，但此处
            // 显式调用以匹配 §4.2 跟进语义并避免 override 语义漂移)。
            connectionCoordinator.cancelSse()
            sessionSyncCoordinator.clearDeltaBuffers()
            lowTrafficPoller.start()
        } else {
            lowTrafficPoller.stop()
            // §F3 (gpter 致命#3): 关省流 → 恢复 SSE 实时同步。M3 守卫此时读到
            // lowTrafficMode=false，startSSE 不再被跳过。已连接直接 startSSE；未连接
            // 走 forceReconnect (testConnection force=true)，健康检查成功后会 startSSE。
            if (_connectionFlow.value.isConnected) {
                connectionCoordinator.startSSE()
            } else {
                forceReconnect()
            }
        }
        lowTrafficModeReloadJob?.cancel()
        lowTrafficModeReloadJob = viewModelScope.launch {
            delay(LOW_TRAFFIC_MODE_DEBOUNCE_MS)
            val sessionId = _chatFlow.value.currentSessionId ?: return@launch
            // §1.8 H: 只重载当前 session；不清其他 session 缓存（不调
            // performGlobalColdStartRefresh / clearSessionWindowCache）。
            loadMessages(sessionId, resetLimit = true)
        }
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

    // R-16 M4: now also satisfies ConnectionCoordinatorCallbacks.loadPendingQuestions.
    override fun loadPendingQuestions() {
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
    // R-16 M3: delegated to [hostProfileController].
    fun resetLocalDataAndResync() {
        hostProfileController.resetLocalDataAndResync()
    }

    // R-16 M3: delegated to [hostProfileController].
    fun activateTunnelForCurrentHost() {
        hostProfileController.activateTunnelForCurrentHost()
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

    // R-16 M4: delegated to [connectionCoordinator] (the sseJob + the SSE
    // collection coroutine now live there; events route to SessionSyncCoordinator
    // via ConnectionCoordinatorCallbacks.onSseEvent).
    override fun startSSE() = connectionCoordinator.startSSE()

    /**
     * R-16 M4: delegated to [sessionSyncCoordinator]. Kept as a private method
     * (NOT removed) so the reflection-based test helper `handleSse(...)` and
     * any direct call sites keep resolving — the SSE event fold (server.connected
     * catch-up trigger + the message/session/status/part/permission/question/todo
     * dispatch) now lives in the coordinator.
     */
    private fun handleSSEEvent(event: SSEEvent) {
        sessionSyncCoordinator.handleEvent(event)
    }

    override fun onCleared() {
        // M1: 停止省流轮询（cancel loop + 清状态）。viewModelScope 即将被取消，
        // 但显式 stop 保证 loopJob 立即释放、不依赖 scope 取消传播。
        lowTrafficPoller.stop()
        // M5 跟进 (§4.2): drop pending delta buffers before the scope is
        // cancelled, so no flush job can fire post-teardown. (onCleared calls
        // connectionCoordinator.cancelSse() directly rather than the cancelSse()
        // override, so clearDeltaBuffers is added explicitly here.)
        sessionSyncCoordinator.clearDeltaBuffers()
        connectionCoordinator.cancelSse()
        super.onCleared()
    }

    private companion object {
        private const val TAG = "MainViewModel"

        /**
         * §D (kimo 重要): onRetryError 写入的瞬态 error 字符串。常量化以便
         * [clearTransientLowTrafficError] 精确匹配清除 (避免漂移)。
         */
        private const val RETRY_ERROR_MSG = "会话运行出错，请检查重试"

        /** 省流模式切换后重载当前 session 的防抖（§1.8 H，避免快速来回切反噬省流）。 */
        private const val LOW_TRAFFIC_MODE_DEBOUNCE_MS = 3000L

        // R-16 M1: FOREGROUND_RELOAD_MIN_INTERVAL_MS (15s) and
        // LONG_ABSENCE_THRESHOLD_MS (5min) moved to
        // ForegroundCatchUpController.companion.

        // R-16 M2b: SESSION_WINDOW_CACHE_CAPACITY moved to
        // SessionSwitcher.companion (the cache now lives there).
    }
}
