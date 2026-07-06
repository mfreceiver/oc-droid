package cn.vectory.ocdroid.ui.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R18 Phase 5 — [FilePreviewUtils] branch coverage extension.
 *
 * The existing `cn.vectory.ocdroid.FilePreviewUtilsTest` exercises the happy
 * path (png / jpg / webp + the four PreviewContentKind buckets). This file
 * fills in the remaining branches so the `when` tables in [isImagePath] and
 * [imageMimeType] are fully traversed:
 *
 *  - every extension entry of [imageExtensions] (gif / bmp / tiff / tif / heic
 *    / heif / ico / svg) for both `isImagePath` and `imageMimeType`.
 *  - uppercase + mixed-case paths.
 *  - no-extension path, empty path, dot-only path.
 *  - `.MD` markdown detection (case-insensitive) preview bucket.
 *  - `imageMimeType` fallback for an unknown extension AND for an extensionless
 *    path (both must yield the generic image MIME fallback).
 */
class FilePreviewUtilsCoverageTest {

    // ───────────────── isImagePath: full extension matrix ─────────────────

    @Test
    fun `isImagePath returns true for every recognised extension`() {
        for (ext in listOf(
            "png", "jpg", "jpeg", "gif", "webp", "bmp",
            "tiff", "tif", "heic", "heif", "ico", "svg",
        )) {
            assertTrue("expected image for ext=$ext", FilePreviewUtils.isImagePath("dir/file.$ext"))
        }
    }

    @Test
    fun `isImagePath is case-insensitive for uppercase extensions`() {
        assertTrue(FilePreviewUtils.isImagePath("X.GIF"))
        assertTrue(FilePreviewUtils.isImagePath("X.BMP"))
        assertTrue(FilePreviewUtils.isImagePath("X.TIFF"))
        assertTrue(FilePreviewUtils.isImagePath("X.HEIC"))
        assertTrue(FilePreviewUtils.isImagePath("X.ICO"))
        assertTrue(FilePreviewUtils.isImagePath("X.SVG"))
    }

    @Test
    fun `isImagePath is case-insensitive for mixed-case extensions`() {
        assertTrue(FilePreviewUtils.isImagePath("photo.Tiff"))
        assertTrue(FilePreviewUtils.isImagePath("icon.SvG"))
    }

    @Test
    fun `isImagePath false for non-image extensions`() {
        assertFalse(FilePreviewUtils.isImagePath("readme.md"))
        assertFalse(FilePreviewUtils.isImagePath("script.kt"))
        assertFalse(FilePreviewUtils.isImagePath("archive.zip"))
        assertFalse(FilePreviewUtils.isImagePath("data.json"))
    }

    @Test
    fun `isImagePath false for path with no extension`() {
        assertFalse(FilePreviewUtils.isImagePath("README"))
        assertFalse(FilePreviewUtils.isImagePath("dir/subdir/"))
    }

    @Test
    fun `isImagePath false for empty path`() {
        assertFalse(FilePreviewUtils.isImagePath(""))
    }

    @Test
    fun `isImagePath false for dot-only filename`() {
        // substringAfterLast('.', "") yields "" → not in the set.
        assertFalse(FilePreviewUtils.isImagePath("."))
        assertFalse(FilePreviewUtils.isImagePath("foo/."))
    }

    @Test
    fun `isImagePath handles trailing-dot filename`() {
        // "foo." → extension is empty string → not an image.
        assertFalse(FilePreviewUtils.isImagePath("foo."))
    }

    // ───────────────── imageMimeType: full mapping ─────────────────

    @Test
    fun `imageMimeType maps every image extension`() {
        assertEquals("image/png", FilePreviewUtils.imageMimeType("a.png"))
        assertEquals("image/jpeg", FilePreviewUtils.imageMimeType("a.jpg"))
        assertEquals("image/jpeg", FilePreviewUtils.imageMimeType("a.jpeg"))
        assertEquals("image/gif", FilePreviewUtils.imageMimeType("a.gif"))
        assertEquals("image/webp", FilePreviewUtils.imageMimeType("a.webp"))
        assertEquals("image/bmp", FilePreviewUtils.imageMimeType("a.bmp"))
        assertEquals("image/tiff", FilePreviewUtils.imageMimeType("a.tiff"))
        assertEquals("image/tiff", FilePreviewUtils.imageMimeType("a.tif"))
        assertEquals("image/heic", FilePreviewUtils.imageMimeType("a.heic"))
        assertEquals("image/heif", FilePreviewUtils.imageMimeType("a.heif"))
        assertEquals("image/x-icon", FilePreviewUtils.imageMimeType("a.ico"))
        assertEquals("image/svg+xml", FilePreviewUtils.imageMimeType("a.svg"))
    }

    @Test
    fun `imageMimeType is case-insensitive`() {
        assertEquals("image/png", FilePreviewUtils.imageMimeType("a.PNG"))
        assertEquals("image/jpeg", FilePreviewUtils.imageMimeType("a.Jpg"))
        assertEquals("image/svg+xml", FilePreviewUtils.imageMimeType("a.SVG"))
    }

    @Test
    fun `imageMimeType falls back to image slash star for unknown extension`() {
        assertEquals("image/*", FilePreviewUtils.imageMimeType("a.dat"))
        assertEquals("image/*", FilePreviewUtils.imageMimeType("a.unknownext"))
    }

    @Test
    fun `imageMimeType falls back to image slash star for extensionless path`() {
        assertEquals("image/*", FilePreviewUtils.imageMimeType("noext"))
        assertEquals("image/*", FilePreviewUtils.imageMimeType(""))
        assertEquals("image/*", FilePreviewUtils.imageMimeType("foo."))
    }

    // ───────────────── previewContentKind: edge cases ─────────────────

    @Test
    fun `previewContentKind prioritises image over binary`() {
        // An image extension is recognised even when isBinary is true — image
        // preview wins over the generic BINARY bucket.
        assertEquals(
            FilePreviewUtils.PreviewContentKind.IMAGE,
            FilePreviewUtils.previewContentKind("foo.png", isBinary = true),
        )
    }

    @Test
    fun `previewContentKind recognises markdown case-insensitively`() {
        assertEquals(
            FilePreviewUtils.PreviewContentKind.MARKDOWN,
            FilePreviewUtils.previewContentKind("readme.MD", isBinary = false),
        )
        assertEquals(
            FilePreviewUtils.PreviewContentKind.MARKDOWN,
            FilePreviewUtils.previewContentKind("readme.md", isBinary = false),
        )
    }

    @Test
    fun `previewContentKind binary beats text for non-image non-markdown`() {
        assertEquals(
            FilePreviewUtils.PreviewContentKind.BINARY,
            FilePreviewUtils.previewContentKind("archive.zip", isBinary = true),
        )
    }

    @Test
    fun `previewContentKind text for non-image non-markdown non-binary`() {
        assertEquals(
            FilePreviewUtils.PreviewContentKind.TEXT,
            FilePreviewUtils.previewContentKind("notes.txt", isBinary = false),
        )
    }

    @Test
    fun `previewContentKind text for extensionless non-binary path`() {
        assertEquals(
            FilePreviewUtils.PreviewContentKind.TEXT,
            FilePreviewUtils.previewContentKind("README", isBinary = false),
        )
    }
}
