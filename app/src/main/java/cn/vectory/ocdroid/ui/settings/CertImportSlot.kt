package cn.vectory.ocdroid.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.vectory.ocdroid.R

/**
 * §mtls-clipboard: status of a single certificate-import slot rendered by
 * [CertImportSlot]. Drives the three visual states (empty / imported / error)
 * without touching the underlying storage layer — the owning dialog maps
 * [Imported] / [Error] to its `stagedP12` / `caStage` state.
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
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
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
                Spacer(Modifier.width(12.dp))
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
}

/**
 * §mtls-clipboard: a clipboard-paste import slot for one certificate role.
 *
 * - [CertSlotStatus.Empty] → a full-width paste button.
 * - [CertSlotStatus.Imported] → a status line (role + subject + size) with a
 *   trailing remove button that clears the staged value.
 * - [CertSlotStatus.Error] → the error message plus the paste button again so
 *   the user can retry immediately.
 *
 * [onPaste] / [onRemove] are pure UI callbacks; the owning dialog performs the
 * actual decode/validate and updates [status]. [enabled] should be false while
 * a paste-validation coroutine is in flight so concurrent pastes are blocked.
 */
@Composable
internal fun CertImportSlot(
    roleLabel: String,
    status: CertSlotStatus,
    onPaste: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    pasteLabel: String = stringResource(R.string.host_cert_paste_generic),
    enabled: Boolean = true,
) {
    Column(modifier.fillMaxWidth()) {
        when (val s = status) {
            CertSlotStatus.Empty -> PasteButton(pasteLabel, onPaste, enabled)

            is CertSlotStatus.Imported -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = ImportedCheckColor,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "$roleLabel: ${s.label} · ${s.sizeBytes} B",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                // §review-5: respect [enabled] so a paste-validation coroutine in
                // flight can't race with a remove (the paste button is already gated
                // by the same flag via PasteButton).
                IconButton(onClick = onRemove, enabled = enabled) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.host_cert_remove),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }

            is CertSlotStatus.Error -> {
                Text(
                    text = s.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(6.dp))
                PasteButton(pasteLabel, onPaste, enabled)
            }
        }
    }
}

@Composable
private fun PasteButton(label: String, onClick: () -> Unit, enabled: Boolean) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 16.dp,
            vertical = 10.dp,
        ),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.ContentPaste,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text(label)
        }
    }
}

/**
 * A green that reads on both light and dark surfaces — M3 has no built-in
 * "success" color role, so this conveys the "imported OK" state for the check
 * glyph. Green 500 sits comfortably on surfaceContainerHigh in either theme.
 */
private val ImportedCheckColor = Color(0xFF4CAF50)
