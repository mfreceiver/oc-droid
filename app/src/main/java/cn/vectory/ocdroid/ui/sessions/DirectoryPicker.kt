package cn.vectory.ocdroid.ui.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.FileNode
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.ui.theme.AppBottomSheet
import cn.vectory.ocdroid.ui.theme.Dimens

/**
 * A directory browser shown as a modal bottom sheet. Used by the Sessions tab
 * "Connect new project" entry to let the user pick a project workdir on the
 * server instead of typing a path by hand.
 *
 * Browsing starts at `/home/` (common Linux home base). Only directories are
 * listed; files are filtered out. Tapping a folder descends into it; the up
 * affordance returns to the parent. Confirming calls [onSelect] with the
 * currently displayed path (absolute, `/`-rooted).
 *
 * Listings are fetched via [OpenCodeRepository.getFileTreeForDirectory], which
 * bypasses the repository's session-scoped workdir injection so browsing is
 * independent of the currently selected session.
 *
 * @param repository   the OpenCode repository used to fetch directory listings.
 * @param onDismiss    invoked when the user cancels (back button, scrim, etc.).
 * @param onSelect     invoked with the chosen absolute path when confirmed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DirectoryPickerSheet(
    repository: OpenCodeRepository,
    onDismiss: () -> Unit,
    onSelect: (path: String) -> Unit
) {
    var currentPath by remember { mutableStateOf("/home/") }
    var entries by remember { mutableStateOf<List<FileNode>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    // A reload trigger so the error retry button can force a re-fetch even when
    // currentPath is unchanged.
    var reloadNonce by remember { mutableStateOf(0) }

    LaunchedEffect(currentPath, reloadNonce) {
        isLoading = true
        error = null
        repository.getFileTreeForDirectory(directory = currentPath, path = "")
            .onSuccess { tree ->
                entries = tree
                    .filter { it.isDirectory }
                    .filterNot { it.name.startsWith(".") }
                    .sortedBy { it.name.lowercase() }
                isLoading = false
            }
            .onFailure { throwable ->
                error = throwable.message ?: "Failed to load directory"
                entries = emptyList()
                isLoading = false
            }
    }

    val canGoUp = parentPath(currentPath) != null

    // §WT0: 迁移到 AppBottomSheet（统一容器色 / sheetState / title 行 + 底部
    // 16dp Spacer）。原 `ModalBottomSheet { ... }` + `rememberModalBottomSheetState`
    // + 手写 title Row 由 scaffold 接管；close IconButton 走 titleTrailing 槽
    // （与现网「标题右侧 ×」语义对齐）；其余 path 显示 / up affordance / listing
    // / action bar 全部保留。注意：底部 action bar 不用 scaffold 的 `footer`
    // 槽（footer 会强制加 divider + 16dp padding，与现网 action bar 的 12dp
    // padding 不同）——保持原视觉。
    AppBottomSheet(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.sessions_tab_new_workdir),
        titleTrailing = {
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.common_close),
                )
            }
        },
    ) {
        Text(
            text = currentPath,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = Dimens.spacing4, end = Dimens.spacing4, bottom = Dimens.spacing2),
        )

        Spacer(modifier = Modifier.height(Dimens.spacing2))

        // --- Up affordance ---
        if (canGoUp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        parentPath(currentPath)?.let { currentPath = it }
                    }
                    .padding(horizontal = Dimens.spacing4, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(Dimens.spacing3))
                Text(
                    text = "..",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(modifier = Modifier.height(Dimens.spacing2))
        }

        // --- Directory listing (weights to fill available sheet height) ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = true)
        ) {
            when {
                isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(Dimens.spacing7),
                    )
                }

                error != null -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(Dimens.spacing6),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = error!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        Spacer(Modifier.size(Dimens.spacing2))
                        TextButton(onClick = { reloadNonce++ }) {
                            Text(stringResource(R.string.common_refresh))
                        }
                    }
                }

                entries.isEmpty() -> {
                    Text(
                        text = stringResource(R.string.sessions_picker_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(Dimens.spacing6),
                    )
                }

                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(entries, key = { it.path }) { node ->
                            DirectoryRow(
                                name = node.name,
                                onClick = {
                                    currentPath = childPath(currentPath, node.name)
                                },
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(Dimens.spacing2))

        // --- Action bar ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Dimens.spacing3),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
            Spacer(Modifier.width(Dimens.spacing2))
            Button(
                onClick = { onSelect(currentPath) },
                enabled = !isLoading,
            ) {
                Text(stringResource(R.string.sessions_tab_add_workdir))
            }
        }
    }
}

@Composable
private fun DirectoryRow(name: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            Icon(
                Icons.Default.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface
            )
        },
        headlineContent = {
            Text(
                text = name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    )
}

/**
 * Returns the parent path of [path] (an absolute `/`-rooted path), or null if
 * [path] is already the filesystem root (`/`) and cannot ascend further.
 */
private fun parentPath(path: String): String? {
    if (path == "/") return null
    // Normalize trailing slash (except for root) so "/home/" and "/home" behave
    // identically when ascending.
    val normalized = if (path.length > 1 && path.endsWith("/")) path.dropLast(1) else path
    if (normalized == "/") return null
    val idx = normalized.lastIndexOf('/')
    return if (idx == 0) "/" else normalized.substring(0, idx)
}

/** Appends a child segment [name] to [current], handling trailing `/`. */
private fun childPath(current: String, name: String): String {
    return if (current.endsWith("/")) "$current$name" else "$current/$name"
}
