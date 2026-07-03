// ChatContextUsageRing.kt — the live context-usage ring shown in the
// ChatTopBar actions cluster (and as the ContextMenuButton anchor). Pure
// relocation from ChatTopBar.kt; visibility stays `internal` (used cross-file
// by ChatSessionTabStrip / ChatTopBar).

package cn.vectory.ocdroid.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.vectory.ocdroid.ui.ContextUsage
import cn.vectory.ocdroid.ui.theme.SemanticColors

@Composable
internal fun ContextUsageRing(usage: ContextUsage?) {
    // #11: 4-stage color scale (cool → hot) reflecting context pressure.
    // Arms are evaluated top-down so the order encodes the thresholds.
    //   null                  → onSurfaceVariant (no data)
    //   < 0.25                → primary (blue, healthy)
    //   0.25 ..< 0.50         → green
    //   0.50 ..< 0.75         → orange
    //   >= 0.75               → error (red)
    // R-24: colours come from theme tokens so they adapt to light/dark. Green
    // → SemanticColors.stateSuccessFg(), orange → SemanticColors.stateWarningFg()
    // (Phase 2：@Composable 主题感知双值，明暗均保证 WCAG 对比度); red/blue reuse
    // M3 colorScheme (error/primary，随 Dynamic Color)。
    val ringColor = when {
        usage == null -> MaterialTheme.colorScheme.onSurfaceVariant
        usage.percentage >= 0.75f -> MaterialTheme.colorScheme.error
        usage.percentage >= 0.50f -> SemanticColors.stateWarningFg()
        usage.percentage >= 0.25f -> SemanticColors.stateSuccessFg()
        else -> MaterialTheme.colorScheme.primary
    }
    val trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(
        alpha = if (usage == null) 0.55f else 0.25f
    )

    Box(
        modifier = Modifier.size(ChatUiTuning.contextRingOuterSize),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            progress = { 1f },
            modifier = Modifier.size(ChatUiTuning.contextRingInnerSize),
            color = trackColor,
            strokeWidth = 3.dp
        )
        if (usage != null) {
            CircularProgressIndicator(
                progress = { usage.percentage },
                modifier = Modifier.size(ChatUiTuning.contextRingInnerSize),
                color = ringColor,
                strokeWidth = 3.dp
            )
        }
    }
}
