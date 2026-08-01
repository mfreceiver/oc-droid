// SseDisconnectBanner.kt — §sse-feedback-ux (P2-1): persistent in-chat banner
// that surfaces a sustained SSE disconnect with per-category visual semantics.
//
// §1.x (batch4): semantically split into 4 categories — REST_OUTAGE / AUTH_FAILURE
// (error, red) vs SSE_STALLED / USER_DISABLED (info, calm). Grace/hysteresis
// handled upstream by [BannerHysteresisState] / [bannerHysteresisReducer].
//
// The banner is a PURE READ of [BannerVisibility] + [SseConnectionFeedback] (pure
// projections of the authoritative connection slice — see
// [cn.vectory.ocdroid.ui.deriveSseConnectionFeedback] / [bannerCategory]). It
// introduces NO writable truth. The Refresh action reuses the existing REST-fallback
// recovery path ([ChatViewModel.refreshCurrentSession] → clear + UNANCHORED
// re-fetch), so the feedback loop closes without a new write surface.
//
// §ui-style-spec: built on the shared [cn.vectory.ocdroid.ui.theme.StatusBanner]
// primitive (Surface + Row chrome, Dimens padding). No scattered dp literals.
// a11y: the leading icon is DECORATIVE (the title text conveys the status), so
// its contentDescription is null per the app a11y rule (decorative icons stay
// null; the Refresh TextButton is labelled by its text and is fully accessible).

package cn.vectory.ocdroid.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.ui.BannerCategory
import cn.vectory.ocdroid.ui.BannerVisibility
import cn.vectory.ocdroid.ui.SseConnectionFeedback
import cn.vectory.ocdroid.ui.disconnectDurationMs
import cn.vectory.ocdroid.ui.theme.Dimens
import cn.vectory.ocdroid.ui.theme.StatusBanner

/**
 * §sse-feedback-ux (§1.4-1.5): persistent in-chat banner with per-category
 * visual dispatch.
 *
 * Renders NOTHING when [visibility] is [BannerVisibility.Hidden], so the call
 * site can collect the [BannerHysteresisState] flow unconditionally and defer
 * all visibility to this composable.
 *
 * Two-tier visual hierarchy:
 *  - Errors (REST_OUTAGE, AUTH_FAILURE) = red/alarming: [errorContainer] bg,
 *    [error] border, [onErrorContainer] tint.
 *  - Info (SSE_STALLED, USER_DISABLED) = calm: [surfaceVariant] bg,
 *    [outline] border, [onSurfaceVariant] tint.
 *
 * @param visibility  current banner visibility (Hidden = render nothing).
 * @param feedback    the latest derived SSE connection status (used for
 *                    [disconnectDurationMs] and type checks).
 * @param onRefresh   invoked by the Refresh button; shown ONLY for
 *                    REST_OUTAGE and AUTH_FAILURE.
 * @param modifier    applied to the outer [StatusBanner].
 */
@Composable
internal fun SseDisconnectBanner(
    visibility: BannerVisibility,
    feedback: SseConnectionFeedback,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Extract the displayed category from visibility
    val showing = visibility as? BannerVisibility.Showing ?: return
    val category = showing.category

    // Per-category visual properties (§1.5 designer spec)
    val (bgColor, borderColor, iconTint, icon, showRefresh) = when (category) {
        BannerCategory.REST_OUTAGE, BannerCategory.AUTH_FAILURE -> CategoryVisuals(
            color = MaterialTheme.colorScheme.errorContainer,
            border = MaterialTheme.colorScheme.error,
            tint = MaterialTheme.colorScheme.onErrorContainer,
            icon = when (category) {
                BannerCategory.REST_OUTAGE -> Icons.Default.CloudOff
                BannerCategory.AUTH_FAILURE -> Icons.Default.ErrorOutline
                else -> Icons.Default.CloudOff // unreachable
            },
            showRefresh = true,
        )
        BannerCategory.SSE_STALLED, BannerCategory.USER_DISABLED -> CategoryVisuals(
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = MaterialTheme.colorScheme.outline,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            icon = when (category) {
                BannerCategory.SSE_STALLED -> Icons.Default.Sync
                BannerCategory.USER_DISABLED -> Icons.Default.CloudOff
                else -> Icons.Default.CloudOff // unreachable
            },
            showRefresh = false,
        )
    }

    // Title by category (§1.4)
    val title = stringResource(
        when (category) {
            BannerCategory.REST_OUTAGE -> R.string.sse_feedback_rest_outage_title
            BannerCategory.AUTH_FAILURE -> R.string.sse_feedback_auth_failure_title
            BannerCategory.SSE_STALLED -> R.string.sse_feedback_sse_stalled_title
            BannerCategory.USER_DISABLED -> R.string.sse_feedback_disabled_title
        }
    )

    // Subtitle: elapsed-time for REST_OUTAGE/AUTH_FAILURE; fixed info for others
    val subtitle: String = when (category) {
        BannerCategory.REST_OUTAGE, BannerCategory.AUTH_FAILURE ->
            resolveDisconnectDurationLabel(
                durationMs = feedback.disconnectDurationMs() ?: 0L,
                justNow = stringResource(R.string.sse_feedback_disconnected_now),
                minutes = stringResource(R.string.sse_feedback_disconnected_minutes),
                hours = stringResource(R.string.sse_feedback_disconnected_hours),
            )
        BannerCategory.SSE_STALLED ->
            stringResource(R.string.sse_feedback_sse_stalled_subtitle)
        BannerCategory.USER_DISABLED ->
            stringResource(R.string.sse_feedback_disabled_subtitle)
    }

    val refreshLabel = stringResource(R.string.common_refresh)

    StatusBanner(
        modifier = modifier,
        color = bgColor,
        border = BorderStroke(Dimens.hairline, borderColor),
        onClick = null,
    ) {
        Icon(
            imageVector = icon,
            // Decorative — the title text conveys the status. App a11y rule:
            // decorative icons keep contentDescription = null.
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(Dimens.iconStd),
        )
        Spacer(Modifier.width(Dimens.spacing2))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = iconTint,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = iconTint,
            )
        }
        // Refresh TextButton: only for REST_OUTAGE and AUTH_FAILURE (§1.5)
        if (showRefresh) {
            TextButton(onClick = onRefresh) {
                Text(refreshLabel, color = iconTint)
            }
        }
    }
}

/**
 * Holder for per-category visual properties — avoids tuple destructuring
 * in the [when] branches above.
 */
private data class CategoryVisuals(
    val color: Color,
    val border: Color,
    val tint: Color,
    val icon: ImageVector,
    val showRefresh: Boolean,
)

/**
 * §sse-feedback-ux: pure (non-composable) elapsed-time label picker. Buckets
 * the disconnect duration into "just now" (< 1 min) / "N min ago" (< 1 h) /
 * "N h ago" (≥ 1 h) and formats against the supplied localized templates. Pure
 * so it is unit-testable without a Compose context.
 *
 * @param durationMs elapsed ms since the disconnect was stamped (≥ 0).
 */
internal fun resolveDisconnectDurationLabel(
    durationMs: Long,
    justNow: String,
    minutes: String,
    hours: String,
): String {
    val seconds = durationMs / 1_000L
    return when {
        seconds < 60L -> justNow
        seconds < 3_600L -> minutes.format(seconds / 60L)
        else -> hours.format(seconds / 3_600L)
    }
}
