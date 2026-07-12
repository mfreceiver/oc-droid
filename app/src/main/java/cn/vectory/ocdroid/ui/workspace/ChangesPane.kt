package cn.vectory.ocdroid.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.FileDiff
import cn.vectory.ocdroid.data.model.VcsInfo
import cn.vectory.ocdroid.data.model.VcsStatusEntry
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.ui.theme.BundledMonoFamily
import cn.vectory.ocdroid.ui.theme.SemanticColors

/**
 * §round-B ① (scheme D.7b): Git → Changes pane.
 *
 * Two EXPLICITLY-separated views (no `ifEmpty { fallback }` — the previous
 * implicit fallback broke session-scoping semantics by surfacing workdir-
 * wide VCS status under the session-diff label):
 *  - [ChangesPaneMode.SESSION]: per-session diffs (the `diffs` param —
 *    usually chat /session/{id}/diff output). File tap shows the patch
 *    already present on the FileDiff (or fetches unified diff lazily).
 *  - [ChangesPaneMode.WORKING_TREE]: workdir-wide VCS status + branch
 *    info from /vcs + /vcs/status, with the unified patch fetched per-
 *    file via /vcs/diff?mode=working_tree on tap.
 *
 * Each segment owns its own empty / loading / error state.
 *
 * VCS presentation (branch / default-branch / not-a-git / loading / error
 * / status color + name / 50-row cap + "+N more") lives here now; the
 * dead helper that used to render it in SettingsScreen was deleted.
 *
 * The diff ModalBottomSheet shows the unified patch compactly (no
 * side-by-side) inside a vertically-scrolling area — same shape as
 * scheme D.7b.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangesPane(
    diffs: List<FileDiff>,
    repository: OpenCodeRepository,
    workdir: String?,
    selectedFile: String?,
    onSelectFile: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var mode by rememberSaveable { mutableStateOf(ChangesPaneMode.SESSION) }

    // ── Working-tree state (loaded lazily when the segment is first shown
    // — avoids fetching VCS data the user never looks at; also keeps the
    // session segment's parity contract intact: no workdir-wide network
    // fetch fires when the user only opens Changes for the session view).
    var vcsState by remember(workdir) { mutableStateOf<VcsLoadState>(VcsLoadState.Loading) }
    LaunchedEffect(mode, workdir) {
        if (mode != ChangesPaneMode.WORKING_TREE) return@LaunchedEffect
        val dir = workdir
        if (dir.isNullOrBlank()) {
            vcsState = VcsLoadState.NoWorkdir
            return@LaunchedEffect
        }
        vcsState = try {
            val info = repository.getVcs(dir).getOrThrow()
            // §B1-fix④ (gpter 🟠): getOrThrow (NOT getOrDefault) so a status
            // fetch failure propagates to the catch → VcsLoadState.Error. The
            // previous getOrDefault(emptyList()) swallowed the error and showed
            // "No changes" / "Not a git repo" (depending on branch presence)
            // instead of the Error state — the Error branch was unreachable.
            val status = repository.getVcsStatus(dir).getOrThrow()
            reduceVcsLoadState(dir, info, status)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            VcsLoadState.Error(e.message ?: "unknown")
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            ChangesPaneMode.entries.forEachIndexed { index, entry ->
                SegmentedButton(
                    selected = mode == entry,
                    onClick = { mode = entry },
                    shape = SegmentedButtonDefaults.itemShape(index, ChangesPaneMode.entries.size),
                    label = { Text(entry.label) },
                )
            }
        }
        HorizontalDivider()

        when (mode) {
            ChangesPaneMode.SESSION -> SessionDiffSegment(
                diffs = diffs,
                selectedFile = selectedFile,
                repository = repository,
                workdir = workdir,
                onSelectFile = onSelectFile,
            )
            ChangesPaneMode.WORKING_TREE -> WorkingTreeSegment(
                state = vcsState,
                workdir = workdir,
                repository = repository,
                selectedFile = selectedFile,
                onSelectFile = onSelectFile,
            )
        }
    }
}

@Composable
private fun SessionDiffSegment(
    diffs: List<FileDiff>,
    selectedFile: String?,
    repository: OpenCodeRepository,
    workdir: String?,
    onSelectFile: (String?) -> Unit,
) {
    val selected = diffs.firstOrNull { it.file == selectedFile }
    if (diffs.isEmpty()) {
        EmptyRow(text = stringResource(R.string.settings_vcs_no_changes))
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        diffs.forEach { diff -> FileDiffRow(diff = diff, onClick = { onSelectFile(diff.file) }) }
    }
    selected?.let { show ->
        DiffBottomSheet(
            file = show.file,
            patch = show.patch,
            onDismiss = { onSelectFile(null) },
        )
    }
    // Suppress unused-warning for repository/workdir — they're threaded for
    // the future "pull patch lazily when FileDiff.patch is null" path (the
    // session-diff endpoint already returns patches; the working-tree
    // segment uses repository directly). Kept on the signature so both
    // segments share the same call-site contract.
    @Suppress("UNUSED_PARAMETER") fun unusedAnchor(r: OpenCodeRepository, w: String?) = Unit
    unusedAnchor(repository, workdir)
}

@Composable
private fun WorkingTreeSegment(
    state: VcsLoadState,
    workdir: String?,
    repository: OpenCodeRepository,
    selectedFile: String?,
    onSelectFile: (String?) -> Unit,
) {
    when (state) {
        is VcsLoadState.Loading -> Row(
            modifier = Modifier.padding(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = workdir.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        is VcsLoadState.NoWorkdir -> EmptyRow(
            text = stringResource(R.string.settings_vcs_no_working_directory),
        )

        is VcsLoadState.NoGit -> Column(Modifier.padding(24.dp)) {
            Text(
                text = workdir.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.settings_vcs_not_a_git_repo),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        is VcsLoadState.Error -> Column(Modifier.padding(24.dp)) {
            Text(
                text = workdir.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.settings_vcs_load_error, state.message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        is VcsLoadState.Loaded -> WorkingTreeLoadedBody(
            info = state.info,
            status = state.status,
            repository = repository,
            workdir = workdir,
            selectedFile = selectedFile,
            onSelectFile = onSelectFile,
        )
    }
}

@Composable
private fun WorkingTreeLoadedBody(
    info: VcsInfo,
    status: List<VcsStatusEntry>,
    repository: OpenCodeRepository,
    workdir: String?,
    selectedFile: String?,
    onSelectFile: (String?) -> Unit,
) {
    val selected = status.firstOrNull { it.file == selectedFile }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // Branch line: branch + dim "(default <defaultBranch>)" suffix when present.
        Text(
            text = stringResource(R.string.settings_vcs_branch),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = info.branch ?: "—",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            info.defaultBranch?.takeIf { it.isNotBlank() }?.let { default ->
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.settings_vcs_default_branch_suffix, default),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (status.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.settings_vcs_no_changes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            return@Column
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.settings_vcs_changed_files),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        // §round-B ①: cap rows so a repo with many untracked / changed files
        // doesn't compose/layout a huge list inside the verticalScroll.
        status.take(VCS_MAX_ROWS).forEach { entry ->
            VcsStatusRow(entry = entry, onClick = { onSelectFile(entry.file) })
        }
        if (status.size > VCS_MAX_ROWS) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.settings_vcs_more_files, status.size - VCS_MAX_ROWS),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }

    // §round-B ① unified-diff fetch: tapping a file in the Working-tree
    // segment fetches the actual unified patch via /vcs/diff?mode=
    // working_tree (the VcsStatusEntry itself carries no patch — only
    // counts + status name). Fetched lazily inside the sheet so the list
    // never blocks on N parallel diff fetches.
    selected?.let { entry ->
        VcsDiffSheet(
            repository = repository,
            workdir = workdir,
            file = entry.file,
            onDismiss = { onSelectFile(null) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VcsDiffSheet(
    repository: OpenCodeRepository,
    workdir: String?,
    file: String,
    onDismiss: () -> Unit,
) {
    var patchState by remember(file, workdir) {
        mutableStateOf<DiffPatchState>(DiffPatchState.Loading)
    }
    // §4.6 (P4b-B): resolve the no-workdir message at composition scope so the
    // LaunchedEffect below can capture it without calling stringResource inside
    // the coroutine body.
    val noWorkdirMessage = stringResource(R.string.files_workdir_none)
    LaunchedEffect(file, workdir) {
        // §4.6 (P4b-B) defensive guard: mirror the pane's own data-load guard
        // (ChangesPane LaunchedEffect ~L91-112). When workdir is null/blank,
        // GET /vcs/diff?mode=working_tree (no `directory` query) → HTTP 400.
        // Bail into an error state instead of issuing the request. NOTE: in
        // the normal flow workdir is non-blank here (the working-tree status
        // list only loads when workdir is present), so a 400 observed in
        // normal use points to a server-side param issue, not this guard —
        // runtime reproduction on the emulator is needed to confirm root cause.
        if (workdir.isNullOrBlank()) {
            patchState = DiffPatchState.Error(noWorkdirMessage)
            return@LaunchedEffect
        }
        patchState = try {
            // §B1-fix④ (gpter 🟠): getOrThrow (NOT getOrDefault) so a diff fetch
            // failure propagates to the catch → DiffPatchState.Error (shown as an
            // error message in the sheet). The previous getOrDefault(emptyList())
            // swallowed the error → no match → DiffPatchState.Loaded(null) → the
            // sheet showed "no unified diff" instead of the actual error.
            //
            // §4.6 (P4b-B): the request this produces is:
            //   GET /vcs/diff?mode=working_tree&directory=<workdir>
            // (Retrofit URL-encodes the directory query value). When workdir is
            // non-null but the server still 400's, the likely cause is a server-
            // side param mismatch (e.g. it expects a trailing slash, a different
            // mode spelling, or rejects URL-encoded slashes). Do NOT fix blind.
            val diffs = repository.getVcsDiff("working_tree", workdir).getOrThrow()
            val match = diffs.firstOrNull { it.file == file }
            if (match != null) DiffPatchState.Loaded(match.patch)
            else DiffPatchState.Loaded(null)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            DiffPatchState.Error(e.message ?: "unknown")
        }
    }
    DiffBottomSheet(
        file = file,
        loading = patchState is DiffPatchState.Loading,
        patch = (patchState as? DiffPatchState.Loaded)?.patch,
        errorMessage = (patchState as? DiffPatchState.Error)?.message,
        onDismiss = onDismiss,
    )
}

private sealed interface DiffPatchState {
    data object Loading : DiffPatchState
    data class Loaded(val patch: String?) : DiffPatchState
    data class Error(val message: String) : DiffPatchState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiffBottomSheet(
    file: String,
    patch: String?,
    loading: Boolean = false,
    errorMessage: String? = null,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = file.substringAfterLast('/').ifEmpty { file },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = file,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
            when {
                loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.common_loading),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                errorMessage != null -> Text(
                    text = stringResource(R.string.settings_vcs_load_error, errorMessage),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                patch.isNullOrBlank() -> Text(
                    text = stringResource(R.string.chat_no_unified_diff),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> Text(
                    text = patch,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}

@Composable
private fun FileDiffRow(diff: FileDiff, onClick: () -> Unit) {
    val statusColor = vcsStatusColor(diff.status)
    val path = diff.file
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(8.dp).background(color = statusColor, shape = CircleShape))
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = path.substringAfterLast('/').ifEmpty { path },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = path,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(6.dp))
        diff.status?.takeIf { it.isNotBlank() }?.let { status ->
            Text(
                text = status,
                style = MaterialTheme.typography.labelSmall,
                color = statusColor,
                modifier = Modifier.padding(end = 4.dp),
            )
        }
        Text(
            text = "+${diff.additions ?: 0} −${diff.deletions ?: 0}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = BundledMonoFamily,
        )
    }
}

@Composable
private fun VcsStatusRow(entry: VcsStatusEntry, onClick: () -> Unit) {
    val statusColor = vcsStatusColor(entry.status)
    val basename = entry.file.substringAfterLast("/").ifEmpty { entry.file }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(8.dp).background(color = statusColor, shape = CircleShape))
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = basename,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = entry.file,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = entry.status,
            style = MaterialTheme.typography.labelSmall,
            color = statusColor,
            modifier = Modifier.padding(end = 4.dp),
        )
        if (entry.additions > 0) {
            Text(
                text = "+${entry.additions}",
                style = MaterialTheme.typography.labelSmall,
                color = SemanticColors.stateSuccessFg(),
                fontFamily = BundledMonoFamily,
            )
        }
        if (entry.deletions > 0) {
            Spacer(Modifier.width(4.dp))
            Text(
                text = "-${entry.deletions}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                fontFamily = BundledMonoFamily,
            )
        }
    }
}

@Composable
private fun EmptyRow(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(24.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
    )
}
