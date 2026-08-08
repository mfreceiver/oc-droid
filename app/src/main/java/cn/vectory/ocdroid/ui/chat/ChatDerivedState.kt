// ChatDerivedState.kt — per-field derived state holder extracted from
// ChatScaffold. Each field is an individual `State<T>` (NOT one bundled
// `derivedStateOf`) to preserve the pre-extraction per-field recompose
// granularity. The factory reads State handles (not values) so every
// `.value` read happens inside the calculation lambda — the snapshot-tracking
// contract documented at ChatScaffold.kt:781-787 / ChatTopBar.kt:195.
//
// §5.2 extraction targets:
//  • routeOwnedContent / onParameterizedRoute / renderedMessages /
//    renderedPartsByMessage / renderedStreamingTexts / renderedStreamingReasoning
//    / chromeSessionId          (:231-258)
//  • sessionsById / curSession / effectiveBusy / curCutoff / curRevertMessageId
//    / curSessionStatus         (:454-483)
//  • computedContextUsage / cachedContextUsageState + SideEffect write-through (:531-539)
//  • visibleAgents / effectiveAgent / effectiveModel                  (:556-600)
//  • currentSessionIsRunning / isCurrentSessionSending / currentActivity /
//    matchingQuestions / pendingQuestion / pendingPermission          (:601-660)
//  • curHostProfile                                                   (:776)
//  • recentSessionsForDrawer                                          (:889-897)

package cn.vectory.ocdroid.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import cn.vectory.ocdroid.data.model.HostProfile
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.model.PermissionRequest
import cn.vectory.ocdroid.data.model.QuestionRequest
import cn.vectory.ocdroid.data.model.RevertCutoff
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.ui.ChatState
import cn.vectory.ocdroid.ui.ComposerState
import cn.vectory.ocdroid.ui.ContextUsage
import cn.vectory.ocdroid.ui.HostState
import cn.vectory.ocdroid.ui.LoadedContent
import cn.vectory.ocdroid.ui.SessionListState
import cn.vectory.ocdroid.ui.SettingsState
import cn.vectory.ocdroid.ui.computeContextUsage
import cn.vectory.ocdroid.ui.currentHostProfile
import cn.vectory.ocdroid.ui.currentSessionStatus
import cn.vectory.ocdroid.ui.effectiveBusySessionIds
import cn.vectory.ocdroid.ui.controller.allSessionsById
import cn.vectory.ocdroid.ui.controller.questionRootIds
import cn.vectory.ocdroid.ui.controller.questionsInTree
import cn.vectory.ocdroid.ui.controller.rootIdOf
import cn.vectory.ocdroid.ui.inferCurrentAgent
import cn.vectory.ocdroid.ui.inferCurrentModel
import cn.vectory.ocdroid.ui.visibleMessages

/**
 * §Item15b: per-field derived state holder extracted from [ChatScaffold].
 * Every field is an individual `State<T>` property so a change to
 * `pendingPermissions` recomposes only readers of [pendingPermission] —
 * identical to the pre-extraction per-local invalidation.
 *
 * Non-negotiable invariants (§5.3):
 * 1. Every `remember`/`derivedStateOf` keeps its EXACT current key list.
 * 2. Per-field granularity (NOT one bundled `derivedStateOf`).
 * 3. `cachedContextUsageState` write-through runs in SideEffect (apply-phase);
 *    sticky last-non-null semantics preserved.
 *
 * @property onChatFileClick Callback (not a State) — recomposed via
 *   `remember(curSession, onOpenChatFilePreview)`.
 */
internal class ChatDerivedState(
    /** §B2: authoritative session id for transcript-adjacent chrome. */
    val chromeSessionId: State<String?>,
    /** True when rendering on a parameterized chat/{sessionId} route. */
    val onParameterizedRoute: State<Boolean>,
    /** Route-owned content when renderable, null otherwise. */
    val routeOwnedContent: State<LoadedContent?>,
    /** Messages for rendering: route-owned or flat chat.messages. */
    val renderedMessages: State<List<Message>>,
    /** Parts by message: route-owned or flat chat.partsByMessage. */
    val renderedPartsByMessage: State<Map<String, List<Part>>>,
    /** Streaming part texts: route-owned or flat. */
    val renderedStreamingTexts: State<Map<String, String>>,
    /** Streaming reasoning part: route-owned or flat. */
    val renderedStreamingReasoning: State<Part?>,
    // ── Session identity ──────────────────────────────────────────────────
    /** Hoisted session map (root + directory + child). */
    val sessionsById: State<Map<String, Session>>,
    /** Current session resolved through chromeSessionId. */
    val curSession: State<Session?>,
    /** IDs of sessions that are currently busy. */
    val effectiveBusy: State<Set<String>>,
    /** Revert cutoff for the current session. */
    val curCutoff: State<RevertCutoff?>,
    /** Resolved revert message id (session.revert or cutoff). */
    val curRevertMessageId: State<String?>,
    /** Current session status badge. */
    val curSessionStatus: State<SessionStatus?>,
    // ── Context usage (SideEffect write-through, apply-phase; bF2 redesign) ──
    /** Snapshot-backed handle for context usage, fed to rememberChatTopBarState. */
    val cachedContextUsageState: State<ContextUsage?>,
    // ── Agent / Model ─────────────────────────────────────────────────────
    /** Visible agent names for the picker / top-bar. */
    val visibleAgents: State<Set<String>>,
    /** Effective agent: pending ?: session ?: infer (route-aware). */
    val effectiveAgent: State<String?>,
    /** Effective model: pending ?: session ?: infer (route-aware). */
    val effectiveModel: State<Message.ModelInfo?>,
    // ── Activity / matching ───────────────────────────────────────────────
    /** Whether the current session is running or retrying. */
    val currentSessionIsRunning: State<Boolean>,
    /** Whether the current session is currently sending. */
    val isCurrentSessionSending: State<Boolean>,
    /** Current session activity capsule (text + startedAt). */
    val currentActivity: State<CurrentSessionActivity?>,
    /** Questions matching the current session's tree. */
    val matchingQuestions: State<List<QuestionRequest>>,
    /** First matching question, or null. */
    val pendingQuestion: State<QuestionRequest?>,
    /** Session-scoped pending permission, or null. */
    val pendingPermission: State<PermissionRequest?>,
    // ── Host profile ──────────────────────────────────────────────────────
    /** Current host profile (for drawer / top-bar). */
    val curHostProfile: State<HostProfile?>,
    // ── Drawer sidebar ────────────────────────────────────────────────────
    /** Recent non-archived root sessions for the drawer / sidebar. */
    val recentSessionsForDrawer: State<List<Session>>,
    // ── Callback (not a State) ─────────────────────────────────────────────
    /** File-click handler wired to onOpenChatFilePreview. */
    val onChatFileClick: (String) -> Unit,
)

/**
 * §Item15b: remember-factory for cross-slice derived read projections.
 * Accepts raw State handles (not values) so `.value` reads happen inside
 * each `derivedStateOf` lambda — preserving snapshot-tracking per the
 * established pattern ([rememberChatTopBarState]).
 *
 * @param routeSessionId  Active parameterized route id (null for bare-chat).
 * @param routeInstance   Route-instance freshness token for content gating.
 * @param chatState       State handle for [ChatViewModel.chatFlow].
 * @param sessionListState State handle for [SessionViewModel.sessionListFlow].
 * @param settingsState   State handle for [OrchestratorViewModel.settingsFlow].
 * @param composerState   State handle for [ComposerViewModel.composerFlow].
 * @param hostState       State handle for [OrchestratorViewModel.hostFlow].
 * @param onOpenChatFilePreview External file-preview callback.
 */
@Composable
internal fun rememberChatDerivedState(
    routeSessionId: String?,
    routeInstance: Long,
    chatState: State<ChatState>,
    sessionListState: State<SessionListState>,
    settingsState: State<SettingsState>,
    composerState: State<ComposerState>,
    hostState: State<HostState>,
    onOpenChatFilePreview: (workdir: String?, path: String) -> Unit,
): ChatDerivedState {
    // ── Route identity group (:231-258) ───────────────────────────────────
    // These were plain vals in ChatScaffold — each becomes a `derivedStateOf`
    // wrapping the original derivation. Function parameters (routeSessionId,
    // routeInstance) are included as remember keys since they are not
    // snapshot-tracked.

    val chromeSessionId: State<String?> = remember(routeSessionId) {
        derivedStateOf {
            chromeSessionIdFor(routeSessionId, chatState.value.currentSessionId)
        }
    }
    val onParameterizedRoute: State<Boolean> = remember(routeSessionId) {
        derivedStateOf { routeSessionId != null }
    }
    val routeOwnedContent: State<LoadedContent?> = remember(routeSessionId, routeInstance) {
        derivedStateOf {
            routeSessionId
                ?.takeIf { isRouteContentRenderable(it, chatState.value.content, routeInstance) }
                ?.let { chatState.value.content }
        }
    }
    val renderedMessages: State<List<Message>> = remember {
        derivedStateOf {
            if (onParameterizedRoute.value) routeOwnedContent.value?.messages ?: emptyList()
            else chatState.value.messages
        }
    }
    val renderedPartsByMessage: State<Map<String, List<Part>>> = remember {
        derivedStateOf {
            if (onParameterizedRoute.value) routeOwnedContent.value?.partsByMessage ?: emptyMap()
            else chatState.value.partsByMessage
        }
    }
    val renderedStreamingTexts: State<Map<String, String>> = remember {
        derivedStateOf {
            if (onParameterizedRoute.value) routeOwnedContent.value?.streamingPartTexts ?: emptyMap()
            else chatState.value.streamingPartTexts
        }
    }
    val renderedStreamingReasoning: State<Part?> = remember {
        derivedStateOf {
            if (onParameterizedRoute.value) routeOwnedContent.value?.streamingReasoningPart
            else chatState.value.streamingReasoningPart
        }
    }

    // ── Session identity group (:454-483) ─────────────────────────────────
    // `sessionsById` EXACT remember keys: (sessionList.sessions,
    // sessionList.directorySessions, sessionList.childSessions).
    val sessionsById: State<Map<String, Session>> = remember(
        sessionListState.value.sessions,
        sessionListState.value.directorySessions,
        sessionListState.value.childSessions,
    ) {
        derivedStateOf {
            allSessionsById(
                sessionListState.value.sessions,
                sessionListState.value.directorySessions,
                sessionListState.value.childSessions,
            )
        }
    }
    val curSession: State<Session?> = remember {
        derivedStateOf {
            chromeSessionId.value?.let { sessionsById.value[it] }
        }
    }
    // `effectiveBusy` EXACT remember keys: (sessionList.activeSessionIds,
    // sessionList.sessionStatuses).
    val effectiveBusy: State<Set<String>> = remember(
        sessionListState.value.activeSessionIds,
        sessionListState.value.sessionStatuses,
    ) {
        derivedStateOf {
            effectiveBusySessionIds(
                sessionListState.value.activeSessionIds,
                sessionListState.value.sessionStatuses,
            )
        }
    }
    val curCutoff: State<RevertCutoff?> = remember {
        derivedStateOf {
            chromeSessionId.value?.let(chatState.value.revertCutoffs::get)
        }
    }
    val curRevertMessageId: State<String?> = remember {
        derivedStateOf {
            val cs = curSession.value
            val co = curCutoff.value
            if (cs != null) cs.revert?.messageId else co?.messageId
        }
    }
    val curSessionStatus: State<SessionStatus?> = remember {
        derivedStateOf {
            currentSessionStatus(sessionListState.value.sessionStatuses, chromeSessionId.value)
        }
    }

    // ── Context usage — Compose-safe redesign (bF2) ─────────────────────────
    // computeContextUsage is PURE over (renderedMessages, providers) — both
    // snapshot-backed — and is still evaluated EVERY composition (freshness over
    // memoization: a host-switch/provider refresh updates usage even when
    // messages are unchanged; kept per the pre-bF2 intent). The write-through
    // into the snapshot-backed cache handle moves from the composition phase
    // into SideEffect (apply phase): writing a MutableState mid-composition can
    // invalidate readers of the same pass; SideEffect runs once per APPLIED
    // composition, off-phase. Sticky semantics preserved VERBATIM: a null
    // computation NEVER overwrites the last non-null usage (`?.let`).
    val computedContextUsage: ContextUsage? =
        computeContextUsage(renderedMessages.value, settingsState.value.providers)
    val cachedContextUsageState = remember { mutableStateOf(computedContextUsage) }
    SideEffect {
        computedContextUsage?.let { cachedContextUsageState.value = it }
    }

    // ── Agent / Model group (:556-600) ────────────────────────────────────
    // `visibleAgents` EXACT remember keys: (settings.agents).
    val visibleAgents: State<Set<String>> = remember(settingsState.value.agents) {
        derivedStateOf {
            settingsState.value.agents.filter { it.isVisible }.map { it.name }.toSet()
        }
    }
    // `effectiveAgent` EXACT remember keys: (chat.pendingAgent, curSession,
    // renderedMessages, visibleAgents, onParameterizedRoute).
    val effectiveAgent: State<String?> = remember(
        chatState.value.pendingAgent,
        curSession.value,
        renderedMessages.value,
        visibleAgents.value,
        onParameterizedRoute.value,
    ) {
        derivedStateOf {
            val sessionAgent = curSession.value?.agent?.takeIf {
                it.isNotBlank() && it in visibleAgents.value
            }
            val inferred = inferCurrentAgent(renderedMessages.value, visibleAgents.value)
            if (onParameterizedRoute.value) {
                sessionAgent ?: inferred
            } else {
                chatState.value.pendingAgent ?: sessionAgent ?: inferred
            }
        }
    }
    // `effectiveModel` EXACT remember keys: (chat.pendingModel, curSession,
    // routeOwnedContent, renderedMessages, visibleAgents,
    // onParameterizedRoute).
    val effectiveModel: State<Message.ModelInfo?> = remember(
        chatState.value.pendingModel,
        curSession.value,
        routeOwnedContent.value,
        renderedMessages.value,
        visibleAgents.value,
        onParameterizedRoute.value,
    ) {
        derivedStateOf {
            val m = curSession.value?.model
            val mid = m?.id
            val mpid = m?.providerId
            val converted =
                if (mid != null && mid.isNotBlank() && mpid != null && mpid.isNotBlank())
                    Message.ModelInfo(modelId = mid, providerId = mpid)
                else null
            val inferred = inferCurrentModel(renderedMessages.value, visibleAgents.value)
            if (onParameterizedRoute.value) {
                routeOwnedContent.value?.currentModel ?: converted ?: inferred
            } else {
                chatState.value.pendingModel ?: converted ?: inferred
            }
        }
    }

    // ── Activity / matching group (:601-660) ──────────────────────────────
    val currentSessionIsRunning: State<Boolean> = remember {
        derivedStateOf {
            curSessionStatus.value?.let { it.isBusy || it.isRetry } == true ||
                chromeSessionId.value?.let { it in composerState.value.sendingSessionIds } == true
        }
    }
    val isCurrentSessionSending: State<Boolean> = remember {
        derivedStateOf {
            chromeSessionId.value?.let { it in composerState.value.sendingSessionIds } == true
        }
    }
    // `currentActivity` EXACT remember keys: (chromeSessionId, curSessionStatus,
    // curRevertMessageId, curCutoff, renderedMessages, renderedPartsByMessage,
    // renderedStreamingReasoning, renderedStreamingTexts).
    val currentActivity: State<CurrentSessionActivity?> = remember(
        chromeSessionId.value,
        curSessionStatus.value,
        curRevertMessageId.value,
        curCutoff.value,
        renderedMessages.value,
        renderedPartsByMessage.value,
        renderedStreamingReasoning.value,
        renderedStreamingTexts.value,
    ) {
        derivedStateOf {
            currentSessionActivity(
                sessionId = chromeSessionId.value,
                status = curSessionStatus.value,
                messages = visibleMessages(
                    renderedMessages.value,
                    curSession.value,
                    curCutoff.value,
                ),
                partsByMessage = renderedPartsByMessage.value,
                streamingReasoningPart = renderedStreamingReasoning.value,
                streamingPartTexts = renderedStreamingTexts.value,
            )
        }
    }
    // `matchingQuestions` EXACT remember keys: (sessionList.pendingQuestions,
    // chromeSessionId, sessionsById).
    val matchingQuestions: State<List<QuestionRequest>> = remember(
        sessionListState.value.pendingQuestions,
        chromeSessionId.value,
        sessionsById.value,
    ) {
        derivedStateOf {
            val root = chromeSessionId.value?.let { rootIdOf(it, sessionsById.value) }
            if (root != null) questionsInTree(root, sessionListState.value.pendingQuestions, sessionsById.value)
            else emptyList()
        }
    }
    val pendingQuestion: State<QuestionRequest?> = remember {
        derivedStateOf { matchingQuestions.value.firstOrNull() }
    }
    // `pendingPermission` EXACT remember keys: (sessionList.pendingPermissions,
    // chromeSessionId).
    val pendingPermission: State<PermissionRequest?> = remember(
        sessionListState.value.pendingPermissions,
        chromeSessionId.value,
    ) {
        derivedStateOf {
            sessionListState.value.pendingPermissions.firstOrNull { it.sessionId == chromeSessionId.value }
        }
    }

    // ── Host profile (:776) ───────────────────────────────────────────────
    val curHostProfile: State<HostProfile?> = remember {
        derivedStateOf {
            currentHostProfile(hostState.value.hostProfiles, hostState.value.currentHostProfileId)
        }
    }

    // ── Drawer sessions (:889-897) ────────────────────────────────────────
    // `recentSessionsForDrawer` EXACT remember keys:
    // (sessionList.sessions, sessionList.directorySessions).
    val recentSessionsForDrawer: State<List<Session>> = remember(
        sessionListState.value.sessions,
        sessionListState.value.directorySessions,
    ) {
        derivedStateOf {
            (sessionListState.value.sessions + sessionListState.value.directorySessions.values.flatten())
                .distinctBy { it.id }
                .filter { it.parentId == null && !it.isArchived }
                .sortedByDescending { it.time?.updated ?: 0L }
        }
    }

    // ── Callback (:478-480) — not a State, exact remember keys: (curSession,
    // onOpenChatFilePreview).
    val onChatFileClick: (String) -> Unit = remember(curSession.value, onOpenChatFilePreview) {
        { path -> onOpenChatFilePreview(curSession.value?.directory, path) }
    }

    return ChatDerivedState(
        chromeSessionId = chromeSessionId,
        onParameterizedRoute = onParameterizedRoute,
        routeOwnedContent = routeOwnedContent,
        renderedMessages = renderedMessages,
        renderedPartsByMessage = renderedPartsByMessage,
        renderedStreamingTexts = renderedStreamingTexts,
        renderedStreamingReasoning = renderedStreamingReasoning,
        sessionsById = sessionsById,
        curSession = curSession,
        effectiveBusy = effectiveBusy,
        curCutoff = curCutoff,
        curRevertMessageId = curRevertMessageId,
        curSessionStatus = curSessionStatus,
        cachedContextUsageState = cachedContextUsageState,
        visibleAgents = visibleAgents,
        effectiveAgent = effectiveAgent,
        effectiveModel = effectiveModel,
        currentSessionIsRunning = currentSessionIsRunning,
        isCurrentSessionSending = isCurrentSessionSending,
        currentActivity = currentActivity,
        matchingQuestions = matchingQuestions,
        pendingQuestion = pendingQuestion,
        pendingPermission = pendingPermission,
        curHostProfile = curHostProfile,
        recentSessionsForDrawer = recentSessionsForDrawer,
        onChatFileClick = onChatFileClick,
    )
}
