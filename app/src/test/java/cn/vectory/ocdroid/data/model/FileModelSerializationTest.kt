package cn.vectory.ocdroid.data.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip + computed-property coverage for the file-shaped models in
 * `data.model.File.kt`: [FileNode], [FileContent], [FileStatusEntry], [FileDiff].
 *
 * Pure kotlinx.serialization — no Android framework dependency.
 */
class FileModelSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    // ── FileNode ──────────────────────────────────────────────────────────

    @Test
    fun `FileNode round trip preserves all fields`() {
        val node = FileNode(
            name = "main.kt",
            path = "/project/src/main.kt",
            absolute = "/home/user/project/src/main.kt",
            type = "file",
            ignored = false
        )
        val encoded = json.encodeToString(node)
        val decoded = json.decodeFromString<FileNode>(encoded)
        assertEquals(node, decoded)
    }

    @Test
    fun `FileNode defaults nullable fields to null when JSON omits them`() {
        val decoded = json.decodeFromString<FileNode>(
            """{"name":"src","path":"/p/src","type":"directory"}"""
        )
        assertEquals("src", decoded.name)
        assertEquals("/p/src", decoded.path)
        assertEquals("directory", decoded.type)
        assertNull(decoded.absolute)
        assertNull(decoded.ignored)
    }

    @Test
    fun `FileNode computed id isDirectory isFile`() {
        val dir = FileNode(name = "src", path = "/p/src", type = "directory")
        assertEquals("/p/src", dir.id)
        assertTrue(dir.isDirectory)
        assertFalse(dir.isFile)

        val file = FileNode(name = "main.kt", path = "/p/main.kt", type = "file")
        assertFalse(file.isDirectory)
        assertTrue(file.isFile)

        // Neither branch when type is something else.
        val other = FileNode(name = "x", path = "/p/x", type = "symlink")
        assertFalse(other.isDirectory)
        assertFalse(other.isFile)
    }

    @Test
    fun `FileNode tolerates unknown JSON keys`() {
        val decoded = json.decodeFromString<FileNode>(
            """{"name":"a","path":"/p/a","type":"file","extra":"ignored","size":42}"""
        )
        assertEquals("a", decoded.name)
        assertEquals("file", decoded.type)
    }

    // ── FileContent ───────────────────────────────────────────────────────

    @Test
    fun `FileContent text round trip and computed text getter`() {
        val content = FileContent(type = "text", content = "hello")
        val encoded = json.encodeToString(content)
        val decoded = json.decodeFromString<FileContent>(encoded)
        assertEquals(content, decoded)
        assertTrue(decoded.isText)
        assertFalse(decoded.isBinary)
        assertEquals("hello", decoded.text)
    }

    @Test
    fun `FileContent binary isBinary and text returns null`() {
        val binary = FileContent(type = "binary", content = null)
        assertFalse(binary.isText)
        assertTrue(binary.isBinary)
        assertNull(binary.text)
    }

    @Test
    fun `FileContent text getter returns null for binary even when content present`() {
        // Defensive: a non-text type carries no text payload even if content is set.
        val binary = FileContent(type = "binary", content = "ignored")
        assertNull(binary.text)
    }

    @Test
    fun `FileContent parses server JSON shape`() {
        val decoded = json.decodeFromString<FileContent>(
            """{"type":"text","content":"line1\nline2"}"""
        )
        assertEquals("text", decoded.type)
        assertEquals("line1\nline2", decoded.content)
    }

    // ── FileStatusEntry ───────────────────────────────────────────────────

    @Test
    fun `FileStatusEntry round trip with all fields populated`() {
        val entry = FileStatusEntry(path = "M src/main.kt", status = "modified")
        val encoded = json.encodeToString(entry)
        val decoded = json.decodeFromString<FileStatusEntry>(encoded)
        assertEquals(entry, decoded)
    }

    @Test
    fun `FileStatusEntry defaults to nulls when JSON empty`() {
        val decoded = json.decodeFromString<FileStatusEntry>("{}")
        assertNull(decoded.path)
        assertNull(decoded.status)
    }

    // ── FileDiff ──────────────────────────────────────────────────────────

    @Test
    fun `FileDiff prefers file field over path field`() {
        val diff = FileDiff(
            filePath = "src/a.kt",
            pathAlt = "src/b.kt",
            before = "x",
            after = "y",
            additions = 1,
            deletions = 2,
            status = "modified"
        )
        assertEquals("src/a.kt", diff.file)
        assertEquals("src/a.kt", diff.id)
    }

    @Test
    fun `FileDiff falls back to path when file field missing`() {
        val diff = FileDiff(pathAlt = "from/Path.kt")
        assertEquals("from/Path.kt", diff.file)
        assertEquals("from/Path.kt", diff.id)
    }

    @Test
    fun `FileDiff file resolves to empty string when both missing`() {
        val diff = FileDiff()
        assertEquals("", diff.file)
        assertEquals("", diff.id)
    }

    @Test
    fun `FileDiff round trip preserves SerialName mappings`() {
        val diff = FileDiff(filePath = "a.kt", pathAlt = "alt", additions = 5, deletions = 3, status = "M")
        val encoded = json.encodeToString(diff)
        // @SerialName("file") on filePath, @SerialName("path") on pathAlt.
        assertTrue(encoded.contains("\"file\":\"a.kt\""))
        assertTrue(encoded.contains("\"path\":\"alt\""))
        val decoded = json.decodeFromString<FileDiff>(encoded)
        assertEquals(diff, decoded)
    }

    @Test
    fun `FileDiff parses server JSON with file key`() {
        val decoded = json.decodeFromString<FileDiff>(
            """{"file":"src/main.kt","additions":10,"deletions":2,"status":"modified"}"""
        )
        assertEquals("src/main.kt", decoded.filePath)
        assertEquals("src/main.kt", decoded.file)
        assertEquals(10, decoded.additions)
        assertEquals(2, decoded.deletions)
        assertEquals("modified", decoded.status)
    }
}
