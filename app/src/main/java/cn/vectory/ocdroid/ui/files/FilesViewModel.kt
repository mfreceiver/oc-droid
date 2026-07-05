package cn.vectory.ocdroid.ui.files

import android.util.Log

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.vectory.ocdroid.data.model.FileContent
import cn.vectory.ocdroid.data.model.FileNode
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FilesUiState(
    val currentPath: String = "",
    /**
     * §R-17 batch4: absolute workdir this browser is scoped to. Threaded
     * explicitly into every file API call (replacing the prior reliance on
     * [OpenCodeRepository.setCurrentDirectory]'s global state). null while
     * no session/project has been bound yet — loads are skipped in that case
     * because there is no directory to scope them to.
     */
    val workdir: String? = null,
    val files: List<FileNode> = emptyList(),
    val fileStatuses: Map<String, String> = emptyMap(),
    val selectedFileContent: FileContent? = null,
    val selectedFilePath: String? = null,
    val isLoading: Boolean = false,
    val isPreviewRefreshing: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class FilesViewModel @Inject constructor(
    val repository: OpenCodeRepository
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
        val gen = requestGeneration.incrementAndGet()
        val parentPath = _state.value.currentPath.substringBeforeLast("/", "")
        _state.update { it.copy(currentPath = parentPath) }
        loadFiles(parentPath, dir, gen)
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

        loadPreview(pathToShow, sessionDirectory, isRefresh = false)
    }

    fun refreshPreview(sessionDirectory: String?) {
        if (_state.value.workdir != sessionDirectory) {
            _state.update { it.copy(workdir = sessionDirectory) }
        }
        val pathToShow = _state.value.selectedFilePath ?: return
        loadPreview(pathToShow, sessionDirectory, isRefresh = true)
    }

    private fun loadPreview(pathToShow: String, sessionDirectory: String?, isRefresh: Boolean) {
        val relPath = resolveRelativePreviewPath(pathToShow, sessionDirectory)
        // §R-17 batch4 fix (maxer): only bump gen after confirming workdir is valid,
        // to avoid invalidating other in-flight loads when there's nothing to load.
        if (sessionDirectory.isNullOrBlank()) {
            _state.update { it.copy(error = "No workdir bound; cannot load preview.") }
            return
        }
        val gen = requestGeneration.incrementAndGet()
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
                    if (!content.content.isNullOrBlank()) {
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
                setDirectoryPreview(path, relPath, tree.filterNot { f -> f.name.startsWith(".") })
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
                            files = tree.filterNot { f -> f.name.startsWith(".") },
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
