package cn.vectory.ocdroid.ui.chat

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import cn.vectory.ocdroid.ui.theme.BundledMonoFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.ui.theme.StopRed
import kotlinx.coroutines.delay

/**
 * §Feature C: a quiet, floating, snackbar-like pill that replaces the former
 * bottom status row. It reports what the active session is doing (e.g.
 * "Searching codebase") along with an elapsed timer and a direct abort action.
 */
@Composable
internal fun ThinkingCapsule(
    text: String,
    startedAtMillis: Long?,
    onAbort: () -> Unit,
    modifier: Modifier = Modifier,
    /** §compact: when false, the abort button is omitted (used by the
     * compacting capsule — compaction cannot be interrupted). */
    showAbort: Boolean = true
) {
    var nowMillis by remember(startedAtMillis) { mutableStateOf(System.currentTimeMillis()) }

    // 1s tick while the activity is live so the elapsed timer updates.
    LaunchedEffect(startedAtMillis) {
        while (startedAtMillis != null) {
            nowMillis = System.currentTimeMillis()
            delay(1_000)
        }
    }

    val borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)

    Surface(
        modifier = modifier
            .border(
                width = 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(50)
            ),
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.95f),
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )

            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                // §glm-2: cap width + ellipsize so a long activity string
                // (e.g. "Searching codebase - some/very/long/path.ts") cannot
                // push the capsule past the screen edge on compact widths.
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 180.dp)
            )

            if (startedAtMillis != null) {
                Text(
                    text = formatElapsed(nowMillis - startedAtMillis),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = BundledMonoFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    maxLines = 1
                )
            }

            if (showAbort) {
                // §a11y: this deliberately uses a compact 28dp touch target so the
                // abort affordance is the same height as the compacting capsule.
                // It trades the standard 48dp target for visual parity in this
                // transient, compressed status surface.
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clickable(onClick = onAbort),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = stringResource(R.string.chat_interrupt_agent),
                        tint = StopRed,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}

internal fun formatElapsed(elapsedMillis: Long): String {
    val seconds = (elapsedMillis.coerceAtLeast(0L) / 1_000L).toInt()
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
