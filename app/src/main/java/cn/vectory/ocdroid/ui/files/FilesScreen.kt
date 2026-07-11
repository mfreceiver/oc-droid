package cn.vectory.ocdroid.ui.files

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.ui.showTimed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    viewModel: FilesViewModel = hiltViewModel(),
    pathToShow: String? = null,
    sessionDirectory: String? = null,
    onCloseFile: () -> Unit = {},
    onFileClick: (String) -> Unit = {},
    // §F5b-back: invoked when system back fires while a preview is open.
    // Hosts pass their close-overlay logic (e.g. showFileBrowser = false) so a
    // single back closes the whole FilesScreen overlay, not just the preview.
    onExit: () -> Unit = {}
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

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
    LaunchedEffect(sessionDirectory) {
        viewModel.bindWorkdir(sessionDirectory)
    }

    LaunchedEffect(pathToShow, sessionDirectory) {
        viewModel.syncPathToShow(pathToShow, sessionDirectory)
    }

    // §F5b-back: preview open → back exits the whole FilesScreen overlay (via
    // the host's onExit) rather than just closing the preview back to the file
    // list (which would need a second back to leave). When no preview is open
    // this handler is disabled, and the host's outer BackHandler dismisses the
    // overlay (behavior unchanged).
    androidx.activity.compose.BackHandler(enabled = state.selectedFilePath != null) {
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
            // Clearing semantics at the root prevents the service from walking the
            // tree; the top bar (back/refresh buttons) remain independently
            // accessible via their own contentDescription.
            .semantics { }
        ) {
            if (state.selectedFilePath == null) {
                TopAppBar(
                    title = { Text(state.currentPath.ifEmpty { stringResource(R.string.files_title) }) },
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
                        sessionDirectory = sessionDirectory,
                        isRefreshing = state.isPreviewRefreshing,
                        onRefresh = { viewModel.refreshPreview(sessionDirectory) },
                        onClose = {
                            viewModel.closePreview()
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
