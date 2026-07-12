package cn.vectory.ocdroid.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import cn.vectory.ocdroid.data.model.TodoItem

@Composable
fun TodoListPanel(
    todos: List<TodoItem>,
    modifier: Modifier = Modifier
) {
    if (todos.isEmpty()) {
        androidx.compose.foundation.layout.Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.foundation.layout.Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.Checklist,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.outline
                )
                Text(
                    "No todos yet",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
        return
    }

    val completed = todos.count { it.isCompleted }
    val total = todos.size

    LazyColumn(modifier = modifier.padding(horizontal = 16.dp)) {
        item {
            if (total > 0) {
                LinearProgressIndicator(
                    progress = { completed.toFloat() / total.toFloat() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                    trackColor = MaterialTheme.colorScheme.outlineVariant
                )
                Text(
                    "$completed/$total completed",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }
        }

        items(todos, key = { it.id }) { todo ->
            // §B4-P3C: 行渲染改 M3 ListItem（与 Agent/Model 选择 sheet 一致）。
            // - leadingContent = Checkbox（状态指示）。
            // - headlineContent = todo 内容 bodyLarge；completed 项叠删除线 + onSurfaceVariant。
            // - 无 per-item 背景色（底色由 AppBottomSheet 的 surfaceContainerLow 统一）。
            ListItem(
                leadingContent = {
                    Checkbox(
                        checked = todo.isCompleted,
                        onCheckedChange = null,
                        modifier = Modifier.size(20.dp),
                    )
                },
                headlineContent = {
                    Text(
                        text = todo.content,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            textDecoration = if (todo.isCompleted) TextDecoration.LineThrough else null,
                        ),
                        color = if (todo.isCompleted) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface,
                    )
                },
            )
        }
    }
}
