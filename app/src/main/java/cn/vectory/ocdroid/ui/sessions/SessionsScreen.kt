package cn.vectory.ocdroid.ui.sessions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.ui.ComposerViewModel
import cn.vectory.ocdroid.ui.OrchestratorViewModel
import cn.vectory.ocdroid.ui.SessionViewModel
import cn.vectory.ocdroid.ui.SettingsViewModel
import cn.vectory.ocdroid.ui.chat.workdirTone
import cn.vectory.ocdroid.util.DebugLog
import cn.vectory.ocdroid.util.WorkdirPaths
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SessionsScreen(
    viewModel: SessionViewModel,
    composerVM: ComposerViewModel,
    orchestratorVM: OrchestratorViewModel,
    settingsVM: SettingsViewModel,
    /** §R-17 batch3e: repository for the DirectoryPickerSheet (was
     *  `viewModel.core.repository`). Injected via the activity-scoped
     *  [FilesViewModel] at the call site; passed in directly here so this
     *  composable does not reach into `.core`. */
    repository: OpenCodeRepository,
    onSwitchToChat: () -> Unit = {},
    /**
     * Phase 7：横屏左 1/3 面板内渲染时设 false，隐藏 TopAppBar 的 navigationIcon
     * （面板始终与 chat pane 并列，无需"返回 chat"——onSwitchToChat 在面板内为 no-op）。
     * 竖屏全屏渲染时默认 true。
     */
    showBackNavigation: Boolean = true
) {
    // §R-17 Stage 3 (+ follow-up debt cleanup): subscribe to the relevant
    // slice Flows directly instead of the whole-app AppState. SessionsScreen
    // only cares about the session list (sessionListFlow, consumed whole since
    // it reads sessions + directorySessions), the draft workdir marker, and the
    // unread badge set.
    //
    // Field-level subscriptions (.map { it.field }.distinctUntilChanged()) are
    // used for draftWorkdir / unreadSessions: draftWorkdir lives on the
    // composer slice alongside the high-frequency inputText (mutates on every
    // keystroke), and unreadSessions lives on the unread slice alongside
    // tempClearedUnread / lastViewedTime (mutated on session switches).
    // Projecting to the single read field means typing / session-switch no
    // longer recompose this screen. (S1 runtime impact is currently zero —
    // HorizontalPager was replaced with explicit nav so SessionsScreen is not
    // kept hot during chat typing — but the field-level subscription is still
    // the cleaner, more precise model.)
    val sessionListState by viewModel.sessionListFlow.collectAsStateWithLifecycle()
    val draftWorkdir by remember { composerVM.composerFlow.map { it.draftWorkdir }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(initialValue = null)
    val unreadSessions by remember { viewModel.unreadFlow.map { it.unreadSessions }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(initialValue = emptySet())
    val recentWorkdirs by settingsVM.recentWorkdirs.collectAsStateWithLifecycle()
    var showNewWorkdirDialog by remember { mutableStateOf(false) }
    var expandedWorkdirs by remember { mutableStateOf(setOf<String>()) }
    var pendingDisconnectWorkdir by remember { mutableStateOf<String?>(null) }
    // M7: long-press a session card → archive confirmation dialog. Null = hidden.
    var pendingArchiveSession by remember { mutableStateOf<Session?>(null) }

    // Derive recent sessions (root sessions: parentId == null, by time.updated desc, top 5).
    // Source merges the global sessions list with directorySessions (#10: prior
    // conversations discovered for a connected workdir that may not yet appear
    // in the global list), deduplicated by id so a session present in both
    // stores is only rendered once.
    // R-17 Stage 3: reads come from sessionListState (a slice Flow), so only
    // session-list mutations re-emit. The remembered derivedStateOf still
    // suppresses structurally-equal results, so an unrelated sessionStatuses
    // tweak recomputes the block but yields an equal list and the LazyColumn
    // items are skipped.
    val recentSessions by remember {
        derivedStateOf {
            (sessionListState.sessions + sessionListState.directorySessions.values.flatten())
                .distinctBy { it.id }
                .filter { it.parentId == null && !it.isArchived }
                .sortedByDescending { it.time?.updated ?: 0L }
                .take(5)
        }
    }

    // Derive workdir groups for the "Connected projects" list, sub-agents
    // (parentId != null) excluded. Sub-agents are only reachable from within
    // a parent conversation via SubAgentCard, never from this workdir list.
    //
    // §grouping-rewrite Round-3 C3: derivation is now GATED by recent_workdirs
    // membership — a workdir is visible iff it is in recentWorkdirs OR it is
    // the active draftWorkdir. This makes the persistent recent_workdirs list
    // the single source of truth for "connected" so disconnecting a workdir
    // (which removes it from recent_workdirs + evicts its cache) hides it
    // from this list EVEN WHEN its live sessions are still in sessionListFlow
    // (the common case during the window between disconnect and the server
    // list refresh). The disconnect dialog promises "removed from the list"
    // unconditionally — the pre-C3 logic broke that promise for any workdir
    // with at least one live (non-archived, parentId==null) session.
    //
    // The derivation lives in [buildWorkdirGroups] (top-level, pure, testable);
    // this derivedStateOf just feeds it the live slice values + caches the
    // structurally-equal result so SSE chat deltas / typing (which do not
    // touch any of the input slices) do not recompose the workdir LazyColumn.
    val workdirGroups by remember {
        derivedStateOf {
            buildWorkdirGroups(
                allSessions = (sessionListState.sessions + sessionListState.directorySessions.values.flatten())
                    .distinctBy { it.id },
                recentWorkdirs = recentWorkdirs,
                draftWorkdir = draftWorkdir,
            )
        }
    }

    // R-17 Stage 3: unreadSessions is a field-level slice Flow (above), so the
    // value is already locally-stable — no derivedStateOf wrapper needed. Lazy
    // item lambdas (recent + workdir session cards) capture this snapshot value
    // directly.

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
                    if (showBackNavigation) {
                        // Back to Chat (the destination-aware entry point). The
                        // Sessions screen is no longer swipe-reachable — this is
                        // the primary way back to the conversation.
                        IconButton(onClick = onSwitchToChat) {
                            Icon(
                                Icons.Default.Forum,
                                contentDescription = stringResource(R.string.common_back)
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
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
                        isUnread = session.id in unreadSessions,
                        status = sessionListState.sessionStatuses[session.id],
                        onClick = { onSessionClick(session.id) },
                        onLongClick = { pendingArchiveSession = session }
                    )
                }
            }

            // --- Workdirs Section ---
            item(key = "workdirs_header") {
                SectionHeader(
                    icon = Icons.Default.Inbox,
                    title = stringResource(R.string.sessions_tab_workdirs),
                    trailing = {
                        // §entry-relocate: "连接新项目"入口从 TopAppBar 移到此处，
                        // 图标换 CreateNewFolder（文件夹+加号，"添加目录"语义最清晰）。
                        IconButton(onClick = { showNewWorkdirDialog = true }) {
                            Icon(
                                Icons.Default.CreateNewFolder,
                                contentDescription = stringResource(R.string.sessions_connect_new_action),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                )
            }

            if (workdirGroups.isEmpty()) {
                item(key = "workdirs_empty") {
                    EmptyRow(stringResource(R.string.sessions_tab_no_workdirs))
                }
            } else {
                items(workdirGroups, key = { "workdir_${WorkdirPaths.normalize(it.first)}" }) { (workdir, sessionsInWorkdir) ->
                    val isExpanded = expandedWorkdirs.contains(workdir)
                    val displayName = workdir.split("/").filter { it.isNotEmpty() }.lastOrNull()
                        ?: workdir
                    // §grouping-rewrite Round-6 F3: normalize-match the draft
                    // badge. The visibility gate (buildWorkdirGroups) uses
                    // WorkdirPaths.normalize for the visible-set, so a draft
                    // whose raw form is a slash variant of a visible workdir
                    // (e.g. draftWorkdir="proj-a/" while the displayed group's
                    // workdir is "/proj-a") collapses onto the same group —
                    // the badge MUST fire for that group, not silently miss
                    // because the raw strings differ. Mirrors the C3/C4/C5
                    // pipeline's normalization contract.
                    //
                    // Capture draftWorkdir into a local val first so Kotlin
                    // can smart-cast the null-check (draftWorkdir is a
                    // delegated property — the compiler refuses to smart-cast
                    // those inline).
                    val draft = draftWorkdir
                    val isDraft = sessionsInWorkdir.isEmpty() &&
                        draft != null &&
                        WorkdirPaths.normalize(workdir) == WorkdirPaths.normalize(draft)

                    Column(modifier = Modifier.fillMaxWidth()) {
                        // Workdir header row (click = expand/collapse, long-click = disconnect).
                        // A1: converged the custom Row onto the M3 ListItem. The
                        // combinedClickable (expand/collapse + long-click disconnect)
                        // is forwarded via the modifier so the whole row remains the
                        // gesture target; the browse / create IconButtons in
                        // trailingContent consume their own taps.
                        ListItem(
                            modifier = Modifier
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
                            ),
                            leadingContent = {
                                Icon(
                                    if (isExpanded) Icons.Default.KeyboardArrowDown
                                    else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            headlineContent = {
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
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier
                                                .clip(MaterialTheme.shapes.extraSmall)
                                                .background(
                                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
                                                )
                                                .padding(horizontal = 6.dp, vertical = 1.dp)
                                        )
                                    }
                                }
                            },
                            supportingContent = {
                                Text(
                                    text = workdir,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    // §workdir-height-fix: constrain to a single line +
                                    // ellipsis. M3 ListItem renders multi-line
                                    // supportingContent with asymmetric bottom spacing
                                    // (the taller left column mismatches the 48dp
                                    // trailing IconButtons row), which made long-path
                                    // workdir items show extra space at the bottom vs
                                    // single-line items. Capping to 1 line keeps every
                                    // workdir row a uniform height. The basename (the
                                    // meaningful part) is already in headlineContent.
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            },
                            trailingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    // Browse files for this project (Bug3: relocated
                                    // from the chat tab strip). Scopes the repository
                                    // to this workdir and opens the file-browser overlay.
                                    // R-12 (WCAG 2.5.5): no explicit size override, so
                                    // the IconButton falls back to its 48dp default touch
                                    // target (the former 32dp was below the minimum).
                                    IconButton(
                                        onClick = {
                                            orchestratorVM.browseFilesInWorkdir(workdir)
                                        }
                                    ) {
                                        Icon(
                                            Icons.Default.FolderOpen,
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
                                            Icons.Default.AddComment,
                                            contentDescription = stringResource(R.string.sessions_tab_create_session),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        )

                        // Expandable session list within workdir. Use a modest
                        // inset (was start=40dp) so session cards use the full
                        // screen width instead of being squeezed into a narrow
                        // column; SessionCard itself adds 8dp horizontal padding.
                        AnimatedVisibility(visible = isExpanded) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp)
                            ) {
                                if (sessionsInWorkdir.isEmpty()) {
                                    EmptyWorkdirPlaceholder(
                                        onClick = {
                                            viewModel.createSessionInWorkdir(workdir)
                                            onSwitchToChat()
                                        }
                                    )
                                }
                                sessionsInWorkdir.forEach { session ->
                                    SessionCard(
                                        session = session,
                                        isUnread = session.id in unreadSessions,
                                        status = sessionListState.sessionStatuses[session.id],
                                        onClick = { onSessionClick(session.id) },
                                        onLongClick = { pendingArchiveSession = session },
                                        onArchive = { pendingArchiveSession = session },
                                        showWorkdir = false
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // --- Disconnect workdir confirmation dialog (persistent disconnect) ---
    pendingDisconnectWorkdir?.let { workdir ->
        AlertDialog(
            onDismissRequest = { pendingDisconnectWorkdir = null },
            title = { Text(stringResource(R.string.workdir_disconnect_title)) },
            text = {
                val name = workdir.split("/").filter { it.isNotEmpty() }.lastOrNull() ?: workdir
                Text(stringResource(R.string.workdir_disconnect_message) + "\n\n" + name + "\n" + workdir)
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        settingsVM.disconnectWorkdir(workdir)
                        expandedWorkdirs = expandedWorkdirs - workdir
                        pendingDisconnectWorkdir = null
                    }
                ) {
                    Text(stringResource(R.string.workdir_disconnect_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDisconnectWorkdir = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    // --- M7: Archive session confirmation dialog ---
    // Long-pressing a session card sets pendingArchiveSession; confirming calls
    // viewModel.core.archiveSession(id), which PATCHes session/{id} with the current
    // timestamp. The server-returned Session (time.archived > 0 ⇒ isArchived)
    // replaces the local copy, so the derivedStateOf filters (!it.isArchived)
    // in recentSessions / workdirGroups drop it from the list automatically.
    pendingArchiveSession?.let { session ->
        AlertDialog(
            onDismissRequest = { pendingArchiveSession = null },
            title = { Text(stringResource(R.string.sessions_archive)) },
            text = {
                Text(stringResource(R.string.sessions_archive_confirm) + "\n\n" + session.displayName)
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.archiveSession(session.id)
                        pendingArchiveSession = null
                    }
                ) {
                    Text(stringResource(R.string.sessions_archive))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingArchiveSession = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    // --- Directory picker (modal bottom sheet) for connecting a new project ---
    if (showNewWorkdirDialog) {
        DirectoryPickerSheet(
            repository = repository,
            onDismiss = { showNewWorkdirDialog = false },
            onSelect = { path ->
                showNewWorkdirDialog = false
                viewModel.createSessionInWorkdir(path)
            }
        )
    }
}

@Composable
private fun SectionHeader(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    trailing: @Composable (() -> Unit)? = null,
) {
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
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface
        )
        // §entry-relocate: 把"连接新项目"入口从 TopAppBar 移到"已连接的项目"
        // 标题行尾部，语义更清晰（紧邻它所作用的对象）。trailing 槽可选，其它
        // SectionHeader 不传 → 行为不变。
        if (trailing != null) {
            Spacer(modifier = Modifier.weight(1f))
            trailing()
        }
    }
}

@Composable
private fun SessionCard(
    session: Session,
    isUnread: Boolean = false,
    status: SessionStatus? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onArchive: (() -> Unit)? = null,
    // When true (default), the session's workdir basename is shown in the
    // subtitle. The connected-projects expanded list passes false because the
    // enclosing group header already conveys the workdir; the top cross-workdir
    // "recent sessions" list keeps it (its items can come from any project).
    showWorkdir: Boolean = true
) {
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
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RectangleShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        // A1: converged the custom title/subtitle Row onto the M3 ListItem.
        // leadingContent = agent icon, headlineContent = title, supportingContent
        // = metadata (workdir • time), trailingContent = archive action + status
        // / unread indicators. combinedClickable stays on the outer ElevatedCard
        // so the whole card remains the click/long-click target (archive button
        // consumes its own taps).
        ListItem(
            modifier = Modifier.heightIn(min = 48.dp),
            headlineContent = {
                Text(
                    text = session.displayName,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
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
                    modifier = Modifier.size(20.dp)
                )
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
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
                                .padding(start = 8.dp)
                                .size(8.dp)
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
                    if (onArchive != null) {
                        IconButton(onClick = onArchive) {
                            Icon(
                                Icons.Default.Archive,
                                contentDescription = stringResource(R.string.sessions_archive)
                            )
                        }
                    }
                }
            }
        )
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

@Composable
private fun EmptyWorkdirPlaceholder(onClick: () -> Unit) {
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clickable { onClick() },
        headlineContent = {
            Text(
                text = stringResource(R.string.workdir_empty_placeholder),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        leadingContent = {
            Icon(
                Icons.Default.AddComment,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    )
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
            .padding(start = 8.dp)
            .size(8.dp)
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

/**
 * §grouping-rewrite Round-4 C4: workdir-path normalization is now funnelled
 * through the shared [WorkdirPaths.normalize] helper so the disconnect
 * pipeline (buildWorkdirGroups visibility gating ↔ SettingsManager.
 * removeRecentWorkdir matching ↔ recent_workdirs persistent storage) agrees
 * on what "same workdir" means end-to-end. The previous file-local
 * `normalizeDirectory` was the load-bearing logic for the visibility gate
 * but only `trim()`-matched on the removal side, so slash variants
 * ("/proj-a" vs "proj-a/") stayed anchored in recent_workdirs after a
 * disconnect. See [WorkdirPaths] for the contract.
 *
 * Call sites use [WorkdirPaths.normalize] directly now — no file-local
 * wrapper — so there is a single source of truth (any future tweak to the
 * normalization rule lands in one place and both layers pick it up).
 */

/**
 * §grouping-rewrite Round-3 C3: pure derivation of the "Connected projects"
 * workdir groups for [SessionsScreen]. Extracted from the composable so the
 * gating logic can be unit-tested (see SessionsScreenTest).
 *
 * **GATING (C3 fix)**: a workdir is visible iff it is in [recentWorkdirs] OR
 * it is the active [draftWorkdir] (the in-progress "connect new project" the
 * user typed but has not yet POSTed). This makes the persistent
 * `recent_workdirs_<fp>` list the single source of truth for "connected" — a
 * disconnect (which removes the workdir from recent_workdirs + evicts its
 * cache) hides the workdir from the list EVEN WHEN its live sessions are
 * still in [allSessions] (the common case during the window between disconnect
 * and the server list refresh). The disconnect dialog promises "removed from
 * the list" unconditionally; the pre-C3 derivation broke that promise for any
 * workdir with at least one live (parentId==null, non-archived) session.
 *
 * **Preserved behaviour** (verified against the pre-C3 inline derivation):
 *  - Directory keys are normalised (trim + strip surrounding slashes) so
 *    phone- and web-created sessions that differ only by leading/trailing "/"
 *    group together.
 *  - The representative ORIGINAL directory (preferring absolute / leading-"/"
 *    form) is preserved in the output's `first` for display + VM calls
 *    (browseFilesInWorkdir / createSessionInWorkdir).
 *  - The active `draftWorkdir` is included even when absent from
 *    recentWorkdirs (the "Connect new project" draft must stay visible while
 *    the user is mid-typing).
 *  - Visible workdirs with zero live sessions are still emitted (the
 *    `workdir_empty_placeholder` row in the UI handles them) — this
 *    preserves the pre-C3 0-live fallback for workdirs the user just
 *    connected but has not yet started a conversation in.
 *  - Output sorted by normalized key for stable ordering.
 *
 * @param allSessions every session the UI currently knows about (typically
 *   `sessionListState.sessions + sessionListState.directorySessions.values.flatten()`,
 *   deduplicated by id by the caller — this function does NOT dedupe; passing
 *   already-deduped input keeps the grouping keys stable).
 * @param recentWorkdirs the persistent `recent_workdirs_<fp>` list — the
 *   source of truth for "connected".
 * @param draftWorkdir the in-progress "connect new project" workdir, or null.
 * @return list of (displayDirectory, liveSessionsSortedByUpdatedDesc) pairs,
 *   one per visible workdir, sorted by normalized directory.
 */
internal fun buildWorkdirGroups(
    allSessions: List<Session>,
    recentWorkdirs: List<String>,
    draftWorkdir: String?,
): List<Pair<String, List<Session>>> {
    // ── Step 1: visible set (the GATE). Insertion-ordered (recent first, then
    // draft) so the output ordering matches the user's mental model: recent
    // connections appear before the in-progress draft. Keyed by normalized
    // directory; we also remember the first-seen ORIGINAL dir string per key
    // so 0-session visible workdirs get a sensible displayDir fallback.
    //
    // §grouping-rewrite Round-4 C4: normalization goes through the shared
    // [WorkdirPaths.normalize] so the visibility gate here agrees with
    // [SettingsManager.removeRecentWorkdir]'s removal-side matching.
    val keyToVisibleDir = LinkedHashMap<String, String>()
    fun addVisible(dir: String) {
        val key = WorkdirPaths.normalize(dir)
        if (key.isBlank()) return
        keyToVisibleDir.putIfAbsent(key, dir)
    }
    recentWorkdirs.forEach(::addVisible)
    if (draftWorkdir != null) addVisible(draftWorkdir)
    val visibleKeys = keyToVisibleDir.keys // LinkedHashSet view; ordered

    // ── Step 2: attach live sessions to their group — IF AND ONLY IF the
    // group is in the visible set. Sessions whose workdir is NOT visible
    // (e.g. just-disconnected but still in the live list, OR a workdir that
    // was never connected and only surfaced via a directory-fetch) are
    // dropped here. This is the load-bearing change vs the pre-C3 logic.
    val groupsByKey = LinkedHashMap<String, MutableList<Session>>()
    val keyToDisplayDir = LinkedHashMap<String, String>()
    for (s in allSessions) {
        if (s.parentId != null) continue
        // Hide archived sessions from the connected-projects list too. The
        // top "recent sessions" list filters them separately.
        if (s.isArchived) continue
        val key = WorkdirPaths.normalize(s.directory)
        if (key.isBlank()) continue
        // C3 gate: sessions for INVISIBLE workdirs do not re-introduce the
        // workdir into the output.
        if (key !in visibleKeys) continue
        groupsByKey.getOrPut(key) { mutableListOf() }.add(s)
        // Prefer an absolute-style (leading "/") representative for display;
        // otherwise keep the first seen. Two sessions that normalize together
        // usually differ only by surrounding slashes, so any pick is
        // functionally equivalent for the VM's directory-scoped APIs.
        val current = keyToDisplayDir[key]
        if (current == null || (s.directory.startsWith('/') && !current.startsWith('/'))) {
            keyToDisplayDir[key] = s.directory
        }
    }

    // ── Step 3: ensure every visible key has an entry (0-live placeholder)
    // and a displayDir (fall back to the visible-set's original dir string).
    for (key in visibleKeys) {
        groupsByKey.getOrPut(key) { mutableListOf() }
        if (key !in keyToDisplayDir) {
            keyToDisplayDir[key] = keyToVisibleDir.getValue(key)
        }
    }

    // ── Step 4: output (displayDir, sessions sorted by updated desc), sorted
    // by normalized key for stable ordering — matches the pre-C3 ordering.
    return groupsByKey.entries
        .map { (key, sessList) ->
            (keyToDisplayDir[key] ?: key) to sessList.sortedByDescending { it.time?.updated ?: 0L }
        }
        .sortedBy { WorkdirPaths.normalize(it.first) }
}
