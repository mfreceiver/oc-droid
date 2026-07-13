package cn.vectory.ocdroid.ui.workspace

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.FileDiff
import cn.vectory.ocdroid.data.model.VcsInfo
import cn.vectory.ocdroid.data.model.VcsStatusEntry
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.ui.theme.AppBottomSheet
import cn.vectory.ocdroid.ui.theme.Dimens

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
 *    file via /vcs/diff?mode=git on tap (working-tree-vs-HEAD view).
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
        // §4.1: 顶部两选项从胶囊式 SegmentedButton 改为 M3 下划线式 TabRow，
        // 与项目其它顶部导航（BottomBar / SectionHeader）的视觉语言一致；full-
        // bleed 条 + 紧跟 HorizontalDivider，是项目主流的 section 切换形态。
        // 复用既有 ChangesPaneMode 枚举与 label，不引入新字符串。
        TabRow(
            selectedTabIndex = mode.ordinal,
        ) {
            ChangesPaneMode.entries.forEach { entry ->
                Tab(
                    selected = mode == entry,
                    onClick = { mode = entry },
                    text = { Text(entry.label) },
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
        // §WT4: SESSION tab keeps the FLAT list (no grouping / headers /
        // dividers / cap — session diffs are usually small). Only the row
        // renderer was unified with WORKING_TREE via the shared [VcsFileRow].
        // Adapter: FileDiff carries the full path in `diff.file`, nullable
        // status, and nullable counts; extract basename + parent-dir + null-
        // safe counts at the call site so VcsFileRow stays model-agnostic.
        diffs.forEach { diff ->
            val path = diff.file
            VcsFileRow(
                basename = path.substringAfterLast('/').ifEmpty { path },
                parentDir = path.substringBeforeLast("/", "").ifEmpty { "·" },
                status = diff.status,
                additions = diff.additions ?: 0,
                deletions = diff.deletions ?: 0,
                onClick = { onSelectFile(diff.file) },
            )
        }
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
        // §B3·P1: Branch header block — Git icon + branch name (bodyLarge) +
        // muted default-branch suffix on the SAME row (previously the label
        // "分支" sat on its own line and the value on the next, which read
        // as disjoint). 16dp horizontal / 12dp vertical padding matches the
        // M3 list rhythm. Missing branch → explicit "未检测到分支" instead of
        // an isolated em-dash.
        BranchHeader(info = info)

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

        // §B3·P1: Changed-files section header — labelLarge + total count
        // ("Changed files · N"). Replaces the previous bare "Changed files"
        // labelMedium.
        Text(
            text = stringResource(R.string.vcs_changed_files_with_count, status.size),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        // §round-B ①: cap rows so a repo with many untracked / changed files
        // doesn't compose/layout a huge list inside the verticalScroll.
        // §B3·P1: render the capped slice grouped by status (Modified /
        // Added / Deleted / Renamed / Other) with inset dividers between
        // rows inside each group.
        val capped = status.take(VCS_MAX_ROWS)
        GroupedVcsStatusList(entries = capped, onSelectFile = onSelectFile)

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
    // segment fetches the actual unified patch via /vcs/diff?mode=git
    // (the working-tree-vs-HEAD view; the VcsStatusEntry itself carries no
    // patch — only counts + status name). Fetched lazily inside the sheet
    // so the list never blocks on N parallel diff fetches.
    selected?.let { entry ->
        VcsDiffSheet(
            repository = repository,
            workdir = workdir,
            file = entry.file,
            onDismiss = { onSelectFile(null) },
        )
    }
}

/**
 * §B3·P1 Branch header — single-row Git branch presentation.
 *
 * `Icons.Default.AccountTree` (same vector the Git nav tab uses) + branch
 * name in bodyLarge + muted default-branch suffix (labelMedium,
 * onSurfaceVariant) when present. Missing branch renders an explicit
 * "未检测到分支" instead of an isolated "—" so the row never looks broken.
 */
@Composable
private fun BranchHeader(info: VcsInfo) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.AccountTree,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(Dimens.iconSm),
        )
        Spacer(Modifier.width(8.dp))
        val branch = info.branch
        if (branch.isNullOrBlank()) {
            Text(
                text = stringResource(R.string.vcs_no_branch_detected),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = branch,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            info.defaultBranch?.takeIf { it.isNotBlank() }?.let { default ->
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.settings_vcs_default_branch_suffix, default),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * §B3·P1 — renders the changed-file list grouped by [vcsStatusGroup]
 * (Modified → Added → Deleted → Renamed → Other, order pinned by
 * [VcsStatusGroup.ordinal]). Each group has a labelLarge + onSurfaceVariant
 * header; rows inside a group are separated by an inset HorizontalDivider
 * (start-aligned with the ListItem text at 16dp) so the eye can scan
 * individual files. No divider between a group's last row and the next
 * group header (the header is itself the separator).
 */
@Composable
private fun GroupedVcsStatusList(
    entries: List<VcsStatusEntry>,
    onSelectFile: (String?) -> Unit,
) {
    val groups = remember(entries) {
        entries
            .groupBy { vcsStatusGroup(it.status) }
            .toList()
            .sortedBy { (group, _) -> group.ordinal }
    }
    groups.forEach { (group, rows) ->
        Text(
            text = vcsGroupLabel(group),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        rows.forEachIndexed { index, entry ->
            // §WT4: WORKING_TREE keeps its grouping / section headers /
            // inset dividers / 50-row cap — only the row renderer was
            // unified with SESSION via the shared [VcsFileRow]. Adapter:
            // VcsStatusEntry has non-null status + non-null counts, and
            // its parent-dir treatment (placeholder "·" for repo-root
            // files) is what VcsFileRow's supporting line was ratified on.
            VcsFileRow(
                basename = entry.file.substringAfterLast("/").ifEmpty { entry.file },
                parentDir = entry.file.substringBeforeLast("/", "").ifEmpty { "·" },
                status = entry.status,
                additions = entry.additions,
                deletions = entry.deletions,
                onClick = { onSelectFile(entry.file) },
            )
            if (index < rows.lastIndex) {
                // Inset divider — start-aligned with the row's text (16dp),
                // full bleed to the right edge. This is what was missing in
                // the original flat list (rows bled into each other).
                HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
            }
        }
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
        // GET /vcs/diff?mode=git (no `directory` query) → HTTP 400. Bail into
        // an error state instead of issuing the request. In the normal flow
        // workdir is non-blank here (the working-tree status list only loads
        // when workdir is present), so reaching this branch means the caller
        // really had no workdir to bind — the 400 is then a client-side
        // contract violation, not a server issue.
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
            // §WT4 (mode literal fix): the request this produces is
            //   GET /vcs/diff?mode=git&directory=<workdir>
            // (Retrofit URL-encodes the directory query value). The opencode
            // server's `vcs.ts` defines `Mode = Schema.Literals(["git","branch"])`,
            // so the old `"working_tree"` value was rejected by the schema
            // decoder → HTTP 400 on every file tap. `"git"` is the semantically-
            // correct value for the working-tree-vs-HEAD diff view this sheet
            // shows. (Verified: this is the ONLY call site sending a mode literal
            // — grep `working_tree` / `getVcsDiff` returns no other producers.)
            val diffs = repository.getVcsDiff("git", workdir).getOrThrow()
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
    // §WT4: migrated from a raw ModalBottomSheet + hand-rolled title to the
    // shared [AppBottomSheet] recipe (SheetRecipe.kt) so the container color,
    // skipPartiallyExpanded state, title typography (titleLarge) and bottom
    // inset match every other sheet in the app.
    //
    // title = file basename (the meaningful identity); the full path is kept
    // as a muted bodySmall line inside content for context (the original sheet
    // had both lines — basename as title, path as subtitle — and AppBottomSheet
    // renders title in titleLarge, so the basename stays prominent and the
    // path secondary, matching the old visual hierarchy).
    //
    // ⚠️ Double-padding pitfall (SheetRecipe §inset-note / 双重 padding 警示):
    // AppBottomSheet already adds a bottom `Spacer(16.dp)` for the safe-area
    // inset. The old sheet wrapped content in `.padding(16.dp)` (all sides).
    // We must NOT keep an outer bottom padding — only horizontal padding for
    // the content's left/right inset. The bottom 12dp Spacer between subtitle
    // and body is content-rhythm (kept); the bottom safe-area is the scaffold.
    AppBottomSheet(
        onDismissRequest = onDismiss,
        title = file.substringAfterLast('/').ifEmpty { file },
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
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
                // §B3·P1: switched the patch body from a single Text +
                // FontFamily.Monospace inside a verticalScroll to the shared
                // UnifiedDiffRenderer — line-level semantic coloring
                // (+/-/hunk/header), LazyColumn row composition, and
                // horizontal scroll for long code lines (no soft-wrap).
                else -> UnifiedDiffRenderer(
                    patch = patch,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp),
                )
            }
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
