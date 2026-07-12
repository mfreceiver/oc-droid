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
 * The payload each AnimatedContent branch needs, carried IN the targetState so the
 * exiting branch (during Question→None / Permission→None transitions) reads the value
 * captured when its state became active — NOT the outer parameter that has since
 * recomposed to null. Fix for the stale-closure NPE: a pending Question cleared on
 * reply success dropped priority Question→None; the exiting Question branch re-ran
 * the shared content lambda which read the outer `question = null`, and
 * `question!!.id` threw.
 *
 * `StatusSlotPriority` (the enum + `pick()`) stays the priority/decision API
 * (consumed by StatusSlotPriorityTest); this sealed type is ONLY the AnimatedContent
 * payload. Only Question and Permission carry data (the two branches that `!!` a
 * nullable outer param); the other variants are objects because their branches
 * already tolerate nulls (`.orEmpty()` / `.takeIf {}` / non-null params).
 */
internal sealed class StatusSlotContent {
    object None : StatusSlotContent()
    object Connecting : StatusSlotContent()
    object Running : StatusSlotContent()
    object Compacting : StatusSlotContent()
    object Retry : StatusSlotContent()
    data class Question(val question: QuestionRequest) : StatusSlotContent()
    data class Permission(val permission: PermissionRequest) : StatusSlotContent()
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

    // §crash-fix: carry the Question/Permission DATA in the AnimatedContent
    // targetState so the exiting branch (during a Question→None / Permission→None
    // transition) reads the value captured when the state became active, not the
    // outer parameter that has since recomposed to null. (AnimatedContent shares
    // one content lambda across entering+exiting children and re-invokes it with
    // the latest closure — so branches MUST read from `active`, not outer scope.)
    val content: StatusSlotContent = when (priority) {
        StatusSlotPriority.Permission ->
            permission?.let { StatusSlotContent.Permission(it) } ?: StatusSlotContent.None
        StatusSlotPriority.Question ->
            question?.let { StatusSlotContent.Question(it) } ?: StatusSlotContent.None
        StatusSlotPriority.Retry -> StatusSlotContent.Retry
        StatusSlotPriority.Compacting -> StatusSlotContent.Compacting
        StatusSlotPriority.Running -> StatusSlotContent.Running
        StatusSlotPriority.Connecting -> StatusSlotContent.Connecting
        StatusSlotPriority.None -> StatusSlotContent.None
    }

    // AnimatedContent carrying the per-branch payload (StatusSlotContent). A variant
    // CHANGE (e.g. Permission preempts Question, or a pending question is cleared on
    // reply → Question→None) cross-fades + slides the old surface out and the new one
    // in. `contentKey = { it::class }` (variant class, not data) ensures a SAME-variant
    // data change (q1→q2, p1→p2, same-id re-emit) updates IN PLACE rather than spawning
    // a second child — so there's never a duplicate SaveableStateProvider(question.id)
    // overlap, and the exiting branch reads its captured payload (active.question /
    // active.permission) instead of the recomposed-to-null outer param (the stale-
    // closure NPE fix). The body `when` is exhaustive over the sealed StatusSlotContent;
    // exactly one branch paints per child.
    //
    // BoxScope threading unchanged from before: branches needing a BoxScope receiver
    // (SessionRetryCard) resolve to this composable's enclosing BoxScope.
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
        targetState = content,
        transitionSpec = {
            (fadeIn(animationSpec = tween(150)) +
                slideInVertically(animationSpec = tween(150)) { -it / 4 })
                .togetherWith(
                    fadeOut(animationSpec = tween(120)) +
                        slideOutVertically(animationSpec = tween(120)) { -it / 4 }
                )
        },
        label = "statusSlot",
        // §oracle-fix: contentKey by sealed-variant CLASS (not the data payload) so a
        // variant change (Question↔None, Question↔Permission, ...) triggers a transition
        // (exiting child retains its captured payload → no stale-closure NPE), while a
        // same-variant data change (q1→q2, p1→p2, same-id changed content) updates IN
        // PLACE (no transition → no two concurrent SaveableStateProvider(question.id)
        // with the same key → no duplicate-key crash; no overlapping interactive cards
        // → no wrong-response on the exiting card).
        contentKey = { it::class },
        modifier = modifier
            .fillMaxWidth()
            .align(Alignment.TopCenter),
    ) { active ->
        when (active) {
            is StatusSlotContent.Permission -> {
                // Caller guarantees permission.sessionId == currentSessionId
                // (status-slot contract). ChatPermissionCard is a non-BoxScope
                // composable, so it renders inside this AnimatedContent's slot.
                // §crash-fix: read permission from `active` (the value captured
                // when this state became active), not the outer param (which may
                // have recomposed to null during the exit transition).
                ChatPermissionCard(
                    permission = active.permission,
                    metadata = permissionMetadata,
                    onRespond = onRespondPermission,
                )
            }
            is StatusSlotContent.Question -> {
                // §1C-FIX-⑥: scope the QuestionCardView in a SaveableStateProvider
                // keyed on the question id. When the slot's priority flips to
                // Permission (and back), or to another question id, the holder
                // keeps this question's answer state alive. §crash-fix: read the
                // question from `active` (captured when Question became active) so
                // the exiting branch during Question→None still has the non-null
                // question instead of the recomposed-to-null outer param.
                saveableStateHolder.SaveableStateProvider(active.question.id) {
                    QuestionCardView(
                        question = active.question,
                        queuePosition = questionQueuePosition,
                        queueTotal = questionQueueTotal,
                        onReply = { answers, onError ->
                            onReplyQuestion(active.question.id, answers, onError)
                        },
                        onReject = { onError -> onRejectQuestion(active.question.id, onError) },
                    )
                }
            }
            StatusSlotContent.Retry -> {
                // status != null when isRetry is true (see pick()). SessionRetryCard
                // is a BoxScope-receiver composable; resolves to this composable's
                // enclosing BoxScope.
                SessionRetryCard(status = sessionStatus)
            }
            StatusSlotContent.Compacting -> {
                // §1C-FIX-⑤: thread compactStartedAt through for the elapsed
                // compaction timer. Null when not started.
                ThinkingCapsule(
                    text = compactingText,
                    startedAtMillis = compactStartedAt.takeIf { it > 0L },
                    onAbort = {},
                    showAbort = false,
                )
            }
            StatusSlotContent.Running -> {
                // §1C-FIX-⑤: thread the activity's startedAtMillis through for the
                // running capsule elapsed timer.
                ThinkingCapsule(
                    text = currentActivityText.orEmpty(),
                    startedAtMillis = currentActivityStartedAtMillis,
                    onAbort = onAbort,
                    showAbort = true,
                )
            }
            StatusSlotContent.Connecting -> {
                // No timer for the connecting capsule (transitional state).
                ThinkingCapsule(
                    text = connectingText,
                    startedAtMillis = null,
                    onAbort = {},
                    showAbort = false,
                )
            }
            StatusSlotContent.None -> {
                // No surface — render nothing. The AnimatedContent keeps a stable
                // slot during enter/exit animation.
            }
        }
    }
}
