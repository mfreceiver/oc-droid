// ChatNavigationEffects.kt — side-effect composables extracted from
// ChatScaffold. Renders nothing; pure effect host containing LaunchedEffect,
// LifecycleEventEffect, and BackHandler blocks.
//
// §5.2 extraction targets:
//  • checkpoint-consume LaunchedEffect(chromeSessionId, routeSavedStateHandle)  :281-288
//  • onOpenSubAgentNavigate callback factory                                   :314-330
//  • reconcile state machine + LaunchedEffect + LifecycleEventEffect
//    (ON_PAUSE / ON_RESUME)                                                     :502-530
//  • parent-session BackHandler(enabled = parent != null)                       :668-679
//  • drawer BackHandler(enabled = drawerState.isOpen)                          :712-714
//  • UiEvent snackbar collection, stale-notice snackbar, compacting auto-clear  :718-766
//
// Non-negotiable invariant: BackHandler order is parent handler FIRST, drawer
// handler AFTER — the LIFO contract (documented at ChatScaffold.kt:703-714) is
// preserved by construction.

package cn.vectory.ocdroid.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.material3.DrawerState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.LifecycleEventEffect
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.ui.ChatState
import cn.vectory.ocdroid.ui.ChatViewModel
import cn.vectory.ocdroid.ui.OrchestratorViewModel
import cn.vectory.ocdroid.ui.ScrollCheckpoint
import cn.vectory.ocdroid.ui.SessionViewModel
import cn.vectory.ocdroid.ui.UiEvent
import cn.vectory.ocdroid.ui.checkpointKeyForChild
import cn.vectory.ocdroid.ui.consumeAnySubAgentCheckpoint
import cn.vectory.ocdroid.ui.resolveMessage
import cn.vectory.ocdroid.ui.showTimed

/**
 * §Item15b: remember-factory for the `onOpenSubAgentNavigate` callback.
 * Extracted from ChatScaffold.kt:314-330 for encapsulation in
 * [ChatNavigationEffects].
 */
@Composable
internal fun rememberOnOpenSubAgentNavigate(
    chromeSessionId: String?,
    routeSavedStateHandle: SavedStateHandle?,
    sessionVM: SessionViewModel,
    orchestratorVM: OrchestratorViewModel,
): (childSessionId: String, checkpoint: ScrollCheckpoint) -> Unit = remember(
    chromeSessionId,
    routeSavedStateHandle,
    sessionVM,
    orchestratorVM,
) {
    { childSessionId, checkpoint ->
        val capturedFromParentId = chromeSessionId
        sessionVM.openSubAgent(childSessionId, checkpoint) { resolvedId, cp ->
            routeSavedStateHandle?.set(
                checkpointKeyForChild(resolvedId),
                cp.copy(capturedFromSessionId = capturedFromParentId),
            )
            orchestratorVM.navigateToChat(resolvedId)
        }
    }
}

/**
 * §Item15b: pure-effect composable that hosts all side-effect blocks from
 * [ChatScaffold]. Renders nothing; returns nothing.
 *
 * BackHandler LIFO order (preserved by construction):
 * 1. Parent-session BackHandler (`enabled = parent != null`) — registered FIRST.
 * 2. Drawer BackHandler (`enabled = drawerState.isOpen`) — registered SECOND
 *    (higher priority; closes drawer before parent handler fires).
 */
@Composable
internal fun ChatNavigationEffects(
    chromeSessionId: String?,
    curSession: Session?,
    chatState: State<ChatState>,
    routeSavedStateHandle: SavedStateHandle?,
    chatVM: ChatViewModel,
    sessionVM: SessionViewModel,
    orchestratorVM: OrchestratorViewModel,
    drawerState: DrawerState,
    closeDrawerAction: () -> Unit,
    snackbarHostState: SnackbarHostState,
    currentSessionIsRunning: Boolean,
    onSnackbarErrorShowDetail: (String) -> Unit,
) {
    // ── Checkpoint-consume LaunchedEffect (:281-288) ──────────────────────
    LaunchedEffect(chromeSessionId, routeSavedStateHandle) {
        val handle = routeSavedStateHandle ?: return@LaunchedEffect
        val sid = chromeSessionId ?: return@LaunchedEffect
        val cp = consumeAnySubAgentCheckpoint(handle, sid)
        if (cp != null) {
            chatVM.requestScrollRestore(sid, cp)
        }
    }

    // ── Reconcile state machine (:502-530) ────────────────────────────────
    var reconcileState by remember { mutableStateOf(ReconcileTriggerState()) }

    // Session switch or first composition: reconcile directly.
    LaunchedEffect(chromeSessionId) {
        val sid = chromeSessionId
        if (sid != null) {
            val (shouldReconcile, nextState) = reconcileState.onSessionChange(sid)
            reconcileState = nextState
            if (shouldReconcile) {
                chatVM.reconcilePendingQuestions()
            }
        }
    }

    // Genuine background → mark pause for next ON_RESUME.
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        reconcileState = reconcileState.onPause()
    }

    // Foreground return: only reconcile for genuine pause→resume.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val (shouldReconcile, nextState) = reconcileState.onResume(chromeSessionId)
        reconcileState = nextState
        if (shouldReconcile && chromeSessionId != null) {
            chatVM.reconcilePendingQuestions()
        }
    }

    // ── Parent-session BackHandler — registered FIRST (:668-679) ──────────
    val parent = curSession?.parentId
    var lastParent by remember { mutableStateOf<String?>(null) }
    if (parent != null) lastParent = parent
    BackHandler(enabled = parent != null) {
        sessionVM.returnToParent { pid -> orchestratorVM.navigateToChat(pid) }
    }

    // ── Drawer BackHandler — registered SECOND (:712-714, LIFO) ──────────
    // The drawer handler MUST be composed AFTER the parent handler so that
    // an open drawer's back closes the drawer FIRST (LIFO contract).
    BackHandler(enabled = drawerState.isOpen) {
        closeDrawerAction()
    }

    // ── UiEvent error/success/info/debug snackbar collection (:718-766) ──
    val context = LocalContext.current
    val errorMessage = stringResource(R.string.chat_error_occurred)
    val errorActionLabel = stringResource(R.string.chat_view)
    val staleNoticeMessage = stringResource(R.string.chat_stale_notice)
    val staleNoticeActionLabel = stringResource(R.string.common_refresh)

    LaunchedEffect(Unit) {
        orchestratorVM.uiEvents.collect { event ->
            val message = event.resolveMessage(context)
            when (event) {
                is UiEvent.Error -> {
                    snackbarHostState.showTimed(
                        message = errorMessage,
                        durationMillis = 3_000L,
                        actionLabel = errorActionLabel,
                        onAction = { onSnackbarErrorShowDetail(message) },
                    )
                }
                is UiEvent.Success -> {
                    snackbarHostState.showTimed(
                        message = message,
                        durationMillis = 2_500L
                    )
                }
                is UiEvent.Info -> {
                    snackbarHostState.showTimed(
                        message = message,
                        durationMillis = 2_500L
                    )
                }
                is UiEvent.Debug -> Unit
            }
        }
    }

    // §B2 rev-gpt MAJOR 2: stale-notice snackbar (:751-758).
    LaunchedEffect(chatState.value.staleNotice, chromeSessionId) {
        if (chatState.value.staleNotice && chromeSessionId != null) {
            snackbarHostState.showTimed(
                message = staleNoticeMessage,
                actionLabel = staleNoticeActionLabel,
                onAction = { chatVM.refreshCurrentSession(chromeSessionId) },
            )
        }
    }

    // Compacting auto-clear (:760-766).
    LaunchedEffect(currentSessionIsRunning, chatState.value.isCompacting) {
        if (chatState.value.isCompacting && !currentSessionIsRunning) {
            if (System.currentTimeMillis() - chatState.value.compactStartedAt > 3000) {
                chatVM.clearCompacting()
            }
        }
    }
}
