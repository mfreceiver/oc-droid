package cn.vectory.ocdroid.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import cn.vectory.ocdroid.data.api.CommandInfo
import cn.vectory.ocdroid.ui.theme.AppTextStyles

// §phase3 (plan §5 task 6 step c): the legacy `ChatInputBar` composable +
// its private `ChatPrimaryActionButton` helper that lived in this file were
// fully superseded by `Composer.kt` (Phase 1B) and have been deleted. The
// `CommandSuggestionsPanel` below is the ONLY remaining declaration — it is
// still reused by Composer.kt's slash-command autocomplete, so it stays.
// `handleComposerSend` (the old send-dispatch helper) was previously lifted
// to ChatFormatHelpers.kt and is covered by JVM unit tests there.

/**
 * Slash-command autocomplete list. Rendered above the composer input row when
 * the user has typed a leading "/" with no space yet. Matching is prefix-based
 * against the merged local+server command list. Tapping a suggestion fills the
 * input with "/<name> " so the user can append arguments.
 *
 * Kept in this file (vs. moved into Composer.kt) to minimise churn — the file
 * name is historical. Excluded from kover coverage as a pure @Composable.
 */
@Composable
internal fun CommandSuggestionsPanel(
    commands: List<CommandInfo>,
    onPick: (String) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        shape = RectangleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 1.dp
    ) {
        // Cap the visible list so a long server command catalog cannot shove
        // the input off-screen on small devices.
        LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
            items(commands, key = { it.name }) { cmd ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(cmd.name) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "/${cmd.name}",
                        style = AppTextStyles.codeBody,
                        color = MaterialTheme.colorScheme.primary
                    )
                    cmd.description?.takeIf { it.isNotBlank() }?.let { desc ->
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = desc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}
