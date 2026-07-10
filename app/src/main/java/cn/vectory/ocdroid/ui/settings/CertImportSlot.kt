package cn.vectory.ocdroid.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * §mtls-clipboard: status of a single certificate-import slot. Drives the
 * three visual states (empty / imported / error) without touching the
 * underlying storage layer — the owning dialog maps [Imported] / [Error] to
 * its `stagedP12` / `caStage` state and renders it via `CompactCertStatusRow`.
 */
sealed interface CertSlotStatus {
    /** Nothing pasted yet (or removed). */
    data object Empty : CertSlotStatus
    /** A valid cert was decoded; [label] is the subject, [sizeBytes] the payload size. */
    data class Imported(val label: String, val sizeBytes: Int) : CertSlotStatus
    /** Paste / decode / validation failed; [message] is shown inline in error color. */
    data class Error(val message: String) : CertSlotStatus
}

/**
 * §mtls-clipboard: a section header (title + optional subtitle on the left,
 * [Switch] on the right) that reveals [content] only while [checked] is true.
 *
 * Used to collapse the Basic Auth, Tunnel, and mTLS groups so a new profile
 * (all OFF) shows only Name + URL + Server group + Insecure toggle, while an
 * existing profile expands the sections it actually uses. The card-style
 * container gives each group clear visual weight inside the editor dialog.
 */
@Composable
internal fun CollapsibleSection(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    // §review-r5 (layout): flat full-width section — no Surface/card wrapper, so
    // the header + content align with sibling sections (e.g. the insecure-HTTPS
    // Row) instead of being inset by card padding (which made mTLS/Basic-Auth/
    // Tunnel render narrower than the flat sections above them).
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
            )
        }
        AnimatedVisibility(
            visible = checked,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(Modifier.padding(top = 12.dp)) {
                content()
            }
        }
    }
}
