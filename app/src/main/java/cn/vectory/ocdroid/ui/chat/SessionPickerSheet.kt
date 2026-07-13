// SessionPickerSheet.kt — lean ModalBottomSheet session switcher (D.4 / WT1).
//
// §WT1 chat-sheets: rewrite as a lean session switcher. The old sheet carried
// a SearchBar, "By workdir" project grouping (+ its WorkdirHeader), section
// titles ("Recent"/"By workdir"), HorizontalDividers, a 480dp hard cap and
// a footer "New session" ExtendedFloatingActionButton. All of that is gone
// per the locked decision (Q3): the user just wants to switch between recent
// sessions quickly. The SessionTabStrip remains the fast switcher between
// open root sessions; this sheet (opened via the ChatTopBar title tap) is the
// fuller recent-list + per-row Archive/Unarchive overflow.
//
// Container: AppBottomSheet (WT0 primitive) — title via the recipe's titleLarge
// slot, no footer, natural height (no fixed cap). Body is a single scrollable
// LazyColumn of recent (root, non-archived) sessions sorted by time.updated
// desc, capped at take(10). Each row is an M3 ListItem with the leading
// workdir-tone dot, headline displayName, supporting workdir•time, and a
// trailing slot carrying the unread/retry state dots + PickerTrailingCheck
// (selected) + the ⋮ overflow IconButton (Archive/Unarchive, P4-4).
//
// The `onNewSession` callback param is kept in the signature (unused) so
// ChatScaffold.kt — which is owned by another lane and passes that arg —
// keeps compiling without edits.

package cn.vectory.ocdroid.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.ui.theme.AppBottomSheet
import cn.vectory.ocdroid.ui.theme.Dimens
import cn.vectory.ocdroid.ui.theme.PickerTrailingCheck

/**
 * Lean session switcher sheet (D.4 / WT1). Renders a single scrollable list
 * of the most recent root, non-archived sessions (`take(10)` by `time.updated`
 * desc). Selecting a row invokes [onSelect]; the per-row `⋮` opens an
 * overflow menu with Archive / Unarchive (P4-4 — the destructive action is
 * always reachable from a visible affordance).
 *
 * **Filtering**: `parentId == null` (sub-agents are reached via the in-chat
 * sub-agent breadcrumb, parity with `SessionTabStrip`) AND `!isArchived`
 * (matches the old strip's filter; archived sessions are out of the default
 * scope).
 *
 * **Selected highlight**: the per-row `isSelected` parameter is resolved by
 * the caller via [resolveEffectiveSelectedId] so the parent root session
 * highlights while a sub-agent is open (parity with the old strip). The
 * highlight is rendered via [PickerTrailingCheck] (WT0 primitive) — no
 * per-row selected background (matches the unified picker language).
 *
 * **`onNewSession`**: kept in the signature for ChatScaffold-source
 * compatibility but unused in the body — the footer "New session" FAB was
 * deleted per the locked WT1 decision (Q3). The "new session" path is
 * surfaced elsewhere (the top-bar overflow / SessionTabStrip's own affordance).
 *
 * @param questionSessionIds per-session "pending question" flag — rendered
 *   as a `?` glyph in the trailing slot (tinted with the workdir tone).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionPickerSheet(
    sessions: List<Session>,
    sessionStatuses: Map<String, SessionStatus>,
    currentSessionId: String?,
    unreadSessions: Set<String>,
    questionSessionIds: Set<String> = emptySet(),
    onSelect: (String) -> Unit,
    onArchive: (String) -> Unit,
    onUnarchive: (String) -> Unit,
    @Suppress("UNUSED_PARAMETER") onNewSession: () -> Unit,
    onDismiss: () -> Unit,
) {
    // §review-D (gpter #4): the per-row overflow DropdownMenu is now anchored
    // INSIDE each SessionPickerRow's `⋮` Box (Tier-A trigger-anchored rule,
    // docs/ui-style-spec.md §A). No hoisted overflow anchor is needed here —
    // each row owns its own expanded state and Archive/Unarchive callbacks.

    // Root, non-archived sessions only — sub-agents are reached via the
    // in-chat sub-agent breadcrumb; archived sessions are out of the
    // picker's default scope. The search box + workdir grouping were removed
    // (WT1 Q3) so the derivation collapses to a single filter + sort + cap.
    val recent = remember(sessions) {
        sessions
            .filter { it.parentId == null && !it.isArchived }
            .sortedByDescending { it.time?.updated ?: 0L }
            .take(10)
    }

    // Resolved "selected" id: current root session when present, otherwise
    // the parent root session when the user is currently viewing a
    // sub-agent (I3 parity fix — the old SessionTabStrip highlighted the
    // parent tab while a sub-agent was open).
    val effectiveSelectedId = resolveEffectiveSelectedId(
        openSessions = recent,
        currentSessionId = currentSessionId,
        parentSessionId = sessions.firstOrNull { it.id == currentSessionId }?.parentId,
    )

    // §WT1: AppBottomSheet (WT0 primitive) — titleLarge + 24/8 padding via the
    // recipe; container colour + sheetState defaults handled by the recipe.
    // No footer (the FAB was deleted). The body LazyColumn sizes naturally —
    // AppBottomSheet does not impose a fixed height cap.
    AppBottomSheet(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.chat_action_sessions),
    ) {
        if (recent.isEmpty()) {
            Text(
                text = stringResource(R.string.sessions_no_active),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    horizontal = Dimens.spacing6,
                    vertical = Dimens.spacing3,
                ),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(recent, key = { "recent_" + it.id }) { s ->
                    SessionPickerRow(
                        session = s,
                        isSelected = s.id == effectiveSelectedId,
                        status = sessionStatuses[s.id],
                        isUnread = s.id in unreadSessions,
                        hasQuestion = s.id in questionSessionIds,
                        onClick = { onSelect(s.id) },
                        onArchive = { onArchive(s.id) },
                        onUnarchive = { onUnarchive(s.id) },
                    )
                }
            }
        }
    }

}

@Composable
private fun SessionPickerRow(
    session: Session,
    isSelected: Boolean,
    status: SessionStatus?,
    isUnread: Boolean,
    hasQuestion: Boolean,
    onClick: () -> Unit,
    // §review-D (gpter #4): the overflow DropdownMenu is anchored INSIDE this
    // row's trailing Box (Tier-A trigger-anchored rule, docs/ui-style-spec.md
    // §A). Each row owns its own expanded state; Archive/Unarchive are routed
    // straight to the parent's session-level callbacks. See
    // [SessionRowOverflowMenu] below for the DropdownMenu body.
    onArchive: () -> Unit,
    onUnarchive: () -> Unit,
) {
    val tone = remember(session.directory) { workdirTone(session.directory) }
    ListItem(
        headlineContent = {
            Text(
                text = session.displayName,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            val workdirBase = session.directory.split("/").filter { it.isNotEmpty() }.lastOrNull().orEmpty()
            val timeText = session.time?.updated?.let { formatTime(it) }.orEmpty()
            Text(
                text = listOf(workdirBase, timeText).filter { it.isNotBlank() }.joinToString("  •  "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = {
            // §WT1: leading workdir-tone dot kept (Q3 explicit "keep") — the
            // visual anchor tying the row to its project colour.
            Box(modifier = Modifier.size(Dimens.iconXs).clip(CircleShape).background(tone))
        },
        trailingContent = {
            // §WT1: trailing slot carries the state dots (question / retry /
            // unread), the unified PickerTrailingCheck (selected → primary
            // Check; replaces the old surfaceContainerHigh selected bg), and
            // the ⋮ overflow IconButton (Archive/Unarchive).
            //
            // §review-D (gpter #4): the overflow DropdownMenu MUST live in the
            // SAME Box as its `⋮` trigger so the popup shares layout coords
            // with the button and anchors to it (Tier-A trigger-anchored rule,
            // docs/ui-style-spec.md §A). Per-row local expanded state is fine —
            // only one menu can be open at a time (the DropdownMenu auto-
            // dismisses on outside tap / item pick via onDismissRequest).
            var overflowExpanded by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (hasQuestion) {
                    Text(
                        text = "?",
                        color = tone,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(end = Dimens.spacingCompact),
                    )
                }
                if (status?.isRetry == true) {
                    Box(
                        modifier = Modifier
                            .size(Dimens.spacing2)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.error)
                    )
                    Spacer(Modifier.size(Dimens.spacingCompact))
                }
                if (isUnread) {
                    Box(
                        modifier = Modifier
                            .size(Dimens.spacing2)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                    Spacer(Modifier.size(Dimens.spacingCompact))
                }
                PickerTrailingCheck(selected = isSelected)
                Box {
                    IconButton(onClick = { overflowExpanded = true }) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.chat_session_overflow),
                        )
                    }
                    SessionRowOverflowMenu(
                        session = session,
                        expanded = overflowExpanded,
                        onDismissRequest = { overflowExpanded = false },
                        onArchive = { overflowExpanded = false; onArchive() },
                        onUnarchive = { overflowExpanded = false; onUnarchive() },
                    )
                }
            }
        },
        modifier = Modifier
            .clickable(onClick = onClick)
            // 600dp 是 tablet 大屏下的内容最大宽度（非 spacing token）；ui-style-spec
            // §3 偏离豁免——这是 layout max-width，不是 spacing 字面量。
            .widthIn(max = 600.dp),
    )
}

@Composable
private fun SessionRowOverflowMenu(
    session: Session,
    // §review-D (gpter #4): expanded + onDismissRequest are now passed in so
    // the DropdownMenu binds to its trigger's per-row expanded state (the
    // menu is composed inside the same Box as the `⋮` IconButton in
    // [SessionPickerRow], making it Tier-A trigger-anchored).
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    onArchive: () -> Unit,
    onUnarchive: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
    ) {
        if (session.isArchived) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.sessions_restore)) },
                onClick = onUnarchive,
            )
        } else {
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(R.string.sessions_archive),
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                onClick = onArchive,
            )
        }
    }
}

private fun formatTime(epochMs: Long): String {
    if (epochMs <= 0L) return ""
    return try {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        sdf.format(java.util.Date(epochMs))
    } catch (_: Exception) {
        ""
    }
}
