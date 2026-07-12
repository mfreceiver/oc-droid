package cn.vectory.ocdroid.ui.workspace

import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.ui.HostViewModel
import cn.vectory.ocdroid.ui.NavRoute
import cn.vectory.ocdroid.ui.OrchestratorViewModel
import cn.vectory.ocdroid.ui.SessionViewModel
import cn.vectory.ocdroid.ui.files.FilesViewModel
import cn.vectory.ocdroid.ui.files.WorkdirControl
import cn.vectory.ocdroid.util.WorkdirPaths
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
    // §Q2 (P4b-A): the current host's connected workdirs (MRU) for the
    // WorkdirControl switcher. Sourced from settingsVM.recentWorkdirs.
    recentWorkdirs: List<String> = emptyList(),
    // §Q2: fallback default workdir when the active session has no directory
    // (e.g. Chat has no session). Typically settingsVM.filesLastWorkdir.
    defaultWorkdir: String? = null,
    // §Q2: invoked when the user picks a workdir in the WorkdirControl.
    // AppShell wires this to persist settingsManager.filesLastWorkdir.
    onWorkdirSelected: (String) -> Unit = {},
) {
    val host by hostVM.hostFlow.collectAsStateWithLifecycle()
    val chat by sessionVM.chatFlow.collectAsStateWithLifecycle()
    val sessions by sessionVM.sessionListFlow.collectAsStateWithLifecycle()
    val activeHost = host.currentHostProfileId
    val activeSession = initialSessionId ?: chat.currentSessionId
    val sessionDerivedWorkdir = sessions.sessions.firstOrNull { it.id == activeSession }?.directory

    // §Q2: local workdir override. Defaults to the session-derived workdir,
    // falling back to defaultWorkdir (filesLastWorkdir) when Chat has no
    // session. The user's WorkdirControl pick overrides it locally WITHOUT
    // mutating the Chat session. rememberSaveable keyed on both defaults so
    // it re-defaults only when the incoming derived value actually changes.
    var localWorkdir by rememberSaveable(sessionDerivedWorkdir, defaultWorkdir) {
        mutableStateOf(sessionDerivedWorkdir ?: defaultWorkdir)
    }
    val effectiveWorkdir = localWorkdir ?: sessionDerivedWorkdir ?: defaultWorkdir

    // §Q2: when the user switches workdir, re-scope the diff to a session in
    // the selected workdir (if one exists) so the Changes pane shows relevant
    // diffs for the browsed project instead of the Chat session's project.
    val effectiveSessionId = effectiveWorkdir?.let { wd ->
        val target = WorkdirPaths.normalize(wd)
        sessions.sessions.firstOrNull {
            WorkdirPaths.normalize(it.directory ?: "") == target
        }?.id
    } ?: activeSession

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
                title = { Text(stringResource(R.string.nav_git)) },
                actions = {
                    // §Q2: WorkdirControl switcher in the Git top bar.
                    WorkdirControl(
                        currentWorkdir = effectiveWorkdir,
                        recentWorkdirs = recentWorkdirs,
                        onSelect = { wd ->
                            localWorkdir = wd
                            onWorkdirSelected(wd)
                        },
                    )
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
