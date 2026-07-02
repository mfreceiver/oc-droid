package cn.vectory.ocdroid.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.ui.theme.opencode

/**
 * §s3-markers: synthetic metadata markers rendered inline in the chat
 * transcript between two turns where an observable agent / model change
 * occurred, or to delimit a context compaction boundary. Generated purely
 * client-side by `injectMetadataMarkers` from the (legacy) message stream
 * — NO new message API is used; the markers walk the existing user /
 * assistant messages and insert a synthetic [Message] (role set to one of
 * the [METADATA_MARKER_ROLES]) wherever `agent` or `resolvedModel` changes
 * between consecutive turns.
 *
 * The row accepts a plain [label] (instead of decoding it from `parts`)
 * because the synthetic marker messages carry no parts — the label is
 * derived at the call site from the marker Message's `agent` / `modelId`
 * field and passed here as a pre-resolved display string.
 *
 * Dispatch is by [role]:
 *  - "agent-switched" → [MarkerChip] with the agent prefix + label.
 *  - "model-switched" → [MarkerChip] with the model prefix + label.
 *  - "compaction"     → [CompactionDivider] (collapsible).
 */
@Composable
internal fun MetadataMarkerRow(
    role: String,
    label: String,
    modifier: Modifier = Modifier
) {
    when (role) {
        "compaction" -> CompactionDivider(label = label, modifier = modifier)
        else -> MarkerChip(role = role, label = label, modifier = modifier)
    }
}

/**
 * §s3-markers: small pill-style chip rendered for agent-switched and
 * model-switched markers. Reads its prefix label from string resources keyed
 * by role (`chat_marker_agent_prefix` / `chat_marker_model_prefix`).
 */
@Composable
private fun MarkerChip(
    role: String,
    label: String,
    modifier: Modifier = Modifier
) {
    val oc = MaterialTheme.opencode
    val prefixRes = when (role) {
        "model-switched" -> R.string.chat_marker_model_prefix
        else -> R.string.chat_marker_agent_prefix
    }
    val iconTint = if (role == "model-switched") oc.stateInfoFg else oc.stateSuccessFg
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = Color.Transparent,
            border = androidx.compose.foundation.BorderStroke(1.dp, oc.borderBase)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = if (role == "model-switched") Icons.Default.Memory else Icons.Default.SmartToy,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "${stringResource(prefixRes)} · $label",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * §s3-markers: collapsible horizontal divider rendered for compaction
 * markers. The [label] is the (compaction-derived) message body summary,
 * which is hidden by default and expanded by tapping the divider.
 */
@Composable
private fun CompactionDivider(
    label: String,
    modifier: Modifier = Modifier
) {
    val oc = MaterialTheme.opencode
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.weight(1f).height(1.dp),
                color = oc.borderBase
            ) {}
            Row(
                modifier = Modifier.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.chat_marker_context_compacted),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) stringResource(R.string.chat_marker_collapse)
                    else stringResource(R.string.chat_marker_expand),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
            }
            Surface(
                modifier = Modifier.weight(1f).height(1.dp),
                color = oc.borderBase
            ) {}
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
            )
        }
    }
}
