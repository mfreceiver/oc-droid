package cn.vectory.ocdroid.ui.workspace

import cn.vectory.ocdroid.data.model.VcsInfo
import cn.vectory.ocdroid.data.model.VcsStatusEntry
import cn.vectory.ocdroid.ui.theme.SemanticColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §round-B ① (scheme D.7b): unit tests for the pure helpers backing the
 * Workspace → Changes pane's VCS segment.
 *
 * - [reduceVcsLoadState] pins the "both-null/empty ⇒ NoGit" interpretation
 *   (the v1 `/vcs` endpoint returns 200 with null fields when the workdir
 *   is not a git repo) and the explicit first-class states (NoWorkdir /
 *   Loading / Error / Loaded). The previous shell hard-coded these inside
 *   the dead SettingsScreen VcsSection; extracting them as a pure fn is
 *   what makes the migration testable.
 * - [vcsStatusColor] pins the file-status → semantic-color mapping
 *   (added / deleted / modified / untracked) so the Workspace Changes
 *   list uses the same visual language as the chat SessionDiffCard.
 */
class WorkspaceVcsHelpersTest {

    // ── reduceVcsLoadState ──────────────────────────────────────────────

    @Test
    fun `null or blank workdir yields NoWorkdir`() {
        assertEquals(VcsLoadState.NoWorkdir, reduceVcsLoadState(null, null, null))
        assertEquals(VcsLoadState.NoWorkdir, reduceVcsLoadState("", null, null))
        assertEquals(VcsLoadState.NoWorkdir, reduceVcsLoadState("   ", null, null))
    }

    @Test
    fun `non-null errorMessage short-circuits to Error even with a workdir`() {
        val state = reduceVcsLoadState("/proj", info = null, status = null, errorMessage = "HTTP 500")
        assertTrue(state is VcsLoadState.Error)
        assertEquals("HTTP 500", (state as VcsLoadState.Error).message)
    }

    @Test
    fun `NoWorkdir wins over errorMessage`() {
        // NoWorkdir is checked first — a missing workdir is the most-decided
        // state (no fetch is even possible); an errorMessage alongside it
        // would be misleading.
        assertEquals(
            VcsLoadState.NoWorkdir,
            reduceVcsLoadState(null, info = null, status = null, errorMessage = "boom"),
        )
    }

    @Test
    fun `null info with a workdir yields Loading`() {
        assertEquals(VcsLoadState.Loading, reduceVcsLoadState("/proj", info = null, status = null))
    }

    @Test
    fun `null branch AND empty status yields NoGit`() {
        // The v1 /vcs endpoint returns 200 with null fields when the workdir
        // is not a git repo — pin that interpretation.
        val state = reduceVcsLoadState(
            "/proj",
            info = VcsInfo(branch = null, defaultBranch = null),
            status = emptyList(),
        )
        assertEquals(VcsLoadState.NoGit, state)
    }

    @Test
    fun `blank-only branch + empty status also yields NoGit`() {
        // Defensive: server may serialize null branch as an empty string.
        val state = reduceVcsLoadState(
            "/proj",
            info = VcsInfo(branch = "   ", defaultBranch = "  "),
            status = emptyList(),
        )
        assertEquals(VcsLoadState.NoGit, state)
    }

    @Test
    fun `branch present with empty status yields Loaded`() {
        val info = VcsInfo(branch = "main", defaultBranch = null)
        val state = reduceVcsLoadState("/proj", info = info, status = emptyList())
        assertTrue(state is VcsLoadState.Loaded)
        val loaded = state as VcsLoadState.Loaded
        assertEquals(info, loaded.info)
        assertTrue(loaded.status.isEmpty())
    }

    @Test
    fun `default-branch present alone (no current branch) still yields Loaded`() {
        // Some repos (fresh clone before first checkout metadata) report
        // default-branch but no current branch — that is a real git repo,
        // NOT a NoGit state.
        val info = VcsInfo(branch = null, defaultBranch = "main")
        val state = reduceVcsLoadState("/proj", info = info, status = emptyList())
        assertTrue(state is VcsLoadState.Loaded)
    }

    @Test
    fun `status rows present yields Loaded`() {
        val info = VcsInfo(branch = null, defaultBranch = null)
        val rows = listOf(VcsStatusEntry(file = "a.txt", additions = 1, deletions = 0, status = "added"))
        val state = reduceVcsLoadState("/proj", info = info, status = rows)
        assertTrue(state is VcsLoadState.Loaded)
        assertEquals(rows, (state as VcsLoadState.Loaded).status)
    }

    @Test
    fun `null status is treated as empty for the NoGit decision`() {
        // Defensive: API client may pass null status alongside an info
        // object — the reduce must not NPE; it interprets null status as
        // empty so the NoGit vs Loaded decision is consistent.
        val info = VcsInfo(branch = "main", defaultBranch = null)
        val state = reduceVcsLoadState("/proj", info = info, status = null)
        assertTrue(state is VcsLoadState.Loaded)
    }

    // ── vcsStatusColor ──────────────────────────────────────────────────

    @Test
    fun `added status maps to addedFile tone`() {
        assertEquals(SemanticColors.addedFile, vcsStatusColor("added"))
    }

    @Test
    fun `deleted status maps to deletedFile tone`() {
        assertEquals(SemanticColors.deletedFile, vcsStatusColor("deleted"))
    }

    @Test
    fun `modified status maps to modifiedFile tone`() {
        assertEquals(SemanticColors.modifiedFile, vcsStatusColor("modified"))
    }

    @Test
    fun `status match is case-insensitive`() {
        // Server may send "Modified" / "MODIFIED" / etc.
        assertEquals(SemanticColors.modifiedFile, vcsStatusColor("Modified"))
        assertEquals(SemanticColors.modifiedFile, vcsStatusColor("MODIFIED"))
        assertEquals(SemanticColors.addedFile, vcsStatusColor("ADDED"))
    }

    @Test
    fun `unknown, untracked or null status falls back to untrackedFile`() {
        assertEquals(SemanticColors.untrackedFile, vcsStatusColor("renamed"))
        assertEquals(SemanticColors.untrackedFile, vcsStatusColor("untracked"))
        assertEquals(SemanticColors.untrackedFile, vcsStatusColor(null))
        assertEquals(SemanticColors.untrackedFile, vcsStatusColor(""))
    }

    // ── vcsStatusGroup (§B3·P1 WORKING_TREE grouping) ──────────────────

    @Test
    fun `known statuses map to their canonical group`() {
        assertEquals(VcsStatusGroup.MODIFIED, vcsStatusGroup("modified"))
        assertEquals(VcsStatusGroup.ADDED, vcsStatusGroup("added"))
        assertEquals(VcsStatusGroup.DELETED, vcsStatusGroup("deleted"))
        assertEquals(VcsStatusGroup.RENAMED, vcsStatusGroup("renamed"))
    }

    @Test
    fun `group mapping is case-insensitive`() {
        assertEquals(VcsStatusGroup.MODIFIED, vcsStatusGroup("Modified"))
        assertEquals(VcsStatusGroup.ADDED, vcsStatusGroup("ADDED"))
        assertEquals(VcsStatusGroup.DELETED, vcsStatusGroup("Deleted"))
    }

    @Test
    fun `unknown statuses collapse to OTHER group`() {
        assertEquals(VcsStatusGroup.OTHER, vcsStatusGroup("untracked"))
        assertEquals(VcsStatusGroup.OTHER, vcsStatusGroup("copied"))
        assertEquals(VcsStatusGroup.OTHER, vcsStatusGroup("typechange"))
        assertEquals(VcsStatusGroup.OTHER, vcsStatusGroup(""))
    }

    @Test
    fun `group ordinal pins the Modified-Added-Deleted-Renamed-Other section order`() {
        // §B3·P1 decision 7: the WORKING_TREE list groups in exactly this order.
        assertTrue(VcsStatusGroup.MODIFIED.ordinal < VcsStatusGroup.ADDED.ordinal)
        assertTrue(VcsStatusGroup.ADDED.ordinal < VcsStatusGroup.DELETED.ordinal)
        assertTrue(VcsStatusGroup.DELETED.ordinal < VcsStatusGroup.RENAMED.ordinal)
        assertTrue(VcsStatusGroup.RENAMED.ordinal < VcsStatusGroup.OTHER.ordinal)
    }
}
