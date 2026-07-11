// StatusSlot.kt — Phase 1C single status slot. Replaces the five competing
// top-center / bottom-anchored overlays in ChatScaffold (compacting capsule,
// retry card, connecting capsule, thinking capsule, question card) plus the
// in-Column permission card with a single composable whose priority rule
// guarantees at most one surface is visible at any time.
//
// RULE (plan §3.3 / scheme C.3, in binding order — highest priority wins):
//   1. Permission (incoming, requires user action)
//   2. Question    (incoming, requires user answer)
//   3. Retry       (failed run, recoverable; SessionStatus.isRetry)
//   4. Compacting  (user-initiated context compaction in progress)
//   5. Running     (agent is producing; activity text available)
//   6. Connecting  (network transitional)
//   0. (none — slot renders nothing)
//
// SESSION-SCOPED FILTER (plan §3.3 G.1 step 6 / scheme E.4 / P5-7):
// callers MUST pre-filter the pending permission + question lists to
// `currentSessionId` before handing them to this composable. The slot does
// not re-apply the filter — the caller is the one source of truth for the
// current session, and re-applying the filter inside the slot would create
// a second read site that has to stay in sync with the caller's other
// pending-X usages. Cross-session pending items are surfaced as a badge on
// the Sessions nav-bar item (Phase 1A's responsibility) and never appear
// in the chat surface.
//
// ANIMATION (scheme C.5): an `AnimatedContent` cross-fade + vertical slide
// when the priority class changes, so a slot swap never jarringly drops
// the old surface. The first-paint (no previous priority) is an immediate
// fade-in only — no slide on a fresh composition.

package cn.vectory.ocdroid.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.PermissionRequest
import cn.vectory.ocdroid.data.model.PermissionResponse
import cn.vectory.ocdroid.data.model.QuestionRequest
import cn.vectory.ocdroid.data.model.SessionStatus

/**
 * The binding priority of the status slot. The slot renders the surface
 * whose priority is the highest among the active inputs. Ties (e.g. a
 * session that is simultaneously `isRetry` and `isRunning`) are settled by
 * the `ordinal` value — the first-declared class wins, matching the
 * spec's order.
 *
 * The ordinal is part of the public API of this file (consumed by
 * [StatusSlotPriorityTest]) and is therefore stable; do NOT reorder the
 * entries without updating the test and the rule doc above.
 */
internal enum class StatusSlotPriority {
    None,
    Connecting,
    Running,
    Compacting,
    Retry,
    Question,
    Permission;

    companion object {
        /**
         * Pure: pick the highest-priority active class. The single decision
         * function for the slot — the slot composable calls this, and
         * StatusSlotPriorityTest verifies the tie-break + boundary cases.
         *
         * Permission and Question are assumed already filtered to the
         * current session by the caller (see file doc). `isBusy` and
         * `isRetry` are SessionStatus flags; we also expose `isRunning`
         * as a separate signal so a session whose status flips to
         * `idle` but the chat surface still has streaming text (e.g.
         * tail of stream during catch-up reload) does not lose the
         * "running" affordance.
         */
        fun pick(
            permission: PermissionRequest?,
            question: QuestionRequest?,
            sessionStatus: SessionStatus?,
            isCompacting: Boolean,
            isRunning: Boolean,
            isConnecting: Boolean,
        ): StatusSlotPriority = when {
            permission != null -> Permission
            question != null -> Question
            sessionStatus?.isRetry == true -> Retry
            isCompacting -> Compacting
            isRunning -> Running
            isConnecting -> Connecting
            else -> None
        }
    }
}

/**
 * §1C: the single status slot. The only thing it does is pick the
 * highest-priority active surface and render it inside an AnimatedContent.
 * All five pre-existing surfaces (SessionRetryCard, Compacting capsule,
 * Connecting capsule, ThinkingCapsule, QuestionCardView) plus the
 * ChatPermissionCard now funnel through this one composable, so the four
 * states (Permission, Question, Running, Connecting) are guaranteed
 * mutually exclusive at the screen-pixel level.
 *
 * The composable takes the pre-computed, pre-filtered inputs and the
 * callbacks. It does NOT read any slice flows; the caller is the source of
 * truth (and the only place that knows the current session id). It is
 * therefore testable in isolation (the priority function is pure) and
 * renderable in any container.
 *
 * @param permission  the current session's pending permission (if any).
 *                    Cross-session pending items are the caller's
 *                    responsibility to filter out (see file doc).
 * @param question    the current session's pending question (if any).
 * @param sessionStatus the current session's status (drives the Retry
 *                    branch — message + countdown).
 * @param isCompacting whether the current session is in context-compaction.
 * @param currentActivityText the live "agent is doing X" text; the Running
 *                    branch renders only when this is non-null.
 * @param isConnecting whether we are mid-handshake to the server.
 * @param onRespondPermission PermissionResponse callback (Permission branch).
 * @param onReplyQuestion  question submit callback (Question branch).
 * @param onRejectQuestion question dismiss callback (Question branch).
 * @param questionQueuePosition 1-based position of the active question
 *                    (Question branch header).
 * @param questionQueueTotal total questions in the queue (Question branch header).
 * @param onAbort abort the current session (Running branch stop button).
 * @param compactStartedAt epoch-ms when compaction started (Compacting
 *                    branch timer).
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun BoxScope.StatusSlot(
    permission: PermissionRequest?,
    question: QuestionRequest?,
    sessionStatus: SessionStatus?,
    isCompacting: Boolean,
    currentActivityText: String?,
    currentActivityStartedAtMillis: Long?,
    compactStartedAt: Long,
    isConnecting: Boolean,
    // §1C-FIX-⑧: scheme E.4 metadata (host / workdir / session /
    // tool / target) for the Permission card. Caller-sourced
    // (ChatScaffold reads from host.hostProfiles + session
    // slices). Null fields render their line as omitted.
    permissionMetadata: ChatPermissionMetadata,
    onRespondPermission: (PermissionResponse) -> Unit,
    onReplyQuestion: (questionId: String, answers: List<List<String>>, onError: () -> Unit) -> Unit,
    onRejectQuestion: (questionId: String, onError: () -> Unit) -> Unit,
    questionQueuePosition: Int,
    questionQueueTotal: Int,
    onAbort: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val priority = StatusSlotPriority.pick(
        permission = permission,
        question = question,
        sessionStatus = sessionStatus,
        isCompacting = isCompacting,
        isRunning = currentActivityText != null,
        isConnecting = isConnecting,
    )

    // AnimatedContent keyed on the priority class: a priority swap cross-
    // fades + slides the old surface out and the new one in. The body
    // composable is a single `when` over the priority enum — exactly one
    // branch runs per frame, so the four states are mutually exclusive by
    // construction (the AnimatedContent target is one of six values; the
    // `when` is exhaustive; only one of its branches paints).
    //
    // The branches that need a BoxScope receiver (SessionRetryCard
    // declares itself as `fun BoxScope.SessionRetryCard(...)`) get the
    // outer BoxScope this composable is bound to — we do NOT wrap in a
    // nested non-BoxScope Box, because then the BoxScope-receiver
    // children (RetryCard's AnimatedVisibility) would not compile.
    // Children that don't need BoxScope (ChatPermissionCard,
    // QuestionCardView, ThinkingCapsule) use Modifier.fillMaxWidth()
    // and the @Composable receiver to render normally.
    val compactingText = stringResource(R.string.chat_compacting)
    val connectingText = stringResource(R.string.chat_connecting_status)
    // §1C-FIX-⑥: a SaveableStateHolder is created at this scope so
    // QuestionCardView's `remember(question.id)` blocks are
    // registered under the question's id. When the slot's priority
    // class changes (e.g. Permission preempts Question, or the
    // question is dismissed and a new one arrives), the holder
    // keeps the answer state alive in the SaveableStateRegistry —
    // a quick re-render of the same question (same id) restores the
    // user's in-progress answers / selected options / custom text
    // instead of resetting them. Cross-id isolation is automatic:
    // a new question id creates a new provider scope, leaving the
    // old answers under the old key (cleaned up on
    // SaveableStateHolder removal — automatic when the holder
    // leaves composition).
    val saveableStateHolder = rememberSaveableStateHolder()
    AnimatedContent(
        targetState = priority,
        transitionSpec = {
            (fadeIn(animationSpec = tween(150)) +
                slideInVertically(animationSpec = tween(150)) { -it / 4 })
                .togetherWith(
                    fadeOut(animationSpec = tween(120)) +
                        slideOutVertically(animationSpec = tween(120)) { -it / 4 }
                )
        },
        label = "statusSlot",
        modifier = modifier
            .fillMaxWidth()
            .align(Alignment.TopCenter),
    ) { active ->
        when (active) {
            StatusSlotPriority.Permission -> {
                // Caller guarantees permission.sessionId == currentSessionId
                // (status-slot contract). ChatPermissionCard is a
                // non-BoxScope composable, so it renders inside this
                // AnimatedContent's slot — its own Surface wraps its
                // body with the required padding / shape.
                ChatPermissionCard(
                    permission = permission!!,
                    metadata = permissionMetadata,
                    onRespond = onRespondPermission,
                )
            }
            StatusSlotPriority.Question -> {
                // §1C-FIX-⑥: scope the QuestionCardView in a
                // SaveableStateProvider keyed on the question id.
                // When the slot's priority flips to Permission (and
                // back), or to another question id, the holder
                // keeps this question's answer state alive; the
                // composable re-enters with the same answer / custom
                // text / current tab / expanded state instead of
                // resetting. Critical for the destructive
                // (Permission-preempted) case where the user has
                // been mid-answer and the agent's permission
                // request suddenly takes the slot.
                saveableStateHolder.SaveableStateProvider(question!!.id) {
                    QuestionCardView(
                        question = question,
                        queuePosition = questionQueuePosition,
                        queueTotal = questionQueueTotal,
                        onReply = { answers, onError ->
                            onReplyQuestion(question.id, answers, onError)
                        },
                        onReject = { onError -> onRejectQuestion(question.id, onError) },
                    )
                }
            }
            StatusSlotPriority.Retry -> {
                // status != null when isRetry is true (see pick()).
                // SessionRetryCard is a BoxScope-receiver composable;
                // because this composable's enclosing scope is a
                // BoxScope, the call resolves correctly.
                SessionRetryCard(status = sessionStatus)
            }
            StatusSlotPriority.Compacting -> {
                // §1C-FIX-⑤: thread compactStartedAt through so the
                // capsule shows the elapsed compaction timer (parity
                // with the pre-1C ChatScreen which set the same field).
                // Null when not started (e.g. the slot is mid-transition
                // and the slice was read before the start ms was set).
                ThinkingCapsule(
                    text = compactingText,
                    startedAtMillis = compactStartedAt.takeIf { it > 0L },
                    onAbort = {},
                    showAbort = false,
                )
            }
            StatusSlotPriority.Running -> {
                // §1C-FIX-⑤: thread the activity's startedAtMillis
                // through so the running capsule shows the elapsed
                // timer. The value comes from
                // [currentSessionActivity] which sources it from the
                // latest user message's time.created — exactly the
                // field the pre-1C ChatScreen's ThinkingCapsuleOverlay
                // used to read.
                ThinkingCapsule(
                    text = currentActivityText.orEmpty(),
                    startedAtMillis = currentActivityStartedAtMillis,
                    onAbort = onAbort,
                    showAbort = true,
                )
            }
            StatusSlotPriority.Connecting -> {
                // No timer for the connecting capsule (the legacy
                // overlay passed null too — the connecting state is
                // transitional and the timer was not displayed).
                ThinkingCapsule(
                    text = connectingText,
                    startedAtMillis = null,
                    onAbort = {},
                    showAbort = false,
                )
            }
            StatusSlotPriority.None -> {
                // No surface — render nothing. The AnimatedContent
                // keeps a stable slot during the enter/exit animation
                // (its size is determined by the largest branch that
                // has been on screen), so an empty branch is fine.
            }
        }
    }
}
