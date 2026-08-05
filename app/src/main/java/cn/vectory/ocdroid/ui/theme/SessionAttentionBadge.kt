// SessionAttentionBadge.kt — WT0 shared row-level session attention badge.
//
// §ui-badges: the SINGLE shared composable for rendering any
// [SessionAttentionLevel] on a session ListItem trailing slot. Every render
// surface (SessionCard / SessionPickerRow / RecentSessionRow) calls this
// instead of scattering inline indicators.
//
// §ui-style-spec §2: no scattered dp/numeric literals — all sizes use Dimens
// tokens, all animation constants reuse SseBreathSpec (shared with ServerStatusIconButton).

package cn.vectory.ocdroid.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.ui.SessionAttentionLevel

/**
 * Shared row-level session attention badge. Renders the visual indicator
 * corresponding to [level] in the trailing slot of a session ListItem.
 *
 * Rendering per tier (priority-aligned visual strength):
 *  - [SessionAttentionLevel.None] → nothing (Unit).
 *  - [SessionAttentionLevel.Unread] → 8dp solid static dot (onSurfaceVariant).
 *  - [SessionAttentionLevel.TransientRetry] → static [Icons.Filled.ErrorOutline]
 *    at [Dimens.iconXs] (14dp), tint error.
 *  - [SessionAttentionLevel.PendingUserInput] → BREATHING [Icons.Filled.HelpOutline]
 *    at [Dimens.iconXs] (14dp), tint primary. Uses the shared [SseBreathSpec]
 *    calm pulse so two breathing tiers do not compete for attention (only
 *    PendingUserInput breathes; HardError is static).
 *  - [SessionAttentionLevel.HardError] → static [Icons.Filled.ErrorOutline]
 *    at [Dimens.iconSm] (18dp), tint error.
 *
 * §a11y: every non-None tier carries a meaningful contentDescription
 * (`cd_unread_marker` / `cd_retry_marker` / `cd_pending_input_marker` /
 * `cd_error_marker`). The badge is the SOLE visual signal of session attention
 * state — the host row's text is only the session display name — so the state
 * must be announced to screen readers here. Decorative icons elsewhere that
 * sit next to their own text label correctly stay `null`; this badge does not.
 *
 * @param level the attention level to render.
 * @param modifier optional modifier applied to the container [Box].
 */
@Composable
internal fun SessionAttentionBadge(
    level: SessionAttentionLevel,
    modifier: Modifier = Modifier,
) {
    // rememberInfiniteTransition is created UNCONDITIONALLY per Compose rules
    // (must never be inside a conditional). The breathing values are gated by
    // the `breathe` flag below — when not breathing, they read 1f (identity).
    val breathe = level is SessionAttentionLevel.PendingUserInput
    val breathTransition = rememberInfiniteTransition(label = "attentionBreath")
    val breathAlpha by breathTransition.animateFloat(
        initialValue = SseBreathSpec.ALPHA_MIN,
        targetValue = SseBreathSpec.ALPHA_MAX,
        animationSpec = infiniteRepeatable(
            animation = tween(SseBreathSpec.DURATION_MS, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "attentionBreathAlpha",
    )
    val breathScale by breathTransition.animateFloat(
        initialValue = SseBreathSpec.SCALE_MIN,
        targetValue = SseBreathSpec.SCALE_MAX,
        animationSpec = infiniteRepeatable(
            animation = tween(SseBreathSpec.DURATION_MS, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "attentionBreathScale",
    )

    // §a11y-sweep: hoisted out of the `when`/`.semantics{}` blocks so the
    // @Composable stringResource calls stay unconditional and in composable
    // scope (`.semantics{}` is a non-composable lambda). Unread uses a Box
    // (no Icon contentDescription param), so its label is consumed in the
    // semantics modifier below; the Icon tiers read their own labels inline.
    val unreadDesc = stringResource(R.string.cd_unread_marker)

    when (level) {
        is SessionAttentionLevel.None -> return
        is SessionAttentionLevel.Unread -> {
            Box(
                modifier = modifier
                    .size(Dimens.spacing2)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant)
                    .semantics { contentDescription = unreadDesc },
            )
        }
        is SessionAttentionLevel.TransientRetry -> {
            Icon(
                imageVector = Icons.Filled.ErrorOutline,
                contentDescription = stringResource(R.string.cd_retry_marker),
                modifier = modifier.size(Dimens.iconXs),
                tint = MaterialTheme.colorScheme.error,
            )
        }
        is SessionAttentionLevel.PendingUserInput -> {
            val dotAlpha = if (breathe) breathAlpha else 1f
            val dotScale = if (breathe) breathScale else 1f
            Icon(
                imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                contentDescription = stringResource(R.string.cd_pending_input_marker),
                modifier = modifier
                    .size(Dimens.iconXs)
                    .graphicsLayer {
                        alpha = dotAlpha
                        scaleX = dotScale
                        scaleY = dotScale
                    },
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        is SessionAttentionLevel.HardError -> {
            Icon(
                imageVector = Icons.Filled.ErrorOutline,
                contentDescription = stringResource(R.string.cd_error_marker),
                modifier = modifier.size(Dimens.iconSm),
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}
