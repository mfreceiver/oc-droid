package cn.vectory.ocdroid.ui.workspace

import androidx.compose.ui.graphics.Color
import cn.vectory.ocdroid.data.model.VcsInfo
import cn.vectory.ocdroid.data.model.VcsStatusEntry
import cn.vectory.ocdroid.ui.theme.SemanticColors

/**
 * §round-B ① (D.7b): pure helpers backing the Workspace → Changes pane's
 * VCS surface. Extracted from the (now-deleted) SettingsScreen VcsSection
 * so the Workspace owns VCS presentation (scheme: "Workspace → Changes
 * owns diff + VCS; chat card is a deep link").
 *
 * The Composable-heavy [ChangesPane] body lives in `ChangesPaneKt` (which
 * is excluded from kover coverage as a @Composable-only file — same
 * pattern as SessionsScreenKt); the testable logic stays here.
 */

/**
 * Load state for the "Working tree" segment of [ChangesPane]. Mirrors the
 * old Settings VcsSection states (Loading / NoWorkdir / NoGit / Error /
 * Loaded) so the Workspace surface preserves every first-class VCS
 * presentation the Settings panel used to render. See scheme D.7b.
 */
internal sealed interface VcsLoadState {
    data object Loading : VcsLoadState
    data object NoWorkdir : VcsLoadState
    data object NoGit : VcsLoadState
    data class Error(val message: String) : VcsLoadState
    data class Loaded(
        val info: VcsInfo,
        val status: List<VcsStatusEntry>,
    ) : VcsLoadState
}

/**
 * The two explicitly-separated views of [ChangesPane] (scheme D.7b +
 * oracle Round-B ①: the previous `ifEmpty { vcsDiffs }` implicit fallback
 * broke session-scoping semantics by surfacing workdir-wide VCS status
 * under the session-diff label). Each segment owns its own data + empty
 * state; the user toggles between them.
 */
enum class ChangesPaneMode(val label: String) {
    SESSION("Session"),
    WORKING_TREE("Working tree"),
}

/**
 * Reduces (info, status) into a [VcsLoadState]. Pure — extracted so the
 * "both-null/empty ⇒ NoGit" interpretation (the v1 `/vcs` endpoint returns
 * 200 with null fields when the workdir is not a git repo) is unit-
 * testable. Mirrors the rule the deleted SettingsScreen VcsSection used.
 */
internal fun reduceVcsLoadState(
    workdir: String?,
    info: VcsInfo?,
    status: List<VcsStatusEntry>?,
    errorMessage: String? = null,
): VcsLoadState {
    if (workdir.isNullOrBlank()) return VcsLoadState.NoWorkdir
    errorMessage?.let { return VcsLoadState.Error(it) }
    if (info == null) return VcsLoadState.Loading
    val rows = status.orEmpty()
    val noBranch = info.branch.isNullOrBlank() && info.defaultBranch.isNullOrBlank()
    if (noBranch && rows.isEmpty()) return VcsLoadState.NoGit
    return VcsLoadState.Loaded(info, rows)
}

/**
 * File-status → semantic color. Mirrors the mapping that lived in the
 * deleted SettingsScreen vcsStatusColor (which in turn mirrored
 * SessionDiffCard.statusColor) so the Workspace Changes list uses the same
 * visual language as the chat SessionDiffCard. Local because that helper
 * is private to the chat package.
 *
 * Matched case-insensitively so "Modified"/"modified"/"MODIFIED" all
 * resolve to the same tone.
 */
internal fun vcsStatusColor(status: String?): Color = when (status?.lowercase()) {
    "added" -> SemanticColors.addedFile
    "deleted" -> SemanticColors.deletedFile
    "modified" -> SemanticColors.modifiedFile
    else -> SemanticColors.untrackedFile
}

/**
 * Caps the eagerly-composed changed-file rows so a repo with many
 * untracked / changed files doesn't compose/layout a huge list inside a
 * scrollable parent. The remainder is summarised as "+N more" by the
 * caller (scheme D.7b + matches the old Settings VcsLoadedBody cap).
 */
internal const val VCS_MAX_ROWS: Int = 50
