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
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import cn.vectory.ocdroid.ui.theme.AppBottomSheet
import cn.vectory.ocdroid.ui.theme.AppFormDialog
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
     * workspace), so the composer / repository slices that fed it are no
     * longer consumed here. The params are KEPT in the signature so AppShell's
     * call site stays unchanged; they are simply no longer read.
     *
     * settingsVM is now CONSUMED again — the new-session affordance (TopAppBar
     * action + empty state) branches on the connected-workdir count from
     * [SettingsViewModel.recentWorkdirs] (0 → disabled, 1 → direct create,
     * ≥2 → workdir picker dialog).
     */
    @Suppress("UNUSED_PARAMETER") composerVM: ComposerViewModel,
    @Suppress("UNUSED_PARAMETER") orchestratorVM: OrchestratorViewModel,
    settingsVM: SettingsViewModel,
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
    // lastViewedTime (mutated on session switches).
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
    // §sessux #3: recentWorkdirs drives the new-session affordance (TopAppBar
    // action + empty state): 0 → disabled, 1 → direct create, ≥2 → picker.
    val recentWorkdirs by settingsVM.recentWorkdirs.collectAsStateWithLifecycle()
    // M7: long-press a session card → archive confirmation dialog. Null = hidden.
    var pendingArchiveSession by remember { mutableStateOf<Session?>(null) }
    // T4 (chat-ux-batch): long-press now opens a Tier-A anchored DropdownMenu
    // (rename / archive) instead of jumping straight to the archive confirm.
    // `menuSession` is the row whose menu is expanded; selecting an item clears
    // it (and routes to `renameSession` or `pendingArchiveSession`).
    var menuSession by remember { mutableStateOf<Session?>(null) }
    // T4: rename form (Tier-C AppFormDialog). Null = hidden; set by the
    // DropdownMenu's rename item.
    var renameSession by remember { mutableStateOf<Session?>(null) }
    // §sessux #3: workdir picker dialog state. Set when the user taps the
    // new-session action with ≥2 connected workdirs.
    var pendingWorkdirPick by remember { mutableStateOf(false) }
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
    // §sessux #4: pending (question / permission) aggregation to ROOT cards.
    // The pending session may be a sub-agent (parentId != null); its marker
    // should surface on the root card so the user sees "this conversation
    // tree needs you". rootHasPending walks the parentId chain from each
    // pending session up to its root and matches the card's session id.
    //
    // §review-fix (gpter MAJOR#1): the ancestor graph MUST include
    // `childSessions` — sub-agents live there (NOT in `sessions` /
    // `directorySessions`), so omitting it silently dropped the most important
    // case (a sub-agent's pending question/permission → its root card).
    // Building the id→Session map once per recomposition (not per-card, not
    // per-pending-set) also removes the O(n) associateBy from each call.
    val sessionsById = remember(
        sessionListState.sessions,
        sessionListState.directorySessions,
        sessionListState.childSessions,
    ) {
        (
            sessionListState.sessions +
                sessionListState.directorySessions.values.flatten() +
                sessionListState.childSessions.values.flatten()
            ).distinctBy { it.id }.associateBy { it.id }
    }
    val pendingQuestionSessionIds = remember(sessionListState.pendingQuestions) {
        sessionListState.pendingQuestions.map { it.sessionId }.toSet()
    }
    val pendingPermissionSessionIds = remember(sessionListState.pendingPermissions) {
        sessionListState.pendingPermissions.map { it.sessionId }.toSet()
    }

    // Nav redesign: the "Connected projects" workdir-groups section moved to
    // the Files tab (project-centric workspace). buildWorkdirGroups (the pure
    // derivation, still defined + unit-tested in this file) is now consumed by
    // FilesScreen; SessionsScreen is the flat session history only.

    // R-17 Stage 3: unreadSessions is a field-level slice Flow (above), so the
    // value is already locally-stable — no derivedStateOf wrapper needed. Lazy
    // item lambdas (recent + workdir session cards) capture this snapshot value
    // directly.

    // Navigate to Chat tab after selecting a session.
    //
    // §WT2-taskB (Q6 locked): Sessions-page tap sets the "jump to latest"
    // intent BEFORE selectSession. The matching SessionSwitcher.switchTo
    // keeps the intent (currentSessionId becomes the tapped id and matches
    // pendingJumpToLatest); ChatMessageList then consumes it once messages
    // have loaded (scrollToItem(0) + followBottom=true + clear). Swipe,
    // tab-strip, and SessionPickerSheet paths do NOT set the intent, so
    // each session's saveable scroll position is preserved there.
    fun onSessionClick(sessionId: String) {
        viewModel.requestJumpToLatest(sessionId)
        viewModel.selectSession(sessionId)
        onSwitchToChat()
    }

    // §sessux #3 / #new3: shared new-session flow. 0 connected workdirs → the
    // entry is disabled in the TopAppBar (so this path is unreachable from
    // there), but the empty-state button is always clickable; with 0 workdirs
    // the flow is an intentional no-op (the title-hint + disabled action
    // signal the precondition). 1 workdir → direct create. ≥2 → picker dialog.
    val onStartNewSession: () -> Unit = {
        when {
            recentWorkdirs.isEmpty() -> Unit
            recentWorkdirs.size == 1 -> {
                viewModel.createSessionInWorkdir(recentWorkdirs.single())
                onSwitchToChat()
            }
            else -> pendingWorkdirPick = true
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        // §sessux #3: when 0 workdirs are connected, the
                        // new-session action is disabled and a small hint is
                        // shown beneath the title so the user understands why
                        // (mirrors the supporting-text pattern; kept compact).
                        Column {
                            Text(stringResource(R.string.nav_sessions))
                            if (recentWorkdirs.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.sessions_new_session_no_projects),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    // §0.8.2 P3.1: top-left navigationIcon removed — Sessions
                    // is a top-level tab now (no back arrow). The
                    // showBackNavigation param is retained in the signature
                    // but no longer read here (see param doc above).
                    actions = {
                        // §sessux #3: new-session IconButton. Disabled (greyed
                        // out) when no workdir is connected; the title-hint
                        // above explains the precondition. With exactly one
                        // workdir it starts a draft there directly; with ≥2
                        // it opens the workdir picker dialog.
                        IconButton(
                            onClick = onStartNewSession,
                            enabled = recentWorkdirs.isNotEmpty(),
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = stringResource(R.string.sessions_new_session_fab),
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
                // §0.8.2 P3.3 / Q9: no extra TOP contentPadding — the title↔
                // first-item gap was inflated by the now-removed docked
                // SearchBar item + the previous vertical=8dp top pad. The
                // first card already has its own padding, so 0 top here keeps
                // a clean gap. Bottom pad retained for scroll comfort.
                contentPadding = PaddingValues(bottom = Dimens.spacing2),
                // §0.8.2 P3.3 / B6·P5: items flush (was hairline=1dp seam —
                // hairline is a divider-thickness token, not list spacing).
                // SessionCards' surfaceContainerLow background keeps visual
                // grouping without a 1dp gap.
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                // §sessux #1: no section header — the whole screen is the
                // session history now (the "Recent Sessions" header was
                // redundant after the workdir-groups block moved to Files).

                if (recentSessions.isEmpty()) {
                    // §sessux #new3: clickable empty state. Triggers the same
                    // shared new-session flow as the TopAppBar action above
                    // (per-spec: ONE lambda, two entry points).
                    item(key = "recent_empty") {
                        SessionsEmptyState(
                            modifier = Modifier.fillParentMaxSize(),
                            onClick = onStartNewSession,
                        )
                    }
                } else {
                    items(recentSessions, key = { it.id }) { session ->
                        // §sessux #4: aggregate pending (question/permission)
                        // from any descendant up to this root card so the
                        // marker always surfaces on the conversation tree's
                        // root entry (matches the AppShell tab badge count).
                        val hasPendingQuestion = rootHasPending(
                            rootId = session.id,
                            sessionsById = sessionsById,
                            pendingSessionIds = pendingQuestionSessionIds,
                        )
                        val hasPendingPermission = rootHasPending(
                            rootId = session.id,
                            sessionsById = sessionsById,
                            pendingSessionIds = pendingPermissionSessionIds,
                        )
                        // T4 (chat-ux-batch): Tier-A anchored DropdownMenu.
                        // The menu is anchored to this Box (the row container)
                        // so it pops at the long-pressed card's position.
                        // `menuSession?.id == session.id` keeps the expanded
                        // state per-row without per-row remember hoisting (the
                        // single screen-level `menuSession` holder drives all
                        // rows; only the matching row's menu expands).
                        Box {
                            SessionCard(
                                session = session,
                                isUnread = session.id in unreadSessions,
                                status = sessionListState.sessionStatuses[session.id],
                                hasPendingQuestion = hasPendingQuestion,
                                hasPendingPermission = hasPendingPermission,
                                onClick = { onSessionClick(session.id) },
                                onLongClick = { menuSession = session }
                            )
                            DropdownMenu(
                                expanded = menuSession?.id == session.id,
                                onDismissRequest = {
                                    if (menuSession?.id == session.id) menuSession = null
                                },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.sessions_rename)) },
                                    onClick = {
                                        renameSession = session
                                        menuSession = null
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.sessions_archive)) },
                                    onClick = {
                                        pendingArchiveSession = session
                                        menuSession = null
                                    },
                                )
                            }
                        }
                    }
                }

                // Nav redesign: the "Connected projects" workdir-groups section
                // moved to the Files tab (project-centric workspace). SessionsScreen
                // is now the flat session-history tab — only the section above
                // is rendered. buildWorkdirGroups (the pure derivation,
                // still defined + unit-tested in this file) is consumed by
                // FilesScreen via the `cn.vectory.ocdroid.ui.sessions` import.
            }
        }
    }

    // §sessux #3 / §13: workdir picker (≥2 connected workdirs). Migrated from
    // AlertDialog to AppBottomSheet (Tier B per ui-style-spec: list/preview
    // surface). Each row is the workdir's basename rendered as a tappable M3
    // ListItem; selecting one starts a draft there + jumps to Chat (same as
    // the single-workdir direct path). The sheet's onDismissRequest (swipe-
    // down / scrim tap) replaces the old explicit Cancel TextButton —
    // AppBottomSheet carries no confirm/dismiss slot by design.
    if (pendingWorkdirPick) {
        AppBottomSheet(
            onDismissRequest = { pendingWorkdirPick = false },
            title = stringResource(R.string.sessions_pick_workdir_title),
        ) {
            recentWorkdirs.forEach { workdir ->
                val basename = workdir.split("/")
                    .filter { it.isNotEmpty() }
                    .lastOrNull() ?: workdir
                ListItem(
                    headlineContent = { Text(basename) },
                    modifier = Modifier.clickable {
                        pendingWorkdirPick = false
                        viewModel.createSessionInWorkdir(workdir)
                        onSwitchToChat()
                    },
                )
            }
        }
    }

    // --- M7: Archive session confirmation dialog ---
    // Long-pressing a session card sets pendingArchiveSession; confirming calls
    // viewModel.core.archiveSession(id), which PATCHes session/{id} with the current
    // timestamp. The server-returned Session (time.archived > 0 ⇒ isArchived)
    // replaces the local copy, so the derivedStateOf filters (!it.isArchived)
    // in recentSessions drop it from the list automatically.
    // §sessux #2: session display name rendered Bold so the user can clearly
    // see WHICH conversation they are about to archive (the prior plain-text
    // run-on made the boundary between prompt and name hard to scan).
    pendingArchiveSession?.let { session ->
        AlertDialog(
            onDismissRequest = { pendingArchiveSession = null },
            title = { Text(stringResource(R.string.sessions_archive)) },
            text = {
                Column {
                    Text(stringResource(R.string.sessions_archive_confirm))
                    Spacer(modifier = Modifier.height(Dimens.spacing2))
                    Text(
                        text = session.displayName,
                        fontWeight = FontWeight.Bold,
                    )
                }
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

    // --- T4 (chat-ux-batch): Rename session dialog (Tier-C AppFormDialog) ---
    // Triggered from the DropdownMenu's "Rename" item. AppFormDialog signature
    // mirrors the canonical usage at ModelManagementSection.kt:139 /
    // HostProfilesManagerScreen.kt:737 (BasicAlertDialog + Surface container;
    // title: String?, confirmButton / dismissButton as @Composable slots, content
    // as ColumnScope trailing lambda — NOT the AlertDialog-like onConfirm/
    // confirmText sketch in the brief). TextField is prefilled with the
    // session's current title (null → empty); placeholder = displayName so an
    // empty field shows the fallback the server will use. Confirm is ALWAYS
    // enabled (allowing empty submit → server clears the title). Helper text
    // conveys the empty-submit semantics.
    renameSession?.let { session ->
        // Keyed by session.id so a fresh open (same or different session) starts
        // from the server-side title rather than a stale typed value.
        var renameText by remember(session.id) { mutableStateOf(session.title ?: "") }
        AppFormDialog(
            onDismissRequest = { renameSession = null },
            title = stringResource(R.string.sessions_rename_title),
            confirmButton = {
                TextButton(onClick = {
                    viewModel.renameSession(session.id, renameText)
                    renameSession = null
                }) {
                    Text(stringResource(R.string.sessions_rename))
                }
            },
            dismissButton = {
                TextButton(onClick = { renameSession = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        ) {
            OutlinedTextField(
                value = renameText,
                onValueChange = { renameText = it },
                placeholder = { Text(session.displayName) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(Dimens.spacing2))
            Text(
                text = stringResource(R.string.sessions_rename_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SessionsEmptyState(modifier: Modifier = Modifier, onClick: () -> Unit) {
    // §sessux #new3: clickable empty state. Mirrors the ChatEmptyState style
    // (centered Column, large outline icon, bodyLarge hint). Inbox icon = the
    // pre-redesign "Connected projects" header icon (familiar to existing
    // users). Tapping triggers the same shared new-session flow as the
    // TopAppBar action (per-spec: ONE lambda, two entry points).
    // §review-fix (gpter MINOR#1 / groker M3): fillParentMaxSize spans the
    // viewport (a LazyColumn item has no bounded max height → fillMaxSize
    // squashes it). fillParentMaxSize is a LazyItemScope extension, so the
    // CALLER (inside `item {}`) passes Modifier.fillParentMaxSize(); this
    // composable just applies the supplied modifier + the click target.
    Box(
        modifier = modifier.clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.Inbox,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.outline,
            )
            Spacer(modifier = Modifier.height(Dimens.spacing4))
            Text(
                text = stringResource(R.string.sessions_empty_hint),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * §sessux #4: pure helper that decides whether a ROOT session card should
 * show a pending marker (question / permission). A pending event may live on
 * a sub-agent (parentId != null) deep in the conversation tree; the user
 * wants the marker on the root card they can see in the flat Sessions list,
 * not hidden on a child they would have to drill into. This helper walks the
 * `parentId` chain from every pending session up to its root and reports
 * whether [rootId] is on any of those root paths.
 *
 * Concretely: returns true iff [rootId] itself is pending OR some session in
 * [allSessions] whose ancestor chain (following `parentId`) terminates at
 * [rootId] is in [pendingSessionIds]. Cycle-safe via a visited set (defensive
 * against malformed parentId loops).
 *
 * Extracted as a pure top-level function so the ancestor-walk contract is
 * unit-testable (cf. [SessionsScreenTest]).
 *
 * @param rootId            the candidate root session id (a card in the list).
 * @param allSessions       every session the UI currently knows about
 *                          (sessions + directorySessions, deduplicated by id
 *                          by the caller — this function does NOT dedupe).
 * @param pendingSessionIds session ids that carry a pending question OR a
 *                          pending permission (caller pre-defines which kind).
 */
internal fun rootHasPending(
    rootId: String,
    sessionsById: Map<String, Session>,
    pendingSessionIds: Set<String>,
): Boolean {
    if (rootId in pendingSessionIds) return true
    if (pendingSessionIds.isEmpty()) return false
    // Walk each pending session's parentId chain up to its root; cycle-safe
    // via `seen`. The caller pre-builds `sessionsById` (including sub-agents
    // from childSessions) once per recomposition.
    val seen = HashSet<String>()
    for (pendingId in pendingSessionIds) {
        seen.clear()
        var current = sessionsById[pendingId] ?: continue
        while (current.id !in seen) {
            if (current.id == rootId) return true
            seen.add(current.id)
            val parentId = current.parentId ?: break
            current = sessionsById[parentId] ?: break
        }
    }
    return false
}

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
