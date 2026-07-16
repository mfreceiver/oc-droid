package cn.vectory.ocdroid.ui.files

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.ui.ComposerViewModel
import cn.vectory.ocdroid.ui.NavRoute
import cn.vectory.ocdroid.ui.OrchestratorViewModel
import cn.vectory.ocdroid.ui.SessionViewModel
import cn.vectory.ocdroid.ui.SettingsViewModel
import cn.vectory.ocdroid.ui.showTimed
import cn.vectory.ocdroid.ui.theme.Dimens
import kotlinx.coroutines.flow.filter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    viewModel: FilesViewModel = hiltViewModel(),
    orchestratorVM: OrchestratorViewModel,
    /**
     * Home-hub (T5): FilesScreen is now a BROWSER-ONLY pane. The project-list
     * first screen has moved to the home page (another lane), so the session /
     * settings / composer VMs are no longer read here. The params are KEPT
     * (with defaults) for AppShell / FilesPane call-site stability.
     */
    @Suppress("UNUSED_PARAMETER") sessionVM: SessionViewModel = hiltViewModel(),
    @Suppress("UNUSED_PARAMETER") settingsVM: SettingsViewModel = hiltViewModel(),
    @Suppress("UNUSED_PARAMETER") composerVM: ComposerViewModel = hiltViewModel(),
    pathToShow: String? = null,
    @Suppress("UNUSED_PARAMETER") sessions: List<Session> = emptyList(),
    @Suppress("UNUSED_PARAMETER") activeSessionId: String? = null,
    // Home-hub (T5): the workdir is now an INPUT — the project row that
    // launched this screen passes it via the `files?workdir=…` route
    // (AppShell threads it as `initialWorkdir = explicitWorkdir`). There is
    // no two-level flip anymore; this screen is always a browser for the
    // supplied workdir (or an empty browser when null).
    initialWorkdir: String? = null,
    // Kept for legacy FilesPane callers; no longer honored.
    @Suppress("UNUSED_PARAMETER") sessionDirectory: String? = null,
    // Kept for legacy FilesPane callers; no longer honored.
    @Suppress("UNUSED_PARAMETER") filesLastWorkdir: String? = sessionDirectory,
    // Kept for AppShell call-site stability; recentWorkdirs is sourced
    // elsewhere now (the WorkdirControl here is read-only).
    @Suppress("UNUSED_PARAMETER") recentWorkdirs: List<String> = emptyList(),
    // Kept for AppShell call-site stability; the read-only WorkdirControl
    // does not consume onSelect anymore.
    @Suppress("UNUSED_PARAMETER") onWorkdirSelected: (String) -> Unit = {},
    // Kept for AppShell call-site stability; the project-list (which used to
    // fire this after a per-row session create/select) has moved to home.
    @Suppress("UNUSED_PARAMETER") onSwitchToChat: () -> Unit = {},
    onCloseFile: () -> Unit = {},
    onFileClick: (String) -> Unit = {},
    // §F5b-back / home-hub (T5): invoked when system back fires while the
    // browser is at root (no preview open, currentPath empty). Hosts pass
    // their close-overlay logic so a single back exits FilesScreen cleanly.
    // (T7 rewires onExit → home.)
    onExit: () -> Unit = {}
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Home-hub (T5): workdir = the route-supplied initialWorkdir. No internal
    // two-level state. null means "no folder selected" — the browser renders
    // empty with a muted "No workdir" indicator in the title.
    val workdir: String? = initialWorkdir

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

    LaunchedEffect(workdir) { viewModel.bindWorkdir(workdir) }

    LaunchedEffect(pathToShow, workdir) {
        if (pathToShow == null && state.selectedFilePath != null) {
            requestClosePreview()
        } else {
            viewModel.syncPathToShow(pathToShow, workdir)
        }
    }

    // §Q10 (P4b-B) + home-hub (T5): reselect (second tap on the Files tab)
    // closes any open preview AND pops the browser to root so the user
    // always has a "go home" gesture from any depth.
    LaunchedEffect(orchestratorVM) {
        orchestratorVM.reselectFlow
            .filter { it == NavRoute.Files }
            .collect {
                if (viewModel.state.value.selectedFilePath != null) {
                    requestClosePreview()
                }
                viewModel.popToRoot()
            }
    }

    // ── BackHandler: three mutually-exclusive states (LIFO stack) ──────────
    // The AppShell top-level BackHandler disables itself when currentRoute ==
    // Files (see AppShell.kt) so these handlers fully own system-back here.
    // Order = preview → directory-up → exit. (Home-hub T5: the old
    // "browser root → project list" tier is gone; browser root now exits.)
    BackHandler(enabled = state.selectedFilePath != null) {
        requestClosePreview()
    }
    // Directory level: inside the bound workdir's file tree (currentPath
    // non-empty) → up one directory. Mirrors the top-bar back button; without
    // this tier system-back from a deep path would jump straight to exit
    // (review fix: top-bar-back and system-back must agree).
    BackHandler(
        enabled = state.selectedFilePath == null &&
            workdir != null &&
            state.currentPath.isNotEmpty()
    ) { viewModel.navigateUp() }
    // Browser root (no preview, no deeper path, or no workdir bound at all)
    // → exit FilesScreen. T7 rewires onExit → home.
    BackHandler(
        enabled = state.selectedFilePath == null &&
            (workdir == null || state.currentPath.isEmpty())
    ) { onExit() }

    Box(modifier = Modifier.fillMaxSize()) {
        // §a11y-binder: clearAndSetSemantics actually CLEARS the descendant
        // semantics tree so the a11y service cannot flood binder walking the
        // deep file-tree node graph. The top bar (back/refresh buttons) stays
        // independently accessible via its own contentDescription.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clearAndSetSemantics { }
        ) {
            if (state.selectedFilePath == null) {
                TopAppBar(
                    title = {
                        // Home-hub (T5): title = "Files" + read-only
                        // WorkdirControl showing the bound project's basename
                        // (mirrors GitScreen.kt). When workdir is null the
                        // WorkdirControl itself renders the muted
                        // `files_workdir_none` ("No workdir") placeholder —
                        // reusing the existing string per the brief's
                        // prefer-reuse directive (no new string added).
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.nav_files))
                            Spacer(Modifier.width(Dimens.spacing2))
                            WorkdirControl(
                                currentWorkdir = workdir,
                                recentWorkdirs = emptyList(),
                                onSelect = {},
                                readOnly = true,
                            )
                        }
                    },
                    navigationIcon = {
                        // Home-hub (T5): back affordance — when currentPath is
                        // empty (workdir root, or no workdir bound) the back
                        // arrow exits FilesScreen; otherwise it navigates one
                        // directory up. The BackHandlers above own the
                        // system-back parallel.
                        IconButton(
                            onClick = {
                                if (state.currentPath.isNotEmpty()) {
                                    viewModel.navigateUp()
                                } else {
                                    onExit()
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
                        sessionDirectory = workdir,
                        isRefreshing = state.isPreviewRefreshing,
                        onRefresh = { viewModel.refreshPreview(workdir) },
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

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}
