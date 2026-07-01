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
}
