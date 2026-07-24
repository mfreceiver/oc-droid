package cn.vectory.ocdroid.ui.files

import android.content.Context
import android.util.Log

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.vectory.ocdroid.data.model.FileContent
import cn.vectory.ocdroid.data.model.FileNode
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class FilesUiState(
    val currentPath: String = "",
    /**
     * §R-17 batch4 / §R18 Phase 2-E step 2: absolute workdir this browser is
     * scoped to. Threaded explicitly into every file API call (the global
     * currentDirectory state was removed). null while no session/project has
     * been bound yet — loads are skipped in that case because there is no
     * directory to scope them to.
     */
    val workdir: String? = null,
    val files: List<FileNode> = emptyList(),
    val fileStatuses: Map<String, String> = emptyMap(),
    val selectedFileContent: FileContent? = null,
    val selectedFilePath: String? = null,
    val isLoading: Boolean = false,
    val isPreviewRefreshing: Boolean = false,
    val error: String? = null,
    val showHiddenFiles: Boolean = false
)

@HiltViewModel
class FilesViewModel @Inject constructor(
    val repository: OpenCodeRepository,
    // §F5: 文件长按分享需 FileProvider + startActivity，注入 Application Context。
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    private val _state = MutableStateFlow(FilesUiState())
    val state: StateFlow<FilesUiState> = _state.asStateFlow()

    /**
     * §R-17 batch4: generation guard. Every public load entry point
     * ([refresh] / [navigateUp] / [selectFile] / [syncPathToShow] /
     * [refreshPreview] / [bindWorkdir]) bumps this counter and shares the
     * captured generation with the helpers it launches. A coroutine that
     * resolves with a stale generation (`gen != requestGeneration.get()`) returns
     * without touching [_state], so a slow response from the previous
     * directory/session cannot overwrite the freshly-bound UI.
     *
     * `@Volatile` because it is read inside coroutine lambdas that may
     * resume on different dispatchers. Uses [AtomicInteger] for atomic
     * increment + visibility across dispatcher boundaries.
     */
    private val requestGeneration = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * §R-17 batch4: binds the browser to [directory]. When the directory
     * changes (e.g. the user picks another session/project from the
     * Sessions tab, or the chat's current session changes), this invalidates
     * every in-flight load via [requestGeneration] and re-loads the file tree
     * + statuses scoped to the new workdir. Called by [FilesScreen] via
     * `LaunchedEffect(sessionDirectory)`.
     */
    fun bindWorkdir(directory: String?) {
        if (_state.value.workdir == directory) return
        _state.update { it.copy(workdir = directory) }
        refresh()
    }

    fun refresh() {
        val dir = _state.value.workdir ?: return
        val gen = requestGeneration.incrementAndGet()
        loadFiles(_state.value.currentPath, dir, gen)
        loadFileStatuses(dir, gen)
    }

    fun navigateUp() {
        val dir = _state.value.workdir ?: return
        val current = _state.value.currentPath
        // §R18 P1-6 fix (maxer+glmer): only an EMPTY currentPath is the true
        // root — no-op there without bumping gen. A '/'-less path like "src"
        // is a FIRST-LEVEL CHILD whose parent is "" (the workdir root); that
        // is a legitimate navigation, not a no-op. The prior guard
        // `normalized.substringBeforeLast('/', "").isEmpty()` returned "" for
        // any '/'-less path (substringBeforeLast returns the default when the
        // delimiter is absent), so it misclassified every top-level child as
        // root and blocked the child → root transition.
        if (current.isEmpty()) return  // true root: no-op, don't bump gen
        val normalized = current.trimEnd('/')
        // parentPath == "" is legitimate (back to workdir root) — do NOT return.
        val parentPath = if (normalized.isEmpty()) "" else normalized.substringBeforeLast('/', "")
        val gen = requestGeneration.incrementAndGet()
        _state.update { it.copy(currentPath = parentPath) }
        loadFiles(parentPath, dir, gen)
    }

    /**
     * §Q10 (P4b-B): resets the browser to the workdir root (currentPath = "")
     * and re-loads the top-level file tree. Used by the Files-tab reselect
     * subscription (reselectFlow == NavRoute.Files) so a second tap on the
     * Files tab returns the user to the workdir root from any nested depth.
     * No-op if already at root or no workdir is bound.
     */
    fun popToRoot() {
        val dir = _state.value.workdir ?: return
        if (_state.value.currentPath.isEmpty()) return  // already at root
        val gen = requestGeneration.incrementAndGet()
        _state.update { it.copy(currentPath = "") }
        loadFiles("", dir, gen)
    }

    fun selectFile(file: FileNode, onFileClick: (String) -> Unit = {}) {
        val dir = _state.value.workdir ?: return
        val gen = requestGeneration.incrementAndGet()
        if (file.isDirectory) {
            _state.update { it.copy(currentPath = file.path) }
            loadFiles(file.path, dir, gen)
        } else {
            onFileClick(file.path)
            loadFileContent(file.path, dir, gen)
        }
    }

    fun toggleShowHiddenFiles() {
        _state.update { it.copy(showHiddenFiles = !it.showHiddenFiles) }
        refresh()
    }

    fun closePreview() {
        // Closing the preview is a pure local state change; no network load
        // to invalidate, so we do NOT bump the generation here.
        _state.update {
            it.copy(
                selectedFilePath = null,
                selectedFileContent = null,
                isPreviewRefreshing = false
            )
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    /**
     * §F5: 长按文件分享。文件在远端服务器——先 getFileContent 取内容（文本=UTF-8
     * 字符串、二进制=base64），再落盘 + 系统分享（见 [shareFileContent]，覆盖所有
     * 格式）。§kimo-B5 + §F5-ZLM: 落盘/解码切到 Dispatchers.IO 避免主线程写盘/ANR；
     * try/catch 兜底 IOException/SecurityException，失败仅记日志（长按是次要路径，
     * 不崩溃优于弹窗）。
     */
    fun shareFile(file: FileNode) {
        val dir = _state.value.workdir ?: return
        val relPath = file.path
        viewModelScope.launch {
            repository.getFileContent(dir, relPath)
                .onSuccess { content ->
                    // §F5-ZLM (C4): shareFileContent 现为 suspend，内部已用
                    // withContext(Dispatchers.IO) 写盘 + withContext(Main) 启动 chooser，
                    // 故此处不再套外层 IO 调度（消除冗余嵌套）；try/catch 兜底，
                    // 行为不变（IO failure 仅记日志）。
                    try {
                        shareFileContent(appContext, relPath, content)
                    } catch (e: Exception) {
                        Log.w("FilesViewModel", "shareFile failed: ${e.message}")
                    }
                }
                .onFailure { Log.w("FilesViewModel", "shareFile fetch failed: ${it.message}") }
        }
    }

    fun syncPathToShow(pathToShow: String?, sessionDirectory: String?) {
        // §R-17 batch4: keep workdir in sync with the session the caller is
        // previewing against — if the chat switched sessions, the new
        // directory takes effect immediately (and invalidates any stale load).
        if (_state.value.workdir != sessionDirectory) {
            _state.update { it.copy(workdir = sessionDirectory) }
        }

        if (pathToShow == null) {
            closePreview()
            return
        }

        // §R18 P1-5: deep-link entry only has a path string (no FileNode), so
        // isDirectory is unknown — loadPreview will probe via getFileContent
        // and fall back to a tree lookup on failure / non-file type response.
        loadPreview(pathToShow, sessionDirectory, isRefresh = false, isDirectory = null)
    }

    fun refreshPreview(sessionDirectory: String?) {
        if (_state.value.workdir != sessionDirectory) {
            _state.update { it.copy(workdir = sessionDirectory) }
        }
        val pathToShow = _state.value.selectedFilePath ?: return
        // §R18 P1-5: state tracks only the path, not whether it was a dir/file,
        // so the reload also probes. (A future commit could persist the kind
        // alongside selectedFilePath and pass it through.)
        loadPreview(pathToShow, sessionDirectory, isRefresh = true, isDirectory = null)
    }

    /**
     * §R18 P1-5: [isDirectory] is a caller-provided hint that selects between
     * the file-content path and the directory-tree path WITHOUT relying on
     * content emptiness:
     *  - `true`  → skip [OpenCodeRepository.getFileContent] entirely and go
     *              straight to [loadDirectoryPreview]. Used when the caller
     *              already has a [FileNode] (or equivalent signal) and wants
     *              to avoid both the empty-file-vs-directory ambiguity and a
     *              wasted round-trip.
     *  - `false` → trust the file response unconditionally (an empty file is
     *              still a file).
     *  - `null`  → probe mode for deep-link entries that only have a path
     *              string. We issue a single [OpenCodeRepository.getFileContent]
     *              call; success with `type != "directory"` resolves as a file
     *              (covers text, binary, image, video, and any future file
     *              type — even when content is empty/blank), otherwise we fall
     *              back to [loadDirectoryPreview]. This is single-shot: we
     *              never issue both calls in parallel, so a directory path
     *              costs at most one extra round-trip.
     */
    private fun loadPreview(
        pathToShow: String,
        sessionDirectory: String?,
        isRefresh: Boolean,
        isDirectory: Boolean?
    ) {
        val relPath = resolveRelativePreviewPath(pathToShow, sessionDirectory)
        // §R-17 batch4 fix (maxer): only bump gen after confirming workdir is valid,
        // to avoid invalidating other in-flight loads when there's nothing to load.
        if (sessionDirectory.isNullOrBlank()) {
            _state.update { it.copy(error = "No workdir bound; cannot load preview.") }
            return
        }
        val gen = requestGeneration.incrementAndGet()

        if (isDirectory == true) {
            viewModelScope.launch {
                if (isRefresh) {
                    _state.update { it.copy(isPreviewRefreshing = true, error = null) }
                }
                loadDirectoryPreview(pathToShow, relPath, sessionDirectory, isRefresh, gen)
            }
            return
        }

        viewModelScope.launch {
            if (isRefresh) {
                _state.update { it.copy(isPreviewRefreshing = true, error = null) }
            }
            // Unreachable — outer loadPreview already early-returns on null workdir.
            // Kept as defense-in-depth.
            val dir = sessionDirectory
            if (dir.isNullOrBlank()) {
                _state.update {
                    it.copy(
                        isPreviewRefreshing = false,
                        error = "No workdir bound; cannot load preview."
                    )
                }
                return@launch
            }
            repository.getFileContent(dir, relPath)
                .onSuccess { content ->
                    if (gen != requestGeneration.get()) return@launch
                    // §R18 P1-5 fix (glmer): discriminate by
                    // `content.type != "directory"`, NOT by `isText || isBinary`.
                    // An empty text/binary file legitimately returns
                    // content == null/""; the prior `!content.content.isNullOrBlank()`
                    // check misdetected such files as directories and silently
                    // swapped in a tree preview. Using `type != "directory"`
                    // (instead of the narrower `isText || isBinary`) also
                    // future-proofs against upcoming file kinds — image/video/
                    // etc. all carry a non-"directory" type and must read as
                    // files, which the prior check would have misclassified
                    // as a directory. isDirectory==false (caller-asserted
                    // file) trusts the response unconditionally; null falls
                    // back to a tree lookup only when the server explicitly
                    // reports type == "directory".
                    if (isDirectory == false || content.type != "directory") {
                        _state.update {
                            it.copy(
                                selectedFileContent = content,
                                selectedFilePath = pathToShow,
                                isPreviewRefreshing = false,
                                error = null
                            )
                        }
                    } else {
                        loadDirectoryPreview(pathToShow, relPath, dir, isRefresh, gen)
                    }
                }
                .onFailure {
                    if (gen != requestGeneration.get()) return@launch
                    loadDirectoryPreview(pathToShow, relPath, dir, isRefresh, gen)
                }
        }
    }

    private fun setDirectoryPreview(path: String, relPath: String, tree: List<FileNode>) {
        _state.update {
            it.copy(
                selectedFilePath = path,
                selectedFileContent = FileContent(
                    type = "text",
                    content = buildDirectoryPreviewContent(relPath, tree)
                ),
                isPreviewRefreshing = false,
                error = null
            )
        }
    }

    private suspend fun loadDirectoryPreview(
        path: String,
        relPath: String,
        directory: String,
        isRefresh: Boolean = false,
        gen: Int
    ) {
        repository.getFileTree(directory, relPath)
            .onSuccess { tree ->
                if (gen != requestGeneration.get()) return
                setDirectoryPreview(path, relPath, tree.filterNot { f -> !_state.value.showHiddenFiles && (f.name.startsWith(".") || f.ignored == true) })
            }
            .onFailure { throwable ->
                if (gen != requestGeneration.get()) return
                _state.update {
                    it.copy(
                        isPreviewRefreshing = if (isRefresh) false else it.isPreviewRefreshing,
                        error = throwable.message
                    )
                }
            }
    }

    private fun loadFiles(path: String, directory: String, gen: Int) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            repository.getFileTree(directory, path.ifEmpty { null })
                .onSuccess { tree ->
                    if (gen != requestGeneration.get()) return@launch
                    _state.update {
                        it.copy(
                            files = tree.filterNot { f -> !_state.value.showHiddenFiles && (f.name.startsWith(".") || f.ignored == true) },
                            isLoading = false
                        )
                    }
                }
                .onFailure { throwable ->
                    if (gen != requestGeneration.get()) return@launch
                    Log.e("OC_ERROR", "loadFiles getFileTree failed", throwable)
                    _state.update {
                        it.copy(
                            isLoading = false,
                            error = throwable.message
                        )
                    }
                }
        }
    }

    private fun loadFileStatuses(directory: String, gen: Int) {
        viewModelScope.launch {
            repository.getFileStatus(directory)
                .onSuccess { statuses ->
                    if (gen != requestGeneration.get()) return@launch
                    _state.update {
                        it.copy(
                            fileStatuses = statuses.mapNotNull { entry ->
                                entry.path?.let { path -> path to (entry.status ?: "untracked") }
                            }.toMap()
                        )
                    }
                }
        }
    }

    private fun loadFileContent(path: String, directory: String, gen: Int) {
        viewModelScope.launch {
            repository.getFileContent(directory, path)
                .onSuccess { content ->
                    if (gen != requestGeneration.get()) return@launch
                    _state.update {
                        it.copy(
                            selectedFileContent = content,
                            selectedFilePath = path,
                            error = null
                        )
                    }
                }
                .onFailure { throwable ->
                    if (gen != requestGeneration.get()) return@launch
                    _state.update { it.copy(error = throwable.message) }
                }
        }
    }
}
