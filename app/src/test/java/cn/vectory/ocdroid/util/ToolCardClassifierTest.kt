package cn.vectory.ocdroid.ui.chat

import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.model.PartState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R18 Phase 5 — [ToolCardClassifier] pure-JVM coverage.
 *
 * The classifier was extracted from iOS `ToolCardClassifier.swift` *specifically
 * so it could be unit-tested without Compose* (see source header). It was
 * previously 0% covered despite driving the file-card-grid vs. collapsed-row
 * UX split. Every entry point is a pure function of a [Part]; this exercises
 * each classifier + the directory-output parser across all branches:
 *
 *  - file-op prefixes (loose match, lowercased)
 *  - patch parts with / without navigable file paths
 *  - write vs. read tool disambiguation
 *  - context-tool categorisation (READ / SEARCH / LIST)
 *  - `<entries>` parsing + summary-line filtering
 *  - `split` partitioning + `toolCallsCount`
 */
class ToolCardClassifierTest {

    // ───────────────── isFileOperation ─────────────────

    @Test
    fun `isFileOperation true for patch part with navigable file path`() {
        val part = Part(
            id = "p1",
            type = "patch",
            files = listOf(Part.FileChange(path = "src/Main.kt")),
        )
        assertTrue(ToolCardClassifier.isFileOperation(part))
    }

    @Test
    fun `isFileOperation false for patch part with no navigable file path`() {
        // A pathless patch would fall out of the file-card grid into nowhere;
        // the classifier must NOT treat it as a file operation.
        val part = Part(id = "p1", type = "patch")
        assertFalse(ToolCardClassifier.isFileOperation(part))
    }

    @Test
    fun `isFileOperation false for patch part whose only path is a directory`() {
        // filePathsForNavigationFiltered drops paths without a "." in the last
        // segment — a directory like "src/" must NOT count as a file op.
        val part = Part(
            id = "p1",
            type = "patch",
            files = listOf(Part.FileChange(path = "src/")),
        )
        assertFalse(ToolCardClassifier.isFileOperation(part))
    }

    @Test
    fun `isFileOperation true for tool whose name starts with a file-op verb`() {
        for (tool in listOf("read_file", "read", "edit_file", "edit", "write_file", "write", "apply_patch", "patch")) {
            val part = Part(id = "p1", type = "tool", tool = tool)
            assertTrue("expected file-op for tool=$tool", ToolCardClassifier.isFileOperation(part))
        }
    }

    @Test
    fun `isFileOperation matches case-insensitively`() {
        // iOS port preserves lowercase normalisation; a server emitting
        // "Read_File" must still group into the file grid.
        val part = Part(id = "p1", type = "tool", tool = "READ_FILE")
        assertTrue(ToolCardClassifier.isFileOperation(part))
    }

    @Test
    fun `isFileOperation false for tool with non-file verb`() {
        val part = Part(id = "p1", type = "tool", tool = "bash")
        assertFalse(ToolCardClassifier.isFileOperation(part))
    }

    @Test
    fun `isFileOperation false when type is text`() {
        val part = Part(id = "p1", type = "text", tool = "read_file")
        assertFalse(ToolCardClassifier.isFileOperation(part))
    }

    @Test
    fun `isFileOperation false when type is tool but tool name is null`() {
        val part = Part(id = "p1", type = "tool", tool = null)
        assertFalse(ToolCardClassifier.isFileOperation(part))
    }

    // ───────────────── isWriteFileOperation ─────────────────

    @Test
    fun `isWriteFileOperation true for patch with navigable path`() {
        val part = Part(
            id = "p1",
            type = "patch",
            files = listOf(Part.FileChange(path = "src/Main.kt")),
        )
        assertTrue(ToolCardClassifier.isWriteFileOperation(part))
    }

    @Test
    fun `isWriteFileOperation true for edit and write tools`() {
        for (tool in listOf("edit_file", "edit", "write_file", "write", "apply_patch", "patch")) {
            val part = Part(id = "p1", type = "tool", tool = tool)
            assertTrue("expected write for tool=$tool", ToolCardClassifier.isWriteFileOperation(part))
        }
    }

    @Test
    fun `isWriteFileOperation false for read-only file tools`() {
        // read stays in the FileCard grid, NOT the unified PatchCard.
        val part = Part(id = "p1", type = "tool", tool = "read_file")
        assertFalse(ToolCardClassifier.isWriteFileOperation(part))
    }

    @Test
    fun `isWriteFileOperation false for bash`() {
        val part = Part(id = "p1", type = "tool", tool = "bash")
        assertFalse(ToolCardClassifier.isWriteFileOperation(part))
    }

    // ───────────────── isContextTool ─────────────────

    @Test
    fun `isContextTool true for read glob grep and list aliases`() {
        for (tool in listOf("read_file", "read", "glob", "grep", "list")) {
            val part = Part(id = "p1", type = "tool", tool = tool)
            assertTrue("expected context tool for $tool", ToolCardClassifier.isContextTool(part))
        }
    }

    @Test
    fun `isContextTool false for write tools even if they touch files`() {
        for (tool in listOf("write_file", "edit", "apply_patch")) {
            val part = Part(id = "p1", type = "tool", tool = tool)
            assertFalse("write tool must not be classified as context: $tool", ToolCardClassifier.isContextTool(part))
        }
    }

    @Test
    fun `isContextTool false for patch parts`() {
        val part = Part(
            id = "p1",
            type = "patch",
            files = listOf(Part.FileChange(path = "a.txt")),
        )
        assertFalse(ToolCardClassifier.isContextTool(part))
    }

    @Test
    fun `isContextTool false for non-tool parts`() {
        val part = Part(id = "p1", type = "text")
        assertFalse(ToolCardClassifier.isContextTool(part))
    }

    @Test
    fun `isContextTool false when tool name is null`() {
        val part = Part(id = "p1", type = "tool", tool = null)
        assertFalse(ToolCardClassifier.isContextTool(part))
    }

    // ───────────────── contextToolCategory ─────────────────

    @Test
    fun `contextToolCategory classifies glob and grep as SEARCH`() {
        assertEquals(
            ToolCardClassifier.ContextCategory.SEARCH,
            ToolCardClassifier.contextToolCategory(Part(id = "p", type = "tool", tool = "glob")),
        )
        assertEquals(
            ToolCardClassifier.ContextCategory.SEARCH,
            ToolCardClassifier.contextToolCategory(Part(id = "p", type = "tool", tool = "grep")),
        )
    }

    @Test
    fun `contextToolCategory classifies list as LIST`() {
        assertEquals(
            ToolCardClassifier.ContextCategory.LIST,
            ToolCardClassifier.contextToolCategory(Part(id = "p", type = "tool", tool = "list")),
        )
    }

    @Test
    fun `contextToolCategory classifies read and unknown as READ`() {
        assertEquals(
            ToolCardClassifier.ContextCategory.READ,
            ToolCardClassifier.contextToolCategory(Part(id = "p", type = "tool", tool = "read_file")),
        )
        // Unknown verbs default to READ rather than blowing up.
        assertEquals(
            ToolCardClassifier.ContextCategory.READ,
            ToolCardClassifier.contextToolCategory(Part(id = "p", type = "tool", tool = "bash")),
        )
    }

    @Test
    fun `contextToolCategory is READ when tool name is null`() {
        assertEquals(
            ToolCardClassifier.ContextCategory.READ,
            ToolCardClassifier.contextToolCategory(Part(id = "p", type = "tool", tool = null)),
        )
    }

    // ───────────────── isTodoWriteTool ─────────────────

    @Test
    fun `isTodoWriteTool true only for exact todowrite`() {
        assertTrue(ToolCardClassifier.isTodoWriteTool(Part(id = "p", type = "tool", tool = "todowrite")))
        assertTrue(ToolCardClassifier.isTodoWriteTool(Part(id = "p", type = "tool", tool = "TodoWrite")))
    }

    @Test
    fun `isTodoWriteTool false for non-todowrite tool and for non-tool parts`() {
        assertFalse(ToolCardClassifier.isTodoWriteTool(Part(id = "p", type = "tool", tool = "todo")))
        assertFalse(ToolCardClassifier.isTodoWriteTool(Part(id = "p", type = "tool", tool = "todowriter")))
        assertFalse(ToolCardClassifier.isTodoWriteTool(Part(id = "p", type = "text", tool = "todowrite")))
        assertFalse(ToolCardClassifier.isTodoWriteTool(Part(id = "p", type = "tool", tool = null)))
    }

    // ───────────────── isDirectoryRead ─────────────────

    @Test
    fun `isDirectoryRead true when read tool output contains the directory marker`() {
        val part = Part(
            id = "p",
            type = "tool",
            tool = "read_file",
            state = PartState(displayString = "done", output = "<type>directory</type>..."),
        )
        assertTrue(ToolCardClassifier.isDirectoryRead(part))
    }

    @Test
    fun `isDirectoryRead false when output contains file marker instead`() {
        val part = Part(
            id = "p",
            type = "tool",
            tool = "read_file",
            state = PartState(displayString = "done", output = "<type>file</type>"),
        )
        assertFalse(ToolCardClassifier.isDirectoryRead(part))
    }

    @Test
    fun `isDirectoryRead false for non-read tools even if marker is present`() {
        // Only read tools can produce directory listings; a `bash` output that
        // happens to embed "<type>directory</type>" must NOT be misclassified.
        val part = Part(
            id = "p",
            type = "tool",
            tool = "bash",
            state = PartState(displayString = "done", output = "<type>directory</type>"),
        )
        assertFalse(ToolCardClassifier.isDirectoryRead(part))
    }

    @Test
    fun `isDirectoryRead false when output is null`() {
        val part = Part(
            id = "p",
            type = "tool",
            tool = "read_file",
            state = PartState(displayString = "done", output = null),
        )
        assertFalse(ToolCardClassifier.isDirectoryRead(part))
    }

    @Test
    fun `isDirectoryRead false for non-tool parts`() {
        val part = Part(id = "p", type = "text", tool = "read_file")
        assertFalse(ToolCardClassifier.isDirectoryRead(part))
    }

    // ───────────────── parseDirectoryEntries ─────────────────

    @Test
    fun `parseDirectoryEntries returns empty for null output`() {
        assertEquals(emptyList<ToolCardClassifier.DirectoryEntry>(), ToolCardClassifier.parseDirectoryEntries(null))
    }

    @Test
    fun `parseDirectoryEntries returns empty when entries tag is absent`() {
        assertEquals(emptyList<ToolCardClassifier.DirectoryEntry>(), ToolCardClassifier.parseDirectoryEntries("no tag here"))
    }

    @Test
    fun `parseDirectoryEntries parses files and subdirectories`() {
        val output = """
            <type>directory</type>
            <entries>
            file1.txt
            subdir/
            file2.kt
            </entries>
        """.trimIndent()

        val entries = ToolCardClassifier.parseDirectoryEntries(output)
        assertEquals(3, entries.size)
        assertEquals(ToolCardClassifier.DirectoryEntry("file1.txt", false), entries[0])
        assertEquals(ToolCardClassifier.DirectoryEntry("subdir", true), entries[1])
        assertEquals(ToolCardClassifier.DirectoryEntry("file2.kt", false), entries[2])
    }

    @Test
    fun `parseDirectoryEntries drops blank lines and summary lines`() {
        val output = """
            <entries>

            (2 entries)
            only.txt

            </entries>
        """.trimIndent()
        val entries = ToolCardClassifier.parseDirectoryEntries(output)
        assertEquals(listOf(ToolCardClassifier.DirectoryEntry("only.txt", false)), entries)
    }

    @Test
    fun `parseDirectoryEntries handles missing closing tag`() {
        // Resilient parse: an unterminated <entries> still yields what was
        // found rather than throwing.
        val output = "<entries>\na.txt\nb.txt"
        val entries = ToolCardClassifier.parseDirectoryEntries(output)
        assertEquals(2, entries.size)
    }

    @Test
    fun `parseDirectoryEntries directory entry name has trailing slash stripped`() {
        val entries = ToolCardClassifier.parseDirectoryEntries("<entries>foo/</entries>")
        assertEquals("foo", entries[0].name)
        assertTrue(entries[0].isDirectory)
    }

    // ───────────────── split + toolCallsCount ─────────────────

    @Test
    fun `split partitions file ops into first list and others into second`() {
        val readPart = Part(id = "p1", type = "tool", tool = "read_file")
        val bashPart = Part(id = "p2", type = "tool", tool = "bash")
        val patchPart = Part(
            id = "p3",
            type = "patch",
            files = listOf(Part.FileChange(path = "src/Main.kt")),
        )
        val textPart = Part(id = "p4", type = "text")

        val (fileParts, otherParts) = ToolCardClassifier.split(listOf(readPart, bashPart, patchPart, textPart))

        assertEquals(listOf(readPart, patchPart), fileParts)
        assertEquals(listOf(bashPart, textPart), otherParts)
    }

    @Test
    fun `split returns empty pairs for empty input`() {
        val (fileParts, otherParts) = ToolCardClassifier.split(emptyList())
        assertTrue(fileParts.isEmpty())
        assertTrue(otherParts.isEmpty())
    }

    @Test
    fun `toolCallsCount counts non-file-operation parts`() {
        // Note: `glob` is a *context* tool (read-only inspection) but NOT in
        // fileOpToolPrefixes, so it is NOT a file operation — it falls into
        // the "other tools" row alongside bash. Locking this in prevents a
        // future "glob is read-only file access" misclassification.
        val parts = listOf(
            Part(id = "p1", type = "tool", tool = "read_file"),     // file op → not counted
            Part(id = "p2", type = "tool", tool = "bash"),          // other  → counted
            Part(id = "p3", type = "tool", tool = "glob"),          // other  → counted (context, not file-op)
            Part(id = "p4", type = "text"),                         // other  → counted
        )
        assertEquals(3, ToolCardClassifier.toolCallsCount(parts))
    }

    @Test
    fun `toolCallsCount is zero when every part is a file op`() {
        val parts = listOf(
            Part(id = "p1", type = "tool", tool = "read_file"),
            Part(
                id = "p2",
                type = "patch",
                files = listOf(Part.FileChange(path = "a.kt")),
            ),
        )
        assertEquals(0, ToolCardClassifier.toolCallsCount(parts))
    }

    @Test
    fun `toolCallsCount is zero for empty list`() {
        assertEquals(0, ToolCardClassifier.toolCallsCount(emptyList()))
    }
}
