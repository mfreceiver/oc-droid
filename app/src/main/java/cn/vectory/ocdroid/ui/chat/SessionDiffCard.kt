package cn.vectory.ocdroid.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Difference
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.FileDiff

/** Timeline summary only; unified diff inspection belongs to Workspace Changes. */
@Composable
internal fun SessionDiffCard(
    sessionId: String,
    diffs: List<FileDiff>,
    onOpenChanges: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (diffs.isEmpty()) return
    ListItem(
        headlineContent = { Text(stringResource(R.string.session_diff_files_count, diffs.size)) },
        leadingContent = { Icon(Icons.Default.Difference, contentDescription = null) },
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp).clickable { onOpenChanges(sessionId) },
    )
}
