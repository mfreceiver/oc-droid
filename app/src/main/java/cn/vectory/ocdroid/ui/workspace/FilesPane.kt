package cn.vectory.ocdroid.ui.workspace

import androidx.compose.runtime.Composable
import cn.vectory.ocdroid.ui.OrchestratorViewModel
import cn.vectory.ocdroid.ui.files.FilesScreen
import cn.vectory.ocdroid.ui.files.FilesViewModel

/** Route-owned home for the existing file browser; no duplicate file implementation. */
@Composable
fun FilesPane(
    viewModel: FilesViewModel,
    orchestratorVM: OrchestratorViewModel,
    workdir: String?,
    pathToShow: String? = null,
    onFileClick: (String) -> Unit = {},
) {
    FilesScreen(
        viewModel = viewModel,
        orchestratorVM = orchestratorVM,
        pathToShow = pathToShow,
        sessionDirectory = workdir,
        onFileClick = onFileClick,
    )
}
