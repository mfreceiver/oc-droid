package cn.vectory.ocdroid

import cn.vectory.ocdroid.ui.files.FilePreviewUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilePreviewUtilsTest {
    @Test
    fun `isImagePath recognizes common image extensions case-insensitively`() {
        assertTrue(FilePreviewUtils.isImagePath("foo/bar/image.png"))
        assertTrue(FilePreviewUtils.isImagePath("foo/bar/photo.JPEG"))
        assertTrue(FilePreviewUtils.isImagePath("foo/bar/icon.WebP"))
        assertFalse(FilePreviewUtils.isImagePath("foo/bar/readme.md"))
        assertFalse(FilePreviewUtils.isImagePath("foo/bar/no_extension"))
    }

    @Test
    fun `imageMimeType maps common image extensions`() {
        assertEquals("image/png", FilePreviewUtils.imageMimeType("image.png"))
        assertEquals("image/jpeg", FilePreviewUtils.imageMimeType("image.jpg"))
        assertEquals("image/webp", FilePreviewUtils.imageMimeType("image.webp"))
        assertEquals("image/*", FilePreviewUtils.imageMimeType("image.unknown"))
    }

    @Test
    fun `previewContentKind prioritizes image markdown binary and text`() {
        assertEquals(
            FilePreviewUtils.PreviewContentKind.IMAGE,
            FilePreviewUtils.previewContentKind("foo/image.png", isBinary = false)
        )
        assertEquals(
            FilePreviewUtils.PreviewContentKind.MARKDOWN,
            FilePreviewUtils.previewContentKind("foo/readme.md", isBinary = false)
        )
        assertEquals(
            FilePreviewUtils.PreviewContentKind.BINARY,
            FilePreviewUtils.previewContentKind("foo/archive.bin", isBinary = true)
        )
        assertEquals(
            FilePreviewUtils.PreviewContentKind.TEXT,
            FilePreviewUtils.previewContentKind("foo/notes.txt", isBinary = false)
        )
    }

    @Test
    fun `isTextPath recognizes text whitelist by extension and special filename`() {
        // 代码/配置/日志扩展名
        assertTrue(FilePreviewUtils.isTextPath("app.log"))
        assertTrue(FilePreviewUtils.isTextPath("data.csv"))
        assertTrue(FilePreviewUtils.isTextPath(".env"))
        assertTrue(FilePreviewUtils.isTextPath("App.vue"))
        assertTrue(FilePreviewUtils.isTextPath("main.cs"))
        assertTrue(FilePreviewUtils.isTextPath("plot.r"))
        // 无扩展名特殊文件名
        assertTrue(FilePreviewUtils.isTextPath("Makefile"))
        assertTrue(FilePreviewUtils.isTextPath("Dockerfile"))
        assertTrue(FilePreviewUtils.isTextPath("repo/.gitignore"))
        // 非文本
        assertFalse(FilePreviewUtils.isTextPath("image.png"))
        assertFalse(FilePreviewUtils.isTextPath("archive.bin"))
        assertFalse(FilePreviewUtils.isTextPath("random_no_ext"))
    }

    @Test
    fun `previewContentKind forces TEXT for whitelisted files even when server reports binary`() {
        // §item5a: server 对 .log/.csv/.env/Dockerfile 误报 binary 时, 白名单强制 TEXT.
        assertEquals(
            FilePreviewUtils.PreviewContentKind.TEXT,
            FilePreviewUtils.previewContentKind("debug.log", isBinary = true)
        )
        assertEquals(
            FilePreviewUtils.PreviewContentKind.TEXT,
            FilePreviewUtils.previewContentKind("export.csv", isBinary = true)
        )
        assertEquals(
            FilePreviewUtils.PreviewContentKind.TEXT,
            FilePreviewUtils.previewContentKind("Dockerfile", isBinary = true)
        )
    }

    @Test
    fun `previewContentKind keeps BINARY for non-whitelisted binary files`() {
        // 真 binary 不被白名单误中.
        assertEquals(
            FilePreviewUtils.PreviewContentKind.BINARY,
            FilePreviewUtils.previewContentKind("build/output.bin", isBinary = true)
        )
        assertEquals(
            FilePreviewUtils.PreviewContentKind.BINARY,
            FilePreviewUtils.previewContentKind("data.dat", isBinary = true)
        )
    }
}
