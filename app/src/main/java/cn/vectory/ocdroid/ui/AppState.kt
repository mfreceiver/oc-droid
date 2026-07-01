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
    // Q6: persisted last-opened phone pager page (0=Chat, 1=Sessions, 2=Settings).
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
