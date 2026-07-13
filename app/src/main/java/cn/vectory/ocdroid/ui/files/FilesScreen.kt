package cn.vectory.ocdroid.ui.files

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.ui.ComposerViewModel
import cn.vectory.ocdroid.ui.NavRoute
import cn.vectory.ocdroid.ui.OrchestratorViewModel
import cn.vectory.ocdroid.ui.SessionViewModel
import cn.vectory.ocdroid.ui.SettingsViewModel
import cn.vectory.ocdroid.ui.sessions.DirectoryPickerSheet
import cn.vectory.ocdroid.ui.sessions.EmptyWorkdirPlaceholder
import cn.vectory.ocdroid.ui.sessions.SessionCard
import cn.vectory.ocdroid.ui.sessions.buildWorkdirGroups
import cn.vectory.ocdroid.ui.showTimed
import cn.vectory.ocdroid.ui.theme.Dimens
import cn.vectory.ocdroid.util.WorkdirPaths
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FilesScreen(
    viewModel: FilesViewModel = hiltViewModel(),
    orchestratorVM: OrchestratorViewModel,
    /**
     * Nav redesign: Files now hosts the project-list (workdir-groups) view
     * alongside the file browser. The session/settings/composer VMs feed the
     * project-list derivation (sessions + directorySessions + recentWorkdirs
     * + draftWorkdir) and the per-row actions (createSessionInWorkdir /
     * disconnectWorkdir / connectWorkdir / refreshDirectorySessions /
     * selectSession). onSwitchToChat fires after a per-row session create /
     * select so the user lands in the composer for the chosen session.
     */
    sessionVM: SessionViewModel = hiltViewModel(),
    settingsVM: SettingsViewModel = hiltViewModel(),
    composerVM: ComposerViewModel = hiltViewModel(),
    pathToShow: String? = null,
    // Nav redesign: FilesScreen now subscribes to sessionVM.sessionListFlow
    // directly (it needs directorySessions too, not just sessions). The param
    // is KEPT for AppShell call-site stability; simply no longer read here.
    @Suppress("UNUSED_PARAMETER") sessions: List<Session> = emptyList(),
    @Suppress("UNUSED_PARAMETER") activeSessionId: String? = null,
    // Nav redesign: the `files?workdir=…` deeplink / programmatic nav seeds
    // browseWorkdir on first entry (see the LaunchedEffect below) so the user
    // lands directly in that project's file browser instead of the list.
    initialWorkdir: String? = null,
    // Kept for legacy FilesPane callers; no longer honored.
    @Suppress("UNUSED_PARAMETER") sessionDirectory: String? = null,
    // Kept for legacy FilesPane callers; no longer honored.
    @Suppress("UNUSED_PARAMETER") filesLastWorkdir: String? = sessionDirectory,
    // Kept for AppShell call-site stability; FilesScreen sources recentWorkdirs
    // from settingsVM.recentWorkdirs now (reactive — picks up disconnect ticks).
    @Suppress("UNUSED_PARAMETER") recentWorkdirs: List<String> = emptyList(),
    // Kept for AppShell call-site stability; the read-only WorkdirControl does
    // not consume onSelect anymore.
    @Suppress("UNUSED_PARAMETER") onWorkdirSelected: (String) -> Unit = {},
    onSwitchToChat: () -> Unit = {},
    onCloseFile: () -> Unit = {},
    onFileClick: (String) -> Unit = {},
    // §F5b-back: invoked when system back fires while a preview is open on the
    // project-list root (browseWorkdir == null). Hosts pass their close-overlay
    // logic so a single back exits FilesScreen cleanly.
    onExit: () -> Unit = {}
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // ── Project-list (first-screen) subscriptions ─────────────────────────
    // sessionVM.sessionListFlow carries both `sessions` and `directorySessions`
    // (per-workdir prefetch); both feed buildWorkdirGroups. unreadSessions is
    // a field-level slice so per-message / per-switch mutations on the unread
    // slice do not recompose this screen.
    val sessionListState by sessionVM.sessionListFlow.collectAsStateWithLifecycle()
    val recentWorkdirsState by settingsVM.recentWorkdirs.collectAsStateWithLifecycle()
    val composerState by composerVM.composerFlow.collectAsStateWithLifecycle()
    val unreadSessions by remember {
        sessionVM.unreadFlow.map { it.unreadSessions }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = emptySet())

    // ── Two-level internal state ──────────────────────────────────────────
    // null (default) → project-list first screen. non-null → file browser for
    // the user-selected workdir (independent of the active chat session).
    // rememberSaveable so config-change / process-death restores the depth.
    var browseWorkdir: String? by rememberSaveable { mutableStateOf(null) }

    // Project-list local UI state.
    var expandedWorkdirs by remember { mutableStateOf(emptySet<String>()) }
    var pendingDisconnectWorkdir by remember { mutableStateOf<String?>(null) }
    var showAddProjectSheet by remember { mutableStateOf(false) }

    // ── Preview dismissing gate (B1-P0: copy→predictive-back crash fix) ───
    // The LayoutCoordinate crash happens because removing FilePreviewPane in
    // the same frame as a SelectionContainer teardown can fire onGlobally-
    // Positioned on the detached node. `dismissing` defers the closePreview()
    // by one frame; PreviewPlainText drops SelectionContainer during that
    // frame so the selection-handle listeners unregister cleanly.
    var dismissing by remember { mutableStateOf(false) }

    LaunchedEffect(state.selectedFilePath) {
        if (PreviewDismissGate.shouldReset(dismissing)) {
            dismissing = false
        }
    }
    LaunchedEffect(dismissing) {
        if (dismissing) {
            withFrameNanos { }
            viewModel.closePreview()
        }
    }

    fun requestClosePreview() {
        if (PreviewDismissGate.shouldStartDismissing(
                currentSelectedFilePath = state.selectedFilePath,
                dismissing = dismissing,
            )
        ) {
            dismissing = true
        }
    }

    // Nav redesign: workdir = USER-SELECTED via the project-list. null on the
    // project-list screen (the file browser is not rendered there).
    val effectiveWorkdir: String? = browseWorkdir

    // Nav redesign: honour the `files?workdir=…` deeplink / programmatic nav.
    // On entry (or when the arg changes) seed browseWorkdir so the user lands
    // directly in that project's file browser. Guarded by `browseWorkdir ==
    // null` so a later reselect-to-root is not re-seized by a stale arg.
    LaunchedEffect(initialWorkdir) {
        if (!initialWorkdir.isNullOrBlank() && browseWorkdir == null) {
            browseWorkdir = initialWorkdir
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.error) {
        state.error?.let { message ->
            snackbarHostState.showTimed(message)
            viewModel.clearError()
        }
    }

    // Refresh once when entering the Files tab so the file tree is up to date
    // for the bound workdir (if any).
    LaunchedEffect(Unit) { viewModel.refresh() }

    LaunchedEffect(effectiveWorkdir) { viewModel.bindWorkdir(effectiveWorkdir) }

    LaunchedEffect(pathToShow, effectiveWorkdir) {
        if (pathToShow == null && state.selectedFilePath != null) {
            requestClosePreview()
        } else {
            viewModel.syncPathToShow(pathToShow, effectiveWorkdir)
        }
    }

    // §Q10 (P4b-B) + nav redesign: reselect (second tap on the Files tab)
    // closes any open preview AND returns to the project-list root so the
    // user always has a "go home" gesture from any depth.
    LaunchedEffect(orchestratorVM) {
        orchestratorVM.reselectFlow
            .filter { it == NavRoute.Files }
            .collect {
                if (viewModel.state.value.selectedFilePath != null) {
                    requestClosePreview()
                }
                browseWorkdir = null
                viewModel.popToRoot()
            }
    }

    // ── BackHandler: four mutually-exclusive states (LIFO stack) ──────────
    // The AppShell top-level BackHandler disables itself when currentRoute ==
    // Files (see AppShell.kt) so these handlers fully own system-back here.
    // Order = preview → directory-up → project-list → exit Chat.
    BackHandler(enabled = state.selectedFilePath != null) {
        requestClosePreview()
    }
    // Directory level: inside a project's file tree (currentPath non-empty) →
    // up one directory. Mirrors the top-bar back button; without this tier
    // system-back from a deep path would jump straight to the project list
    // (review fix: top-bar-back and system-back must agree).
    BackHandler(
        enabled = state.selectedFilePath == null &&
            browseWorkdir != null &&
            state.currentPath.isNotEmpty()
    ) { viewModel.navigateUp() }
    BackHandler(
        enabled = state.selectedFilePath == null &&
            browseWorkdir != null &&
            state.currentPath.isEmpty()
    ) { browseWorkdir = null }
    BackHandler(
        enabled = state.selectedFilePath == null && browseWorkdir == null
    ) { onExit() }

    // ── Project-list derivation (buildWorkdirGroups from SessionsScreen) ───
    val workdirGroups by remember(
        sessionListState.sessions,
        sessionListState.directorySessions,
        recentWorkdirsState,
        composerState.draftWorkdir,
    ) {
        derivedStateOf {
            buildWorkdirGroups(
                allSessions = (sessionListState.sessions +
                    sessionListState.directorySessions.values.flatten())
                    .distinctBy { it.id },
                recentWorkdirs = recentWorkdirsState,
                draftWorkdir = composerState.draftWorkdir,
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (browseWorkdir == null) {
            // ─────────── First screen: project list ───────────────────────
            ProjectListPane(
                workdirGroups = workdirGroups,
                expandedWorkdirs = expandedWorkdirs,
                onToggleExpand = { workdir ->
                    expandedWorkdirs = if (workdir in expandedWorkdirs) {
                        expandedWorkdirs - workdir
                    } else {
                        // Fix #6c: re-fetch this project's directory sessions
                        // on expand so a conversation created elsewhere (web)
                        // — whose session.created SSE was missed while
                        // backgrounded — appears without a full reconnect.
                        sessionVM.refreshDirectorySessions(workdir)
                        expandedWorkdirs + workdir
                    }
                },
                onLongClickWorkdir = { workdir -> pendingDisconnectWorkdir = workdir },
                onBrowseFiles = { workdir ->
                    browseWorkdir = workdir
                    viewModel.popToRoot()
                },
                onCreateSessionInWorkdir = { workdir ->
                    sessionVM.createSessionInWorkdir(workdir)
                    onSwitchToChat()
                },
                onSelectSession = { sessionId ->
                    sessionVM.selectSession(sessionId)
                    onSwitchToChat()
                },
                sessionStatuses = sessionListState.sessionStatuses,
                unreadSessions = unreadSessions,
                onAddProject = { showAddProjectSheet = true },
            )
        } else {
            // ─────────── Second screen: file browser ──────────────────────
            // §a11y-binder: clearAndSetSemantics actually CLEARS the descendant
            // semantics tree so the a11y service cannot flood binder walking
            // the deep file-tree node graph. The top bar (back/refresh buttons)
            // stays independently accessible via its own contentDescription.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .clearAndSetSemantics { }
            ) {
                if (state.selectedFilePath == null) {
                    TopAppBar(
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(state.currentPath.ifEmpty {
                                    stringResource(R.string.files_title)
                                })
                                Spacer(Modifier.width(8.dp))
                                WorkdirControl(
                                    currentWorkdir = effectiveWorkdir,
                                    recentWorkdirs = emptyList(),
                                    onSelect = {},
                                    readOnly = true,
                                )
                            }
                        },
                        navigationIcon = {
                            // Nav redesign: back affordance owns BOTH depth
                            // transitions inside the file browser — when
                            // currentPath is empty (workdir root), the back
                            // arrow returns to the project list; otherwise it
                            // navigates one directory up. The two BackHandlers
                            // above own the system-back parallel.
                            IconButton(
                                onClick = {
                                    if (state.currentPath.isNotEmpty()) {
                                        viewModel.navigateUp()
                                    } else {
                                        browseWorkdir = null
                                    }
                                }
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.common_back),
                                )
                            }
                        },
                        actions = {
                            IconButton(onClick = viewModel::refresh) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = stringResource(R.string.common_refresh),
                                )
                            }
                        }
                    )
                }

                when {
                    state.selectedFilePath != null && state.selectedFileContent != null -> {
                        FilePreviewPane(
                            path = state.selectedFilePath!!,
                            fileContent = state.selectedFileContent!!,
                            repository = viewModel.repository,
                            sessionDirectory = effectiveWorkdir,
                            isRefreshing = state.isPreviewRefreshing,
                            onRefresh = { viewModel.refreshPreview(effectiveWorkdir) },
                            textSelectable = PreviewDismissGate.textSelectable(dismissing),
                            onClose = {
                                requestClosePreview()
                                onCloseFile()
                            }
                        )
                    }

                    state.isLoading -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }

                    else -> {
                        FileBrowserPane(
                            files = state.files,
                            fileStatuses = state.fileStatuses,
                            onFileSelected = { file ->
                                viewModel.selectFile(file, onFileClick)
                            },
                            onFileLongPress = viewModel::shareFile
                        )
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }

    // ── Disconnect workdir confirmation dialog (project-list first screen) ─
    pendingDisconnectWorkdir?.let { workdir ->
        AlertDialog(
            onDismissRequest = { pendingDisconnectWorkdir = null },
            title = { Text(stringResource(R.string.workdir_disconnect_title)) },
            text = {
                val name = workdir.split("/")
                    .filter { it.isNotEmpty() }
                    .lastOrNull() ?: workdir
                Text(
                    stringResource(R.string.workdir_disconnect_message) +
                        "\n\n" + name + "\n" + workdir
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        settingsVM.disconnectWorkdir(workdir)
                        // §task5-lifecycle: clear unread for this workdir's sessions.
                        sessionVM.clearUnreadForWorkdir(workdir)
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

    // ── Directory picker (add new project) ─────────────────────────────────
    if (showAddProjectSheet) {
        DirectoryPickerSheet(
            repository = viewModel.repository,
            onDismiss = { showAddProjectSheet = false },
            onSelect = { path ->
                showAddProjectSheet = false
                // Nav redesign: register the project WITHOUT creating a session
                // (project = first-class entity, independent of sessions). The
                // row appears next frame via the recentWorkdirs tick; tapping
                // it lets the user browse files or start a conversation.
                settingsVM.connectWorkdir(path)
            }
        )
    }
}

/**
 * Nav redesign: FilesScreen first screen — the project-list (workdir-groups)
 * view ported from the now-removed SessionsScreen "Connected projects" block.
 * Renders one ListItem per workdir (expand/collapse + long-press disconnect)
 * with two trailing IconButtons: FolderOpen (browse files) + AddComment (new
 * session in this workdir). Expanded rows reveal their SessionCard list (or
 * the EmptyWorkdirPlaceholder when there are zero live sessions).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectListPane(
    workdirGroups: List<Pair<String, List<Session>>>,
    expandedWorkdirs: Set<String>,
    onToggleExpand: (String) -> Unit,
    onLongClickWorkdir: (String) -> Unit,
    onBrowseFiles: (String) -> Unit,
    onCreateSessionInWorkdir: (String) -> Unit,
    onSelectSession: (String) -> Unit,
    sessionStatuses: Map<String, cn.vectory.ocdroid.data.model.SessionStatus>,
    unreadSessions: Set<String>,
    onAddProject: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.files_tab_projects)) },
                actions = {
                    // "添加项目" — CreateNewFolder (folder + plus, the clearest
                    // "add a directory" semantic). Opens DirectoryPickerSheet →
                    // settingsVM.connectWorkdir (registers without creating a
                    // session; project is a first-class entity).
                    IconButton(onClick = onAddProject) {
                        Icon(
                            Icons.Default.CreateNewFolder,
                            contentDescription = stringResource(R.string.files_add_project),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
            contentPadding = PaddingValues(bottom = Dimens.spacing2),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            if (workdirGroups.isEmpty()) {
                item(key = "workdirs_empty") {
                    Text(
                        text = stringResource(R.string.sessions_tab_no_workdirs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(
                            horizontal = Dimens.spacing6,
                            vertical = Dimens.spacing3,
                        ),
                    )
                }
            } else {
                items(
                    workdirGroups,
                    key = { "workdir_${WorkdirPaths.normalize(it.first)}" }
                ) { (workdir, sessionsInWorkdir) ->
                    WorkdirRow(
                        workdir = workdir,
                        sessionsInWorkdir = sessionsInWorkdir,
                        isExpanded = workdir in expandedWorkdirs,
                        onToggleExpand = { onToggleExpand(workdir) },
                        onLongClick = { onLongClickWorkdir(workdir) },
                        onBrowseFiles = { onBrowseFiles(workdir) },
                        onCreateSession = { onCreateSessionInWorkdir(workdir) },
                        onSelectSession = onSelectSession,
                        sessionStatuses = sessionStatuses,
                        unreadSessions = unreadSessions,
                    )
                }
            }
        }
    }
}

/**
 * One workdir row in the project list (header + expandable session list).
 * Mirrors the layout shipped in the pre-nav-redesign SessionsScreen "Connected
 * projects" block so the per-project affordances (expand, create session,
 * browse files, long-press disconnect) stay in familiar positions.
 */
@Composable
private fun WorkdirRow(
    workdir: String,
    sessionsInWorkdir: List<Session>,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onLongClick: () -> Unit,
    onBrowseFiles: () -> Unit,
    onCreateSession: () -> Unit,
    onSelectSession: (String) -> Unit,
    sessionStatuses: Map<String, cn.vectory.ocdroid.data.model.SessionStatus>,
    unreadSessions: Set<String>,
) {
    val displayName = workdir.split("/").filter { it.isNotEmpty() }.lastOrNull() ?: workdir
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
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            headlineContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Medium,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
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
                    // Nav redesign: per-row "browse files" (FolderOpen). Sets
                    // browseWorkdir = workdir → the second-screen file browser
                    // takes over (independent of the active chat session).
                    IconButton(onClick = onBrowseFiles) {
                        Icon(
                            Icons.Default.FolderOpen,
                            contentDescription = stringResource(R.string.files_project_browse),
                            modifier = Modifier.size(Dimens.iconSm),
                        )
                    }
                    // Create-session affordance for this workdir. Tapping it
                    // opens a fresh draft against this directory AND jumps to
                    // Chat so the user lands in the composer for the new
                    // (draft) session.
                    IconButton(onClick = onCreateSession) {
                        Icon(
                            Icons.Default.AddComment,
                            contentDescription = stringResource(R.string.sessions_tab_create_session),
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
                        isUnread = session.id in unreadSessions,
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
