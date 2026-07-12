package cn.vectory.ocdroid.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * §B3·P1 (glmer gate fix): unit tests for [DiffLineKind.from] — the pure
 * prefix-classifier that drives the whole [UnifiedDiffRenderer] colorization.
 *
 * The regression-prone invariant is **prefix order**: the 3-char file-header
 * prefix (`+++` / `---`) MUST be matched before the 1-char `+` / `-`, or
 * every file header gets misread as an added/deleted line and the whole
 * patch renders with the wrong color bands. These tests pin that order, plus
 * the hunk-header (`@@`) / context fall-through.
 *
 * Pure (no Compose) — runs under `testDebugUnitTest` (in `./scripts/check.sh`).
 * [DiffLineKind] is `internal` and [DiffLineKind.from] lives in its companion,
 * both reachable from this same-module test.
 */
class UnifiedDiffRendererTest {

    // ── FILE_HEADER (the regression-critical case: 3-char prefix must win
    //    over the 1-char +/- check). ──────────────────────────────────────

    @Test
    fun `three-plus file header is NOT misread as an added line`() {
        // If the 1-char '+' check ran first, "+++" would return ADDED.
        assertEquals(DiffLineKind.FILE_HEADER, DiffLineKind.from("+++"))
    }

    @Test
    fun `three-minus file header is NOT misread as a deleted line`() {
        // If the 1-char '-' check ran first, "---" would return DELETED.
        assertEquals(DiffLineKind.FILE_HEADER, DiffLineKind.from("---"))
    }

    @Test
    fun `file header with path suffix stays FILE_HEADER`() {
        assertEquals(DiffLineKind.FILE_HEADER, DiffLineKind.from("+++ b/src/Main.kt"))
        assertEquals(DiffLineKind.FILE_HEADER, DiffLineKind.from("--- a/src/Main.kt"))
        assertEquals(DiffLineKind.FILE_HEADER, DiffLineKind.from("+++ /dev/null"))
    }

    // ── HUNK ─────────────────────────────────────────────────────────────

    @Test
    fun `double-at hunk header classifies as HUNK`() {
        assertEquals(DiffLineKind.HUNK, DiffLineKind.from("@@ -1,5 +1,7 @@"))
    }

    @Test
    fun `triple-at merged hunk header classifies as HUNK`() {
        // `@@@` starts with `@@` → HUNK (the startsWith("@@") check covers it).
        assertEquals(DiffLineKind.HUNK, DiffLineKind.from("@@@ -1,3 +1,4 @@@"))
    }

    @Test
    fun `bare double-at still HUNK even without trailing at-signs`() {
        assertEquals(DiffLineKind.HUNK, DiffLineKind.from("@@ -10,2 +10,2"))
    }

    // ── ADDED / DELETED (1-char prefix) ──────────────────────────────────

    @Test
    fun `single-plus line classifies as ADDED`() {
        assertEquals(DiffLineKind.ADDED, DiffLineKind.from("+x"))
        assertEquals(DiffLineKind.ADDED, DiffLineKind.from("+added line"))
        assertEquals(DiffLineKind.ADDED, DiffLineKind.from("+"))
    }

    @Test
    fun `single-minus line classifies as DELETED`() {
        assertEquals(DiffLineKind.DELETED, DiffLineKind.from("-x"))
        assertEquals(DiffLineKind.DELETED, DiffLineKind.from("-removed line"))
        assertEquals(DiffLineKind.DELETED, DiffLineKind.from("-"))
    }

    // ── CONTEXT (fall-through) ───────────────────────────────────────────

    @Test
    fun `space-prefixed context line classifies as CONTEXT`() {
        assertEquals(DiffLineKind.CONTEXT, DiffLineKind.from(" context line"))
        assertEquals(DiffLineKind.CONTEXT, DiffLineKind.from(" "))
    }

    @Test
    fun `plain text without diff prefix classifies as CONTEXT`() {
        assertEquals(DiffLineKind.CONTEXT, DiffLineKind.from("x"))
        assertEquals(DiffLineKind.CONTEXT, DiffLineKind.from("just text"))
    }

    @Test
    fun `empty string classifies as CONTEXT`() {
        // No prefix matches → CONTEXT fall-through (matches renderer impl:
        // an empty line in a patch is a blank context line).
        assertEquals(DiffLineKind.CONTEXT, DiffLineKind.from(""))
    }

    @Test
    fun `backslash no-newline marker classifies as CONTEXT`() {
        // The git "No newline at end of file" marker starts with `\ `, which
        // has no +/- / @@ prefix → CONTEXT (kept un-tinted, as a diff viewer
        // should — it's metadata, not an added/deleted line).
        assertEquals(DiffLineKind.CONTEXT, DiffLineKind.from("\\ No newline at end of file"))
    }

    // ── Prefix-order regression pin ──────────────────────────────────────

    @Test
    fun `prefix order is hunk then file-header then added then deleted`() {
        // Pin the full classification table in one place so a future
        // re-ordering of the `when` branches is caught loudly.
        assertEquals(DiffLineKind.HUNK, DiffLineKind.from("@@ -1,1 +1,1 @@"))
        assertEquals(DiffLineKind.FILE_HEADER, DiffLineKind.from("+++ b/x"))
        assertEquals(DiffLineKind.FILE_HEADER, DiffLineKind.from("--- a/x"))
        assertEquals(DiffLineKind.ADDED, DiffLineKind.from("+new"))
        assertEquals(DiffLineKind.DELETED, DiffLineKind.from("-old"))
        assertEquals(DiffLineKind.CONTEXT, DiffLineKind.from(" same"))
    }
}
