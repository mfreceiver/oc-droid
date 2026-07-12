package cn.vectory.ocdroid.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.FileContent
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.ui.files.FilePreviewPane
import cn.vectory.ocdroid.ui.files.resolveRelativePreviewPath
import kotlinx.coroutines.CancellationException

/** Repository-backed Chat preview with no dependency on mutable FilesViewModel state. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatFilePreviewScreen(
    repository: OpenCodeRepository,
    workdir: String?,
    path: String?,
    onClose: () -> Unit,
) {
    var content by remember(workdir, path) { mutableStateOf<FileContent?>(null) }
    var error by remember(workdir, path) { mutableStateOf<String?>(null) }
    var refreshKey by remember(workdir, path) { mutableStateOf(0) }
    // §B2: resolve the locally-generated fallback strings at the composable
    // scope (stringResource is @Composable — can't be called inside the
    // LaunchedEffect below). Server-provided `it.message` / `failure.message`
    // stay as-is per the existing policy (server-owned text).
    val noPathMessage = stringResource(R.string.chat_preview_no_path)
    val loadFailedMessage = stringResource(R.string.chat_preview_load_failed)

    LaunchedEffect(repository, workdir, path, refreshKey) {
        content = null
        error = null
        if (workdir.isNullOrBlank() || path.isNullOrBlank()) {
            error = noPathMessage
            return@LaunchedEffect
        }
        try {
            repository.getFileContent(workdir, resolveRelativePreviewPath(path, workdir))
                .onSuccess { content = it }
                .onFailure { error = it.message ?: loadFailedMessage }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: loadFailedMessage
        }
    }

    val loaded = content
    if (loaded != null && !path.isNullOrBlank()) {
        FilePreviewPane(
            path = path,
            fileContent = loaded,
            repository = repository,
            sessionDirectory = workdir,
            onRefresh = { refreshKey++ },
            onClose = onClose,
        )
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(path?.substringAfterLast('/').orEmpty()) },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.common_back),
                            )
                        }
                    },
                )
            },
        ) { padding ->
            Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                if (error == null) CircularProgressIndicator() else Text(error.orEmpty())
            }
        }
    }
}
