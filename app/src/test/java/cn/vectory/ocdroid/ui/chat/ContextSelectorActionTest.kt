package cn.vectory.ocdroid.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * §round-B ② (scheme D.5): pins the workdir-selection contract for the
 * ContextSelectorSheet.
 *
 * The previous shell picked the FIRST session in the target directory
 * (arbitrary sibling) and only fell back to createSessionInWorkdir when
 * no session existed there. That broke session scoping — the user's
 * current conversation was silently swapped for an unrelated one in the
 * same workdir.
 *
 * Scheme D.5 contract (pinned here):
 *  - currentSession already in the target workdir → PRESERVE_CURRENT (no-op).
 *  - otherwise → MATERIALIZE_DRAFT (open a fresh scoped draft).
 */
class ContextSelectorActionTest {

    @Test
    fun `null current session always materializes a draft`() {
        assertEquals(
            WorkdirAction.MATERIALIZE_DRAFT,
            resolveWorkdirSelection(currentSessionDirectory = null, targetWorkdir = "/proj"),
        )
    }

    @Test
    fun `current session in target workdir is preserved`() {
        assertEquals(
            WorkdirAction.PRESERVE_CURRENT,
            resolveWorkdirSelection(currentSessionDirectory = "/proj", targetWorkdir = "/proj"),
        )
    }

    @Test
    fun `current session in a different workdir materializes a draft in the target`() {
        assertEquals(
            WorkdirAction.MATERIALIZE_DRAFT,
            resolveWorkdirSelection(currentSessionDirectory = "/proj-a", targetWorkdir = "/proj-b"),
        )
    }

    @Test
    fun `empty current directory does not match a non-empty target`() {
        // Defensive: an early-state Session may have an empty directory string
        // — normalization yields "" which never equals a non-empty target.
        assertEquals(
            WorkdirAction.MATERIALIZE_DRAFT,
            resolveWorkdirSelection(currentSessionDirectory = "", targetWorkdir = "/proj"),
        )
    }

    // ── §B1-fix⑤ (三评委共识): normalization-based comparison ───────────

    @Test
    fun `surrounding-slash variants are recognised as the same workdir`() {
        // Normalization (WorkdirPaths.normalize = trim + trim('/')) collapses
        // "/proj", "/proj/", "proj", " /proj " to the same key → PRESERVE.
        assertEquals(
            WorkdirAction.PRESERVE_CURRENT,
            resolveWorkdirSelection(currentSessionDirectory = "/proj", targetWorkdir = "/proj/"),
        )
        assertEquals(
            WorkdirAction.PRESERVE_CURRENT,
            resolveWorkdirSelection(currentSessionDirectory = "/proj/", targetWorkdir = "/proj"),
        )
        assertEquals(
            WorkdirAction.PRESERVE_CURRENT,
            resolveWorkdirSelection(currentSessionDirectory = "proj", targetWorkdir = "/proj/"),
        )
        assertEquals(
            WorkdirAction.PRESERVE_CURRENT,
            resolveWorkdirSelection(currentSessionDirectory = " /proj ", targetWorkdir = "/proj"),
        )
    }

    @Test
    fun `genuinely different workdirs still materialize a draft`() {
        assertEquals(
            WorkdirAction.MATERIALIZE_DRAFT,
            resolveWorkdirSelection(currentSessionDirectory = "/proj-a", targetWorkdir = "/proj-b"),
        )
        assertEquals(
            WorkdirAction.MATERIALIZE_DRAFT,
            resolveWorkdirSelection(currentSessionDirectory = "/proj-a", targetWorkdir = "/proj-a/sub"),
        )
    }
}
