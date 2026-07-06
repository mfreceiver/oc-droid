package cn.vectory.ocdroid.ui.chat

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Public
import androidx.compose.ui.graphics.vector.ImageVector
import cn.vectory.ocdroid.data.model.Part
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.TimeZone

/**
 * R-18 Phase 5+ coverage: pure (non-@Composable) helpers in `ui/chat/` that
 * the momo / gpter Gate-5 review flagged as falsely classified "untestable"
 * (S-1 blocker). All seven functions live in same-package `internal` scope
 * and are pure JVM-callable:
 *
 *  - [formatHm]                  (ChatRenderUtils.kt, ~6 LOC)
 *  - [String.wrappablePath]      (ChatRenderUtils.kt, ~2 LOC)
 *  - [formatElapsed]             (ThinkingCapsule.kt, ~4 LOC)
 *  - [toolIcon]                  (ChatToolCards.kt, ~20 LOC, returns ImageVector)
 *  - [countDiffLines]            (ChatToolCards.kt, ~15 LOC)
 *  - [contextToolLabel]          (ChatPatchCards.kt, ~10 LOC)
 *  - [parseTaskXml]              (ChatSubAgentCard.kt, ~13 LOC)
 *
 * `toolIcon` returns an [ImageVector] from `androidx.compose.material:material-icons-extended`.
 * That dependency is a pure-Kotlin object graph (no Android runtime required),
 * and the test runner is plain JUnit4 (no ComposeTestRule needed for non-
 * composable code), so all 7 functions are exercised here directly.
 *
 * parseSubAgentName / stripSubAgentSuffix are NOT covered here — they already
 * have a dedicated suite in `ChatSubAgentCardParseTest`.
 */
class ChatPureFunctionsTest {

    // formatHm uses the JVM default timezone for formatting. Pin it for the
    // duration of the suite so the expected HH:mm strings are deterministic.
    @Before
    fun fixTimezone() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    // ── formatHm (ChatRenderUtils.kt) ───────────────────────────────────────

    @Test
    fun `formatHm formats UTC midnight as 00_00`() {
        // 1970-01-01T00:00:00Z → "00:00" in UTC.
        assertEquals("00:00", formatHm(0L))
    }

    @Test
    fun `formatHm formats noon as 12_00`() {
        // 1970-01-01T12:00:00Z → "12:00".
        assertEquals("12:00", formatHm(12L * 60 * 60 * 1000))
    }

    @Test
    fun `formatHm formats 23_59 as last minute of day`() {
        assertEquals("23:59", formatHm((23L * 60 + 59) * 60 * 1000))
    }

    @Test
    fun `formatHm produces two-digit zero-padded shape`() {
        // Pin the format string itself: HH:mm always yields two digits each.
        val out = formatHm(5L * 60 * 1000)  // 00:05
        assertEquals(5, out.length)
        assertEquals('0', out[0])
        assertEquals('0', out[1])
        assertEquals(':', out[2])
        assertEquals('0', out[3])
        assertEquals('5', out[4])
    }

    @Test
    fun `formatHm handles negative epoch without throwing`() {
        // Negative epoch (pre-1970) is a valid java.util.Date input; pin that
        // the function does not throw and yields the HH:mm shape (catch-all
        // returns "" on Exception, but SimpleDateFormat handles negatives).
        val out = formatHm(-1L)
        // Either "" (caught) or a 5-char HH:mm — both acceptable; pin shape.
        assertTrue(
            "expected HH:mm shape or empty, got '$out'",
            out.isEmpty() || out.length == 5
        )
    }

    // ── String.wrappablePath (ChatRenderUtils.kt) ───────────────────────────

    @Test
    fun `wrappablePath inserts zero-width space after every slash`() {
        val out = "/foo/bar/baz".wrappablePath()
        // Each '/' is followed by U+200B; the leading '/' too.
        assertEquals("/\u200Bfoo/\u200Bbar/\u200Bbaz", out)
    }

    @Test
    fun `wrappablePath leaves paths without slash unchanged`() {
        assertEquals("README.md", "README.md".wrappablePath())
    }

    @Test
    fun `wrappablePath handles empty string`() {
        assertEquals("", "".wrappablePath())
    }

    @Test
    fun `wrappablePath on bare slash yields slash plus ZWSP`() {
        assertEquals("/\u200B", "/".wrappablePath())
    }

    @Test
    fun `wrappablePath preserves relative path with multiple separators`() {
        val out = "a/b/c".wrappablePath()
        assertEquals("a/\u200Bb/\u200Bc", out)
        // Verify visually-identical rendering: stripping U+200B recovers input.
        assertEquals("a/b/c", out.replace("\u200B", ""))
    }

    // ── formatElapsed (ThinkingCapsule.kt) ──────────────────────────────────

    @Test
    fun `formatElapsed zero millis formats as 0_00`() {
        assertEquals("0:00", formatElapsed(0L))
    }

    @Test
    fun `formatElapsed sub-second millis floor to 0_00`() {
        assertEquals("0:00", formatElapsed(999L))
    }

    @Test
    fun `formatElapsed exactly one second formats as 0_01`() {
        assertEquals("0:01", formatElapsed(1_000L))
    }

    @Test
    fun `formatElapsed sixty-five seconds formats as 1_05`() {
        assertEquals("1:05", formatElapsed(65_000L))
    }

    @Test
    fun `formatElapsed one hour formats as 60_00`() {
        // No hour rollover: 3600s renders as 60:00.
        assertEquals("60:00", formatElapsed(3_600_000L))
    }

    @Test
    fun `formatElapsed negative coerces to zero`() {
        // coerceAtLeast(0L) — negative elapsed (clock skew) must not render "-1:.."
        assertEquals("0:00", formatElapsed(-1L))
        assertEquals("0:00", formatElapsed(-10_000L))
    }

    // ── toolIcon (ChatToolCards.kt) ─────────────────────────────────────────
    //
    // toolIcon maps a tool name (lowercased prefix-match) to a Material icon.
    // We verify each branch returns a DISTINCT ImageVector (the prefix table
    // is ordered so specific names win over generic — e.g. webfetch before
    // read, todowrite before read), plus the null-default branch.

    @Test
    fun `toolIcon returns distinct vectors across all branches`() {
        // Collect one canonical name per branch in declared-priority order.
        val cases = listOf(
            null,            // → Build (default for null tool)
            "question",      // → LiveHelp
            "webfetch",      // → Public
            "task",          // → AccountTree
            "todowrite",     // → Checklist
            "todoread",      // → Checklist
            "read",          // → FileOpen
            "list",          // → FileOpen
            "glob",          // → Search
            "grep",          // → Search
            "edit",          // → Edit
            "write",         // → Edit
            "apply_patch",   // → Edit
            "patch",         // → Edit
            "bash",          // → Terminal
            "terminal",      // → Terminal
            "cmd",           // → Terminal
            "shell",         // → Terminal
            "unknownTool"    // → Build (fallback)
        )
        val vectors: List<ImageVector> = cases.map { toolIcon(it) }
        // Every result is non-null.
        vectors.forEach { assertNotNull("toolIcon($it) returned null", it) }

        // Distinct icons expected (Build, LiveHelp, Public, AccountTree,
        // Checklist, FileOpen, Search, Edit, Terminal).
        val distinctCount = vectors.toSet().size
        assertEquals(
            "expected 9 distinct ImageVectors across the branch table",
            9,
            distinctCount
        )
    }

    @Test
    fun `toolIcon for null returns Build fallback`() {
        val build = toolIcon("unknown")
        assertEquals(build, toolIcon(null))
    }

    @Test
    fun `toolIcon is case-insensitive via lowercase`() {
        // The function lowercases the input before prefix matching.
        assertEquals(toolIcon("READ"), toolIcon("read"))
        assertEquals(toolIcon("Bash"), toolIcon("bash"))
        assertEquals(toolIcon("WEBFETCH"), toolIcon("webfetch"))
    }

    @Test
    fun `toolIcon prefixes win over generic matches in declared order`() {
        // todowrite matches both "todoread|todowrite" (Checklist) and would
        // match a hypothetical "to*" prefix — pin the priority.
        assertEquals(toolIcon("todowrite"), toolIcon("todoread"))
        // task → AccountTree, NOT a generic Build fallback.
        assertEquals(
            "task must map to AccountTree",
            Icons.Default.AccountTree,
            toolIcon("task")
        )
        // webfetch → Public, NOT FileOpen (which read/list map to).
        assertEquals(
            "webfetch must map to Public",
            Icons.Default.Public,
            toolIcon("webfetch")
        )
    }

    // ── countDiffLines (ChatToolCards.kt) ───────────────────────────────────

    @Test
    fun `countDiffLines empty returns zero to zero`() {
        assertEquals(0 to 0, countDiffLines(""))
    }

    @Test
    fun `countDiffLines counts single add and delete`() {
        assertEquals(1 to 1, countDiffLines("+added\n-removed"))
    }

    @Test
    fun `countDiffLines skips plus plus plus header`() {
        // +++ b/file is a diff header — must NOT be counted as add.
        assertEquals(0 to 0, countDiffLines("+++ b/file.txt"))
    }

    @Test
    fun `countDiffLines skips triple minus header`() {
        assertEquals(0 to 0, countDiffLines("--- a/file.txt"))
    }

    @Test
    fun `countDiffLines skips at-at chunk header`() {
        assertEquals(0 to 0, countDiffLines("@@ -1,3 +1,4 @@"))
    }

    @Test
    fun `countDiffLines skips triple star sentinels`() {
        // opencode's apply_patch format uses "*** Add File: foo" / "*** Update File: bar".
        assertEquals(0 to 0, countDiffLines("*** Add File: src/Main.kt"))
        assertEquals(0 to 0, countDiffLines("*** Update File: src/Util.kt"))
        assertEquals(0 to 0, countDiffLines("*** End of Patch"))
    }

    @Test
    fun `countDiffLines mixed patch with headers counts only content lines`() {
        val patch = """
            --- a/old.txt
            +++ b/new.txt
            @@ -1,2 +1,3 @@
             context line
            -old line
            +new line
            +another new
        """.trimIndent()
        // 2 additions, 1 deletion; the context line (leading space) is neither.
        assertEquals(2 to 1, countDiffLines(patch))
    }

    @Test
    fun `countDiffLines applies-patch format counts add and del sentinels`() {
        val patch = """
            *** Begin Patch
            *** Add File: new.txt
            +content
            +more content
            *** End of Patch
        """.trimIndent()
        // *** headers skipped; 2 adds counted.
        assertEquals(2 to 0, countDiffLines(patch))
    }

    @Test
    fun `countDiffLines single plus line counts as add`() {
        assertEquals(1 to 0, countDiffLines("+solo"))
    }

    // ── contextToolLabel (ChatPatchCards.kt) ────────────────────────────────

    private fun partWithTool(tool: String?): Part = Part(
        id = "p1",
        type = "tool",
        tool = tool
    )

    @Test
    fun `contextToolLabel read returns Read`() {
        assertEquals("Read", contextToolLabel(partWithTool("read")))
    }

    @Test
    fun `contextToolLabel glob returns Glob`() {
        assertEquals("Glob", contextToolLabel(partWithTool("glob")))
    }

    @Test
    fun `contextToolLabel grep returns Grep`() {
        assertEquals("Grep", contextToolLabel(partWithTool("grep")))
    }

    @Test
    fun `contextToolLabel list returns List`() {
        assertEquals("List", contextToolLabel(partWithTool("list")))
    }

    @Test
    fun `contextToolLabel prefixes win case-insensitively`() {
        // Function lowercases before prefix matching.
        assertEquals("Read", contextToolLabel(partWithTool("READ")))
        assertEquals("Grep", contextToolLabel(partWithTool("GREP")))
    }

    @Test
    fun `contextToolLabel unknown tool uppercases first char`() {
        assertEquals("Customtool", contextToolLabel(partWithTool("customtool")))
    }

    @Test
    fun `contextToolLabel null tool uppercases empty string`() {
        // null → "" → replaceFirstChar uppercases nothing → "".
        assertEquals("", contextToolLabel(partWithTool(null)))
    }

    // ── parseTaskXml (ChatSubAgentCard.kt) ──────────────────────────────────

    @Test
    fun `parseTaskXml returns null for null input`() {
        assertNull(parseTaskXml(null))
    }

    @Test
    fun `parseTaskXml returns null for blank input`() {
        assertNull(parseTaskXml(""))
        assertNull(parseTaskXml("   "))
    }

    @Test
    fun `parseTaskXml returns null when no task tag present`() {
        assertNull(parseTaskXml("just plain text, no XML here"))
    }

    @Test
    fun `parseTaskXml parses id state and taskResult`() {
        val xml = """<task id="t-1" state="completed"><task_result>done</task_result></task>"""
        val result = parseTaskXml(xml)
        assertNotNull(result)
        assertEquals("t-1", result!!.id)
        assertEquals("completed", result.state)
        assertEquals("done", result.taskResult)
    }

    @Test
    fun `parseTaskXml parses error state`() {
        val xml = """<task id="t-2" state="error"><task_result>failed step 3</task_result></task>"""
        val result = parseTaskXml(xml)!!
        assertEquals("t-2", result.id)
        assertEquals("error", result.state)
        assertEquals("failed step 3", result.taskResult)
    }

    @Test
    fun `parseTaskXml with no attributes returns null id and state`() {
        val xml = """<task><task_result>only result</task_result></task>"""
        val result = parseTaskXml(xml)!!
        assertNull(result.id)
        assertNull(result.state)
        assertEquals("only result", result.taskResult)
    }

    @Test
    fun `parseTaskXml with no taskResult returns null taskResult`() {
        val xml = """<task id="t-3" state="completed"></task>"""
        val result = parseTaskXml(xml)!!
        assertEquals("t-3", result.id)
        assertEquals("completed", result.state)
        assertNull(result.taskResult)
    }

    @Test
    fun `parseTaskXml trims whitespace from taskResult`() {
        val xml = """<task id="t-4"><task_result>
   trimmed body
</task_result></task>"""
        val result = parseTaskXml(xml)!!
        assertEquals("trimmed body", result.taskResult)
    }

    @Test
    fun `parseTaskXml blank taskResult yields null taskResult`() {
        // The function applies .takeIf { it.isNotBlank() } after trim.
        val xml = """<task id="t-5"><task_result>

</task_result></task>"""
        val result = parseTaskXml(xml)!!
        assertNull(result.taskResult)
    }

    @Test
    fun `parseTaskXml only scans from first task tag`() {
        // Output may contain text before <task>; the function uses
        // indexOf("<task") as the regex search offset.
        val xml = """preamble text <task id="t-6" state="completed"><task_result>ok</task_result></task>"""
        val result = parseTaskXml(xml)!!
        assertEquals("t-6", result.id)
        assertEquals("completed", result.state)
        assertEquals("ok", result.taskResult)
    }

    @Test
    fun `parseTaskXml ignores task tag attributes appearing before task tag`() {
        // Edge case: ensure id/state extraction is anchored at the <task
        // position, not at any earlier `id=` or `state=` in the preamble.
        val xml = """noise id="wrong" state="wrong" <task id="right" state="completed"></task>"""
        val result = parseTaskXml(xml)!!
        assertEquals("right", result.id)
        assertEquals("completed", result.state)
    }
}
