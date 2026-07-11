package cn.vectory.ocdroid.ui.workspace

import androidx.compose.runtime.Composable
import cn.vectory.ocdroid.ui.files.FilesScreen
import cn.vectory.ocdroid.ui.files.FilesViewModel

/** Route-owned home for the existing file browser; no duplicate file implementation. */
@Composable
fun FilesPane(
    viewModel: FilesViewModel,
    workdir: String?,
    pathToShow: String? = null,
    onFileClick: (String) -> Unit = {},
) {
    FilesScreen(
        viewModel = viewModel,
        pathToShow = pathToShow,
        sessionDirectory = workdir,
        onFileClick = onFileClick,
    )
}
