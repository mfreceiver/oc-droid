package cn.vectory.ocdroid.ui.files

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.FileNode
import cn.vectory.ocdroid.ui.theme.SemanticColors

@Composable
internal fun FileBrowserPane(
    files: List<FileNode>,
    fileStatuses: Map<String, String>,
    onFileSelected: (FileNode) -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        items(files, key = { it.path }) { file ->
            FileRow(
                file = file,
                status = fileStatuses[file.path],
                onClick = { onFileSelected(file) }
            )
        }
    }
}

@Composable
internal fun FileRow(
    file: FileNode,
    status: String?,
    onClick: () -> Unit
) {
    val statusColor = when (status) {
        "added" -> SemanticColors.addedFile
        "modified" -> SemanticColors.modifiedFile
        "deleted" -> SemanticColors.deletedFile
        else -> if (status == "untracked") SemanticColors.untrackedFile else null
    }

    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            Icon(
                imageVector = if (file.isDirectory) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                contentDescription = null,
                tint = statusColor ?: MaterialTheme.colorScheme.onSurface
            )
        },
        headlineContent = {
            Text(
                text = file.name,
                color = statusColor ?: MaterialTheme.colorScheme.onSurface
            )
        },
        trailingContent = {
            if (file.ignored == true) {
                Text(
                    text = stringResource(R.string.files_ignored),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    )
}
