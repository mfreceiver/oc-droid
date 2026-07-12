// SessionPickerSheet.kt — Phase 1B ModalBottomSheet session picker (D.4).
//
// Replaces the second-row `SessionTabStrip` + `HorizontalPager` from the old
// ChatScreen with a sheet-based session switcher. The sheet renders Recent
// + By-workdir sections (no Search yet — Search is Phase 2 per G.2 step 1).
// Each row is an M3 `ListItem`; the MoreVert trigger opens an
// overflow menu with Archive / Unarchive (P4-4 — the old long-press archive
// gesture is replaced with an explicit overflow menu so the destructive
// action is always reachable from a visible affordance).
//
// §1B-FIX: the body uses a LazyColumn so the Recent + By-workdir sections
// scroll inside the sheet when the session count exceeds the visible area.
// The "New session" FAB lives in a fixed footer below the LazyColumn so it
// stays reachable no matter how far the user has scrolled.

package cn.vectory.ocdroid.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SearchBar
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.ui.theme.Dimens
import androidx.compose.ui.res.stringResource

/**
 * Phase 1B session picker (D.4 / G.1 step 1+2). Renders the root-session list
 * grouped into Recent + By-workdir (no Search yet). Selecting a row invokes
 * [onSelect]; the per-row MoreVert opens the archive / unarchive overflow
 * menu. The "New session" affordance is an [ExtendedFloatingActionButton]
 * in a fixed footer below the scrollable list so it is always reachable
 * regardless of how many sessions exist.
 *
 * Filtering (root, non-archived):
 *  - non-root sessions (`parentId != null`) are EXCLUDED — sub-agents are
 *    reached via the in-chat sub-agent breadcrumb, not via this picker
 *    (the SessionTabStrip parity rule from ChatSessionTabStrip.kt §17.2).
 *  - archived sessions (`isArchived`) are EXCLUDED from the default view
 *    (matches the old SessionTabStrip's `!it.isArchived` filter). The
 *    per-row overflow menu surfaces "Unarchive" on a row that the caller
 *    flagged as archived — the caller's responsibility is to pass any
 *    archived rows it wants to surface.
 *
 * Sub-agent parent highlight: the per-row `isSelected` parameter is
 * resolved by the caller via [resolveEffectiveSelectedId] so the parent
 * root session highlights while a sub-agent is open (parity with the
 * old SessionTabStrip behaviour).
 *
 * [composer's draftWorkdir] is unused here — the sheet is purely about
 * session selection; the "New session" path is decided by [onNewSession]
 * (the caller falls back to settingsManager.currentWorkdir when the
 * draft workdir is null).
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
    onNewSession: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Overflow menu anchor — when non-null, the DropdownMenu is rendered for
    // the matching session id. Archive / unarchive are visible by default;
    // the overflow is the canonical destructive-affordance surface (P4-4).
    // Long-press will be wired to combinedClickable in a follow-up patch;
    // for Phase 1B the per-row MoreVert IconButton is the only overflow
    // entry point (a11y-friendly + doesn't depend on combinedClickable).
    var overflowFor by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }

    // Root, non-archived sessions only — sub-agents are reached via the
    // in-chat sub-agent breadcrumb; archived sessions are out of the
    // picker's default scope (callers can still pass archived sessions
    // and the per-row overflow surfaces "Unarchive").
    val rootSessions = remember(sessions, query) {
        sessions.filter { it.parentId == null && !it.isArchived }
            .filter { session ->
                query.isBlank() || session.displayName.contains(query, true) || session.directory.contains(query, true)
            }
    }
    val recent = remember(rootSessions) {
        rootSessions
            .sortedByDescending { it.time?.updated ?: 0L }
            .take(10)
    }
    val byWorkdir = remember(rootSessions) {
        rootSessions
            .sortedBy { it.directory.split("/").lastOrNull() ?: it.directory }
            .groupBy { it.directory }
            .toSortedMap()
    }

    // Resolved "selected" id: current root session when present, otherwise
    // the parent root session when the user is currently viewing a
    // sub-agent (I3 parity fix — the old SessionTabStrip highlighted the
    // parent tab while a sub-agent was open).
    val effectiveSelectedId = resolveEffectiveSelectedId(
        openSessions = rootSessions,
        currentSessionId = currentSessionId,
        parentSessionId = sessions.firstOrNull { it.id == currentSessionId }?.parentId,
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        // §1B-FIX: Column split into a scrollable LazyColumn (header +
        // Recent + By-workdir) and a fixed footer holding the "New session"
        // FAB. The FAB lives OUTSIDE the LazyColumn so it does NOT scroll
        // away and stays reachable for the user. (Pre-fix the body was a
        // plain Column — recent(10) + per-workdir lists + FAB overflowed
        // the sheet on phones and the bottom half of the list + the FAB
        // were unreachable.)
        Column(modifier = Modifier.fillMaxWidth()) {
            // Fixed header (does not scroll).
            Text(
                text = stringResource(R.string.chat_action_sessions),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = Dimens.spacing6, vertical = Dimens.spacing2),
            )
            SearchBar(
                query = query,
                onQueryChange = { query = it },
                onSearch = {},
                active = false,
                onActiveChange = {},
                placeholder = { Text("Search sessions") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.spacing4),
            ) {}
            HorizontalDivider()

            // Scrollable body: header rows + per-session rows.
            // The content list (Recent + By-workdir) is interleaved with
            // section headers via stable keys so the LazyColumn can
            // recycle cells. When both Recent and By-workdir are empty the
            // empty-state placeholder still scrolls into view.
            val noRows = recent.isEmpty() && byWorkdir.isEmpty()
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (noRows) 0.dp else 480.dp),
            ) {
                if (recent.isNotEmpty()) {
                    item("hdr_recent") {
                        SectionHeader(text = stringResource(R.string.sessions_recent_section))
                    }
                    items(recent, key = { "recent_" + it.id }) { s ->
                        SessionPickerRow(
                            session = s,
                            isSelected = s.id == effectiveSelectedId,
                            status = sessionStatuses[s.id],
                            isUnread = s.id in unreadSessions,
                            hasQuestion = s.id in questionSessionIds,
                            onClick = { onSelect(s.id) },
                            onOverflow = { overflowFor = s.id },
                        )
                    }
                    item("div_recent") { HorizontalDivider() }
                }
                if (byWorkdir.isNotEmpty()) {
                    item("hdr_by_workdir") {
                        SectionHeader(text = stringResource(R.string.sessions_by_workdir_section))
                    }
                    byWorkdir.forEach { (workdir, list) ->
                        item("workdir_" + workdir) {
                            WorkdirHeader(workdir = workdir)
                        }
                        items(list, key = { "wd_" + workdir + "_" + it.id }) { s ->
                            SessionPickerRow(
                                session = s,
                                isSelected = s.id == effectiveSelectedId,
                                status = sessionStatuses[s.id],
                                isUnread = s.id in unreadSessions,
                                hasQuestion = s.id in questionSessionIds,
                                onClick = { onSelect(s.id) },
                                onOverflow = { overflowFor = s.id },
                            )
                        }
                    }
                }
                if (noRows) {
                    item("empty") {
                        Text(
                            text = stringResource(R.string.sessions_no_active),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = Dimens.spacing6, vertical = Dimens.spacing3),
                        )
                    }
                }
            }

            // Fixed footer: New session FAB. Lives OUTSIDE the LazyColumn so
            // it stays reachable no matter how far the user scrolled.
            HorizontalDivider()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Dimens.spacing4),
                contentAlignment = Alignment.CenterEnd,
            ) {
                ExtendedFloatingActionButton(
                    onClick = onNewSession,
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.sessions_new_session)) },
                )
            }
        }
    }

    val overflowSession = overflowFor?.let { id -> sessions.firstOrNull { it.id == id } }
    if (overflowSession != null) {
        SessionRowOverflowMenu(
            session = overflowSession,
            onDismiss = { overflowFor = null },
            onArchive = { overflowFor = null; onArchive(overflowSession.id) },
            onUnarchive = { overflowFor = null; onUnarchive(overflowSession.id) },
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Dimens.spacing6, vertical = Dimens.spacing2),
    )
}

@Composable
private fun WorkdirHeader(workdir: String) {
    val baseName = workdir.split("/").filter { it.isNotEmpty() }.lastOrNull() ?: workdir
    Text(
        text = baseName,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(horizontal = Dimens.spacing6, vertical = Dimens.spacing1),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun SessionPickerRow(
    session: Session,
    isSelected: Boolean,
    status: SessionStatus?,
    isUnread: Boolean,
    hasQuestion: Boolean,
    onClick: () -> Unit,
    onOverflow: () -> Unit,
) {
    val tone = remember(session.directory) { workdirTone(session.directory) }
    val surfaceColor =
        if (isSelected) MaterialTheme.colorScheme.surfaceContainerHigh
        else MaterialTheme.colorScheme.surface
    ListItem(
        colors = androidx.compose.material3.ListItemDefaults.colors(
            containerColor = surfaceColor,
        ),
        headlineContent = {
            Text(
                text = session.displayName,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
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
            Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(tone))
        },
        trailingContent = {
            androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
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
                IconButton(onClick = onOverflow) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.chat_session_overflow),
                    )
                }
            }
        },
        modifier = Modifier
            .clickable(onClick = onClick)
            .widthIn(max = 600.dp)
    )
}

@Composable
private fun SessionRowOverflowMenu(
    session: Session,
    onDismiss: () -> Unit,
    onArchive: () -> Unit,
    onUnarchive: () -> Unit,
) {
    DropdownMenu(
        expanded = true,
        onDismissRequest = onDismiss,
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
