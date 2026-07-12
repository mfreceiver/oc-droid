package cn.vectory.ocdroid.ui.workspace

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.ui.HostViewModel
import cn.vectory.ocdroid.ui.NavRoute
import cn.vectory.ocdroid.ui.OrchestratorViewModel
import cn.vectory.ocdroid.ui.SessionViewModel
import cn.vectory.ocdroid.ui.files.FilesViewModel
import cn.vectory.ocdroid.ui.files.WorkdirControl
import kotlinx.coroutines.flow.filter

private const val STATE_HOST = "git.host"
private const val STATE_SESSION = "git.session"
private const val STATE_FILE = "git.selectedFile"

/** Top-level Git destination backed by the existing session/working-tree Changes pane. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitScreen(
    filesVM: FilesViewModel,
    sessionVM: SessionViewModel,
    hostVM: HostViewModel,
    orchestratorVM: OrchestratorViewModel,
    savedStateHandle: SavedStateHandle,
    initialSessionId: String? = null,
    // §files-git-readonly-workdir: retained for AppShell call-site stability.
    // The WorkdirControl is now read-only; this list is NOT consumed here.
    @Suppress("UNUSED_PARAMETER") recentWorkdirs: List<String> = emptyList(),
    // §files-git-readonly-workdir: retained for AppShell call-site stability.
    // No longer honored as a fallback (workdir derives from active session).
    @Suppress("UNUSED_PARAMETER") defaultWorkdir: String? = null,
    // §files-git-readonly-workdir: retained for AppShell call-site stability.
    // No longer invoked from this screen (the WorkdirControl is read-only).
    @Suppress("UNUSED_PARAMETER") onWorkdirSelected: (String) -> Unit = {},
) {
    val host by hostVM.hostFlow.collectAsStateWithLifecycle()
    val chat by sessionVM.chatFlow.collectAsStateWithLifecycle()
    val sessions by sessionVM.sessionListFlow.collectAsStateWithLifecycle()
    val activeHost = host.currentHostProfileId
    val activeSession = initialSessionId ?: chat.currentSessionId
    // §files-git-readonly-workdir: derive the workdir directly from the
    // active session's directory. No local override, no last-browsed
    // fallback — null when there is no active session (the WorkdirControl
    // renders the muted "No workdir" placeholder in that case).
    val effectiveWorkdir = sessions.sessions.firstOrNull { it.id == activeSession }?.directory

    // §Q2: scope the diff to the active session (the WorkdirControl is now
    // read-only, so this is just the active session id). Kept as an explicit
    // derivation so the LaunchedEffect / computeVisibleDiffs inputs stay
    // observable + the saved-state restore path is unchanged.
    val effectiveSessionId = activeSession

    var state by remember { mutableStateOf(WorkspaceState()) }

    LaunchedEffect(activeHost, effectiveSessionId, effectiveWorkdir) {
        state = resolveWorkspaceState(
            savedHost = savedStateHandle[STATE_HOST],
            savedSession = savedStateHandle[STATE_SESSION],
            savedFile = savedStateHandle[STATE_FILE],
            workdir = effectiveWorkdir,
            activeHost = activeHost,
            activeSession = effectiveSessionId,
        )
        savedStateHandle[STATE_HOST] = state.hostId
        savedStateHandle[STATE_SESSION] = state.sessionId
        savedStateHandle[STATE_FILE] = state.selectedFile
    }
    val visibleDiffs = computeVisibleDiffs(
        savedHost = state.hostId,
        savedSession = state.sessionId,
        savedFile = state.selectedFile,
        workdir = effectiveWorkdir,
        activeHost = activeHost,
        activeSession = effectiveSessionId,
        sessionDiffs = sessions.sessionDiffs,
    )

    // §Q10 (P4b-B): reselect subscription — a second tap on the Git tab
    // closes the open diff sheet (clears selectedFile + saved state) so the
    // user lands on the clean Changes pane.
    LaunchedEffect(orchestratorVM) {
        orchestratorVM.reselectFlow
            .filter { it == NavRoute.Git }
            .collect {
                state = state.copy(selectedFile = null)
                savedStateHandle[STATE_FILE] = null
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.nav_git))
                        Spacer(Modifier.width(8.dp))
                        WorkdirControl(
                            currentWorkdir = effectiveWorkdir,
                            recentWorkdirs = emptyList(),
                            onSelect = {},
                            readOnly = true,
                        )
                    }
                },
            )
        },
    ) { padding ->
        ChangesPane(
            diffs = visibleDiffs,
            repository = filesVM.repository,
            workdir = effectiveWorkdir,
            selectedFile = state.selectedFile,
            onSelectFile = { file ->
                state = state.copy(selectedFile = file)
                savedStateHandle[STATE_FILE] = file
            },
            modifier = Modifier.padding(padding),
        )
    }
}
