package cn.vectory.ocdroid.ui.sessions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.ui.ComposerViewModel
import cn.vectory.ocdroid.ui.ConnectionPhase
import cn.vectory.ocdroid.ui.ConnectionState
import cn.vectory.ocdroid.ui.ConnectionViewModel
import cn.vectory.ocdroid.ui.HostState
import cn.vectory.ocdroid.ui.HostViewModel
import cn.vectory.ocdroid.ui.OrchestratorViewModel
import cn.vectory.ocdroid.ui.SessionViewModel
import cn.vectory.ocdroid.ui.SettingsViewModel
import cn.vectory.ocdroid.ui.chat.copyToSystemClipboard
import cn.vectory.ocdroid.ui.chat.workdirTone
import cn.vectory.ocdroid.ui.currentHostProfile
import cn.vectory.ocdroid.ui.effectiveBusySessionIds
import cn.vectory.ocdroid.ui.home.ServerStatusIconButton
import cn.vectory.ocdroid.ui.theme.AppBottomSheet
import cn.vectory.ocdroid.ui.theme.AppConfirmDialog
import cn.vectory.ocdroid.ui.theme.AppFormDialog
import cn.vectory.ocdroid.ui.theme.AppSectionHeader
import cn.vectory.ocdroid.ui.theme.Dimens
import cn.vectory.ocdroid.util.DebugLog
import cn.vectory.ocdroid.util.WorkdirPaths
import cn.vectory.ocdroid.util.workdirBasename
import kotlinx.coroutines.flow.MutableStateFlow
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
     * home-hub T3: SessionsScreen is now the HOME hub — two collapsible
     * sections (Recently Session / Attached Project) + a top-right
     * [ServerStatusIconButton]. The composer / orchestrator / repository
     * slices feed the new-session affordance, the workdir-groups derivation
     * (composer.draftWorkdir) and the DirectoryPickerSheet respectively.
     */
    @Suppress("UNUSED_PARAMETER") composerVM: ComposerViewModel,
    @Suppress("UNUSED_PARAMETER") orchestratorVM: OrchestratorViewModel,
    settingsVM: SettingsViewModel,
    /** §R-17 batch3e: repository for the DirectoryPickerSheet (was
     *  `viewModel.core.repository`). Injected via the activity-scoped
     *  [FilesViewModel] at the call site; passed in directly here so this
     *  composable does not reach into `.core`. */
    repository: OpenCodeRepository,
    onSwitchToChat: () -> Unit = {},
    /**
     * Opens the Files destination for a workdir (Attached-Project row's
     * "browse files" IconButton).
     */
    onOpenFiles: (workdir: String, path: String?) -> Unit = { _, _ -> },
    /**
     * home-hub T3: the home page never shows a back button (it is the root).
     * The param is retained in the signature for AppShell call-site stability;
     * treated as false here regardless of the passed value.
     */
    @Suppress("UNUSED_PARAMETER") showBackNavigation: Boolean = true,
    /**
     * home-hub T3 (NEW, backward-compatible): connection VM feeds
     * [ServerStatusIconButton] (isConnected / isConnecting / connectionPhase /
     * traffic / tunnel). Nullable + defaulted so the existing AppShell call
     * site (which does not pass it yet) keeps compiling; T7 wires the real VM.
     * When null, the status icon degrades to Idle / empty host list.
     */
    connectionVM: ConnectionViewModel? = null,
    /**
     * home-hub T3 (NEW, backward-compatible): host VM feeds
     * [ServerStatusIconButton] (hostProfiles / currentHostProfileId /
     * serverVersion via the host + connection slices). Nullable + defaulted for
     * the same back-compat reason; T7 wires the real VM.
     */
    hostVM: HostViewModel? = null,
    /**
     * home-hub T3 (NEW, backward-compatible): opens the Git destination for a
     * workdir (Attached-Project row's git IconButton). Defaults to a no-op so
     * AppShell's existing call site keeps compiling; T7 wires the navigation.
     */
    onOpenGit: (workdir: String) -> Unit = {},
    /**
     * home-hub T3 (NEW, backward-compatible): opens the Settings tab from the
     * server-management dialog's Settings IconButton. Defaults to a no-op so
     * AppShell's existing call site keeps compiling; T7 wires the navigation.
     */
    onNavigateToSettings: () -> Unit = {},
) {
    // ── Slice subscriptions ────────────────────────────────────────────────
    // sessionListFlow (sessions + directorySessions) + unreadFlow feed the
    // Recently-Session list + the Attached-Project workdir groups. Field-level
    // subscriptions (.map { it.field }.distinctUntilChanged()) for draftWorkdir
    // / unreadSessions avoid recomposing on unrelated slice mutations.
    val sessionListState by viewModel.sessionListFlow.collectAsStateWithLifecycle()
    val composerState by composerVM.composerFlow.collectAsStateWithLifecycle()
    val unreadSessions by remember { viewModel.unreadFlow.map { it.unreadSessions }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(initialValue = emptySet())
    val recentWorkdirs by settingsVM.recentWorkdirs.collectAsStateWithLifecycle()

    // home-hub T3: connection / traffic / host slices for the top-right
    // ServerStatusIconButton. When the VMs are not yet wired (pre-T7 AppShell
    // call site), remembered empty-state fallback flows keep the reads
    // non-null so the icon degrades to Idle / empty host list instead of NPE.
    val connectionFallback = remember { MutableStateFlow(ConnectionState()) }
    val hostFallback = remember { MutableStateFlow(HostState()) }
    val connection by (connectionVM?.connectionFlow ?: connectionFallback)
        .collectAsStateWithLifecycle()
    val host by (hostVM?.hostFlow ?: hostFallback).collectAsStateWithLifecycle()
    val curHostProfile = currentHostProfile(host.hostProfiles, host.currentHostProfileId)

    // ── Title: "<app_name> v<versionName>" ─────────────────────────────────
    // Mirrors ChatTopBar.kt:275-281 — one binder call per composition via
    // remember(context); versionName may carry a git-hash suffix, shown
    // verbatim. Degrades to app_name only when PackageManager throws.
    val context = LocalContext.current
    val versionName by remember(context) {
        mutableStateOf(
            runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull()
        )
    }
    val appName = stringResource(R.string.app_name)
    // §review-fix IMPORTANT-1: spec requires "<app_name> v <versionName>"
    // (space AFTER the "v"); was missing the space.
    val homeTitle = if (versionName != null) "$appName v $versionName" else appName

    // ── Local UI state ─────────────────────────────────────────────────────
    // Two collapsible sections; rememberSaveable so config-change / process-
    // death restores the expanded state.
    var recentExpanded by rememberSaveable { mutableStateOf(true) }
    var projectsExpanded by rememberSaveable { mutableStateOf(true) }
    // Per-workdir expand set (Attached-Project rows reveal their sessions).
    var expandedWorkdirs by remember { mutableStateOf(emptySet<String>()) }
    // M7: long-press a session card → archive confirmation dialog.
    var pendingArchiveSession by remember { mutableStateOf<Session?>(null) }
    // T4: long-press a session card → Tier-A anchored DropdownMenu (rename /
    // archive / copy-id).
    var menuSession by remember { mutableStateOf<Session?>(null) }
    // T4: rename form (Tier-C AppFormDialog).
    var renameSession by remember { mutableStateOf<Session?>(null) }
    // §sessux #3: workdir picker dialog state (≥2 connected workdirs).
    var pendingWorkdirPick by remember { mutableStateOf(false) }
    // Attached-Project: DirectoryPickerSheet (attach new project).
    var showAddProjectSheet by remember { mutableStateOf(false) }
    // Attached-Project: long-press workdir → disconnect AppConfirmDialog.
    var pendingDisconnectWorkdir by remember { mutableStateOf<String?>(null) }

    // ── Derived: flat recent-session list ──────────────────────────────────
    // ALL root sessions (parentId == null, non-archived) across every connected
    // project, by time.updated desc. Merges the global sessions list with
    // directorySessions (#10: per-workdir conversations), deduplicated by id.
    //
    // §review-fix IMPORTANT-2: keyed the `remember` on the two slice fields the
    // derivation reads (sessions + directorySessions) — matching the sibling
    // `workdirGroups` derivation below — so a new session-flow emission always
    // recomputes the recent list / empty state. The prior unkeyed `remember`
    // risked capturing a stale snapshot. The derivedStateOf lambda still reads
    // the live State-backed fields (root parentId==null, non-archived, sorted
    // by time.updated desc).
    val recentSessions by remember(
        sessionListState.sessions,
        sessionListState.directorySessions,
    ) {
        derivedStateOf {
            (sessionListState.sessions + sessionListState.directorySessions.values.flatten())
                .distinctBy { it.id }
                .filter { it.parentId == null && !it.isArchived }
                .sortedByDescending { it.time?.updated ?: 0L }
        }
    }
    // §sessux #4: pending (question / permission) aggregation to ROOT cards.
    // The pending session may be a sub-agent (parentId != null); its marker
    // should surface on the root card. Includes childSessions so sub-agents
    // are walked (review-fix gpter MAJOR#1).
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
    val effectiveBusy = remember(
        sessionListState.activeSessionIds,
        sessionListState.sessionStatuses,
    ) {
        effectiveBusySessionIds(
            sessionListState.activeSessionIds,
            sessionListState.sessionStatuses,
        )
    }

    // ── Derived: workdir groups (Attached-Project section) ─────────────────
    val workdirGroups by remember(
        sessionListState.sessions,
        sessionListState.directorySessions,
        recentWorkdirs,
        composerState.draftWorkdir,
    ) {
        derivedStateOf {
            buildWorkdirGroups(
                allSessions = (sessionListState.sessions +
                    sessionListState.directorySessions.values.flatten())
                    .distinctBy { it.id },
                recentWorkdirs = recentWorkdirs,
                draftWorkdir = composerState.draftWorkdir,
            )
        }
    }

    // Navigate to Chat tab after selecting / creating a session.
    fun onSessionClick(sessionId: String) {
        viewModel.selectSession(sessionId)
        onSwitchToChat()
    }

    // §sessux #3 / #new3: shared new-session flow. 0 connected workdirs → the
    // entry is disabled (so this path is unreachable from there); 1 workdir →
    // direct create; ≥2 → picker dialog.
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
                        Text(
                            text = homeTitle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    // home-hub: the home page never shows a back button — it
                    // is the root. showBackNavigation is ignored (treated
                    // false); no navigationIcon slot is populated.
                    actions = {
                        // §fix-home-refresh (9.5 gate, decision 2a): soft-refresh
                        // button — re-runs the connection coordinator's full
                        // fan-out (global getSessions + per-workdir
                        // directorySessions restore WITH the host-identity guard)
                        // without forcing a reconnect (contrast the
                        // ServerStatusIconButton "force refresh" popup, which
                        // does coldStartReconnect). Disabled while a sessions
                        // refresh is already in flight to prevent double-fire.
                        // Reuses the generic common_refresh label
                        // ("Refresh"/"刷新"); chat_force_refresh stays distinct
                        // (that's the force-reconnect affordance).
                        IconButton(
                            onClick = { connectionVM?.loadInitialData() },
                            enabled = !sessionListState.isRefreshingSessions,
                        ) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.common_refresh),
                            )
                        }
                        // Top-right server-status affordance (T2). Wired to
                        // the connection / host slices; degrades to
                        // Idle / empty host list when the VMs are not wired.
                        ServerStatusIconButton(
                            isConnected = connection.isConnected,
                            isConnecting = connection.isConnecting,
                            isIdle = connection.connectionPhase is ConnectionPhase.Idle,
                            hostProfiles = host.hostProfiles,
                            currentHostProfileId = host.currentHostProfileId,
                            tunnelActivationState = connection.tunnelActivationState,
                            showTunnelAuth = (curHostProfile?.tunnelPasswordId != null),
                            serverVersion = connection.serverVersion,
                            onSelectHost = { id -> hostVM?.selectHostProfile(id) },
                            // §final-review F1: home popup "force refresh" does
                            // a REAL reconnect (coldStartReconnect →
                            // testConnection(force=true, retries=3) +
                            // re-establishes the SSE/stream + re-pulls host /
                            // session data via the connection coordinator's
                            // fan-out). refreshTrafficStats additionally
                            // snapshots the cumulative byte counters for the
                            // dialog's display. The previous wiring
                            // (refreshTrafficStats alone) only copied the
                            // existing traffic snapshot — no reconnect, no
                            // data refresh, contradicting requirement 2's
                            // "强制刷新". coldStartReconnect is the same path
                            // the app uses on cold start and the Chat
                            // force-refresh flow.
                            onRefresh = {
                                connectionVM?.coldStartReconnect()
                                connectionVM?.refreshTrafficStats()
                            },
                            onActivateTunnel = { hostVM?.activateTunnelForCurrentHost() },
                            onNavigateToSettings = onNavigateToSettings,
                        )
                    }
                )
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(bottom = Dimens.spacing2),
                // §ui-style-spec §2: 0.dp irreducible — flush arrangement so
                // cards group by their surfaceContainerLow background (no
                // inter-card gap); each card's internal padding provides the
                // visual rhythm.
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                // ── Section 1: Recently Session ───────────────────────────
                // Header (whole row clickable to toggle) + trailing new-session
                // IconButton. Content (the recent-session list / empty state)
                // is emitted only while recentExpanded.
                item(key = "recent_header") {
                    AppSectionHeader(
                        text = stringResource(R.string.home_section_recent),
                        modifier = Modifier.clickable { recentExpanded = !recentExpanded },
                        trailing = {
                            IconButton(
                                onClick = onStartNewSession,
                                enabled = recentWorkdirs.isNotEmpty(),
                            ) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = stringResource(R.string.sessions_new_session_fab),
                                )
                            }
                        },
                    )
                }
                if (recentExpanded) {
                    if (recentSessions.isEmpty()) {
                        // home-hub: compact empty hint (NOT fillParentMaxSize —
                        // the two-section home page must keep the Attached
                        // Project section visible below). Tapping triggers the
                        // shared new-session flow.
                        item(key = "recent_empty") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(onClick = onStartNewSession)
                                    .padding(
                                        horizontal = Dimens.spacing6,
                                        vertical = Dimens.spacing3,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = stringResource(R.string.sessions_empty_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else {
                        items(recentSessions, key = { it.id }) { session ->
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
                            Box {
                                var pressOffset by remember { mutableStateOf(DpOffset.Zero) }
                                val density = LocalDensity.current
                                SessionCard(
                                    session = session,
                                    isUnread = session.id in unreadSessions && session.id !in effectiveBusy,
                                    status = sessionListState.sessionStatuses[session.id],
                                    hasPendingQuestion = hasPendingQuestion,
                                    hasPendingPermission = hasPendingPermission,
                                    onClick = { onSessionClick(session.id) },
                                    onLongClick = { offset ->
                                        pressOffset = with(density) {
                                            DpOffset(offset.x.toDp(), offset.y.toDp())
                                        }
                                        menuSession = session
                                    }
                                )
                                DropdownMenu(
                                    expanded = menuSession?.id == session.id,
                                    onDismissRequest = {
                                        if (menuSession?.id == session.id) menuSession = null
                                    },
                                    offset = pressOffset,
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
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.sessions_copy_id)) },
                                        onClick = {
                                            copyToSystemClipboard(context, session.id)
                                            menuSession = null
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                // ── Section 2: Attached Project ───────────────────────────
                // Header (whole row clickable to toggle) + trailing attach
                // IconButton (CreateNewFolder → DirectoryPickerSheet →
                // settingsVM.connectWorkdir; attach stays on home). Content =
                // buildWorkdirGroups; each row carries three trailing
                // IconButtons (files / git / new-session) + an expand chevron
                // revealing that workdir's sessions.
                item(key = "projects_header") {
                    AppSectionHeader(
                        text = stringResource(R.string.home_section_projects),
                        modifier = Modifier.clickable { projectsExpanded = !projectsExpanded },
                        trailing = {
                            IconButton(onClick = { showAddProjectSheet = true }) {
                                Icon(
                                    Icons.Default.CreateNewFolder,
                                    contentDescription = stringResource(R.string.files_add_project),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                    )
                }
                if (projectsExpanded) {
                    if (workdirGroups.isEmpty()) {
                        item(key = "workdirs_empty") {
                            ListItem(
                                headlineContent = {
                                    Text(
                                        text = stringResource(R.string.sessions_tab_no_workdirs),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                            )
                        }
                    } else {
                        items(
                            workdirGroups,
                            key = { "workdir_${WorkdirPaths.normalize(it.first)}" }
                        ) { (workdir, sessionsInWorkdir) ->
                            HomeWorkdirRow(
                                workdir = workdir,
                                sessionsInWorkdir = sessionsInWorkdir,
                                isExpanded = workdir in expandedWorkdirs,
                                onToggleExpand = {
                                    expandedWorkdirs = if (workdir in expandedWorkdirs) {
                                        expandedWorkdirs - workdir
                                    } else {
                                        // Fix #6c: re-fetch this project's directory
                                        // sessions on expand so a conversation created
                                        // elsewhere (web) appears without a full reconnect.
                                        viewModel.refreshDirectorySessions(workdir)
                                        expandedWorkdirs + workdir
                                    }
                                },
                                onLongClick = { pendingDisconnectWorkdir = workdir },
                                onBrowseFiles = { onOpenFiles(workdir, null) },
                                onOpenGit = { onOpenGit(workdir) },
                                onCreateSession = {
                                    viewModel.createSessionInWorkdir(workdir)
                                    onSwitchToChat()
                                },
                                onSelectSession = { sessionId ->
                                    viewModel.selectSession(sessionId)
                                    onSwitchToChat()
                                },
                                sessionStatuses = sessionListState.sessionStatuses,
                                unreadSessions = unreadSessions,
                                effectiveBusy = effectiveBusy,
                            )
                        }
                    }
                }
            }
        }
    }

    // ── Workdir picker (≥2 connected workdirs): Tier-B AppBottomSheet ──────
    // Selecting one starts a draft there + jumps to Chat (same as the single-
    // workdir direct path).
    if (pendingWorkdirPick) {
        AppBottomSheet(
            onDismissRequest = { pendingWorkdirPick = false },
            title = stringResource(R.string.sessions_pick_workdir_title),
        ) {
            recentWorkdirs.forEach { workdir ->
                val basename = workdir.workdirBasename() ?: workdir
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

    // ── Directory picker (attach new project): DirectoryPickerSheet ────────
    // Registers the project WITHOUT creating a session (project = first-class
    // entity). The row appears next frame via the recentWorkdirs tick; attach
    // stays on the home page (no onSwitchToChat).
    if (showAddProjectSheet) {
        DirectoryPickerSheet(
            repository = repository,
            onDismiss = { showAddProjectSheet = false },
            onSelect = { path ->
                showAddProjectSheet = false
                settingsVM.connectWorkdir(path)
                // §fix-connect-prefetch (9.5 gate, decision 1b): connectWorkdir
                // only registers the workdir (no fetch) — pre-fix the new
                // project's sessions didn't appear until the user manually
                // expanded the row. Prefetch here via the GUARDED
                // connectionCoordinator path so a mid-flight host/profile
                // switch drops stale-host results. Pass the EXACT same `path`
                // (connectWorkdir stores path.trim(); the coordinator trims
                // internally; the directorySessions key then matches the
                // recentWorkdirs stored form that buildWorkdirGroups looks up).
                viewModel.refreshDirectorySessions(path)
            }
        )
    }

    // ── Disconnect workdir confirmation (Tier-C AppConfirmDialog) ──────────
    // Long-pressing a workdir header sets pendingDisconnectWorkdir; confirming
    // calls settingsVM.disconnectWorkdir + clears unread for that workdir's
    // sessions + collapses the row.
    pendingDisconnectWorkdir?.let { workdir ->
        val name = workdir.workdirBasename() ?: workdir
        AppConfirmDialog(
            title = stringResource(R.string.workdir_disconnect_title),
            body = stringResource(R.string.workdir_disconnect_message) +
                "\n\n" + name + "\n" + workdir,
            confirmText = stringResource(R.string.workdir_disconnect_confirm),
            onConfirm = {
                settingsVM.disconnectWorkdir(workdir)
                viewModel.clearUnreadForWorkdir(workdir)
                expandedWorkdirs = expandedWorkdirs - workdir
                pendingDisconnectWorkdir = null
            },
            dismissText = stringResource(R.string.common_cancel),
            onDismiss = { pendingDisconnectWorkdir = null },
        )
    }

    // ── Archive session confirmation (Tier-C AppConfirmDialog) ─────────────
    pendingArchiveSession?.let { session ->
        AppConfirmDialog(
            title = stringResource(R.string.sessions_archive),
            bodyContent = {
                Text(stringResource(R.string.sessions_archive_confirm))
                Spacer(modifier = Modifier.height(Dimens.spacing2))
                Text(
                    text = session.displayName,
                    fontWeight = FontWeight.Bold,
                )
            },
            confirmText = stringResource(R.string.sessions_archive),
            onConfirm = {
                viewModel.archiveSession(session.id)
                pendingArchiveSession = null
            },
            dismissText = stringResource(R.string.common_cancel),
            onDismiss = { pendingArchiveSession = null },
        )
    }

    // ── Rename session dialog (Tier-C AppFormDialog) ───────────────────────
    renameSession?.let { session ->
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

/**
 * home-hub T3: one workdir row in the Attached-Project section (header +
 * expandable session list). Ported from FilesScreen.kt's `WorkdirRow`
 * (FilesScreen.kt:570-667) with an added git IconButton (AccountTree) so each
 * row carries THREE trailing IconButtons: files (FolderOpen) / git
 * (AccountTree) / new-session (AddComment).
 *
 * The header is `combinedClickable` (tap → toggle expand, long-press →
 * disconnect confirm). An AnimatedVisibility reveals the workdir's sessions
 * (or the EmptyWorkdirPlaceholder when there are zero live sessions).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeWorkdirRow(
    workdir: String,
    sessionsInWorkdir: List<Session>,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onLongClick: () -> Unit,
    onBrowseFiles: () -> Unit,
    onOpenGit: () -> Unit,
    onCreateSession: () -> Unit,
    onSelectSession: (String) -> Unit,
    sessionStatuses: Map<String, SessionStatus>,
    unreadSessions: Set<String>,
    effectiveBusy: Set<String>,
) {
    val displayName = workdir.workdirBasename() ?: workdir
    Column(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            modifier = Modifier.combinedClickable(
                onClick = onToggleExpand,
                onLongClick = onLongClick,
            ),
            leadingContent = {
                Icon(
                    if (isExpanded) Icons.Default.KeyboardArrowDown
                    else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            headlineContent = {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Medium,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            supportingContent = {
                Text(
                    text = workdir,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // files (FolderOpen) → onOpenFiles(workdir, null).
                    IconButton(onClick = onBrowseFiles) {
                        Icon(
                            Icons.Default.FolderOpen,
                            contentDescription = stringResource(R.string.files_project_browse),
                            modifier = Modifier.size(Dimens.iconSm),
                        )
                    }
                    // git (AccountTree) → onOpenGit(workdir).
                    IconButton(onClick = onOpenGit) {
                        Icon(
                            Icons.Default.AccountTree,
                            contentDescription = stringResource(R.string.project_action_git),
                            modifier = Modifier.size(Dimens.iconSm),
                        )
                    }
                    // new-session (AddComment) → createSessionInWorkdir + Chat.
                    IconButton(onClick = onCreateSession) {
                        Icon(
                            Icons.Default.AddComment,
                            contentDescription = stringResource(R.string.project_action_new_session),
                            modifier = Modifier.size(Dimens.iconSm),
                        )
                    }
                }
            }
        )

        AnimatedVisibility(visible = isExpanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.spacing2)
            ) {
                if (sessionsInWorkdir.isEmpty()) {
                    EmptyWorkdirPlaceholder(onClick = onCreateSession)
                }
                sessionsInWorkdir.forEach { session ->
                    SessionCard(
                        session = session,
                        isUnread = session.id in unreadSessions && session.id !in effectiveBusy,
                        status = sessionStatuses[session.id],
                        onClick = { onSelectSession(session.id) },
                        onLongClick = {},
                        onArchive = null,
                        showWorkdir = false,
                    )
                }
            }
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

@Composable
internal fun EmptyWorkdirPlaceholder(onClick: () -> Unit) {
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            // §ui-style-spec §2: 2.dp irreducible (no 2dp token; tight inset
            // so the placeholder reads as a dense hint row, not a full card).
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
