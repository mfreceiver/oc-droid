package cn.vectory.ocdroid.ui.sessions

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
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
import cn.vectory.ocdroid.ui.theme.Dimens
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
    /**
     * Nav redesign: SessionsScreen is now the flat session-history tab. The
     * "Connected projects" block moved to the Files tab (project-centric
     * workspace), so the composer / settings / repository slices that fed it
     * are no longer consumed here. The params are KEPT in the signature so
     * AppShell's call site stays unchanged; they are simply no longer read.
     */
    @Suppress("UNUSED_PARAMETER") composerVM: ComposerViewModel,
    @Suppress("UNUSED_PARAMETER") orchestratorVM: OrchestratorViewModel,
    @Suppress("UNUSED_PARAMETER") settingsVM: SettingsViewModel,
    /** §R-17 batch3e: repository for the DirectoryPickerSheet (was
     *  `viewModel.core.repository`). Injected via the activity-scoped
     *  [FilesViewModel] at the call site; passed in directly here so this
     *  composable does not reach into `.core`. */
    @Suppress("UNUSED_PARAMETER") repository: OpenCodeRepository,
    onSwitchToChat: () -> Unit = {},
    /**
     * Opens the Files destination.
     *
     * Nav redesign: browsing is the Files tab now (the per-workdir FolderOpen
     * button moved there). The param is KEPT in the signature so AppShell's
     * call site stays unchanged; it is simply no longer invoked from this
     * composable.
     */
    @Suppress("UNUSED_PARAMETER") onOpenFiles: (workdir: String, path: String?) -> Unit = { _, _ -> },
    /**
     * Phase 7：横屏左 1/3 面板内渲染时设 false，隐藏 TopAppBar 的 navigationIcon
     * （面板始终与 chat pane 并列，无需"返回 chat"——onSwitchToChat 在面板内为 no-op）。
     * 竖屏全屏渲染时默认 true。
     *
     * §0.8.2 P3.1: Sessions is now a top-level tab — the top-left `Forum`
     * navigationIcon has been removed. This param is no longer read here, but
     * is KEPT in the signature to avoid touching the AppShell call site
     * (which still passes it). `onSwitchToChat` is still used after selecting
     * / creating a session.
     */
    @Suppress("UNUSED_PARAMETER") showBackNavigation: Boolean = true
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
    // Nav redesign: draftWorkdir / recentWorkdirs moved to the Files tab (the
    // "Connected projects" block now lives there). SessionsScreen is the flat
    // session history — it only needs the session list + unread badges.
    val unreadSessions by remember { viewModel.unreadFlow.map { it.unreadSessions }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(initialValue = emptySet())
    // M7: long-press a session card → archive confirmation dialog. Null = hidden.
    var pendingArchiveSession by remember { mutableStateOf<Session?>(null) }
    // Derive the FLAT session list: ALL root sessions (parentId == null,
    // non-archived) across every connected project, by time.updated desc.
    // Nav redesign: the old `.take(5)` "recent" cap is removed — the Sessions
    // tab is now the single flat session history (no workdir grouping, which
    // moved to the Files tab). Source merges the global sessions list with
    // directorySessions (#10: per-workdir conversations), deduplicated by id.
    val recentSessions by remember {
        derivedStateOf {
            (sessionListState.sessions + sessionListState.directorySessions.values.flatten())
                .distinctBy { it.id }
                .filter { it.parentId == null && !it.isArchived }
                .sortedByDescending { it.time?.updated ?: 0L }
        }
    }

    // Nav redesign: the "Connected projects" workdir-groups section moved to
    // the Files tab (project-centric workspace). buildWorkdirGroups (the pure
    // derivation, still defined + unit-tested in this file) is now consumed by
    // FilesScreen; SessionsScreen is the flat session history only.

    // R-17 Stage 3: unreadSessions is a field-level slice Flow (above), so the
    // value is already locally-stable — no derivedStateOf wrapper needed. Lazy
    // item lambdas (recent + workdir session cards) capture this snapshot value
    // directly.

    // Navigate to Chat tab after selecting a session
    fun onSessionClick(sessionId: String) {
        viewModel.selectSession(sessionId)
        onSwitchToChat()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(stringResource(R.string.nav_sessions))
                    },
                    // §0.8.2 P3.1: top-left navigationIcon removed — Sessions
                    // is a top-level tab now (no back arrow). The
                    // showBackNavigation param is retained in the signature
                    // but no longer read here (see param doc above).
                )
            },
            // Nav redesign: new-session FAB. Mirrors SessionPickerSheet's
            // onNewSession semantic — starts a fresh draft against the current
            // workdir (viewModel.createSession) and jumps to Chat so the user
            // lands in the composer for the new (draft) session.
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = {
                        viewModel.createSession()
                        onSwitchToChat()
                    },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.sessions_new_session_fab)) },
                )
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                // §0.8.2 P3.3 / Q9: no extra TOP contentPadding — the title↔
                // first-item gap was inflated by the now-removed docked
                // SearchBar item + the previous vertical=8dp top pad. The
                // first SectionHeader already carries its own vertical=12dp
                // internal padding, so 0 top here keeps a clean gap. Bottom
                // pad retained for scroll comfort.
                contentPadding = PaddingValues(bottom = Dimens.spacing2),
                // §0.8.2 P3.3 / B6·P5: items flush (was hairline=1dp seam —
                // hairline is a divider-thickness token, not list spacing).
                // SessionCards' surfaceContainerLow background keeps visual
                // grouping without a 1dp gap.
                verticalArrangement = Arrangement.spacedBy(0.dp)
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
                    EmptyRow(
                        text = stringResource(R.string.sessions_tab_no_sessions)
                    )
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

            // Nav redesign: the "Connected projects" workdir-groups section
            // moved to the Files tab (project-centric workspace). SessionsScreen
            // is now the flat session-history tab — only the Recent section
            // above is rendered. buildWorkdirGroups (the pure derivation,
            // still defined + unit-tested in this file) is consumed by
            // FilesScreen via the `cn.vectory.ocdroid.ui.sessions` import.
            }
        }
    }

    // --- M7: Archive session confirmation dialog ---
    // Long-pressing a session card sets pendingArchiveSession; confirming calls
    // viewModel.core.archiveSession(id), which PATCHes session/{id} with the current
    // timestamp. The server-returned Session (time.archived > 0 ⇒ isArchived)
    // replaces the local copy, so the derivedStateOf filters (!it.isArchived)
    // in recentSessions drop it from the list automatically.
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
            .padding(horizontal = Dimens.spacing4, vertical = Dimens.spacing3),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(Dimens.iconSm),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(Dimens.spacing2))
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
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
internal fun SessionCard(
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
            // §0.8.2 P3.3: vertical padding 2.dp → 0.dp (horizontal kept).
            // LazyColumn arrangement is flush (spacedBy(0.dp)); cards rely on
            // their surfaceContainerLow background for visual grouping. Card
            // internal layout untouched.
            .padding(horizontal = Dimens.spacing2, vertical = 0.dp)
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

@Composable
private fun EmptyRow(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Dimens.spacing6, vertical = Dimens.spacing3)
    )
}

@Composable
internal fun EmptyWorkdirPlaceholder(onClick: () -> Unit) {
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
                modifier = Modifier.size(Dimens.iconSm),
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
            .padding(start = Dimens.spacing2)
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
