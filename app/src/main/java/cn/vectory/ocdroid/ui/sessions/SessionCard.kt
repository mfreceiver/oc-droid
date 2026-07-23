package cn.vectory.ocdroid.ui.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.ui.chat.workdirTone
import cn.vectory.ocdroid.ui.theme.Dimens
import cn.vectory.ocdroid.util.DebugLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun SessionCard(
    session: Session,
    isUnread: Boolean = false,
    status: SessionStatus? = null,
    // §sessux #4: root-aggregated pending markers. The Sessions screen
    // pre-computes these via [rootHasPending] (any descendant along the
    // parentId chain has a pending question/permission → flag the root card).
    // Defaults false so call sites that don't care (e.g. FilesScreen's
    // expanded workdir list) keep their existing simpler rendering.
    hasPendingQuestion: Boolean = false,
    hasPendingPermission: Boolean = false,
    onClick: () -> Unit,
    // Q7: carries the long-press Offset (relative to the SessionCard node) so
    // the caller can anchor the DropdownMenu near the touch point.
    onLongClick: (Offset) -> Unit = {},
    onArchive: (() -> Unit)? = null,
    // When true (default), the session's workdir basename is shown in the
    // subtitle. The connected-projects expanded list passes false because the
    // enclosing group header already conveys the workdir; the top cross-workdir
    // "recent sessions" list keeps it (its items can come from any project).
    showWorkdir: Boolean = true
) {
    // Q7 (gating fix): non-consuming press-position observer. Captures the
    // touch point (px) for DropdownMenu anchoring while leaving combinedClickable
    // fully in charge of tap/long-press (ripple + role/a11y semantics).
    var pressPositionPx by remember { mutableStateOf(Offset.Zero) }

    // Prefer the latest message time (time.updated); fall back to time.created.
    val updatedText = (session.time?.updated?.takeIf { it > 0L } ?: session.time?.created?.takeIf { it > 0L })
        ?.let { formatTime(it) }

    // Subtitle: the last-updated time always; the workdir basename only
    // when showWorkdir is true (the connected-projects group omits it
    // because its header already shows the workdir). Empty when there is
    // neither a workdir nor a timestamp. Computed up-front so it can feed
    // ListItem's supportingContent slot.
    val subtitleParts = buildList {
        if (showWorkdir) {
            session.directory.split("/").filter { it.isNotEmpty() }.lastOrNull()?.let { add(it) }
        }
        if (updatedText != null) add(updatedText)
    }


    Surface(
        modifier = Modifier
            .fillMaxWidth()
            // Q7 (gating fix): a NON-CONSUMING pointerInput observer records
            // the last DOWN position (px) WITHOUT calling down.consume() and
            // WITHOUT waitForUpOrCancellation — so the event stays available
            // for combinedClickable below, which then drives tap / long-press
            // WITH ripple indication + role/semantics + a11y click behavior.
            // Order matters: observer BEFORE combinedClickable (both before
            // padding) so the gesture nodes share an origin aligned with the
            // parent Box, matching DropdownMenu's parent-relative offset.
            // awaitFirstDown(requireUnconsumed = false) receives the down
            // even if an upstream modifier already observed it.
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    pressPositionPx = down.position
                    // Deliberately do NOT consume; do NOT wait for up/cancel.
                }
            }
            .combinedClickable(
                onClick = onClick,
                onLongClick = { onLongClick(pressPositionPx) }
            )
            // §0.8.2 P3.3: vertical padding 2.dp → 0.dp (horizontal kept).
            // LazyColumn arrangement is flush (spacedBy(0.dp)); cards rely on
            // their surfaceContainerLow background for visual grouping. Card
            // internal layout untouched.
            // §ui-style-spec §2: 0.dp irreducible (no inter-card vertical
            // padding — grouping is by background, not gap).
            .padding(horizontal = Dimens.spacing2, vertical = 0.dp),
        shape = RectangleShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        // A1: converged the custom title/subtitle Row onto the M3 ListItem.
        // leadingContent = agent icon, headlineContent = title, supportingContent
        // = metadata (workdir • time), trailingContent = archive action + status
        // / unread indicators. combinedClickable stays on the outer Surface so
        // the whole card remains the click/long-click target (ripple + a11y
        // semantics intact; archive button consumes its own taps).
        ListItem(
            modifier = Modifier.heightIn(min = Dimens.touchTargetMin),
            headlineContent = {
                Text(
                    text = session.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            supportingContent = if (subtitleParts.isNotEmpty()) {
                {
                    Text(
                        text = subtitleParts.joinToString("  •  "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else null,
            leadingContent = {
                Icon(
                    Icons.AutoMirrored.Filled.Chat,
                    contentDescription = null,
                    tint = workdirTone(session.directory),
                    modifier = Modifier.size(Dimens.iconSm)
                )
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // §sessux #4: pending-question marker. Rendered as a "?" in
                    // the session's own workdir-hash colour so the per-tree
                    // tint stays consistent with the leading icon + tab strip.
                    // Sibling to the unread dot — question is higher-priority
                    // (it blocks the agent) but they don't both fire for the
                    // same session (a pending question precludes new unread).
                    if (hasPendingQuestion) {
                        Text(
                            text = "?",
                            color = workdirTone(session.directory),
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(end = Dimens.spacing2),
                        )
                    }
                    // §sessux #4: pending-permission marker (small lock).
                    // Indicates the conversation tree is blocked on a tool-
                    // use authorisation. Uses onSurfaceVariant (muted) so it
                    // does not compete with the louder "?" / status dot.
                    if (hasPendingPermission) {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = stringResource(R.string.cd_permission_marker),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(end = Dimens.spacing2)
                                .size(Dimens.iconSm),
                        )
                    }
                    // M6: status indicator. retry → solid red dot;
                    // busy / idle / null → nothing (busy dot removed per §user-req;
                    // a running session is already signalled elsewhere).
                    SessionStatusDot(status)
                    if (isUnread) {
                        // Small unread dot. Positioned to the LEFT of the
                        // archive button (not the far right edge) so the
                        // archive action stays the rightmost, easiest-to-reach
                        // affordance, and the unread marker sits just inside it.
                        Box(
                            modifier = Modifier
                                .padding(start = Dimens.spacing2)
                                .size(Dimens.spacing2)
                                .background(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    shape = CircleShape
                                )
                        )
                    }
                    // 归档 icon on the right edge (rightmost). Only rendered
                    // where a handler is supplied (connected-projects expanded
                    // list); omitted from the top "recent sessions" list.
                    // IconButton gives a 48dp touch target (R-12) in a compact
                    // right-edge footprint.
                    // §0.8.2 P3.4: icon size 24dp (default) → Dimens.iconSm (18)
                    // to match the workdir-header AddComment icon.
                    if (onArchive != null) {
                        IconButton(onClick = onArchive) {
                            Icon(
                                Icons.Default.Archive,
                                contentDescription = stringResource(R.string.sessions_archive),
                                modifier = Modifier.size(Dimens.iconSm)
                            )
                        }
                    }
                }
            }
        )
    }
}

/**
 * M6: Small (~8dp) status indicator rendered on the right edge of a session
 * card's title row, just before the unread dot. Mapping:
 * - retry → solid red dot (retry/error semantic).
 * - busy / idle / null → nothing rendered. The busy dot was removed per
 *   §user-req; a running session is already surfaced via the chat surface
 *   (streaming indicator / "生成中…" placeholder), so a duplicate list-side
 *   busy marker was visual noise.
 */
@Composable
private fun SessionStatusDot(status: SessionStatus?) {
    // §user-req: busy dot removed — only retry/error renders a status indicator.
    // idle / null / busy → nothing rendered.
    if (status == null || status.isIdle || status.isBusy) return
    Box(
        modifier = Modifier
            .padding(start = Dimens.spacing2)
            .size(Dimens.spacing2)
            .background(color = MaterialTheme.colorScheme.error, shape = CircleShape)
    )
}

private fun formatTime(epochMs: Long): String {
    return try {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        sdf.format(Date(epochMs))
    } catch (e: Exception) {
        DebugLog.w("SessionsScreen", "formatTime failed: ${e.message}")
        ""
    }
}
