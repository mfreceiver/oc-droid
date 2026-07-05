package cn.vectory.ocdroid.ui.util

import cn.vectory.ocdroid.data.model.FileContent
import cn.vectory.ocdroid.util.DebugLog
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.nio.file.Paths

object MarkdownImageResolver {

    private val unixAbsoluteRoots = setOf("Users", "private", "var", "tmp", "home", "opt", "etc", "Volumes")

    private val imageExtensions = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "tiff", "tif", "heic", "heif", "ico", "svg"
    )

    private val imagePattern = Regex("""!\[([^\]]*)\]\(([^)]+)\)""")

    fun normalizeStandaloneImageBlocks(text: String): String {
        val lines = text.split('\n')
        if (lines.size <= 1) return text

        val normalized = ArrayList<String>(lines.size)
        for (index in lines.indices) {
            val line = lines[index]
            normalized.add(line)

            if (!isStandaloneMarkdownImageLine(line)) continue
            if (index + 1 >= lines.size) continue

            val nextLine = lines[index + 1]
            if (nextLine.trim().isNotEmpty()) {
                normalized.add("")
            }
        }

        return normalized.joinToString("\n")
    }

    /**
     * §16.2 (parallel prefetch): resolve local-file image references in
     * [text] to inline `data:` URIs by fetching their contents in parallel.
     *
     * Previously this looped sequentially over each match and awaited its
     * `fetchContent` in turn, serializing N network/file round-trips. The
     * rewrite splits the work into two passes:
     *
     *   1. Synchronous filter/normalize pass — no I/O, just decides which
     *      matches are eligible (image extension, resolvable path) and
     *      records the replacement metadata.
     *   2. Concurrent fetch pass — `coroutineScope { pending.map { async { … } }.awaitAll() }`
     *      so all eligible contents are fetched in parallel, then applies
     *      the replacements in descending range order so earlier offsets
     *      remain valid as the string is mutated.
     *
     * The downstream HTTPS-image prefetch path is handled separately by
     * [HttpImageHolder.prefetch] (fire-and-forget, parallel by construction).
     */
    suspend fun resolveImages(
        text: String,
        markdownFilePath: String? = null,
        workspaceDirectory: String? = null,
        fetchContent: suspend (String) -> FileContent
    ): String {
        val matches = imagePattern.findAll(text).toList()
        if (matches.isEmpty()) return text

        // Pass 1: filter + normalize (pure, no I/O).
        data class PendingMatch(
            val range: IntRange,
            val alt: String,
            val mimeType: String,
            val path: String,
        )
        val pending = matches.mapNotNull { match ->
            val alt = match.groupValues[1]
            val rawUrl = match.groupValues[2].trim().trim('<', '>')
            if (rawUrl.isEmpty() || rawUrl.startsWith("data:") || rawUrl.contains("://")) return@mapNotNull null
            val normalizedPath = normalizeImagePath(rawUrl, markdownFilePath, workspaceDirectory) ?: return@mapNotNull null
            val ext = normalizedPath.substringAfterLast('.', "").lowercase()
            if (ext !in imageExtensions) return@mapNotNull null
            PendingMatch(match.range, alt, mimeTypeForExtension(ext), normalizedPath)
        }
        if (pending.isEmpty()) return text

        // Pass 2: parallel fetch. Failures of individual fetches collapse to
        // null and the corresponding match is left unrewritten (its original
        // markdown stays intact, matching the prior sequential behavior).
        val fetched: List<Pair<PendingMatch, FileContent>?> = coroutineScope {
            pending.map { pm ->
                async {
                    runCatching { pm to fetchContent(pm.path) }
                        .getOrNull()
                }
            }.awaitAll()
        }

        // Apply in reverse range order so prior offsets stay valid.
        var result = text
        for (entry in fetched.filterNotNull().sortedByDescending { it.first.range.first }) {
            val (pm, content) = entry
            val base64Data = content.content?.takeIf { it.isNotBlank() } ?: continue
            val cleaned = base64Data.replace("\n", "").replace("\r", "").replace(" ", "")
            result = result.replaceRange(pm.range, "![${pm.alt}](data:${pm.mimeType};base64,${cleaned})")
        }
        return result
    }

    internal fun normalizeImagePath(
        rawUrl: String,
        markdownFilePath: String? = null,
        workspaceDirectory: String? = null
    ): String? {
        if (rawUrl.isBlank() || rawUrl.startsWith("data:") || rawUrl.contains("://")) return null

        val withoutFragment = rawUrl.substringBefore('#').substringBefore('?').trim()
        if (withoutFragment.isBlank()) return null

        val resolved = when {
            withoutFragment.startsWith("/") && workspaceDirectory.isNullOrBlank() -> withoutFragment.trimStart('/')
            withoutFragment.startsWith("/") -> relativizeAgainstWorkspace(withoutFragment, workspaceDirectory)
            markdownFilePath.isNullOrBlank() -> withoutFragment
            else -> {
                val baseDirectory = Paths.get(markdownFilePath).parent
                val combined = (baseDirectory ?: Paths.get("")).resolve(withoutFragment).normalize().toString()
                relativizeAgainstWorkspace(combined, workspaceDirectory)
            }
        }

        return resolved
            ?.replace('\\', '/')
            ?.let { restoreUnixAbsolutePathIfNeeded(it, markdownFilePath, workspaceDirectory) }
            ?.let { normalized ->
                if (workspaceDirectory.isNullOrBlank() && normalized.startsWith("/")) normalized else normalized.trimStart('/')
            }
            ?.takeIf { it.isNotBlank() }
    }

    private fun restoreUnixAbsolutePathIfNeeded(
        path: String,
        markdownFilePath: String?,
        workspaceDirectory: String?
    ): String {
        if (!workspaceDirectory.isNullOrBlank()) return path
        if (path.startsWith("/")) return path
        if (path.matches(Regex("^[A-Za-z]:[/\\\\].*"))) return path

        val firstSegment = path.substringBefore('/')
        val contextFirstSegment = markdownFilePath
            ?.replace('\\', '/')
            ?.trimStart('/')
            ?.substringBefore('/')

        return if (firstSegment in unixAbsoluteRoots && contextFirstSegment == firstSegment) {
            "/$path"
        } else {
            path
        }
    }

    private fun relativizeAgainstWorkspace(path: String, workspaceDirectory: String?): String? {
        if (workspaceDirectory.isNullOrBlank()) return path
        return try {
            val workspacePath = Paths.get(workspaceDirectory).normalize()
            val targetPath = if (Paths.get(path).isAbsolute) {
                Paths.get(path).normalize()
            } else {
                workspacePath.resolve(path).normalize()
            }
            workspacePath.relativize(targetPath).toString()
        } catch (e: Exception) {
            DebugLog.w("MarkdownImageResolver", "relativizeAgainstWorkspace failed: ${e.message}")
            path
        }
    }

    private fun mimeTypeForExtension(extension: String): String {
        return when (extension) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "tiff", "tif" -> "image/tiff"
            "heic" -> "image/heic"
            "heif" -> "image/heif"
            "ico" -> "image/x-icon"
            "svg" -> "image/svg+xml"
            else -> "image/*"
        }
    }

    private fun isStandaloneMarkdownImageLine(line: String): Boolean {
        val trimmed = line.trim()
        if (!trimmed.startsWith("![") || !trimmed.endsWith(")")) return false
        val closeAlt = trimmed.indexOf(']')
        if (closeAlt < 0 || closeAlt + 1 >= trimmed.length) return false
        val afterAlt = trimmed.substring(closeAlt + 1)
        return afterAlt.startsWith("(") && afterAlt.length > 1
    }
}
