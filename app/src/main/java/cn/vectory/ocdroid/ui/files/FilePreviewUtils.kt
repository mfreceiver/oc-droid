package cn.vectory.ocdroid.ui.files

object FilePreviewUtils {
    enum class PreviewContentKind {
        IMAGE,
        MARKDOWN,
        BINARY,
        TEXT
    }

    private val imageExtensions = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "tiff", "tif", "heic", "heif", "ico", "svg"
    )

    fun isImagePath(path: String): Boolean {
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext in imageExtensions
    }

    fun imageMimeType(path: String): String {
        return when (path.substringAfterLast('.', "").lowercase()) {
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

    fun previewContentKind(path: String, isBinary: Boolean): PreviewContentKind {
        return when {
            isImagePath(path) -> PreviewContentKind.IMAGE
            path.endsWith(".md", ignoreCase = true) -> PreviewContentKind.MARKDOWN
            isBinary -> PreviewContentKind.BINARY
            else -> PreviewContentKind.TEXT
        }
    }
}
