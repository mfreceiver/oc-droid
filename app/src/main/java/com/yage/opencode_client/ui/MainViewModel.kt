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
import com.yage.opencode_client.util.LanguageMode
import com.yage.opencode_client.util.ThemeMode
import com.yage.opencode_client.util.TrafficTracker
import com.yage.opencode_client.ui.theme.MarkdownFontSizes
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

sealed class TunnelActivationState {
    data object Idle : TunnelActivationState()
    data object Loading : TunnelActivationState()
    data object Success : TunnelActivationState()
    data class Error(val message: String) : TunnelActivationState()
}

data class ConnectionFormSettings(
    val serverUrl: String,
    val username: String,
    val password: String
)

data class AppState(
    val isConnected: Boolean = false,
    val isConnecting: Boolean = false,
    val serverVersion: String? = null,
    val sessions: List<Session> = emptyList(),
    val loadedSessionLimit: Int = MainViewModelTimings.sessionPageSize,
    val hasMoreSessions: Boolean = true,
    val isLoadingMoreSessions: Boolean = false,
    val isRefreshingSessions: Boolean = false,
    val expandedSessionIds: Set<String> = emptySet(),
    val currentSessionId: String? = null,
    // Q6: persisted last-opened phone pager page (0=Chat...3=Settings).
    val lastNavPage: Int = 0,
    val sessionStatuses: Map<String, SessionStatus> = emptyMap(),
    // SSE-trust split store (S4): messages hold only Message metadata; parts
    // live separately keyed by message id (populated by API load). Live
    // streaming text is overlaid via streamingPartTexts (keyed by partId).
    val messages: List<Message> = emptyList(),
    val partsByMessage: Map<String, List<Part>> = emptyMap(),
    // §on-demand: cursor-based history paging. olderMessagesCursor is the opaque
    // V1 cursor for fetching the next older page (null = no more / reset).
    val olderMessagesCursor: String? = null,
    val hasMoreMessages: Boolean = true,
    val isLoadingMessages: Boolean = false,
    val agents: List<AgentInfo> = emptyList(),
    val selectedAgentName: String = "build",
    val providers: ProvidersResponse? = null,
    val pendingPermissions: List<PermissionRequest> = emptyList(),
    val pendingQuestions: List<QuestionRequest> = emptyList(),
    val inputText: String = "",
    val error: String? = null,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val languageMode: LanguageMode = LanguageMode.SYSTEM,
    val markdownFontSizes: MarkdownFontSizes = MarkdownFontSizes(),
    val filePathToShowInFiles: String? = null,
    val filePreviewOriginRoute: String? = null,
    val streamingPartTexts: Map<String, String> = emptyMap(),
    val streamingReasoningPart: Part? = null,
    val sessionTodos: Map<String, List<TodoItem>> = emptyMap(),
    val sendingSessionIds: Set<String> = emptySet(),
    val imageAttachments: List<ComposerImageAttachment> = emptyList(),
    val hostProfiles: List<HostProfile> = emptyList(),
    val currentHostProfileId: String? = null,
    val connectionPhase: String? = null,
    val tunnelActivationState: TunnelActivationState = TunnelActivationState.Idle,
    /**
     * Sub-agent (child) sessions keyed by parent session ID. Populated lazily
     * after a session is selected via [MainViewModel.loadChildSessions]. Used
     * to render sub-agent cards and to support parent->child navigation.
     */
    val childSessions: Map<String, List<Session>> = emptyMap(),
    /**
     * Per-directory root sessions, keyed by workdir path. Populated by
     * [MainViewModel.createSessionInWorkdir] via
     * [OpenCodeRepository.getSessionsForDirectory] so the user can see (and
     * pick up) prior conversations when connecting a project. Stored
     * separately from [sessions] so periodic global refreshes do not discard
     * it. [SessionsScreen] merges both for display.
     */
    val directorySessions: Map<String, List<Session>> = emptyMap(),
    /**
     * Mirror of [SettingsManager.openSessionIds] (browser-tab style list of
     * "open" session IDs in open-order, most recently opened first). Kept in
     * AppState so the Compose UI recomposes when the list changes.
     * [SettingsManager] remains the persistent source of truth — this field
     * is the observable projection.
     */
    val openSessionIds: List<String> = emptyList(),
    /**
     * Non-null when the user has entered "draft" (deferred-create) mode by
     * invoking [MainViewModel.createSessionInWorkdir]. The repository's
     * current directory has been set to this workdir, but no POST /session
     * has been issued. The first [MainViewModel.sendMessage] will create
     * the session on demand and clear this field. Selecting/creating any
     * other session or switching host discards the draft.
     */
    val draftWorkdir: String? = null,
    /**
     * Per-session last-viewed epoch millis. Updated whenever the user
     * selects/opens a session. Used as the basis for [unreadSessions].
     * Not currently persisted across restarts.
     */
    val lastViewedTime: Map<String, Long> = emptyMap(),
    /**
     * Session IDs that have received SSE message updates more recently than
     * their [lastViewedTime] (and are not the currently-open session).
     * Drives the unread badge in the session lists. Cleared on open.
     */
    val unreadSessions: Set<String> = emptySet(),
    /**
     * Sessions the user has opened (cleared the unread badge) and that are
     * still eligible to be *re-marked* unread if they remain busy when the
     * user navigates away, or if a new message.created arrives for them while
     * they are not the current session.
     *
     * A session leaves this set when the server reports it going idle
     * (busy -> idle) — at that point the in-flight task is complete and there
     * is nothing further to surface. This implements the "swipe away from a
     * busy session = re-mark unread; session completes cleanly = do not"
     * behaviour.
     */
    val tempClearedUnread: Set<String> = emptySet(),
    /**
     * Slash commands available in the composer. Merges a small set of
     * client-side commands (/clear, /compact, /undo, /redo) with the
     * server-published list (GET /command). Populated by
     * [MainViewModel.loadCommands] on connect.
     */
    val availableCommands: List<CommandInfo> = emptyList(),
    /**
     * Cumulative HTTP traffic counters mirrored from [TrafficTracker] for the
     * Settings page. Refreshed on-demand by [MainViewModel.refreshTrafficStats]
     * (e.g. when Settings opens) rather than on every request, to avoid
     * recomposing the whole app on each network call. [totalTrafficBytes] is
     * derived so it can never drift out of sync.
     */
    val trafficSent: Long = 0L,
    val trafficReceived: Long = 0L
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
        val languageMode: LanguageMode = LanguageMode.SYSTEM,
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
            val revertMessageId = currentSession?.revert?.messageId ?: return messages
            return messages.filter { message -> message.id < revertMessageId }
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
            languageMode = languageMode,
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
) : ViewModel() {

    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

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

    private var sseJob: Job? = null
    private var lastHealthCheckTime = 0L

    /**
     * §15.2: guards the very first [AppLifecycleMonitor.isInForeground]
     * emission. StateFlow always delivers its current value to a new
     * collector, so without this flag the catch-up would fire spuriously on
     * ViewModel construction (where there is no actual background→foreground
     * transition to recover from). We treat the first emission as the
     * baseline "current state" and only act on subsequent transitions, which
     * matches the spec's "ON_START = 回前台" semantics.
     */
    @Volatile private var hasObservedForegroundState: Boolean = false

    /**
     * SSE-trust model: timestamp (epoch ms) of the most recent message load,
     * used to throttle the foreground (ON_START) catch-up reload to at most
     * once per [FOREGROUND_RELOAD_MIN_INTERVAL_MS]. Avoids re-downloading the
     * latest message window on rapid background→foreground cycling.
     */
    @Volatile private var lastLoadAtMs: Long = 0L

    init {
        loadSettings()
        // §15.2: foreground/background hook (N8 — onEach+launchIn, NOT a
        // suspend `collect`, since `init` is synchronous and cannot block).
        appLifecycleMonitor.isInForeground
            .onEach { onForegroundChanged(it) }
            .launchIn(viewModelScope)

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

    /**
     * §15.2 / R-A: process foreground/background transitions.
     *
     * On **enter foreground** (ON_START): force a connection check (bypassing
     * the 30s throttle), and — at most once per
     * [FOREGROUND_RELOAD_MIN_INTERVAL_MS] — wipe stale streaming buffers and
     * reload the current session. The wipe and reload are coupled: clearing the
     * overlay without reloading would drop a finalized turn whose text lives
     * only in `streamingPartTexts`, so both run together or neither does. N13:
     * `currentSessionId?.let{}` null guard — draft mode or a freshly-closed
     * last tab leaves it null.
     *
     * On **enter background** (ON_STOP): cancel the SSE feed (R-A: saves data,
     * avoids half-open sockets; the §18 independent 30s poller takes over
     * notification duty).
     */
    private fun onForegroundChanged(inForeground: Boolean) {
        // §15.2: the first emission from AppLifecycleMonitor.isInForeground
        // is the current state, not a transition. Skip catch-up logic so the
        // ViewModel's very first subscribe does not spuriously reload (which
        // would race with MainActivity's own LaunchedEffect testConnection
        // and break tests that do not pre-mock checkHealth).
        if (!hasObservedForegroundState) {
            hasObservedForegroundState = true
            return
        }
        if (inForeground) {
            testConnection(force = true)
            val now = System.currentTimeMillis()
            if (now - lastLoadAtMs >= FOREGROUND_RELOAD_MIN_INTERVAL_MS) {
                lastLoadAtMs = now
                // Catch-up: wipe streaming state first so a stale partial does
                // not overwrite the freshly-loaded snapshot. Coupled to the
                // reload below — see method doc.
                _state.update {
                    it.copy(
                        streamingPartTexts = emptyMap(),
                        streamingReasoningPart = null
                    )
                }
                _state.value.currentSessionId?.let { loadMessages(it, resetLimit = true) }
            }
        } else {
            sseJob?.cancel()
            sseJob = null
        }
    }

    /**
     * §Stage D (gpter 阻塞 #1): tear down any in-flight SSE feed BEFORE the
     * repository is reconfigured for a host / profile switch. Without this,
     * the SSE job bound to the PREVIOUS host keeps delivering events into
     * AppState while the new host's health probe is still in flight — those
     * stale events (session/status/message/permission/question) would pollute
     * the freshly-cleared state for the new profile.
     */
    private fun cancelSseForReconfigure() {
        sseJob?.cancel()
        sseJob = null
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
                    runCatching { repository.getSession(sessionId).getOrNull() }.getOrNull()
                }
                if (fetched != null) {
                    _state.update { st ->
                        st.copy(sessions = upsertSession(st.sessions, fetched))
                    }
                }
            }
            selectSession(sessionId)
        }
    }

    private fun loadSettings() {
        applySavedSettings(repository, settingsManager, hostProfileStore, _state)
    }

    /** Q6: persist the phone pager page the user navigated to, so cold start lands there. */
    fun setLastNavPage(page: Int) {
        val clamped = page.coerceIn(0, 2)
        if (_state.value.lastNavPage == clamped) return
        settingsManager.lastNavPage = clamped
        _state.update { it.copy(lastNavPage = clamped) }
    }

    fun configureServer(url: String, username: String? = null, password: String? = null) {
        // §Stage D (gpter 阻塞 #1): cancel any in-flight SSE feed BEFORE
        // repository.configure, otherwise events from the previous
        // credential/host keep landing in AppState during the new probe.
        cancelSseForReconfigure()
        settingsManager.serverUrl = url
        settingsManager.username = username
        settingsManager.password = password
        repository.configure(url, username, password)
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
            _state.update { it.copy(
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
                directorySessions = emptyMap(),
                draftWorkdir = null,
                availableCommands = emptyList()
            ) }
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
            _state.update {
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
                    directorySessions = emptyMap(),
                    draftWorkdir = null,
                    availableCommands = emptyList()
                )
            }
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
        _state.update {
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
        repository.configure(profile.serverUrl, profile.basicAuth?.username, password)
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
            _state.update { it.copy(isConnecting = true, error = null, connectionPhase = "connecting") }
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
                    _state.update {
                        it.copy(connectionPhase = "reconnecting (attempt $attempt/$maxAttempts)")
                    }
                }
                val healthResult = repository.checkHealth()
                if (healthResult.isSuccess) {
                    val health = healthResult.getOrNull()
                    if (health != null && health.healthy) {
                        _state.update {
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
                        _state.update { it.copy(serverVersion = health.version) }
                    }
                }
                if (attempt >= maxAttempts || !isActive) {
                    _state.update {
                        it.copy(
                            isConnected = false,
                            isConnecting = false,
                            connectionPhase = "disconnected",
                            error = healthResult.exceptionOrNull()?.let { e ->
                                errorMessageOrFallback(e, "Connection failed")
                            }
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
                        _state.update { it.copy(directorySessions = it.directorySessions + (workdir to sessions)) }
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
                    _state.update {
                        it.copy(availableCommands = mergeCommands(localCommands(), serverCommands))
                    }
                }
                .onFailure { error ->
                    reportNonFatalIssue(TAG, "Failed to load commands", error)
                    _state.update { it.copy(availableCommands = localCommands()) }
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
                    _state.update {
                        it.copy(error = "Open or create a session before running /$cmd")
                    }
                    return
                }
                setInputText("")
                viewModelScope.launch {
                    val args = if (arguments.isBlank()) emptyMap() else mapOf("text" to arguments)
                    repository.executeCommand(sessionId, cmd, args)
                        .onFailure { error ->
                            _state.update {
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
            onLoadMessages = { sessionId -> loadMessages(sessionId) }
        )
    }

    fun loadMoreSessions() {
        launchLoadMoreSessions(
            scope = viewModelScope,
            repository = repository,
            state = _state,
            onSelectSession = ::selectSession
        )
    }

    private fun loadSessionStatus() {
        launchLoadSessionStatus(
            scope = viewModelScope,
            repository = repository,
            state = _state
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

        selectSessionState(_state, settingsManager, sessionId)
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
            _state.update { it.copy(sessions = upsertSession(it.sessions, targetSession)) }
        }
        // Reset the collapsible-card expansion state (#13): switching sessions
        // collapses all cards. Done here (not in selectSessionState / loadMessages)
        // so history pagination (loadMore) preserves the user's in-progress
        // expand state within the same session.
        clearExpandedParts()
        // Sync the repository's workdir context to the selected session so that
        // directory-scoped requests (files, prompt, create) target the right cwd.
        syncCurrentDirectoryForSession(_state, repository, sessionId)
        loadMessages(sessionId)
        loadSessionStatus()
        loadChildSessions(sessionId)
        // Selecting a concrete session discards any in-progress draft.
        // Update openSessionIds + unread tracking + lastViewedTime. Skip the
        // openSessionIds prepend for sub-agents (parentId != null): they are
        // transient navigations from within a parent conversation and must not
        // pollute the open-tabs list. Sub-agents are only reachable via SubAgentCard.
        val now = System.currentTimeMillis()
        _state.update {
            // Re-mark the previous session as unread if it was busy when the
            // user navigated away (its temp-cleared badge should resurface so
            // the user knows there is still in-flight activity to come back to).
            val withReMark = if (previousWasBusyAndCleared) {
                it.copy(unreadSessions = it.unreadSessions + previousSessionId!!)
            } else {
                it
            }
            withReMark.copy(
                draftWorkdir = null,
                unreadSessions = withReMark.unreadSessions - sessionId,
                lastViewedTime = withReMark.lastViewedTime + (sessionId to now),
                // Track the newly-selected session as "temp-cleared" so a
                // subsequent switch away (while busy) or a new message.created
                // can re-mark it. The previous session stays in the set until
                // the server reports it going idle (handled in SyncActions).
                tempClearedUnread = withReMark.tempClearedUnread + sessionId
            )
        }
        if (targetSession?.parentId == null) {
            val updated = (listOf(sessionId) + settingsManager.openSessionIds).distinct().take(8)
            settingsManager.openSessionIds = updated
            _state.update { it.copy(openSessionIds = updated) }
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
                        _state.update { it.copy(childSessions = it.childSessions + (sessionId to children)) }
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
                ?: runCatching { repository.getSession(childSessionId).getOrNull() }.getOrNull()
            // #14: only upsert + select when the child actually resolved.
            // Previously a null child still triggered selectSession(childSessionId),
            // which would set currentSessionId to a non-existent session and
            // leave currentSession null. Surface the failure via the error
            // channel instead (same mechanism as testConnection failures).
            if (child != null) {
                _state.update { state -> state.copy(sessions = upsertSession(state.sessions, child)) }
                selectSession(childSessionId)
            } else {
                _state.update { it.copy(error = "子任务会话不可用") }
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
        _state.update { currentState ->
            val next = currentState.copy(
                openSessionIds = updated,
                unreadSessions = currentState.unreadSessions - sessionId
            )
            if (isCurrent && nextId == null) {
                settingsManager.currentSessionId = null
                next.copy(currentSessionId = null, messages = emptyList(), partsByMessage = emptyMap(), inputText = "")
            } else {
                // Keep currentSessionId pointing at the closed session for now;
                // selectSession(nextId) below performs the switch (and saves the
                // closed session's draft via the explicit setDraftText above).
                next
            }
        }
        if (isCurrent && nextId != null) {
            selectSession(nextId)
        }
    }

    fun loadMessages(sessionId: String, resetLimit: Boolean = true) {
        launchLoadMessages(viewModelScope, repository, _state, sessionId, resetLimit, settingsManager)
    }

    /** Load messages with delay when triggered by SSE/send (server may need time to persist). */
    private fun loadMessagesWithRetry(sessionId: String, resetLimit: Boolean = true) {
        launchLoadMessagesWithRetry(viewModelScope, sessionId, _state, resetLimit, ::loadMessages)
    }

    fun loadMoreMessages() {
        val sessionId = _state.value.currentSessionId ?: return
        launchLoadMoreMessages(viewModelScope, repository, _state, sessionId)
    }

    private fun loadAgents() {
        viewModelScope.launch {
            repository.getAgents()
                .onSuccess { agents ->
                    val currentAgent = _state.value.selectedAgentName
                    val validAgent = if (agents.none { it.name == currentAgent }) {
                        "build"
                    } else {
                        currentAgent
                    }
                    _state.update { it.copy(agents = agents, selectedAgentName = validAgent) }
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
        launchLoadProviders(viewModelScope, repository, _state) { message, error ->
            reportNonFatalIssue(TAG, message, error)
        }
    }

    fun createSession(title: String? = null) {
        launchCreateSession(viewModelScope, repository, _state, title, ::selectSession)
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
        _state.update {
            it.copy(
                currentSessionId = null,
                messages = emptyList(),
                partsByMessage = emptyMap(),
                inputText = "",
                imageAttachments = emptyList(),
                sessionTodos = emptyMap(),
                streamingPartTexts = emptyMap(),
                streamingReasoningPart = null,
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
                    _state.update { it.copy(directorySessions = it.directorySessions + (workdir to sessions)) }
                }
        }
    }

    fun forkSession(sessionId: String, messageId: String?) {
        launchForkSession(viewModelScope, repository, _state, sessionId, messageId, ::selectSession)
    }

    fun archiveSession(sessionId: String) {
        launchSetSessionArchived(viewModelScope, repository, _state, sessionId, archived = true)
    }

    fun restoreSession(sessionId: String) {
        launchSetSessionArchived(viewModelScope, repository, _state, sessionId, archived = false)
    }

    fun deleteSession(sessionId: String) {
        launchDeleteSession(viewModelScope, repository, _state, settingsManager, sessionId, ::selectSession)
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
            _state.update { it.copy(draftWorkdir = null) }
            viewModelScope.launch {
                repository.createSession(title = null)
                    .onSuccess { session ->
                        _state.update { state ->
                            val openIds = (listOf(session.id) + settingsManager.openSessionIds).distinct().take(8)
                            settingsManager.openSessionIds = openIds
                            val now = System.currentTimeMillis()
                            state.copy(
                                sessions = upsertSession(state.sessions, session),
                                currentSessionId = session.id,
                                draftWorkdir = null,
                                openSessionIds = openIds,
                                unreadSessions = state.unreadSessions - session.id,
                                lastViewedTime = state.lastViewedTime + (session.id to now)
                            )
                        }
                        settingsManager.currentSessionId = session.id
                        dispatchSendMessage(session.id)
                    }
                    .onFailure { error ->
                        _state.update {
                            it.copy(
                                // Restore draft mode so the user can retry.
                                // draftWorkdir was synchronously cleared before
                                // launch to guard against double-tap; on failure
                                // we put it back (the captured local above) so
                                // the composer stays in draft mode and the
                                // untouched inputText remains editable. A retry
                                // is a fresh invoke that re-clears + re-launches.
                                draftWorkdir = draftWorkdir,
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
        if (_state.value.sendingSessionIds.contains(sessionId)) return
        val text = _state.value.inputText.trim()
        val attachments = _state.value.imageAttachments
        if (text.isEmpty() && attachments.isEmpty()) return

        _state.update { state -> state.copy(sendingSessionIds = state.sendingSessionIds + sessionId) }

        val agent = _state.value.selectedAgentName
        val model: Message.ModelInfo? = null
        val currentSession = _state.value.currentSession

        fun dispatchSend() {
            launchSendMessage(
                scope = viewModelScope,
                repository = repository,
                state = _state,
                sessionId = sessionId,
                text = text,
                attachments = attachments,
                agent = agent,
                model = model,
                onRefreshMessages = ::loadMessagesWithRetry,
                onRefreshSessions = ::loadSessions,
                onSuccess = {
                    settingsManager.setDraftText(sessionId, "")
                    _state.update { it.copy(imageAttachments = emptyList()) }
                },
                onComplete = {
                    _state.update { state -> state.copy(sendingSessionIds = state.sendingSessionIds - sessionId) }
                }
            )
        }

        if (currentSession?.isArchived == true) {
            viewModelScope.launch {
                repository.updateSessionArchived(sessionId, -1L)
                    .onSuccess { updated ->
                        _state.update { state ->
                            state.copy(sessions = state.sessions.map { session -> if (session.id == sessionId) updated else session })
                        }
                        dispatchSend()
                    }
                    .onFailure { error ->
                        _state.update { it.copy(error = "Failed to restore session: ${errorMessageOrFallback(error, "unknown error")}") }
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
                    _state.update { it.copy(error = errorMessageOrFallback(error, "Failed to abort session")) }
                }
        }
    }

    fun setInputText(text: String) {
        _state.update { it.copy(inputText = text) }
        _state.value.currentSessionId?.let { settingsManager.setDraftText(it, text) }
    }

    fun addImageAttachments(attachments: List<ComposerImageAttachment>) {
        if (attachments.isEmpty()) return
        _state.update { state ->
            state.copy(imageAttachments = (state.imageAttachments + attachments).take(4))
        }
    }

    fun removeImageAttachment(id: String) {
        _state.update { state ->
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
                    _state.update { state ->
                        state.copy(
                            sessions = state.sessions.map { session -> if (session.id == sessionId) updatedSession else session },
                            inputText = draft,
                            imageAttachments = emptyList(),
                            error = null
                        )
                    }
                    settingsManager.setDraftText(sessionId, draft)
                    loadMessages(sessionId)
                    loadSessions()
                }
                .onFailure { error ->
                    _state.update { it.copy(error = "Failed to edit message: ${errorMessageOrFallback(error, "unknown error")}") }
                }
        }
    }

    fun selectAgent(agentName: String) {
        settingsManager.selectedAgentName = agentName
        _state.update { it.copy(selectedAgentName = agentName) }
        _state.value.currentSessionId?.let { settingsManager.setAgentForSession(it, agentName) }
    }

    fun toggleSessionExpanded(sessionId: String) {
        _state.update { state ->
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
        _state.update { it.copy(themeMode = mode) }
    }

    fun setLanguageMode(mode: LanguageMode) {
        settingsManager.languageMode = mode
        _state.update { it.copy(languageMode = mode) }
    }

    fun setMarkdownFontSizes(sizes: MarkdownFontSizes) {
        settingsManager.markdownFontSizes = sizes
        _state.update { it.copy(markdownFontSizes = sizes) }
    }

    fun respondPermission(sessionId: String, permissionId: String, response: PermissionResponse) {
        viewModelScope.launch {
            repository.respondPermission(sessionId, permissionId, response)
                .onSuccess {
                    _state.update { it.copy(
                        pendingPermissions = it.pendingPermissions.filter { p -> p.id != permissionId }
                    )}
                }
                .onFailure { error ->
                    _state.update { it.copy(error = errorMessageOrFallback(error, "Failed to respond to permission")) }
                }
        }
    }

    fun loadPendingPermissions() {
        viewModelScope.launch {
            repository.getPendingPermissions()
                .onSuccess { permissions ->
                    _state.update { it.copy(pendingPermissions = permissions) }
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
                    _state.update { it.copy(pendingQuestions = questions) }
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
                    _state.update { currentState ->
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
                    _state.update { currentState ->
                        currentState.copy(pendingQuestions = currentState.pendingQuestions.filter { it.id != requestId })
                    }
                }
                .onFailure { error ->
                    Log.w(TAG, "Failed to reject question: ${error.message}")
                }
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    /**
     * Mirrors the latest [TrafficTracker] totals into [AppState] so the Settings
     * page renders current figures. Called on-demand (e.g. when Settings opens)
     * rather than on every request, to avoid global recomposition per network
     * call — the counter itself accumulates continuously in the background.
     */
    fun refreshTrafficStats() {
        _state.update {
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

    fun refreshCurrentHost() {
        testConnection(force = true)
    }

    fun activateTunnelForCurrentHost() {
        val profile = hostProfileStore.currentProfile()
        // The tunnel password is POSTed to the opencode server URL itself (the
        // server acts as the tunnel gateway). Surface WHY activation can't
        // proceed instead of silently returning — otherwise the user taps
        // "Activate Tunnel" and nothing happens with no clue.
        val passwordId = profile.tunnelPasswordId
        if (passwordId == null) {
            _state.update {
                it.copy(
                    tunnelActivationState = TunnelActivationState.Error("未设置隧道密码"),
                    error = "隧道激活失败：未设置隧道认证密码。请在「服务器」设置中填写隧道密码并保存后再试。"
                )
            }
            return
        }
        val password = settingsManager.getTunnelPassword(passwordId)
        if (password.isNullOrBlank()) {
            _state.update {
                it.copy(
                    tunnelActivationState = TunnelActivationState.Error("隧道密码为空"),
                    error = "隧道激活失败：已配置密码标识但存储为空（可能保存时未输入）。请重新输入隧道密码并保存。"
                )
            }
            return
        }

        _state.update { it.copy(tunnelActivationState = TunnelActivationState.Loading) }
        viewModelScope.launch {
            repository.activateTunnel(profile.serverUrl, password)
                .onSuccess {
                    _state.update {
                        it.copy(
                            tunnelActivationState = TunnelActivationState.Success,
                            error = "Tunnel activated"
                        )
                    }
                    Log.d(TAG, "Tunnel activated successfully for ${profile.serverUrl}")
                }
                .onFailure { error ->
                    // The repository now enriches the exception with type+message
                    // (e.g. "HTTP 401: ...", "UnknownHostException: ...",
                    // "SSLPeerUnverifiedException: ..."). Surface that directly —
                    // no generic prefix that would double up the fallback string.
                    val msg = errorMessageOrFallback(error, "未知错误（无异常信息）")
                    _state.update {
                        it.copy(
                            tunnelActivationState = TunnelActivationState.Error(msg),
                            error = "隧道激活失败：$msg"
                        )
                    }
                    Log.e(TAG, "Tunnel activation failed", error)
                }
        }
    }

    fun showFileInFiles(path: String, originRoute: String? = null) {
        _state.update { it.copy(filePathToShowInFiles = path, filePreviewOriginRoute = originRoute) }
    }

    fun clearFileToShow() {
        _state.update { it.copy(filePathToShowInFiles = null, filePreviewOriginRoute = null) }
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
        showFileInFiles(workdir, "sessions")
    }

    /** Restore the global currentDirectory to its pre-browse value. Call when
     *  the Sessions file-browser overlay closes (onCloseFile / BackHandler). */
    fun restoreDirectoryAfterBrowse() {
        if (browseActive) {
            repository.setCurrentDirectory(browseSavedDirectory)
            browseSavedDirectory = null
            browseActive = false
        }
    }

    private fun startSSE() {
        sseJob?.cancel()
        sseJob = launchSseCollection(viewModelScope, repository, _state, ::handleSSEEvent)
    }

    private fun handleSSEEvent(event: SSEEvent) {
        handleIncomingSseEvent(
            state = _state,
            event = event,
            onRefreshMessages = ::loadMessagesWithRetry,
            onRefreshSessions = ::loadSessions,
            onLoadPendingPermissions = ::loadPendingPermissions,
            onNonFatalIssue = { message -> reportNonFatalIssue(TAG, message) }
        )
    }

    override fun onCleared() {
        sseJob?.cancel()
        super.onCleared()
    }

    private companion object {
        private const val TAG = "MainViewModel"

        /**
         * SSE-trust model: minimum interval between foreground (ON_START)
         * catch-up message reloads. Avoids re-downloading the latest message
         * window on rapid background→foreground cycling. Mirrors opencode-web,
         * which has no idle/foreground reload at all.
         */
        private const val FOREGROUND_RELOAD_MIN_INTERVAL_MS = 15_000L
    }
}
