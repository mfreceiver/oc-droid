// MessageCard.kt — Phase 1C message-row overflow menu. Wraps the existing
// [MessageRow] (the per-part dispatcher in ChatMessageRow.kt) with a long-
// press-triggered DropdownMenu offering Copy / Edit & rerun / Fork. The
// Edit & rerun entry is destructive (it routes to
// ChatViewModel.editFromMessage, which is the RevertConversation use case's
// single entry point — see plan §2.2 / §3.3 / scheme D.6) and therefore
// MUST be confirmed before it fires.
//
// §B5-P4: the visible MoreVert IconButton was removed (it duplicated the
// long-press affordance and was deemed visually noisy). The DropdownMenu
// anchor is preserved by keeping the small TopEnd Box that previously
// hosted the IconButton — the DropdownMenu is still composed INSIDE that
// Box, so its popup anchors to the message card's top-end corner exactly
// as before (the historical "menu attaches to window top-left" bug — see
// §0.8.2 P2.4 anchor-fix below — does NOT regress). Long-press now also
// fires [HapticFeedbackType.LongPress] for tactile confirmation, and the
// row exposes a contentDescription (reusing R.string.message_actions_menu)
// so screen-reader users discover the long-press → actions affordance.
//
// DESTRUCTIVE-GATE CONTRACT (plan §3.3 / scheme D.6):
//   1. The action is the SINGLE call to ChatViewModel.editFromMessage —
//      which is a 100% pass-through to the Phase 0 RevertConversation use
//      case (RevertConversation.execute). The fail-closed cutoff /
//      streaming-intercept / dedup / outcome semantics are owned by that
//      use case and verified by RevertConversationTest. The UI MUST NOT
//      re-implement or short-circuit that path.
//   2. A confirmation dialog blocks the action. The confirm button is
//      error-colored (MaterialTheme.colorScheme.error). The message body
//      states the impact ("the agent will be rewound to before this
//      message; everything after it is dropped") so a tap-the-confirm
//      mistake is impossible without reading.
//   3. Revert is DISABLED when the session is busy / retrying / streaming
//      / sending — double-insurance, because the use case itself
//      intercepts and returns Failure, but a disabled menu item is the
//      honest UX ("the agent is still producing, come back in a moment").
//
// GESTURE POLICY (plan §3.3 / scheme E.5):
//   - Tap = no-op. The message is selected for scroll-anchor / keyboard
//     navigation only; never triggers a destructive action.
//   - Long-press = ACCELERATOR: opens the overflow menu. §B5-P4 also fires
//     [HapticFeedbackType.LongPress] for tactile confirmation (no ripple —
//     `indication = null` is preserved; the menu opening + haptic IS the
//     feedback). The accelerator opens the menu and stops there — the user
//     must still tap an item AND confirm destructive items. A long-press
//     NEVER fires revert / fork on its own.
//   - Horizontal swipe = reserved for transcript / sheet content; never
//     wired to a destructive action on a message row.
//
// EDIT & RERUN vs REVERT (plan §3.3 / scheme D.6):
//   In this project, ChatViewModel.editFromMessage IS the revert action
//   (its body calls RevertConversation.execute which is the Phase 0
//   destructive-revert orchestration boundary). The two product labels
//   "Edit & rerun from here" and "Revert to this point" point at the
//   same use case — exposing both as separate menu items would surface
//   two destructive entries that share an impact. We therefore expose
//   ONE entry labelled "Edit & rerun" (the user-facing phrasing, which
//   reads as a soft "edit your message" action) AND treat it as a
//   destructive action (confirmation dialog + error color + impact copy
//   + disabled-while-busy). The destructive gate is the load-bearing
//   property; the label is a UX choice.

package cn.vectory.ocdroid.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.Part

/**
 * §1C: the per-message card wrapping the existing [MessageRow]. Long-press
 * (with [HapticFeedbackType.LongPress]) opens a DropdownMenu offering Copy /
 * Edit & rerun / Fork, and the destructive confirmation dialog. §B5-P4
 * removed the always-visible MoreVert IconButton (long-press already opened
 * the same menu); the DropdownMenu anchor is preserved by the small TopEnd
 * Box below. The card body is delegated to the existing [MessageRow] via a
 * `body: @Composable ColumnScope.() -> Unit` slot — the per-part rendering
 * and the parity scroll/coalesce path are NOT touched.
 *
 * @param message the message being rendered (drives the per-row menu
 *                enablement — Edit & rerun is offered on user rows only;
 *                Fork + Copy are offered on every row).
 * @param parts the message's parts (passed to the body).
 * @param streamingPartTexts the chat's streaming part text map (passed
 *                to the body).
 * @param streamingReasoningPartId the active streaming reasoning part id
 *                (passed to the body).
 * @param repository the OpenCode repository (passed to the body).
 * @param workspaceDirectory the session workdir (passed to the body).
 * @param onFileClick file-click callback (passed to the body).
 * @param onOpenSubAgent sub-agent open callback (passed to the body).
 * @param expandedParts / onToggleExpand / staleQuestionPartKeys /
 *        showMessageDecoration — passed verbatim to the body.
 * @param canCopy whether Copy is offered (always true today; kept as a
 *        param so future gating — e.g. permission to read system
 *        clipboard — can land here without churning the signature).
 * @param canEditAndRerun whether Edit & rerun is offered AND enabled.
 *        False for non-user messages (the destructive action is gated to
 *        user-typed turns — Phase 0 RevertConversation rejects non-user
 *        message ids, so we just don't offer it). Also false when the
 *        session is busy / retrying / streaming / sending (double-insurance
 *        with the use case's own intercept).
 * @param canFork whether Fork is offered. Always true for messages in an
 *        existing session; the param exists so Phase 2 can gate it
 *        (e.g. when offline) without a signature churn.
 * @param onCopy callback when Copy is tapped. Receives the message text
 *        (concatenation of all text parts) so the caller decides where
 *        the text lands (clipboard, share-sheet, etc.). The default
 *        convenience hook below writes the text to the system clipboard.
 * @param onEditAndRerun callback when Edit & rerun is CONFIRMED. Must
 *        route to ChatViewModel.editFromMessage — the confirmation
 *        happens INSIDE this composable; the callback only fires after
 *        the user taps the destructive confirm button.
 * @param onFork callback when Fork is tapped. Must route to
 *        SessionViewModel.forkSession(messageId).
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun MessageCard(
    message: Message,
    parts: List<Part>,
    streamingPartTexts: Map<String, String>,
    streamingReasoningPartId: String?,
    repository: cn.vectory.ocdroid.data.repository.OpenCodeRepository,
    workspaceDirectory: String?,
    onFileClick: (String) -> Unit,
    onOpenSubAgent: (String) -> Unit,
    expandedParts: Map<String, Boolean>,
    onToggleExpand: (String, Boolean) -> Unit,
    staleQuestionPartKeys: Set<String>,
    showMessageDecoration: Boolean,
    canCopy: Boolean,
    canEditAndRerun: Boolean,
    canFork: Boolean,
    onCopy: (String) -> Unit,
    onEditAndRerun: (String) -> Unit,
    onFork: (String) -> Unit,
    // §slimapi-client-v1 §G6 (Task 16): threaded through to MessageRow for
    // the "展开省略内容" affordance.
    partExpandStates: Map<PartKey, PartExpandState> = emptyMap(),
    onExpandParts: (List<Part>) -> Unit = {},
) {
    var overflowOpen by remember { mutableStateOf(false) }
    // §press-anchor (Bug4 fix): capture the long-press touch point so the
    // DropdownMenu opens AT the finger instead of a hard-coded corner.
    // combinedClickable's onLongClick has no Offset parameter, so a NON-
    // CONSUMING pointerInput on the outer Box records the last DOWN in this
    // card's local px; onLongClick snapshots it into menuAnchorPx, which feeds
    // DropdownMenu's `offset`. Mirrors the SessionsScreen Q7 gating-fix pattern.
    var pressPositionPx by remember { mutableStateOf(Offset.Zero) }
    var menuAnchorPx by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    // §1C-FIX-③/④: the destructive-gate state machine. Owned by
    // Compose's `remember` (per-message scope; resets when the
    // message id changes). The pure transitions live in
    // [confirmOnMenuTap] / [confirmOnConfirmTap] / [confirmOnCancel]
    // so the gate is unit-testable in
    // [MessageCardDestructiveGateTest] without a Compose harness.
    var confirmState by remember { mutableStateOf(ConfirmState.MenuClosed) }

    // §E.5: combinedClickable's onLongClick is the accelerator that opens
    // the menu. The onClick is a no-op (per E.5, tap = navigate or select,
    // never a destructive action). The empty indication disables the
    // ripple so long-press doesn't visually distort the message — the
    // menu opening IS the feedback.
    val longPressInteraction = remember { MutableInteractionSource() }
    val context = LocalContext.current
    // §B5-P4: haptic + a11y for the long-press accelerator. The removed
    // MoreVert IconButton was redundant with long-press; we keep the
    // menu discoverable by firing [HapticFeedbackType.LongPress] on the
    // long-press and exposing a contentDescription (reusing the existing
    // R.string.message_actions_menu — "Message actions" / "消息操作") so
    // TalkBack users hear the long-press → actions affordance. No new
    // string resource needed.
    val hapticFeedback = LocalHapticFeedback.current
    val actionsLabel = stringResource(R.string.message_actions_menu)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            // §press-anchor (Q7 gating-fix pattern): a NON-CONSUMING pointerInput
            // records the last DOWN position (px) WITHOUT consuming it and WITHOUT
            // waitForUpOrCancellation — so combinedClickable below still owns
            // tap/long-press + ripple/a11y semantics. Order: observer BEFORE
            // combinedClickable so the gesture origin matches the DropdownMenu's
            // parent-relative offset (both anchored to this outer Box).
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    pressPositionPx = down.position
                    // Deliberately do NOT consume; do NOT wait for up/cancel.
                }
            }
            .combinedClickable(
                interactionSource = longPressInteraction,
                indication = null,
                onClick = { /* tap = select (no-op), per E.5 */ },
                onLongClick = {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    // §press-anchor: snapshot the press point so the menu opens
                    // at the finger; the snapshot stays stable while the menu is
                    // open even if a later DOWN updates pressPositionPx.
                    menuAnchorPx = pressPositionPx
                    overflowOpen = true
                },
            )
            // §a11y-B5: 告知屏幕阅读器用户长按可触发消息操作菜单。复用现有
            // message_actions_menu 标签，无需新增 string。`mergeDescendants =
            // false`（默认）——不与子节点（消息正文文本）的 semantics 合并，
            // TalkBack 会先读这行 affordance 提示，再读消息正文。
            .semantics {
                contentDescription = actionsLabel
            }
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // The existing per-part rendering — delegated verbatim.
            MessageRow(
                message = message,
                parts = parts,
                streamingPartTexts = streamingPartTexts,
                streamingReasoningPartId = streamingReasoningPartId,
                repository = repository,
                workspaceDirectory = workspaceDirectory,
                onFileClick = onFileClick,
                onOpenSubAgent = onOpenSubAgent,
                expandedParts = expandedParts,
                onToggleExpand = onToggleExpand,
                staleQuestionPartKeys = staleQuestionPartKeys,
                showMessageDecoration = showMessageDecoration,
                partExpandStates = partExpandStates,
                onExpandParts = onExpandParts,
            )
        }

        // §press-anchor (Bug4 fix): the DropdownMenu now opens AT the long-press
        // touch point instead of a hard-coded corner. The previous TopEnd anchor
        // Box (0.8.2 P2.4 / B5-P4) parked the popup at the card's top-right
        // regardless of where the finger landed — for short messages near the
        // top that read as "fixed at screen top-right". The wrapper Box is now
        // origin-anchored (default TopStart, no padding) so its top-left
        // coincides with the outer Box's origin; the NON-CONSUMING pointerInput
        // above records the press in that same outer-Box-local px space, and we
        // feed it to DropdownMenu's `offset` (converted px→dp). The popup thus
        // lands at the finger (DropdownMenu still clamps to stay on-screen).
        Box(modifier = Modifier) {
            DropdownMenu(
                expanded = overflowOpen,
                onDismissRequest = { overflowOpen = false },
                offset = with(density) {
                    DpOffset(menuAnchorPx.x.toDp(), menuAnchorPx.y.toDp())
                },
            ) {
                if (canCopy) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.message_action_copy)) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.ContentCopy,
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            overflowOpen = false
                            // Default Copy semantics: concatenate every text part
                            // (in order) and put it in the system clipboard. The
                            // extracted text matches what the user sees in the
                            // transcript — non-text parts (tools / patches / images
                            // / reasoning) are intentionally excluded (no
                            // human-readable surface for the recipient).
                            onCopy(collectMessageText(parts, streamingPartTexts, context))
                        },
                    )
                }
                // §1C-DEV-NOTE: only user-typed messages expose the destructive
                // entry. The use case (RevertConversation) rejects non-user
                // message ids, so offering the menu item on assistant rows
                // would be a misleading dead-end. Fork + Copy are non-destructive
                // and stay on every row.
                if (message.isUser) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.message_action_edit_rerun)) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Edit,
                                contentDescription = null,
                            )
                        },
                        // §1C-FIX-②: the menu-item enablement uses the
                        // PURE [canEditAndRerun] predicate. The caller
                        // (ChatMessageContent.kt's MessageCard wrap)
                        // computes the [DestructiveGateInputs] from the
                        // canonical slices — busy / retry / sending /
                        // streamingPartTexts / streamingReasoningPart /
                        // isUser. Mirrors RevertConversation's own
                        // intercept set; the predicate is a faithful
                        // double-insurance alongside the use case.
                        enabled = canEditAndRerun,
                        onClick = {
                            // §1C-FIX-③/④: the menu tap dispatches the
                            // pure [confirmOnMenuTap] state-machine
                            // transition. The destructive callback NEVER
                            // fires from the menu tap — only from the
                            // confirm dialog's confirm button (see below).
                            // Closing the menu and opening the dialog
                            // happen via the state machine; the visual
                            // surface (DropdownMenu + AlertDialog) is
                            // driven by the state, not by a separate
                            // boolean.
                            overflowOpen = false
                            confirmState = confirmOnMenuTap(confirmState)
                        },
                    )
                }
                if (canFork) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.message_action_fork)) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Edit,
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            overflowOpen = false
                            onFork(message.id)
                        },
                    )
                }
            }
        }
    }

    // §DESTRUCTIVE GATE: the confirmation dialog. Body copy explicitly
    // states the impact ("everything after this message is dropped"); the
    // confirm button is error-colored and is the SOLE path to the
    // destructive action. Driven by the [ConfirmState] state
    // machine (see [confirmOnMenuTap] / [confirmOnConfirmTap] /
    // [confirmOnCancel]). The machine is the SOLE authority for
    // whether the destructive callback may fire — the Composable
    // reads the state, dispatches events, and renders the
    // AlertDialog only when the state is [ConfirmState.ConfirmOpen]
    // OR [ConfirmState.ConfirmFired] (the latter keeps the dialog
    // visible for one frame so the user sees the "spinner /
    // acknowledged" feedback before the composable closes it; the
    // RevertConversation inFlight set is the authoritative
    // dedup guard).
    if (confirmState == ConfirmState.ConfirmOpen || confirmState == ConfirmState.ConfirmFired) {
        val title = stringResource(R.string.message_revert_confirm_title)
        val body = stringResource(R.string.message_revert_confirm_body)
        val cancelLabel = stringResource(R.string.common_cancel)
        val confirmLabel = stringResource(R.string.message_revert_confirm_button)
        // §1C-FIX-③: the confirm button is DISABLED once the
        // destructive callback has fired (ConfirmFired state). The
        // button's onClick dispatches [confirmOnConfirmTap], which
        // returns firesCallback=true ONLY on the first tap from
        // ConfirmOpen. A second tap from ConfirmFired returns
        // firesCallback=false — the callback never fires twice. The
        // button's enabled flag is the visible UX signal; the state
        // machine is the load-bearing guard.
        val confirmButtonEnabled = confirmState == ConfirmState.ConfirmOpen
        AlertDialog(
            onDismissRequest = {
                confirmState = confirmOnCancel(confirmState)
            },
            title = { Text(title) },
            text = { Text(body) },
            confirmButton = {
                TextButton(
                    enabled = confirmButtonEnabled,
                    onClick = {
                        val result = confirmOnConfirmTap(confirmState)
                        confirmState = result.nextState
                        if (result.firesCallback) {
                            // SOLE fire path. The callback MUST
                            // route to chatVM.editFromMessage — the
                            // Phase 0 RevertConversation use case
                            // intercepts streaming/busy and
                            // fail-closes the cutoff; the UI does
                            // not re-implement any of that.
                            onEditAndRerun(message.id)
                        }
                    },
                ) {
                    Text(
                        text = confirmLabel,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        confirmState = confirmOnCancel(confirmState)
                    },
                ) {
                    Text(cancelLabel)
                }
            },
        )
    }
}

/**
 * §1C: pure helper that concatenates every text part in order. Non-text
 * parts (tool / patch / reasoning / image / file / sub-agent) are
 * skipped — those payloads are not human-readable copy targets. Streaming
 * deltas (in `streamingPartTexts`) take precedence over the part's
 * committed text so the user copies what they SEE in the transcript
 * (a streaming partial string, not the stale server text).
 *
 * Extractable as a `fun` (not `@Composable`) so it is unit-testable
 * without a Compose harness.
 */
internal fun collectMessageText(
    parts: List<Part>,
    streamingPartTexts: Map<String, String>,
    context: Context?,
): String {
    // The Context parameter is intentionally optional: the helper is
    // called from a @Composable (with LocalContext.current) for the
    // production path, but unit tests exercise the helper directly and
    // can pass null (the helper does NOT touch the context for the
    // text-only path — the parameter exists so future
    // resource-resolution / locale-aware joining can land here without
    // a signature churn at every call site).
    val pieces = parts.mapNotNull { part ->
        if (!part.isText) return@mapNotNull null
        val streaming = streamingPartTexts[part.id]
        if (streaming != null) {
            streaming.takeIf { it.isNotEmpty() }
        } else {
            part.text?.takeIf { it.isNotEmpty() }
        }
    }
    return pieces.joinToString(separator = "\n")
}

/**
 * §1C-DEV: convenience hook — wire the default Copy behaviour to the
 * system clipboard. The caller passes this as `onCopy` when it does not
 * need custom copy semantics. Thread-safe (ClipboardManager is the
 * platform service); we explicitly use the application context to avoid
 * holding an Activity.
 */
internal fun copyToSystemClipboard(context: Context, text: String) {
    val cm = context.applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: return
    val clip = ClipData.newPlainText("ocdroid-message", text)
    cm.setPrimaryClip(clip)
}

// ── §1C-FIX-②/④: top-level pure helpers (testable, no Compose harness) ─

/**
 * Snapshot of the session-state inputs the [canEditAndRerun] predicate
 * needs. A plain Kotlin value type so unit tests can construct it
 * directly without spinning up an AppCore / slice-flow / Compose
 * context. The call site (ChatMessageContent.kt's MessageCard wrap)
 * populates each field from the canonical slices:
 *
 *  - `sessionIsBusy`       ← SessionStatus.isBusy
 *  - `sessionIsRetry`      ← SessionStatus.isRetry
 *  - `isUser`              ← Message.isUser
 *  - `isSending`           ← ComposerState.sendingSessionIds.contains(sessionId)
 *  - `hasStreamingText`    ← ChatState.streamingPartTexts.isNotEmpty()
 *  - `hasStreamingReasoning` ← ChatState.streamingReasoningPart != null
 *
 * The shape mirrors [RevertConversation]'s own intercept set (busy +
 * retry + sending + streamingPartTexts + streamingReasoningPart) so
 * the UI gate is a faithful double-insurance alongside the use case
 * (the use case is authoritative; the UI mirror is the honest UX).
 */
internal data class DestructiveGateInputs(
    val isUser: Boolean,
    val sessionIsBusy: Boolean,
    val sessionIsRetry: Boolean,
    val isSending: Boolean,
    val hasStreamingText: Boolean,
    val hasStreamingReasoning: Boolean,
)

/**
 * §1C-FIX-②/④: PURE predicate. The single source of truth for whether
 * the Edit & rerun menu entry is offered AND enabled on a given
 * message row. Returns true iff EVERY condition holds:
 *
 *   1. The message is a user-typed turn (`Message.isUser`). Phase 0
 *      RevertConversation rejects non-user message ids — assistant /
 *      system rows are not valid revert pivots, so the menu item
 *      is hidden on those rows entirely.
 *   2. The session is NOT in [SessionStatus.isBusy] (the server is
 *      currently producing a turn; reverting would race the SSE
 *      stream).
 *   3. The session is NOT in [SessionStatus.isRetry] (a failed run is
 *      about to backoff; reverting would race the retry).
 *   4. The current session is NOT in the composer's sendingSessionIds
 *      (a send-ACK is in flight; reverting before the user message
 *      exists on the server would target a message id the server
 *      does not yet own).
 *   5. ChatState.streamingPartTexts is empty (no SSE text deltas in
 *      flight; a non-empty map means an assistant turn is still being
 *      streamed — the §user-part-guard in SessionSyncCoordinator
 *      filters out the user-input-echo part, so this catch-all
 *      protects the window BEFORE the first server-side part is
 *      recorded).
 *   6. ChatState.streamingReasoningPart is null (the standalone
 *      streaming reasoning part is active — even if no text delta
 *      has arrived yet, the server is producing a turn).
 *
 * This is a faithful mirror of [RevertConversation.execute]'s own
 * intercept set ([RevertConversation.kt:23-26]) — the use case is
 * authoritative, this predicate is the UI's honest "don't even show
 * the button" double-insurance. The two layers converge: a busy /
 * streaming / sending / retrying session shows the menu item as
 * disabled, AND the use case's own check returns Failure if the
 * gate is somehow bypassed.
 *
 * Pure (no Compose / state / time), unit-tested in
 * [MessageCardDestructiveGateTest].
 */
internal fun canEditAndRerun(inputs: DestructiveGateInputs): Boolean {
    if (!inputs.isUser) return false
    if (inputs.sessionIsBusy) return false
    if (inputs.sessionIsRetry) return false
    if (inputs.isSending) return false
    if (inputs.hasStreamingText) return false
    if (inputs.hasStreamingReasoning) return false
    return true
}

/**
 * §1C-FIX-③/④: PURE state machine for the destructive confirm dialog.
 * The two buttons (menu → confirm dialog → confirm button) and the
 * consumed flag (to enforce exactly-once) are pure data. The
 * Composable drives the machine by reading the state and dispatching
 * events; the predicate in [confirmShouldFire] decides whether the
 * destructive callback may fire.
 *
 * The state model:
 *   - `MenuClosed`   — the overflow menu is not open.
 *   - `MenuOpen`     — the overflow menu is open; tap on "Edit & rerun"
 *                      moves the machine to `ConfirmOpen`.
 *   - `ConfirmOpen`  — the confirm dialog is up; the destructive
 *                      callback has NOT yet fired.
 *   - `ConfirmFired` — the destructive callback has fired exactly once.
 *                      The confirm button is disabled, the dialog
 *                      stays open briefly while the use case's
 *                      inFlight guard takes over, then the
 *                      composable clears `confirmRevertOpen` to
 *                      return to MenuClosed.
 *
 * Pure; the unit test verifies the three load-bearing transitions
 * (menu tap → no fire; confirm tap → fire; repeated confirm tap → still
 * exactly one fire). The state machine does not own the
 * `confirmRevertOpen` / `overflowOpen` / `consumed` Composable
 * memory cells; the caller holds them. The Composable reads the
 * state to drive enablement, and dispatches the events to mutate
 * the cells.
 */
internal enum class ConfirmState { MenuClosed, MenuOpen, ConfirmOpen, ConfirmFired }

/**
 * §1C-FIX-③/④: the menu tap on "Edit & rerun". Opens the confirm
 * dialog WITHOUT firing the destructive callback. The previous
 * implementation (without this state machine) was vulnerable to a
 * rapid double-tap on the menu item re-opening the dialog; this
 * state machine keeps the dialog open across the second tap
 * (idempotent). The destructive callback NEVER fires from the menu
 * tap — only from the confirm tap.
 */
internal fun confirmOnMenuTap(state: ConfirmState): ConfirmState = when (state) {
    // Idempotent: if a confirm dialog is already open, the menu tap
    // is a no-op (it doesn't matter that the menu's onClick also
    // runs — the dialog is the visible surface and the user can
    // confirm or cancel from there). Same when the callback has
    // already fired — the menu's onClick doesn't fire the
    // destructive callback (only the dialog's confirm does, and it
    // is gated on state != ConfirmFired).
    ConfirmState.MenuClosed, ConfirmState.MenuOpen -> ConfirmState.ConfirmOpen
    ConfirmState.ConfirmOpen, ConfirmState.ConfirmFired -> state
}

/**
 * §1C-FIX-③/④: the confirm dialog's confirm-button tap. Returns the
 * new state and a boolean indicating whether the destructive
 * callback MAY fire. The Composable reads the boolean to decide
 * whether to invoke `onEditAndRerun` — it is the SOLE fire path,
 * AND the function ensures it fires AT MOST ONCE per dialog open.
 */
internal data class ConfirmResult(val nextState: ConfirmState, val firesCallback: Boolean)

internal fun confirmOnConfirmTap(state: ConfirmState): ConfirmResult = when (state) {
    // First confirm tap → fire the callback and lock the state. The
    // button's enabled flag is driven by `state != ConfirmFired`,
    // so a second confirm tap is a no-op (the button is disabled
    // and the lambda never re-runs).
    ConfirmState.ConfirmOpen -> ConfirmResult(ConfirmState.ConfirmFired, firesCallback = true)
    // Defensive: any other state (menu closed, menu open, or
    // already-fired) cannot fire the callback from the confirm
    // button. The button is only rendered when state == ConfirmOpen
    // anyway, but a stray tap on a still-rendering button cannot
    // bypass the gate.
    ConfirmState.MenuClosed, ConfirmState.MenuOpen, ConfirmState.ConfirmFired ->
        ConfirmResult(state, firesCallback = false)
}

/**
 * §1C-FIX-③/④: the confirm dialog's cancel-button tap / outside
 * dismiss. Returns the state machine to MenuClosed without firing
 * the callback. The next menu tap on Edit & rerun reopens the
 * dialog fresh.
 */
internal fun confirmOnCancel(state: ConfirmState): ConfirmState = when (state) {
    ConfirmState.ConfirmOpen -> ConfirmState.MenuClosed
    else -> state
}
