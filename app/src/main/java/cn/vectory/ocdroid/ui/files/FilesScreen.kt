package cn.vectory.ocdroid.ui.files

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.ui.NavRoute
import cn.vectory.ocdroid.ui.OrchestratorViewModel
import cn.vectory.ocdroid.ui.showTimed
import kotlinx.coroutines.flow.filter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    viewModel: FilesViewModel = hiltViewModel(),
    orchestratorVM: OrchestratorViewModel,
    pathToShow: String? = null,
    sessions: List<Session> = emptyList(),
    activeSessionId: String? = null,
    // §files-git-readonly-workdir: the workdir is now derived SOLELY from the
    // active session's directory (real-time). Manual workdir selection in the
    // Files tab is removed — switching projects happens by opening a session
    // in the target directory. The retained params below are kept in the
    // signature (no-op) so AppShell's call site doesn't churn; they are NOT
    // honored as overrides anymore.
    // Kept for route-source signalling; no longer applied as an override.
    @Suppress("UNUSED_PARAMETER") initialWorkdir: String? = null,
    // Kept for legacy FilesPane callers; no longer honored.
    @Suppress("UNUSED_PARAMETER") sessionDirectory: String? = null,
    // Kept for legacy FilesPane callers; no longer honored.
    @Suppress("UNUSED_PARAMETER") filesLastWorkdir: String? = sessionDirectory,
    // §Q2 (P4b-A): retained for the WorkdirControl switcher body. The Files
    // control now renders in readOnly mode, so this list is NOT consumed
    // here — but the param stays in the signature so AppShell's call site is
    // unchanged.
    @Suppress("UNUSED_PARAMETER") recentWorkdirs: List<String> = emptyList(),
    // §files-git-readonly-workdir: retained for AppShell call-site stability.
    // No longer invoked from this screen (the WorkdirControl is read-only).
    @Suppress("UNUSED_PARAMETER") onWorkdirSelected: (String) -> Unit = {},
    onCloseFile: () -> Unit = {},
    onFileClick: (String) -> Unit = {},
    // §F5b-back: invoked when system back fires while a preview is open.
    // Hosts pass their close-overlay logic (e.g. showFileBrowser = false) so a
    // single back closes the whole FilesScreen overlay, not just the preview.
    onExit: () -> Unit = {}
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // §B1-P0 (fix copy→predictive-back crash): the LayoutCoordinate crash
    // happens because the FilesScreen `when` branch removes FilePreviewPane
    // from composition IN THE SAME FRAME as the BackHandler / close-button
    // commit calls viewModel.closePreview(). When the removed subtree happens
    // to contain a SelectionContainer (text/markdown-source preview), its
    // selection handles' onGloballyPositioned callback can still fire on the
    // now-detached LayoutCoordinates node → IllegalStateException.
    //
    // Fix (oracle scheme d): gate removal behind a one-frame "dismissing"
    // phase. While `dismissing == true`, FilePreviewPane is still composed
    // BUT PreviewPlainText drops SelectionContainer (renders plain Text), so
    // the selection-handle listeners unregister cleanly during that frame.
    // On the next frame we call closePreview() — by then the pane removal is
    // safe because SelectionContainer is already gone.
    var dismissing by remember { mutableStateOf(false) }

    // Reset dismissing on ANY selectedFilePath transition. See
    // PreviewDismissGate's kdoc for the full rationale: in short, StateFlow
    // is conflating and can collapse `X → null → Y` into a single `X → Y`
    // emission that this LaunchedEffect observes. If we reset only on
    // `== null`, a conflate-collapsed transition would never hit the reset
    // branch and `dismissing` would stick at true — the preview would stay
    // non-selectable and every close entry would early-return (lock-up).
    // Resetting on any change covers all three cases: normal close (X→null),
    // reopen mid-dismiss (X→Y), and first open (null→Y, no-op).
    LaunchedEffect(state.selectedFilePath) {
        if (PreviewDismissGate.shouldReset(dismissing)) {
            dismissing = false
        }
    }

    // Drives the deferred close. Keyed on `dismissing` so a fresh `true` tick
    // relaunches the effect. withFrameNanos {} suspends until the next
    // choreographer frame — that single frame is when the recomposition
    // stripping SelectionContainer (because the host flipped textSelectable
    // to false) commits and disposes the selection handles. Only then do we
    // invoke closePreview() to drop the pane.
    LaunchedEffect(dismissing) {
        if (dismissing) {
            withFrameNanos { }
            viewModel.closePreview()
        }
    }

    // Unified close entry point — every "close preview" path (system/predictive
    // back commit, TopBar close button, Files-tab reselect) MUST go through
    // this so the dismissing gate always runs.
    //
    // The transition decision is delegated to [PreviewDismissGate] (pure,
    // JVM-tested). Closing guard: reentry-safe — see the gate's kdoc.
    fun requestClosePreview() {
        if (PreviewDismissGate.shouldStartDismissing(
                currentSelectedFilePath = state.selectedFilePath,
                dismissing = dismissing,
            )
        ) {
            dismissing = true
        }
    }

    // §files-git-readonly-workdir: the workdir is derived directly from the
    // active session's directory on every recomposition so Files follows
    // Chat session switches. No local override, no last-browsed fallback —
    // null when there is no active session (the WorkdirControl renders the
    // muted "No workdir" placeholder in that case).
    val effectiveWorkdir = sessions.firstOrNull { it.id == activeSessionId }?.directory

    // C1: error feedback via a 3s Snackbar (replaces the former AppToast top
    // overlay; previously used the M3 default duration).
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.error) {
        state.error?.let { message ->
            snackbarHostState.showTimed(message)
            viewModel.clearError()
        }
    }

    // Refresh once when entering the Files tab so the list is up to date.
    LaunchedEffect(Unit) {
        viewModel.refresh()
    }

    // §R-17 batch4: bind the browser's directory context to the host session
    // (chat current-session directory OR sessions-tab fileBrowserWorkdir).
    // Re-binds + refreshes whenever the host passes a different workdir.
    // §Q2: uses the effective workdir so both a local selection and a Chat
    // session switch re-bind the browser.
    LaunchedEffect(effectiveWorkdir) {
        viewModel.bindWorkdir(effectiveWorkdir)
    }

    LaunchedEffect(pathToShow, effectiveWorkdir) {
        // §B1-P0: when pathToShow is null but a preview is currently open,
        // the VM's syncPathToShow would internally call closePreview (same-
        // frame pane removal → LayoutCoordinate crash on text/markdown-source
        // previews). Route that case through the dismissing gate instead so
        // SelectionContainer unlinks first. Workdir is already synced by the
        // parallel LaunchedEffect(effectiveWorkdir) → bindWorkdir above, so
        // skipping syncPathToShow here doesn't lose the workdir update.
        //
        // No `&& !dismissing` here: requestClosePreview's own closing guard
        // already no-ops when dismissing is in flight, and adding the guard
        // inline would let dismissing==true fall through to syncPathToShow's
        // closePreview — a same-frame removal bypassing the gate.
        if (pathToShow == null && state.selectedFilePath != null) {
            requestClosePreview()
        } else {
            viewModel.syncPathToShow(pathToShow, effectiveWorkdir)
        }
    }

    // §Q10 (P4b-B): reselect subscription — a second tap on the Files tab
    // closes any open preview and returns the browser to the workdir root.
    // Reads viewModel.state.value (not the composed `state` delegate) inside
    // the collect lambda so the freshest StateFlow value is used regardless
    // of when the reselect emission arrives.
    LaunchedEffect(orchestratorVM) {
        orchestratorVM.reselectFlow
            .filter { it == NavRoute.Files }
            .collect {
                // §B1-P0: go through requestClosePreview so the SelectionContainer
                // is unlinked cleanly before the pane is removed (same dismissing
                // gate as back/close-button). popToRoot runs synchronously — it
                // only touches currentPath, not selectedFilePath, so order
                // against the deferred closePreview() is safe.
                if (viewModel.state.value.selectedFilePath != null) {
                    requestClosePreview()
                }
                viewModel.popToRoot()
            }
    }

    // §F5b-back / §files-back-fix (Blocker-2): two strictly-mutually-exclusive
    // BackHandlers own the system-back behaviour inside Files so the AppShell
    // top-level BackHandler can stay out of the way (it disables itself when
    // currentRoute == Files — see AppShell.kt). Compose's BackHandler stack is
    // LIFO, and AppShell's handler is composed AFTER this screen, so without
    // the explicit `route != Files` disable on AppShell the shell would win
    // the back press and jump straight to Chat even with a preview open. The
    // two handlers here are mutually exclusive on `selectedFilePath != null`
    // so exactly one is enabled in every state:
    //   - preview open  → requestClosePreview (returns to file list, via the
    //                     dismissing gate so SelectionContainer unlinks first)
    //   - preview closed → onExit (exits Files back to Chat)
    androidx.activity.compose.BackHandler(enabled = state.selectedFilePath != null) {
        requestClosePreview()
    }
    androidx.activity.compose.BackHandler(enabled = state.selectedFilePath == null) {
        onExit()
    }

    // The snackbar is rendered as a sibling overlay (NOT a child of the Column)
    // so appearing/disappearing does not push the file tree vertically.
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier
            .fillMaxSize()
            // §a11y-binder: the file tree generates a deep Compose semantics node
            // tree. Android's accessibility service traverses it via binder (~300ms
            // per traversal, 16-33KB each), producing massive binder floods
            // (ADB-confirmed: BpBinder Large outgoing transaction every ~300ms).
            // clearAndSetSemantics here actually CLEARS the descendant semantics
            // tree so the accessibility service cannot walk it (a plain
            // .semantics { } would only MERGE an empty config — a no-op). The top
            // bar (back/refresh buttons) remain independently accessible via their
            // own contentDescription.
            .clearAndSetSemantics { }
        ) {
            if (state.selectedFilePath == null) {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(state.currentPath.ifEmpty { stringResource(R.string.files_title) })
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
                        if (state.currentPath.isNotEmpty()) {
                            IconButton(onClick = viewModel::navigateUp) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = viewModel::refresh) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.common_refresh))
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
                        // §B1-P0: drop SelectionContainer one frame before the
                        // pane is removed; see dismissing / requestClosePreview.
                        // Decision routed through PreviewDismissGate so the
                        // contract is JVM-tested.
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
                        androidx.compose.material3.CircularProgressIndicator()
                    }
                }

                else -> {
                    FileBrowserPane(
                        files = state.files,
                        fileStatuses = state.fileStatuses,
                        onFileSelected = { file -> viewModel.selectFile(file, onFileClick) },
                        // §F5: 文件名长按 → 下载并系统分享（所有文件格式）。
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
