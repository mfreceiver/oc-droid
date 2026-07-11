package cn.vectory.ocdroid.ui.workspace

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.vectory.ocdroid.ui.HostViewModel
import cn.vectory.ocdroid.ui.SessionViewModel
import cn.vectory.ocdroid.ui.files.FilesViewModel

private const val STATE_HOST = "workspace.host"
private const val STATE_SESSION = "workspace.session"
private const val STATE_FILE = "workspace.selectedFile"

/** Workspace destination. Selection is route SavedStateHandle-backed, never persisted globally. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceScaffold(
    filesVM: FilesViewModel,
    sessionVM: SessionViewModel,
    hostVM: HostViewModel,
    savedStateHandle: SavedStateHandle,
    initialSessionId: String? = null,
    initialTab: WorkspaceTab = WorkspaceTab.Files,
    /**
     * §phase2: explicit workdir from the typed route (when opened via
     * `workspace/files?workdir=...`). Overrides the current-session workdir
     * derivation so that, e.g., tapping workdir B's folder on Sessions
     * opens B (not the current session A's directory).
     */
    initialWorkdir: String? = null,
    /**
     * §phase2: specific file path from the typed route (when opened via
     * `workspace/files?path=...`). Forwarded to FilesPane.pathToShow so a
     * chat file-path tap locates the file (was always-null before this fix).
     */
    initialPath: String? = null,
    onOpenFiles: () -> Unit,
    onOpenChanges: (String?) -> Unit,
) {
    val host by hostVM.hostFlow.collectAsStateWithLifecycle()
    val chat by sessionVM.chatFlow.collectAsStateWithLifecycle()
    val sessions by sessionVM.sessionListFlow.collectAsStateWithLifecycle()
    val activeHost = host.currentHostProfileId
    val activeSession = initialSessionId ?: chat.currentSessionId
    // §phase2: prefer the explicit route-carried workdir; fall back to the
    // current session's directory (the original derivation).
    val sessionWorkdir = sessions.sessions.firstOrNull { it.id == activeSession }?.directory
    val workdir = initialWorkdir ?: sessionWorkdir
    var state by remember { mutableStateOf(WorkspaceState()) }

    LaunchedEffect(activeHost, activeSession, workdir) {
        // §phase2-isolation: carry workdir identity into WorkspaceState so
        // matches() can do strict identity comparison. Without this, a stale
        // diff snapshot from workdir A could render under workdir B when
        // host+session happen to coincide.
        //
        // §B1-fix: workdir MUST be restored alongside sessionId after forHost.
        // On first visit savedStateHandle[*] are all null, so forHost(activeHost)
        // sees hostId(null) != activeHost and resets the ENTIRE state (including
        // the workdir just set above) — copying back ONLY sessionId left workdir
        // null, so matches(activeHost, activeSession, workdir=actual) was false
        // and visibleDiffs was always empty (the session-diff deep link landed
        // on an empty Changes list). The fix restores workdir too. Cross-host
        // isolation is unaffected: a REAL host switch (hostId non-null and !=
        // activeHost) still clears via forHost + HostProfileController's purge.
        state = resolveWorkspaceState(
            savedHost = savedStateHandle[STATE_HOST],
            savedSession = savedStateHandle[STATE_SESSION],
            savedFile = savedStateHandle[STATE_FILE],
            workdir = workdir,
            activeHost = activeHost,
            activeSession = activeSession,
        )
        savedStateHandle[STATE_HOST] = state.hostId
        savedStateHandle[STATE_SESSION] = state.sessionId
        savedStateHandle[STATE_FILE] = state.selectedFile
    }
    val visibleDiffs = computeVisibleDiffs(
        savedHost = state.hostId,
        savedSession = state.sessionId,
        savedFile = state.selectedFile,
        workdir = workdir,
        activeHost = activeHost,
        activeSession = activeSession,
        sessionDiffs = sessions.sessionDiffs,
    )

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Workspace") })
        PrimaryTabRow(selectedTabIndex = if (initialTab == WorkspaceTab.Files) 0 else 1) {
            Tab(selected = initialTab == WorkspaceTab.Files, onClick = onOpenFiles, text = { Text("Files") })
            Tab(selected = initialTab == WorkspaceTab.Changes, onClick = { onOpenChanges(activeSession) }, text = { Text("Changes") })
        }
        when (initialTab) {
            // §phase2: forward initialPath to FilesPane so a chat file-path
            // tap locates the file (FilesPane.pathToShow, was always-null
            // before this fix — fix-6 dropped the path on the floor).
            WorkspaceTab.Files -> FilesPane(filesVM, workdir, pathToShow = initialPath)
            WorkspaceTab.Changes -> ChangesPane(
                diffs = visibleDiffs,
                repository = filesVM.repository,
                workdir = workdir,
                selectedFile = state.selectedFile,
                onSelectFile = { file ->
                    state = state.copy(selectedFile = file)
                    savedStateHandle[STATE_FILE] = file
                },
            )
        }
    }
}

enum class WorkspaceTab { Files, Changes }
