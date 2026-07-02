// ChatContextUsageDialog.kt — context-usage detail dialog and its private
// section/row helpers + count formatters. Split out of ChatTopBar.kt; pure
// relocation with no behaviour change.

package cn.vectory.ocdroid.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.ui.ContextUsage
import java.util.Locale

/**
 * R-28: fixed currency symbol for the context-usage cost display.
 *
 * Confirmed with the user: this value is always CNY regardless of device
 * locale (the upstream server reports cost in CNY), so we deliberately do NOT
 * use NumberFormat.getCurrencyInstance(locale) — that would render "$" or
 * "€" on non-CNY devices and mislead the user. Pinned to "¥" with the same
 * 4-decimal formatting as before, so the visual is unchanged; the constant
 * centralises the policy and documents why locale is intentionally ignored.
 */
private const val CURRENCY_SYMBOL = "¥"

@Composable
internal fun ContextUsageDialog(
    usage: ContextUsage?,
    onDismiss: () -> Unit,
    /**
     * §context-compact: optional callback fired when the user taps the
     * "压缩" (compact) button. When null (the default), the button is
     * hidden and the dialog behaves as before — so call sites that do not
     * care about compaction (and ChatScreen.kt) keep compiling unchanged.
     */
    onCompact: (() -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_context)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (usage == null) {
                    Text(
                        stringResource(R.string.chat_no_usage_data),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    ContextUsageSection(stringResource(R.string.chat_context_model_section)) {
                        ContextUsageRow(stringResource(R.string.chat_context_provider), usage.providerId ?: stringResource(R.string.chat_context_unknown))
                        ContextUsageRow(stringResource(R.string.chat_context_model), usage.modelId ?: stringResource(R.string.chat_context_unknown))
                        ContextUsageRow(stringResource(R.string.chat_context_limit), formatCount(usage.contextLimit))
                    }
                    ContextUsageSection(stringResource(R.string.chat_context_tokens)) {
                        ContextUsageRow(stringResource(R.string.chat_context_total), formatCount(usage.totalTokens))
                        ContextUsageRow(stringResource(R.string.chat_context_input), formatOptionalCount(usage.inputTokens))
                        ContextUsageRow(stringResource(R.string.chat_context_output), formatOptionalCount(usage.outputTokens))
                        ContextUsageRow(stringResource(R.string.chat_context_reasoning), formatOptionalCount(usage.reasoningTokens))
                        ContextUsageRow(stringResource(R.string.chat_context_cached_read), formatOptionalCount(usage.cachedReadTokens))
                        ContextUsageRow(stringResource(R.string.chat_context_cached_write), formatOptionalCount(usage.cachedWriteTokens))
                    }
                    ContextUsageSection(stringResource(R.string.chat_context_cost)) {
                        ContextUsageRow(stringResource(R.string.chat_context_cost), usage.cost?.let { "$CURRENCY_SYMBOL${String.format(Locale.US, "%.4f", it)}" } ?: stringResource(R.string.chat_context_no_cost))
                    }
                }
            }
        },
        // §context-compact: only render the compact affordance when the caller
        // wired a callback AND we actually have usage data to compact against.
        dismissButton = if (onCompact != null && usage != null) {
            {
                TextButton(onClick = onCompact) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.Archive,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.chat_context_compact))
                    }
                }
            }
        } else null,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_done))
            }
        }
    )
}

@Composable
private fun ContextUsageSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
        content()
    }
}

@Composable
private fun ContextUsageRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 16.dp)
        )
    }
}

private fun formatCount(value: Int): String = String.format(Locale.US, "%,d", value)

private fun formatOptionalCount(value: Int?): String = value?.let(::formatCount) ?: "-"
