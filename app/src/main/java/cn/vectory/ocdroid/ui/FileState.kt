package cn.vectory.ocdroid.ui

/**
 * §R-17 M3: file-browser-domain state slice. Authoritative storage lives in
 * [MainViewModel._fileFlow]. Field set strictly follows RFC R-17 §2.6.
 * Consumed only by FilesScreen + the AppShell file-overlay (AppShell is the
 * sole shell; the legacy PhoneLayout + USE_NEW_SHELL flag were removed in
 * the redesign); isolating it prevents file-open/close from recomposing
 * unrelated subscribers.
 */
data class FileState(
    val filePathToShowInFiles: String? = null,
    val filePreviewOriginRoute: String? = null,
    val fileBrowserOpen: Boolean = false,
    val fileBrowserWorkdir: String? = null
)
