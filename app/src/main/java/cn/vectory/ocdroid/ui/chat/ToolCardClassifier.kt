package cn.vectory.ocdroid.ui.chat

import cn.vectory.ocdroid.data.model.Part

/**
 * Classification logic for the "tool card render redo": which parts render as
 * file cards (2-column grid) vs. collapse into a single "N tool calls" row.
 * Extracted so the behavior is unit-testable without Compose. Ported from iOS
 * ToolCardClassifier.swift, with one Android-specific tweak: a `patch` only
 * counts as a file operation when it carries navigable file paths
 * (filePathsForNavigationFiltered), so a "pathless patch" doesn't fall out of
 * the grid into nowhere.
 */
object ToolCardClassifier {
    /**
     * A "file operation" is a patch part (with navigable paths), or a tool whose
     * name matches one of the file-op verbs. Loose prefix match so aliases like
     * "edit"/"write"/"read"/"patch" and full forms like "edit_file"/"apply_patch"
     * all count.
     */
    val fileOpToolPrefixes = listOf(
        "apply_patch", "edit_file", "write_file", "read_file",
        "patch", "edit", "write", "read",
    )

    /**
     * Tools that mutate files (write/edit/patch). These render as the unified
     * collapsible PatchCard (basename + diff stats) instead of the read-only
     * FileCard grid.
     */
    val writeFilePrefixes = listOf(
        "apply_patch", "edit_file", "write_file", "patch", "edit", "write",
    )

    fun isFileOperation(part: Part): Boolean {
        if (part.isPatch && part.filePathsForNavigationFiltered.isNotEmpty()) return true
        if (!part.isTool) return false
        val tool = part.tool?.lowercase() ?: return false
        return fileOpToolPrefixes.any { tool.startsWith(it) }
    }

    /**
     * True for file-mutating operations (write/edit/patch with a navigable path).
     * Read-only file tools fall through to the FileCard grid.
     */
    fun isWriteFileOperation(part: Part): Boolean {
        // §kimo-B4: 任意 patch 类型部分都进 PatchCard（不再要求 navigable path）——
        // PatchCard 已对无路径情况做内容回退（inputSummary/output），避免无扩展名
        // (Makefile/Dockerfile) 或服务端未填路径的 patch 落到 BasicCard 且展开为空。
        if (part.isPatch) return true
        if (!part.isTool) return false
        val tool = part.tool?.lowercase() ?: return false
        return writeFilePrefixes.any { tool.startsWith(it) }
    }

    /**
     * `read` tool names (and aliases) — a directory read can only come from one of
     * these, never from edit/write/patch.
     */
    val readToolPrefixes = listOf("read_file", "read")

    // ── opencode-web paradigm classifiers ──────────────────────────────

    /** Context tools: read-only file inspection (read/glob/grep/list). */
    val contextToolPrefixes = listOf("read_file", "read", "glob", "grep", "list")

    /** True for read-only context tools (not writes, not patches). */
    fun isContextTool(part: Part): Boolean {
        if (part.isPatch) return false
        if (!part.isTool) return false
        val tool = part.tool?.lowercase() ?: return false
        if (writeFilePrefixes.any { tool.startsWith(it) }) return false
        return contextToolPrefixes.any { tool.startsWith(it) }
    }

    /** Category of a context tool for grouping/counting in ContextToolGroup. */
    enum class ContextCategory { READ, SEARCH, LIST }

    fun contextToolCategory(part: Part): ContextCategory {
        val tool = part.tool?.lowercase() ?: return ContextCategory.READ
        return when {
            tool.startsWith("glob") || tool.startsWith("grep") -> ContextCategory.SEARCH
            tool.startsWith("list") -> ContextCategory.LIST
            else -> ContextCategory.READ
        }
    }

    fun isTodoWriteTool(part: Part): Boolean =
        part.isTool && part.tool?.lowercase() == "todowrite"

    /**
     * True when this part is a `read` whose tool output reports a directory.
     * The server embeds `<type>directory</type>` in the read output for a folder
     * (vs. `<type>file</type>` for a file).
     */
    fun isDirectoryRead(part: Part): Boolean {
        if (!part.isTool) return false
        val tool = part.tool?.lowercase() ?: return false
        if (readToolPrefixes.none { tool.startsWith(it) }) return false
        val output = part.toolOutput ?: return false
        return output.contains("<type>directory</type>")
    }

    /** One entry in a directory read: a child file or subdirectory. */
    data class DirectoryEntry(
        val name: String,
        val isDirectory: Boolean
    )

    /**
     * Parse the `<entries>…</entries>` block out of a directory read's output.
     * Each line is a child name; names ending in "/" are subdirectories. Blank
     * lines and trailing summary lines like "(12 entries)" are dropped. The
     * display name keeps the trailing "/" stripped so the UI can render it
     * uniformly with its own icon.
     */
    fun parseDirectoryEntries(output: String?): List<DirectoryEntry> {
        if (output == null) return emptyList()
        val openIdx = output.indexOf("<entries>")
        if (openIdx < 0) return emptyList()
        val afterOpen = output.substring(openIdx + "<entries>".length)
        val closeIdx = afterOpen.indexOf("</entries>")
        val body = if (closeIdx >= 0) afterOpen.substring(0, closeIdx) else afterOpen

        val entries = mutableListOf<DirectoryEntry>()
        for (rawLine in body.split("\n")) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            // Drop summary lines such as "(12 entries)" or "(0 entries)".
            if (line.startsWith("(") && line.endsWith("entries)")) continue
            val isDir = line.endsWith("/")
            val name = if (isDir) line.dropLast(1) else line
            if (name.isEmpty()) continue
            entries.add(DirectoryEntry(name = name, isDirectory = isDir))
        }
        return entries
    }

    /**
     * Split a buffered run of tool/patch parts into the file-card group (grid) and
     * the "other tools" group (merged into one "N tool calls" disclosure row).
     */
    fun split(parts: List<Part>): Pair<List<Part>, List<Part>> {
        val fileParts = mutableListOf<Part>()
        val otherParts = mutableListOf<Part>()
        for (part in parts) {
            if (isFileOperation(part)) {
                fileParts.add(part)
            } else {
                otherParts.add(part)
            }
        }
        return Pair(fileParts, otherParts)
    }

    /** Number shown in the merged "N tool calls" row (count of non-file tools). */
    fun toolCallsCount(parts: List<Part>): Int =
        parts.count { !isFileOperation(it) }
}

// ── Per-message tool count summary categories ────────────────────────────

/**
 * User-facing category for a classified tool run, used by [ToolCountSummary]
 * to render a per-message tally. [categoryCounts] returns empty for items
 * excluded from the summary (e.g. sub-agent tasks, which render as their own
 * bordered card and would double-count the run).
 */
internal enum class ToolCategory { EDITS, READS, SHELL, WEB, OTHER }

/**
 * Per-category counts contributed by one classified tool run. A [ToolRenderItem.ContextGroup]
 * contributes one READS per part it contains (a group of read+grep+list = 3 reads); other
 * items contribute 1 to their category. [ToolRenderItem.SubAgent] contributes nothing (it
 * renders as its own bordered card and would double-count the run).
 */
internal fun ToolRenderItem.categoryCounts(): Map<ToolCategory, Int> = when (this) {
    is ToolRenderItem.WritePatch -> mapOf(ToolCategory.EDITS to 1)
    is ToolRenderItem.ContextGroup -> mapOf(ToolCategory.READS to parts.size)
    is ToolRenderItem.SubAgent -> emptyMap()
    is ToolRenderItem.Basic -> {
        val t = part.tool?.lowercase() ?: ""
        val cat = when {
            t.startsWith("bash") || t.startsWith("terminal") ||
                t.startsWith("cmd") || t.startsWith("shell") -> ToolCategory.SHELL
            t.startsWith("webfetch") || t.startsWith("web_fetch") ||
                t.startsWith("websearch") || t.startsWith("web_search") -> ToolCategory.WEB
            else -> ToolCategory.OTHER
        }
        mapOf(cat to 1)
    }
}
