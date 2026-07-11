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

    // §F5b-whitelist: common text file extensions that the server sometimes
    // reports as binary (e.g. .log/.csv/.env/.lock). When `previewContentKind`
    // sees one of these, it forces TEXT regardless of the server's isBinary.
    // Grouped by category for readability; collapsed at runtime.
    private val textExtensions = setOf(
        // code
        "kt", "java", "py", "ts", "tsx", "js", "jsx", "go", "rs", "c", "cpp", "h", "hpp",
        "rb", "php", "swift", "scala", "sh", "bash", "zsh", "sql",
        // config / markup
        "json", "yaml", "yml", "toml", "ini", "conf", "cfg", "properties", "xml",
        "html", "htm", "css", "gradle", "env",
        // text / logs
        "txt", "log", "csv", "tsv", "lock", "map",
        // (md is handled by its own branch above; kept out to avoid redundancy)
    )

    // §F5b-whitelist: extensionless files (full-name match, case-insensitive).
    // Stored already-lowercased so `isTextPath` can compare without allocating.
    private val textFilenames = setOf(
        "makefile", "dockerfile", ".gitignore", ".gitattributes", ".editorconfig",
        ".babelrc", ".eslintrc", ".prettierrc", "jenkinsfile"
    )

    fun isImagePath(path: String): Boolean {
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext in imageExtensions
    }

    /**
     * §F5b-whitelist: true if [path] is a known text file (by full filename OR
     * extension, case-insensitive). Used to override the server's `isBinary`
     * report for common text files (.log/.csv/.env/.lock/Dockerfile/…).
     */
    fun isTextPath(path: String): Boolean {
        val filename = path.substringAfterLast('/', "").lowercase()
        if (filename.isEmpty()) return false
        if (filename in textFilenames) return true
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext.isNotEmpty() && ext in textExtensions
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
            // §F5b-whitelist: common text files that the server misreports as
            // binary (.log/.csv/.env/.lock/Dockerfile/…). Forces TEXT before
            // the isBinary check so they get a normal text preview.
            isTextPath(path) -> PreviewContentKind.TEXT
            isBinary -> PreviewContentKind.BINARY
            else -> PreviewContentKind.TEXT
        }
    }
}
