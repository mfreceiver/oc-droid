package cn.vectory.ocdroid.ui.chat

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.ui.files.FilesScreen
import cn.vectory.ocdroid.ui.files.FilesViewModel
import cn.vectory.ocdroid.ui.MainViewModel
import cn.vectory.ocdroid.ui.TunnelActivationState
import cn.vectory.ocdroid.ui.computeContextUsage
import cn.vectory.ocdroid.ui.currentHostProfile
import cn.vectory.ocdroid.ui.currentSession
import cn.vectory.ocdroid.ui.currentSessionStatus
import cn.vectory.ocdroid.ui.showTimed
import cn.vectory.ocdroid.ui.visibleMessages
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

/**
 * §B3: provides a [WindowSizeClass] computed once in [cn.vectory.ocdroid.MainActivity]
 * via the M3 entry point `calculateWindowSizeClass(activity)`. Screens read it
 * through this local instead of hand-rolling `LocalConfiguration.current.screenWidthDp >= N`
 * checks, which drift from the canonical M3 breakpoints (Compact <600,
 * Medium 600-839, Expanded ≥840). Nullable so previews / unit tests that run
 * without a provider fall back gracefully rather than crash.
 */
val LocalWindowSizeClass = staticCompositionLocalOf<WindowSizeClass?> { null }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: MainViewModel,
    onNavigateToSettings: () -> Unit = {},
    onNavigateToSessions: () -> Unit = {}
) {
    // §R-17 M5: AppState no longer carries the per-domain mirror fields.
    // ChatScreen reads the authoritative slice flows directly. The only AppState
    // field still consumed here is the cross-domain `error`.
    // §R-17 M5.1 (kimo 🟠#1): subscribe ONLY to error (map+distinctUntilChanged)
    // instead of the whole AppState — the mirror double-write makes `_state` emit
    // on every keystroke / SSE delta, which would recompose ChatScreen each time
    // and cancel the slice-migration recomposition-isolation gains.
    val appStateError by viewModel.state
        .map { it.error }
        .distinctUntilChanged()
        .collectAsStateWithLifecycle(initialValue = null)
    // §success-channel: subscribe to AppState.successMessage (cross-domain,
    // one-shot, same map+distinctUntilChanged pattern as appStateError) so
    // tunnel-activation success and refresh-success render as a positive
    // snackbar instead of being jammed through `error` and misrendered as
    // "发生错误".
    val appStateSuccess by viewModel.state
        .map { it.successMessage }
        .distinctUntilChanged()
        .collectAsStateWithLifecycle(initialValue = null)
    val connection by viewModel.connectionFlow.collectAsStateWithLifecycle()
    val traffic by viewModel.trafficFlow.collectAsStateWithLifecycle()
    val composer by viewModel.composerFlow.collectAsStateWithLifecycle()
    val file by viewModel.fileFlow.collectAsStateWithLifecycle()
    val settings by viewModel.settingsFlow.collectAsStateWithLifecycle()
    val chat by viewModel.chatFlow.collectAsStateWithLifecycle()
    val sessionList by viewModel.sessionListFlow.collectAsStateWithLifecycle()
    val unread by viewModel.unreadFlow.collectAsStateWithLifecycle()
    val host by viewModel.hostFlow.collectAsStateWithLifecycle()
    var showFileBrowser by remember { mutableStateOf(false) }
    // §顶部 session tab 联动显隐：状态产生在 ChatMessageList（listState 所在），
    // 消费在 ChatTopBar（AnimatedVisibility 包裹 SessionTabStrip）。这里在
    // 共同祖先 ChatScreen 提升 `tabVisible`，通过 onTabVisibilityChange 回调
    // 从 ChatMessageList 写入，通过 prop 从 ChatTopBar 读取。默认显示。
    var tabVisible by remember { mutableStateOf(true) }
    // §Feature B: first back in a root session arms a 1s exit-confirmation
    // window; the BackHandler is disabled while pendingExit is true so the next
    // back propagates to the system and exits the app.
    var pendingExit by remember { mutableStateOf(false) }
    // §error-detail-dialog: when a detailed error message is shown via snackbar,
    // the full text is stashed here for the "查看" dialog instead of cluttering
    // the snackbar itself.
    var errorDetail by remember { mutableStateOf<String?>(null) }
    // §G4 会话切换时重置 tab 可见——避免从隐藏态切到新会话后 tab 仍不可见。
    LaunchedEffect(chat.currentSessionId) { tabVisible = true }
    // §R-17 Stage 2: expandedParts collect moved INTO ChatMessageList (it now
    // subscribes to viewModel.expandedParts directly), so ChatScreen no longer
    // needs to observe it — toggling a card recomposes only ChatMessageList.
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // §C1: standard M3 Snackbar host. Replaces the former top-aligned AppToast
    // for both AppState.error and bottom-bar question-submit failures. Mounted
    // inside the chat-area Box below (BottomCenter); showSnackbar() is driven
    // by LaunchedEffects keyed on the error sources.
    val snackbarHostState = remember { SnackbarHostState() }
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        scope.launch {
            viewModel.addImageAttachments(loadImageAttachments(context, uris))
        }
    }

    // §R-17 Stage 2: stable lambda references so ChatMessageList / ChatInputBar
    // (which now collect their own slices) can be SKIPPED by the Compose runtime
    // when their slice inputs are unchanged. Without this, a fresh lambda
    // identity on every ChatScreen recomposition would force both children to
    // recompose regardless of slice state. showFileBrowser is a
    // remember{mutableStateOf} delegate whose setter is referentially stable,
    // so capturing it inside remember(viewModel) is safe.
    val onChatFileClick: (String) -> Unit = remember(viewModel) {
        { path -> viewModel.showFileInFiles(path, "chat"); showFileBrowser = true }
    }
    val onAddImages: () -> Unit = remember(imagePickerLauncher) {
        { imagePickerLauncher.launch("image/*") }
    }

    // Cross-slice derived views (R-17 M5: moved out of the AppState getters).
    val curSession = currentSession(sessionList.sessions, chat.currentSessionId)
    val curSessionStatus = currentSessionStatus(sessionList.sessionStatuses, chat.currentSessionId)
    // Cache last non-null contextUsage so the ring stays visible during streaming
    val computedContextUsage = computeContextUsage(chat.messages, settings.providers)
    var cachedContextUsage by remember { mutableStateOf(computedContextUsage) }
    computedContextUsage?.let { cachedContextUsage = it }
    val currentSessionIsRunning = curSessionStatus?.let { it.isBusy || it.isRetry } == true ||
        chat.currentSessionId?.let { it in composer.sendingSessionIds } == true
    val currentActivity = remember(
        chat.currentSessionId,
        curSessionStatus,
        chat.messages,
        chat.partsByMessage,
        chat.streamingReasoningPart,
        chat.streamingPartTexts,
    ) {
        currentSessionActivity(
            sessionId = chat.currentSessionId,
            status = curSessionStatus,
            messages = visibleMessages(chat.messages, curSession),
            partsByMessage = chat.partsByMessage,
            streamingReasoningPart = chat.streamingReasoningPart,
            streamingPartTexts = chat.streamingPartTexts,
        )
    }


    // #14: edge-swipe / system-back returns to the parent session when viewing a
    // child session. Registered here (deeper than PhoneLayout's pager-level
    // BackHandler from #12) so it wins dispatch priority while a child session
    // is open. lastParent caches the most recent non-null parentId so the back
    // callback still resolves the right target even if state recomposes mid-press.
    val parent = curSession?.parentId
    var lastParent by remember { mutableStateOf<String?>(null) }
    if (parent != null) lastParent = parent
    BackHandler(enabled = parent != null) { lastParent?.let { viewModel.selectSession(it) } }

    // §Feature B: root-session double-confirm before system back exits the app.
    // First back shows a 1s snackbar and disables this handler; a second back
    // within the window propagates to the system. After 1s the handler re-arms.
    BackHandler(enabled = parent == null && !pendingExit) {
        pendingExit = true
        scope.launch {
            snackbarHostState.showTimed(
                message = "退出请再次滑动",
                durationMillis = 1000L
            )
        }
    }
    // §Feature B: auto-reset the exit-confirmation window after 1s. This also
    // dismisses the snackbar naturally because showTimed uses Indefinite + a
    // 1s timeout; when pendingExit flips back the UI re-arms the handler.
    LaunchedEffect(pendingExit) {
        if (pendingExit) {
            delay(1_000)
            pendingExit = false
        }
    }

    // Status-bar inset is handled by ChatTopBar's M3 TopAppBar (its default
    // TopAppBarDefaults.windowInsets consumes the status bar inset), so this
    // Column must NOT also apply statusBarsPadding() — that would double-pad.
    // If ChatTopBar ever reverts to not handling the inset, re-add
    // .statusBarsPadding() here.
    // R-17 M1: derive ChatTopBarState inside a remembered derivedStateOf.
    // The lambda only reads the AppState slices that ChatTopBar actually
    // consumes (sessions / currentSessionId / statuses / agents / cached
    // contextUsage / connection / host profiles / tunnel / tabs / traffic /
    // serverVersion). It deliberately does NOT read the high-frequency SSE
    // fields (streamingPartTexts / visibleMessages / partsByMessage /
    // streamingReasoningPart / inputText). An SSE token delta mutates only
    // those fields, which makes the State<AppState> emit a new value and
    // forces this block to re-evaluate, but the resulting ChatTopBarState is
    // structurally equal to the previous one (data-class equals) — so
    // derivedStateOf suppresses the change and ChatTopBar is skipped.
    val curHostProfile = currentHostProfile(host.hostProfiles, host.currentHostProfileId)
    val topBarState by remember {
        derivedStateOf {
            // §tunnel-reactivity: recompute curHostProfile / curSession INSIDE the
            // lambda (not captured from the outer scope). remember{} creates the
            // DerivedState once; outer vals are captured by value at first
            // composition and would go stale on host/session switch (the tunnel
            // activate button / parent title failed to update until recompose-
            // from-scratch). Reading host.*/sessionList.*/chat.* State here makes
            // derivedStateOf re-evaluate with fresh values.
            val curHostProfile = currentHostProfile(host.hostProfiles, host.currentHostProfileId)
            val curSession = currentSession(sessionList.sessions, chat.currentSessionId)
            // Resolve openSessionIds to actual Session objects for the
            // top-bar tab strip. Filtered to root sessions (parentId == null)
            // so sub-agents never duplicate the title-slot breadcrumb. Also
            // drops archived sessions as a render defense — the cold-start,
            // user-archive, and SSE session.updated paths all evict archived
            // ids from openSessionIds, but this guarantees no archived tab
            // renders even if a path misses (e.g. a stale cached id).
            val resolvedOpenSessions = sessionList.openSessionIds
                .mapNotNull { id -> sessionList.sessions.find { it.id == id } }
                .filter { it.parentId == null && !it.isArchived }
            ChatTopBarState(
                sessions = sessionList.sessions,
                currentSessionId = chat.currentSessionId,
                sessionStatuses = sessionList.sessionStatuses,
                hasMoreSessions = sessionList.hasMoreSessions,
                isLoadingMoreSessions = sessionList.isLoadingMoreSessions,
                isRefreshingSessions = sessionList.isRefreshingSessions,
                expandedSessionIds = sessionList.expandedSessionIds,
                agents = settings.agents.filter { it.isVisible },
                selectedAgentName = settings.selectedAgentName,
                contextUsage = cachedContextUsage,
                sessionTodos = sessionList.sessionTodos[chat.currentSessionId ?: ""] ?: emptyList(),
                hostName = curHostProfile?.name ?: "No Host",
                isConnected = connection.isConnected,
                isConnecting = connection.isConnecting,
                connectionPhase = connection.connectionPhase,
                hostProfiles = host.hostProfiles,
                currentHostProfileId = host.currentHostProfileId,
                tunnelActivationState = connection.tunnelActivationState,
                showTunnelAuth = (curHostProfile?.tunnelPasswordId != null),
                openSessions = resolvedOpenSessions,
                unreadSessions = unread.unreadSessions,
                draftWorkdir = composer.draftWorkdir,
                parentSessionId = curSession?.parentId,
                parentSessionTitle = curSession?.parentId?.let { pid ->
                    sessionList.sessions.firstOrNull { it.id == pid }?.displayName
                },
                trafficSent = traffic.trafficSent,
                trafficReceived = traffic.trafficReceived,
                serverVersion = connection.serverVersion,
                // §model-selection: providers + disabledModels + currentModel
                // drive the top-bar model quick-switch picker.
                providers = settings.providers,
                disabledModels = settings.disabledModels,
                currentModel = chat.currentModel
            )
        }
    }

    // R-17 M1 R1: wrap ChatTopBarActions in remember so the actions instance
    // (and its lambda / method-reference fields) stays referentially stable
    // across recompositions. Without this the actions argument was a fresh
    // instance on every recomposition and defeated the topBarState
    // derivedStateOf — ChatTopBar recomposed regardless of whether topBarState
    // had changed. Keys: viewModel is a @Stable hilt single-instance VM (stable
    // for the screen's lifetime); onNavigateToSettings / onNavigateToSessions
    // are nav lambdas sourced from the parent, listed as keys so a new parent
    // identity re-builds the actions bundle. The VM method references and the
    // wrapping lambdas only capture viewModel (itself stable), so they do not
    // need to be keys.
    val topBarActions = remember(viewModel, onNavigateToSettings, onNavigateToSessions) {
        ChatTopBarActions(
            onSelectSession = viewModel::selectSession,
            onCloseSession = viewModel::closeSession,
            onSelectAgent = viewModel::selectAgent,
            onNavigateToSettings = onNavigateToSettings,
            onNavigateToSessions = onNavigateToSessions,
            onRefreshMessages = { viewModel.refreshCurrentSession() },
            onRefreshTrafficStats = viewModel::refreshTrafficStats,
            onSelectHost = { viewModel.selectHostProfile(it) },
            onActivateTunnel = { viewModel.activateTunnelForCurrentHost() },
            onSwitchModel = { providerId, modelId -> viewModel.switchSessionModel(providerId, modelId) },
        )
    }
    // §kimo#6: remember the compact lambda so ChatTopBar can skip when stable.
    val onContextCompact = remember(viewModel) { { viewModel.compactSession() } }

    // §3-scroll-memory: hoisted per-session scroll-position cache. Owned HERE
    // (above the HorizontalPager) so the pager disposing & recreating a page's
    // ChatMessageList on currentSessionId flip no longer drops the user's
    // scroll position for the page they swiped away from. Previously these
    // were `remember{}` blocks INSIDE ChatMessageList — bound to its
    // composition lifetime — so a horizontal swipe A→B discarded A's saved
    // position (the A page's ChatMessageList was disposed when its
    // `sessionId == chat.currentSessionId` guard flipped to false) and B
    // started fresh. Hoisting fixes the disposal; the saved-position restore
    // logic inside ChatMessageList reads/writes these via params.
    val savedPositions = remember { mutableStateMapOf<String, Pair<Int, Int>>() }
    val accessOrder = remember { mutableStateListOf<String>() }
    // §3-clear: when a session leaves `openSessionIds` (close tab / archive /
    // SSE-driven removal) drop its cached scroll position — the user has
    // signalled they're done with it, and a future reopen should land on the
    // default latest-message view instead of a stale offset for a different
    // message window. Keyed on openSessionIds structural equality (NOT pointer
    // identity — LaunchedEffect compares keys by equals(); List<String>
    // participates in equals so a same-content replacement does NOT re-fire).
    // (gpt-1 fix): the prior `if (open.isNotEmpty())` guard skipped cleanup
    // when the LAST session closed — leaving stale entries that a reopen would
    // wrongly restore. Removed: on cold start savedPositions is itself empty,
    // so `keys - open` = empty - empty = empty → natural no-op; the guard was
    // both unnecessary and harmful.
    LaunchedEffect(sessionList.openSessionIds) {
        val open = sessionList.openSessionIds.toSet()
        val stale = savedPositions.keys - open
        if (stale.isNotEmpty()) {
            stale.forEach { id ->
                savedPositions.remove(id)
                accessOrder.remove(id)
            }
        }
    }
    // §3-clear: manual refresh = full forget (per product decision: the cached
    // position is no longer meaningful after a window reset). The nonce is a
    // monotonic counter incremented in performGlobalColdStartRefresh; observing
    // it via distinctUntilChanged-like LaunchedEffect(key) re-fires on each
    // bump. Initial composition (nonce == 0) is skipped so cold start doesn't
    // pointlessly clear an already-empty cache.
    val refreshNonce = chat.refreshNonce
    LaunchedEffect(refreshNonce) {
        if (refreshNonce > 0L) {
            savedPositions.clear()
            accessOrder.clear()
        }
    }

    // §pager: native HorizontalPager for swipe-to-switch. Each page maps to
    // one root session; adjacent pages are visible during the drag for a book-
    // like pager feel. Sub-agent sessions fall back to direct content.
    val rootSessions = remember(sessionList.openSessionIds, sessionList.sessions) {
        sessionList.openSessionIds
            .mapNotNull { id -> sessionList.sessions.find { it.id == id } }
            .filter { it.parentId == null && !it.isArchived }
    }
    val currentRootIndex = remember(rootSessions, chat.currentSessionId, parent) {
        val id = resolveEffectiveSelectedId(rootSessions, chat.currentSessionId, parent)
        rootSessions.indexOfFirst { it.id == id }.coerceAtLeast(0)
    }
    val pagerState = rememberPagerState(
        initialPage = currentRootIndex,
        pageCount = { rootSessions.size.coerceAtLeast(1) }
    )

    // §#4: hoist the current session's pending question + its answer snapshot
    // here (root ChatScreen scope) so BOTH ChatInputBar (inside the chat
    // Surface) and QuestionCardView (sibling of the Surface) can read/write
    // the same state. The bottom ChatInputBar primary button submits the
    // question via viewModel.replyQuestion, in lockstep with QuestionCardView's
    // own Submit. questionAnswersValid requires EVERY sub-question to have an
    // effective answer (matches the multi-tab Next→Submit flow: Submit only
    // fires when all tabs are answered).
    val pendingQuestion = remember(sessionList.pendingQuestions, chat.currentSessionId) {
        sessionList.pendingQuestions.firstOrNull { it.sessionId == chat.currentSessionId }
    }
    var questionAnswers by remember { mutableStateOf<List<List<String>>>(emptyList()) }
    // 🟠-1: surface bottom-bar question-submit failures to the user (the VM's
    // replyQuestion only Log.w's + invokes onError; it does NOT write to
    // AppState.error, and we can't modify MainViewModel this round). This local
    // error string is surfaced via the M3 Snackbar (see the LaunchedEffect
    // keyed on it below), which auto-dismisses after its duration elapses.
    var questionSubmitError by remember { mutableStateOf<String?>(null) }
    // 🟠-2: in-flight guard for the bottom-bar question submit — prevents
    // double-submits on slow networks (the in-card Submit already has its own
    // isSending guard; this mirrors it for the bottom-bar path).
    var isSubmittingQuestion by remember { mutableStateOf(false) }
    LaunchedEffect(pendingQuestion?.id) {
        // Reset hoisted answers whenever the active question changes
        // (covers a new question arriving, or the current one being
        // dismissed/resolved).
        questionAnswers = emptyList()
        // 🟠-2: a question-id change (incl. →null on success/reject) means the
        // in-flight request has resolved — release the guard. The success path
        // has no onSuccess callback (replyQuestion only offers onError), so
        // observing pendingQuestion becoming null is the success signal.
        isSubmittingQuestion = false
    }
    // §C1: surface AppState.error via a 3s Snackbar (was a top-aligned AppToast
    // that auto-dismissed after 3s, then a Long-duration M3 Snackbar). The
    // LaunchedEffect key is the error value, so a new error relaunches the
    // effect — showTimed suspends until the snackbar finishes (action tap or
    // 3s timeout), at which point we clear the source via viewModel.clearError()
    // (the onDismiss-equivalent). SnackbarHostState serializes concurrent
    // showSnackbar calls, so a simultaneous global-error + question-error still
    // displays in turn.
    LaunchedEffect(appStateError) {
        appStateError?.let { error ->
            // §error-detail: all errors show a generic snackbar + "查看" action
            // that opens a detail dialog with the full message. 10s duration
            // (not the default 3s) so the user has time to notice and tap.
            snackbarHostState.showTimed(
                message = "发生错误",
                durationMillis = 10_000L,
                actionLabel = "查看",
                onAction = { errorDetail = error }
            )
            viewModel.clearError()
        }
    }
    // §C1: bottom-bar question-submit failure → same pattern as above; clears
    // `questionSubmitError` after the snackbar finishes so a new failure can
    // fire again.
    LaunchedEffect(questionSubmitError) {
        questionSubmitError?.let { error ->
            snackbarHostState.showTimed(
                message = "发生错误",
                durationMillis = 10_000L,
                actionLabel = "查看",
                onAction = { errorDetail = error }
            )
            questionSubmitError = null
        }
    }
    // §success-channel: surface AppState.successMessage as a SHORT positive
    // snackbar. No "查看" action (success messages are short by design —
    // "隧道激活成功" / "已刷新"), 2.5s duration so it auto-dismisses quickly
    // without lingering like the 10s error variant. Consumes on read so a
    // stale value can't re-fire on recomposition.
    LaunchedEffect(appStateSuccess) {
        appStateSuccess?.let { message ->
            snackbarHostState.showTimed(
                message = message,
                durationMillis = 2_500L
            )
            viewModel.clearSuccessMessage()
        }
    }
    // §Phase1E stale notice — now a 3s M3 Snackbar (was a top-aligned
    // StaleNoticeBanner). Fires when returning after a >5min background
    // absence. Carries the "reload all sessions" action; tapping it within the
    // 3s window triggers a full cold-start refresh. If the timeout fires first
    // the snackbar auto-dismisses — the stale flag clears naturally on session
    // switch / next successful catch-up, so no source-clear is needed here.
    // Keyed on (staleNotice, currentSessionId) so switching into the stale
    // session re-arms it.
    LaunchedEffect(chat.staleNotice, chat.currentSessionId) {
        if (chat.staleNotice && chat.currentSessionId != null) {
            snackbarHostState.showTimed(
                message = "长时间未查看，仅显示最新内容。",
                actionLabel = "刷新",
                onAction = { viewModel.refreshCurrentSession() }
            )
        }
    }
    // §compact: clear the compacting flag when the session transitions from
    // busy → idle (compaction complete on the server). The 3s floor guard
    // prevents premature clearing during the POST→server-startup gap.
    LaunchedEffect(currentSessionIsRunning, chat.isCompacting) {
        if (chat.isCompacting && !currentSessionIsRunning) {
            if (System.currentTimeMillis() - chat.compactStartedAt > 3000) {
                viewModel.clearCompacting()
            }
        }
    }
    // §pager: when the pager settles on a new page, select that session.
    // Guarded to root sessions so a sub-agent view never accidentally switches
    // tabs via the pager.
    //
    // §draft-guard (new-session bug fix): keep the LATEST currentSessionId in a
    // rememberUpdatedState so this long-lived effect (keyed on the stable
    // pagerState, launched once) reads the current value instead of the stale
    // launch-time capture, and SKIP the auto-select while currentSessionId is
    // null (draft mode — entered via createSessionInWorkdir's "new session"
    // affordance). Without this guard, the snapshotFlow's INITIAL emit of
    // settledPage on Chat entry would find a real root session and call
    // onSelectSession on it, clobbering the draft (showing an old session
    // instead of the empty composer the user just asked for). The session only
    // materialises on first send; until then the pager must not steal focus.
    val currentSessionIdLatest by rememberUpdatedState(chat.currentSessionId)
    // §flicker-fix: this settle effect is keyed only on pagerState so it does
    // NOT restart on every tab-list change (restarting would re-emit the
    // current settledPage and auto-select during the close transition). That
    // means its closure captures rootSessions/parent at launch time. After a
    // tab close the captured list is stale, so a swipe resolves the settled
    // page index against the OLD list — often landing on the just-closed
    // session, which SessionSwitcher reopens, which the external-sync effect
    // below then fights, oscillating the tabs a↔b. Mirror the
    // rememberUpdatedState pattern commit 2344e38 used for currentSessionId so
    // the collector always resolves against the freshest list + parent.
    val rootSessionsLatest by rememberUpdatedState(rootSessions)
    val parentLatest by rememberUpdatedState(parent)
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { page ->
                val session = rootSessionsLatest.getOrNull(page)
                // §close-left-guard: only drive a selection when the pager has
                // truly settled on the page it currently shows. After closing a
                // tab LEFT of the current one, the list shifts and settledPage
                // can transiently lag currentPage (which the external-sync
                // effect has already corrected via scrollToPage). Requiring
                // page == currentPage filters that stale emission so we don't
                // fire a one-frame onSelectSession on the shifted neighbour.
                if (session != null && currentSessionIdLatest != null &&
                    session.id != currentSessionIdLatest && parentLatest == null &&
                    page == pagerState.currentPage
                ) {
                    topBarActions.onSelectSession(session.id)
                }
            }
    }
    // §pager: when the session changes externally (tab tap, sessions list,
    // back gesture), scroll the pager to match without animation.
    LaunchedEffect(chat.currentSessionId, rootSessions) {
        if (parent == null && rootSessions.isNotEmpty()) {
            val idx = rootSessions.indexOfFirst { it.id == chat.currentSessionId }
            if (idx != -1 && idx != pagerState.currentPage) {
                pagerState.scrollToPage(idx)
            }
        }
    }
    val questionAnswersValid = pendingQuestion?.let { pq ->
        pq.questions.isNotEmpty() &&
            pq.questions.indices.all { i ->
                i < questionAnswers.size && questionAnswers[i].isNotEmpty()
            }
    } ?: false

    Column(modifier = Modifier.fillMaxSize()) {
        ChatTopBar(
            state = topBarState,
            actions = topBarActions,
            tabVisible = tabVisible,
            onContextCompact = onContextCompact
        )

        // In draft mode (no session yet but a workdir has been chosen), the
        // chat area is intentionally empty — the user is mid-composition and
        // the session will materialise on first send. We do not render the
        // "select or create session" empty state in that case.
        // §10: on wide screens (M3 WindowSizeClass: Medium 600-839dp or
        // Expanded ≥840dp) wrap the conversation area (messages + composer) in
        // a v2-style card — rounded 10, surface (bg-base), 2dp elevation, 8dp
        // outer padding. On phone (Compact <600dp) the chat stays full-bleed
        // for maximum display area (RectangleShape + transparent + 0 elevation
        // ⇒ an invisible, non-clipping wrapper). The TopBar stays outside the
        // card either way (a full-width top bar is more natural on mobile and
        // avoids double-rounded corners at the top).
        // §B3: `isWide` follows the M3 WindowSizeClass provided by MainActivity
        // (Compact vs Medium/Expanded) instead of a hand-rolled `screenWidthDp
        // >= 600` threshold. The `?: screenWidthDp >= 600` arm is a
        // preview/test fallback when no provider is mounted — it preserves the
        // previous behavior in those contexts.
        val isWide = LocalWindowSizeClass.current
            ?.let { it.widthSizeClass != WindowWidthSizeClass.Compact }
            ?: (LocalConfiguration.current.screenWidthDp >= 600)
        val cardShape = if (isWide) MaterialTheme.shapes.large else RectangleShape
        Surface(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .then(if (isWide) Modifier.padding(8.dp) else Modifier),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = cardShape,
            shadowElevation = if (isWide) 2.dp else 0.dp,
            tonalElevation = if (isWide) 1.dp else 0.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // §pager: chat content wrapper. HorizontalPager shows root
                // sessions side-by-side so adjacent pages are visible during the
                // swipe, giving the book-like "both pages move together" feel.
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    val isDraft = composer.draftWorkdir != null && chat.currentSessionId == null
                    if (chat.currentSessionId != null && parent == null && rootSessions.size >= 2) {
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.fillMaxSize()
                        ) { page ->
                            // §review (momo 🟠-4): defensive bounds guard. When a
                            // root session is archived/deleted from another client,
                            // rootSessions shrinks but pagerState.currentPage can
                            // transiently exceed pageCount until the
                            // currentSessionId/rootSessions sync effect clamps it.
                            // Compose does not guarantee clamping the `page` arg,
                            // so guard against IndexOutOfBoundsException here.
                            val session = rootSessions.getOrNull(page)
                            val sessionId = session?.id
                            if (sessionId != null && sessionId == chat.currentSessionId) {
                                ChatMessageList(
                                    viewModel = viewModel,
                                    onFileClick = onChatFileClick,
                                    onTabVisibilityChange = { tabVisible = it },
                                    savedPositions = savedPositions,
                                    accessOrder = accessOrder
                                )
                            } else {
                                // Adjacent session — messages are not loaded
                                // until the pager settles and selects it.
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator()
                                }
                            }
                        }
                    } else if (chat.currentSessionId != null) {
                        // Single root session, sub-agent session, or pager
                        // disabled — render the message list directly.
                        ChatMessageList(
                            viewModel = viewModel,
                            onFileClick = onChatFileClick,
                            onTabVisibilityChange = { tabVisible = it },
                            savedPositions = savedPositions,
                            accessOrder = accessOrder
                        )
                    } else if (!isDraft) {
                        ChatEmptyState(
                            isConnected = connection.isConnected,
                            isConnecting = connection.isConnecting,
                            connectionPhase = connection.connectionPhase,
                            hostName = curHostProfile?.name
                                ?: curHostProfile?.serverUrl
                                    ?.substringAfter("://")
                                    ?.substringBefore("/")
                                ?: "server",
                            onConnect = { viewModel.testConnection() }
                        )
                    }

                    // §Phase1E stale notice now surfaces as a timed M3 Snackbar (see
                    // the LaunchedEffect keyed on chat.staleNotice above), so no
                    // top-aligned banner is rendered here. The bottom-mounted
                    // SnackbarHost below overlays both error/stale snackbars.

                    // §C1: M3 Snackbar replaces the former top-aligned AppToast for
                    // both AppState.error (tunnel activation / global errors) and the
                    // bottom-bar question-submit failure. Default bottom placement is
                    // accepted per the migration spec. SnackbarHost is the LAST child
                    // of this Box so it overlays the message list / empty state; the
                    // actual showSnackbar() calls are driven by the LaunchedEffects
                    // keyed on `appStateError` / `questionSubmitError` above.
                    SnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )

                    // §Feature C: floating capsule at top-center.
                    // §compact: when isCompacting, show a capsule without abort
                    // button ("压缩中…") instead of the normal thinking capsule.
                    if (chat.isCompacting) {
                        ThinkingCapsuleOverlay(
                            visible = true,
                            text = "压缩中…",
                            startedAtMillis = chat.compactStartedAt.takeIf { it > 0 },
                            onAbort = {},
                            showAbort = false
                        )
                    } else {
                        ThinkingCapsuleOverlay(
                            visible = currentSessionIsRunning && currentActivity != null,
                            text = currentActivity?.text ?: "",
                            startedAtMillis = currentActivity?.startedAtMillis,
                            onAbort = viewModel::abortSession
                        )
                    }

                    // §user-req: 手动刷新/连接期间显示胶囊提示
                    ThinkingCapsuleOverlay(
                        visible = connection.isConnecting && !connection.isConnected,
                        text = "连接中…",
                        startedAtMillis = null,
                        onAbort = { }
                    )
                }

        // §error-detail: dialog showing the full error text when the user taps
        // "查看" on a detailed-error snackbar. Scrollable + selectable so long
        // technical messages (HTTP bodies, stack traces) are readable.
        errorDetail?.let { detail ->
            AlertDialog(
                onDismissRequest = { errorDetail = null },
                title = { Text("错误详情") },
                text = {
                    SelectionContainer {
                        // AlertDialog's text slot has unbounded height, so the
                        // scrollable must be capped with heightIn before
                        // verticalScroll — otherwise "Vertically scrollable
                        // component was measured with an infinity maximum height
                        // constraints" crash. Mirrors ChatServerManagementDialog /
                        // ModelManagementDialog pattern.
                        Column(
                            modifier = Modifier
                                .heightIn(max = 400.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                text = detail,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { errorDetail = null }) {
                        Text(stringResource(R.string.common_done))
                    }
                }
            )
        }
            }
        }

        // §user-req: ChatInputBar 移到 Surface 外部（与 Surface 同级，作为
        // 顶层 Column 的下一个 child），避免被聊天卡片的背景/圆角/elevation 包裹。
        // Input bar is enabled whenever there is either a concrete session OR
        // a draft workdir (so the user can type the first message that will
        // materialise the session).
        if (chat.currentSessionId != null || composer.draftWorkdir != null) {
            ChatInputBar(
                viewModel = viewModel,
                isBusy = currentSessionIsRunning || chat.isCompacting,
                onAddImages = onAddImages,
                pendingQuestion = pendingQuestion,
                questionAnswersValid = questionAnswersValid,
                questionSubmitting = isSubmittingQuestion,
                onSubmitQuestion = {
                    // 🟠-2: guard — if a submit is already in flight, ignore
                    // the tap (the button is also disabled via canSend, but
                    // this is the source-of-truth guard). Structured as a
                    // positive if (not early-return) because onSubmitQuestion
                    // is a param lambda — a bare `return` here would
                    // non-locally return from the ChatScreen composable.
                    if (!isSubmittingQuestion) {
                        pendingQuestion?.let { pq ->
                            isSubmittingQuestion = true
                            // 🟠-1: onError surfaces the failure to the user.
                            // replyQuestion has no onSuccess callback — success
                            // is observed via LaunchedEffect(pendingQuestion?.id)
                            // releasing the guard when the question is removed.
                            viewModel.replyQuestion(pq.id, questionAnswers) {
                                isSubmittingQuestion = false
                                questionSubmitError = context.getString(R.string.question_submit_failed)
                            }
                        }
                    }
                }
            )
        }

        sessionList.pendingPermissions.firstOrNull()?.let { permission ->
            ChatPermissionCard(
                permission = permission,
                onRespond = { response ->
                    viewModel.respondPermission(permission.sessionId, permission.id, response)
                }
            )
        }

        sessionList.pendingQuestions
            .filter { it.sessionId == chat.currentSessionId }
            .firstOrNull()
            ?.let { question ->
                QuestionCardView(
                    question = question,
                    onReply = { answers, onError ->
                        // §momo 🟡-1: card Submit also flips isSubmittingQuestion so the
                        // bottom-bar primary button is disabled in lockstep — unifies the
                        // two guards against cross-path double-submit. onError resets both
                        // (card's isSending via onError, isSubmittingQuestion here); success
                        // → pendingQuestion→null → LaunchedEffect above resets.
                        isSubmittingQuestion = true
                        viewModel.replyQuestion(question.id, answers) {
                            isSubmittingQuestion = false
                            onError()
                        }
                    },
                    onReject = { viewModel.rejectQuestion(question.id) },
                    // §#4: receive the live answer snapshot so the bottom-bar
                    // primary button can submit the question in lockstep with
                    // this card's own Submit. [questionAnswers] is the same
                    // state fed into ChatInputBar above.
                    onAnswersChange = { questionAnswers = it }
                )
            }
    }

    // File browser overlay — opened by tapping a file path in chat (preview).
    // (Project-level browsing lives on the Sessions screen now.) The Box is
    // given an opaque surface background so the chat content underneath does
    // not bleed through (FilesScreen's own root is transparent by design —
    // opaqueness is the host's responsibility).
    if (showFileBrowser) {
        val filesViewModel: FilesViewModel = hiltViewModel()
        // System back closes the in-chat file preview instead of exiting the
        // app (the parent-session BackHandler at L104 is gated on parent!=null,
        // and PhoneLayout's is disabled on the Chat destination, so without
        // this a root-session file preview would back-exit the app).
        BackHandler { showFileBrowser = false }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
        ) {
            FilesScreen(
                viewModel = filesViewModel,
                pathToShow = file.filePathToShowInFiles,
                sessionDirectory = curSession?.directory,
                onCloseFile = {
                    viewModel.clearFileToShow()
                    showFileBrowser = false
                },
                onFileClick = { path ->
                    viewModel.showFileInFiles(path, "chat")
                }
            )
        }
    }
}

/**
 * §Phase1E stale notice now surfaces as a timed M3 Snackbar (see the
 * LaunchedEffect keyed on chat.staleNotice in ChatScreen). The former
 * top-aligned StaleNoticeBanner composable was removed when the notice
 * migrated to the snackbar host.
 */

/**
 * §Feature C: top-center floating thinking capsule. Extracted to a BoxScope
 * extension so [AnimatedVisibility] resolves to the top-level function and can
 * use [BoxScope.align]; inside the root [Column] it was colliding with the
 * [ColumnScope.AnimatedVisibility] overload.
 */
@Composable
private fun BoxScope.ThinkingCapsuleOverlay(
    visible: Boolean,
    text: String,
    startedAtMillis: Long?,
    onAbort: () -> Unit,
    showAbort: Boolean = true
) {
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.align(Alignment.TopCenter)
    ) {
        Box(Modifier.padding(top = 8.dp)) {
            ThinkingCapsule(
                text = text,
                startedAtMillis = startedAtMillis,
                onAbort = onAbort,
                showAbort = showAbort
            )
        }
    }
}

private data class CurrentSessionActivity(
    val text: String,
    val startedAtMillis: Long?,
)

private fun currentSessionActivity(
    sessionId: String?,
    status: SessionStatus?,
    messages: List<Message>,
    partsByMessage: Map<String, List<Part>>,
    streamingReasoningPart: Part?,
    streamingPartTexts: Map<String, String>,
): CurrentSessionActivity? {
    val sid = sessionId ?: return null
    val startedAt = messages.lastOrNull { it.sessionId == sid && it.isUser }?.time?.created
    val text = bestSessionActivityText(sid, status, messages, partsByMessage, streamingReasoningPart, streamingPartTexts)
    return CurrentSessionActivity(text = text, startedAtMillis = startedAt)
}

private fun bestSessionActivityText(
    sessionId: String,
    status: SessionStatus?,
    messages: List<Message>,
    partsByMessage: Map<String, List<Part>>,
    streamingReasoningPart: Part?,
    streamingPartTexts: Map<String, String>,
): String {
    status?.message?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }

    messages.asReversed().forEach { message ->
        if (message.sessionId != sessionId) return@forEach
        (partsByMessage[message.id] ?: emptyList()).asReversed()
            .firstOrNull { it.isTool && it.stateDisplay == "running" }
            ?.let { part -> formatStatusFromPart(part)?.let { return it } }
    }

    if (streamingReasoningPart?.sessionId == sessionId) {
        val key = streamingReasoningPart.id
        return formatThinkingFromReasoningText(streamingPartTexts[key].orEmpty())
    }

    messages.asReversed().forEach { message ->
        if (message.sessionId != sessionId) return@forEach
        (partsByMessage[message.id] ?: emptyList()).asReversed().firstOrNull()?.let { part ->
            formatStatusFromPart(part)?.let { return it }
        }
    }

    return status?.takeIf { it.isRetry }?.let { "Retrying" } ?: "Thinking"
}

private fun formatStatusFromPart(part: Part): String? {
    if (part.isTool) {
        val base = when (part.tool) {
            "task" -> "Delegating"
            "todowrite", "todoread" -> "Planning"
            "read" -> "Gathering context"
            "list", "grep", "glob" -> "Searching codebase"
            "webfetch" -> "Searching web"
            "edit", "write" -> "Making edits"
            "bash" -> "Running commands"
            else -> null
        }
        val topic = (part.toolReason ?: part.toolInputSummary)?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            base != null && topic != null -> "$base - $topic"
            base != null -> base
            else -> null
        }
    }

    if (part.isReasoning) return formatThinkingFromReasoningText(part.text.orEmpty())
    if (part.isText) return "Gathering thoughts"
    return null
}

private fun formatThinkingFromReasoningText(text: String): String {
    val topic = Regex("^\\*\\*([^*]+)\\*\\*").find(text.trim())?.groupValues?.getOrNull(1)?.trim()
    return if (!topic.isNullOrEmpty()) "Thinking - $topic" else "Thinking"
}
