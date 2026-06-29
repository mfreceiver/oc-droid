package com.yage.opencode_client.ui.sessions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yage.opencode_client.R
import com.yage.opencode_client.data.model.Session
import com.yage.opencode_client.ui.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SessionsScreen(
    viewModel: MainViewModel,
    onSwitchToChat: () -> Unit = {}
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showNewWorkdirDialog by remember { mutableStateOf(false) }
    var expandedWorkdirs by remember { mutableStateOf(setOf<String>()) }
    // Locally-hidden workdirs (long-press → disconnect). UI-only; reset on refresh/re-enter tab.
    var hiddenWorkdirs by remember { mutableStateOf(setOf<String>()) }
    var pendingDisconnectWorkdir by remember { mutableStateOf<String?>(null) }

    // Derive recent sessions (root sessions: parentId == null, by time.updated desc, top 5).
    // Source merges the global sessions list with directorySessions (#10: prior
    // conversations discovered for a connected workdir that may not yet appear
    // in the global list), deduplicated by id so a session present in both
    // stores is only rendered once.
    val recentSessions = remember(state.sessions, state.directorySessions) {
        (state.sessions + state.directorySessions.values.flatten())
            .distinctBy { it.id }
            .filter { it.parentId == null && !it.isArchived }
            .sortedByDescending { it.time?.updated ?: 0L }
            .take(5)
    }

    // Derive workdir groups, excluding locally-hidden workdirs and sub-agents
    // (parentId != null). Sub-agents are only reachable from within a parent
    // conversation via SubAgentCard, never from this workdir list.
    //
    // The draft workdir (when the user has invoked createSessionInWorkdir but
    // no session has been POSTed yet) is appended with an empty session list
    // so the in-progress "connect" is visible alongside connected projects.
    //
    // Source merges state.sessions with state.directorySessions (#10) so a
    // workdir that has only been discovered via the directory-scoped fetch
    // (and not yet promoted into the global list by a periodic refresh) still
    // shows its sessions here. Deduplicated by id to avoid double-rendering.
    val workdirGroups = remember(state.sessions, state.directorySessions, hiddenWorkdirs, state.draftWorkdir) {
        val allSessions = (state.sessions + state.directorySessions.values.flatten())
            .distinctBy { it.id }
        // Fix #6b: normalize directory keys (trim + strip surrounding slashes)
        // so a web-created session whose directory differs only by a trailing
        // or leading slash groups under the EXISTING project instead of
        // spawning a hidden duplicate group. Normalization is applied
        // consistently to the hiddenWorkdirs filter, the groupBy key, and the
        // draft check. The representative ORIGINAL directory
        // (keyToDisplayDir) is preserved for display and for VM calls
        // (browseFilesInWorkdir / createSessionInWorkdir) which expect the
        // un-normalized path.
        val normalizedHidden = hiddenWorkdirs.mapTo(mutableSetOf()) { normalizeDirectory(it) }
        val groupsByKey = LinkedHashMap<String, MutableList<Session>>()
        val keyToDisplayDir = LinkedHashMap<String, String>()
        for (s in allSessions) {
            if (s.parentId != null) continue
            val key = normalizeDirectory(s.directory)
            if (key in normalizedHidden) continue
            groupsByKey.getOrPut(key) { mutableListOf() }.add(s)
            // Prefer an absolute-style (leading "/") representative for
            // display; otherwise keep the first seen. Two sessions that
            // normalize together usually differ only by surrounding slashes,
            // so any pick is functionally equivalent for the VM's
            // directory-scoped APIs.
            val current = keyToDisplayDir[key]
            if (current == null || (s.directory.startsWith('/') && !current.startsWith('/'))) {
                keyToDisplayDir[key] = s.directory
            }
        }
        state.draftWorkdir?.let { draft ->
            val key = normalizeDirectory(draft)
            if (key !in groupsByKey && key !in normalizedHidden) {
                groupsByKey[key] = mutableListOf()
                keyToDisplayDir.putIfAbsent(key, draft)
            }
        }
        // (displayDir, sessions), sorted by normalized key for stable ordering.
        groupsByKey.entries
            .map { (key, sessList) ->
                (keyToDisplayDir[key] ?: key) to sessList.sortedByDescending { it.time?.updated ?: 0L }
            }
            .sortedBy { normalizeDirectory(it.first) }
    }

    // Navigate to Chat tab after selecting a session
    fun onSessionClick(sessionId: String) {
        viewModel.selectSession(sessionId)
        onSwitchToChat()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.nav_sessions))
                },
                navigationIcon = {
                    // Back to Chat (the destination-aware entry point). The
                    // Sessions screen is no longer swipe-reachable — this is
                    // the primary way back to the conversation.
                    IconButton(onClick = onSwitchToChat) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back)
                        )
                    }
                },
                actions = {
                    // Connect-new-project affordance lives in the TopAppBar so
                    // it is reachable from anywhere on the Sessions page (no
                    // matter whether the workdir list is empty or populated).
                    IconButton(onClick = { showNewWorkdirDialog = true }) {
                        Icon(
                            Icons.Default.CreateNewFolder,
                            contentDescription = stringResource(R.string.sessions_connect_new_action),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            // --- Recent Sessions Section ---
            item(key = "recent_header") {
                SectionHeader(
                    icon = Icons.Default.History,
                    title = stringResource(R.string.sessions_tab_recent)
                )
            }

            if (recentSessions.isEmpty()) {
                item(key = "recent_empty") {
                    EmptyRow(stringResource(R.string.sessions_tab_no_sessions))
                }
            } else {
                items(recentSessions, key = { it.id }) { session ->
                    SessionCard(
                        session = session,
                        isUnread = session.id in state.unreadSessions,
                        onClick = { onSessionClick(session.id) }
                    )
                }
            }

            // --- Workdirs Section ---
            item(key = "workdirs_header") {
                SectionHeader(
                    icon = Icons.Default.Folder,
                    title = stringResource(R.string.sessions_tab_workdirs)
                )
            }

            if (workdirGroups.isEmpty()) {
                item(key = "workdirs_empty") {
                    EmptyRow(stringResource(R.string.sessions_tab_no_workdirs))
                }
            } else {
                items(workdirGroups, key = { "workdir_${normalizeDirectory(it.first)}" }) { (workdir, sessionsInWorkdir) ->
                    val isExpanded = expandedWorkdirs.contains(workdir)
                    val displayName = workdir.split("/").filter { it.isNotEmpty() }.lastOrNull()
                        ?: workdir
                    val isDraft = workdir == state.draftWorkdir && sessionsInWorkdir.isEmpty()

                    Column(modifier = Modifier.fillMaxWidth()) {
                        // Workdir header row (click = expand/collapse, long-click = disconnect)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        if (isExpanded) {
                                            expandedWorkdirs = expandedWorkdirs - workdir
                                        } else {
                                            expandedWorkdirs = expandedWorkdirs + workdir
                                            // Fix #6c: re-fetch this project's directory
                                            // sessions on expand so a conversation created
                                            // elsewhere (web) — whose session.created SSE
                                            // event was missed while backgrounded — appears
                                            // without a full reconnect. Fire-and-forget; on
                                            // failure the existing list is kept (onSuccess only).
                                            viewModel.refreshDirectorySessions(workdir)
                                        }
                                    },
                                    onLongClick = { pendingDisconnectWorkdir = workdir }
                                )
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (isExpanded) Icons.Default.KeyboardArrowDown
                                else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = if (isExpanded) "Collapse" else "Expand",
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = displayName,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    if (isDraft) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = stringResource(R.string.sessions_draft_badge),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(
                                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                                )
                                                .padding(horizontal = 6.dp, vertical = 1.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = workdir,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            // Browse files for this project (Bug3: relocated
                            // from the chat tab strip). Scopes the repository
                            // to this workdir and opens the file-browser overlay.
                            // R-12 (WCAG 2.5.5): no explicit size override, so
                            // the IconButton falls back to its 48dp default touch
                            // target (the former 32dp was below the minimum).
                            IconButton(
                                onClick = {
                                    viewModel.browseFilesInWorkdir(workdir)
                                }
                            ) {
                                Icon(
                                    Icons.Default.Folder,
                                    contentDescription = stringResource(R.string.nav_files),
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            // Create-session affordance for this workdir. Tapping
                            // it opens a fresh draft against this directory AND
                            // jumps to Chat so the user lands in the composer
                            // for the new (draft) session. The draft itself is
                            // created by createSessionInWorkdir; the navigation
                            // is wired here so SessionsScreen stays decoupled
                            // from the Chat destination's internals.
                            // R-12 (WCAG 2.5.5): 48dp default touch target (was 32dp).
                            IconButton(
                                onClick = {
                                    viewModel.createSessionInWorkdir(workdir)
                                    onSwitchToChat()
                                }
                            ) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = stringResource(R.string.sessions_tab_create_session),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        // Expandable session list within workdir
                        AnimatedVisibility(visible = isExpanded && sessionsInWorkdir.isNotEmpty()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 40.dp, end = 16.dp)
                            ) {
                                sessionsInWorkdir.forEach { session ->
                                    SessionCard(
                                        session = session,
                                        isUnread = session.id in state.unreadSessions,
                                        onClick = { onSessionClick(session.id) }
                                    )
                                }
                            }
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        }
    }

    // --- Disconnect workdir confirmation dialog (UI-only filter) ---
    pendingDisconnectWorkdir?.let { workdir ->
        AlertDialog(
            onDismissRequest = { pendingDisconnectWorkdir = null },
            title = { Text(stringResource(R.string.sessions_disconnect_workdir)) },
            text = {
                val name = workdir.split("/").filter { it.isNotEmpty() }.lastOrNull() ?: workdir
                Text(stringResource(R.string.sessions_disconnect_confirm) + "\n\n" + name + "\n" + workdir)
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        hiddenWorkdirs = hiddenWorkdirs + workdir
                        expandedWorkdirs = expandedWorkdirs - workdir
                        pendingDisconnectWorkdir = null
                    }
                ) {
                    Text(stringResource(R.string.sessions_disconnect_workdir))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDisconnectWorkdir = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    // --- Directory picker (modal bottom sheet) for connecting a new project ---
    if (showNewWorkdirDialog) {
        DirectoryPickerSheet(
            repository = viewModel.repository,
            onDismiss = { showNewWorkdirDialog = false },
            onSelect = { path ->
                showNewWorkdirDialog = false
                viewModel.createSessionInWorkdir(path)
            }
        )
    }
}

@Composable
private fun SectionHeader(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun SessionCard(session: Session, isUnread: Boolean = false, onClick: () -> Unit) {
    val dirBasename = session.directory.split("/").filter { it.isNotEmpty() }.lastOrNull()
        ?: session.directory
    // Prefer the latest message time (time.updated); fall back to time.created.
    val updatedText = (session.time?.updated?.takeIf { it > 0L } ?: session.time?.created?.takeIf { it > 0L })
        ?.let { formatTime(it) }

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = session.displayName,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                if (isUnread) {
                    // Small primary-colored unread dot on the right edge.
                    Box(
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .size(8.dp)
                            .background(
                                color = MaterialTheme.colorScheme.primary,
                                shape = CircleShape
                            )
                    )
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = dirBasename,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (updatedText != null) {
                    Text(
                        text = "  •  $updatedText",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyRow(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
    )
}

private fun formatTime(epochMs: Long): String {
    return try {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        sdf.format(Date(epochMs))
    } catch (_: Exception) {
        ""
    }
}

/**
 * Strip surrounding whitespace and slashes so directories that differ only by
 * a leading or trailing "/" (common between phone- and web-created sessions)
 * compare and group as equal. Fix #6b. Used only for grouping/membership keys
 * in [SessionsScreen]; the representative ORIGINAL directory is preserved for
 * display and VM calls.
 */
private fun normalizeDirectory(dir: String): String = dir.trim().trim('/')
