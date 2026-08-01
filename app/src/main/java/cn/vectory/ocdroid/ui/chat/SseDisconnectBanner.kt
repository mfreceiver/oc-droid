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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.ui.BannerCategory
import cn.vectory.ocdroid.ui.BannerVisibility
import cn.vectory.ocdroid.ui.SSE_FEEDBACK_TICK_MS
import cn.vectory.ocdroid.ui.theme.Dimens
import cn.vectory.ocdroid.ui.theme.StatusBanner
import kotlinx.coroutines.delay

@Composable
internal fun SseDisconnectBanner(
    visibility: BannerVisibility,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val showing = visibility as? BannerVisibility.Showing ?: return
    val category = showing.category

    val (bgColor, borderColor, iconTint, icon, showRefresh) = when (category) {
        BannerCategory.REST_OUTAGE, BannerCategory.AUTH_FAILURE -> CategoryVisuals(
            color = MaterialTheme.colorScheme.errorContainer,
            border = MaterialTheme.colorScheme.error,
            tint = MaterialTheme.colorScheme.onErrorContainer,
            icon = when (category) {
                BannerCategory.REST_OUTAGE -> Icons.Default.CloudOff
                BannerCategory.AUTH_FAILURE -> Icons.Default.ErrorOutline
                else -> Icons.Default.CloudOff
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
                else -> Icons.Default.CloudOff
            },
            showRefresh = false,
        )
    }

    val title = stringResource(
        when (category) {
            BannerCategory.REST_OUTAGE -> R.string.sse_feedback_rest_outage_title
            BannerCategory.AUTH_FAILURE -> R.string.sse_feedback_auth_failure_title
            BannerCategory.SSE_STALLED -> R.string.sse_feedback_sse_stalled_title
            BannerCategory.USER_DISABLED -> R.string.sse_feedback_disabled_title
        }
    )

    // §C3: elapsed from showing.sinceMs + fresh clock (not torn feedback)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(showing.sinceMs, showing.category) {
        now = System.currentTimeMillis()
        while (true) {
            delay(SSE_FEEDBACK_TICK_MS)
            now = System.currentTimeMillis()
        }
    }
    val elapsedMs = (now - showing.sinceMs).coerceAtLeast(0L)

    val subtitle: String = when (category) {
        BannerCategory.REST_OUTAGE ->
            resolveDisconnectDurationLabel(
                durationMs = elapsedMs,
                justNow = stringResource(R.string.sse_feedback_disconnected_now),
                minutes = stringResource(R.string.sse_feedback_disconnected_minutes),
                hours = stringResource(R.string.sse_feedback_disconnected_hours),
            )
        BannerCategory.AUTH_FAILURE ->
            showing.authReason ?: stringResource(R.string.sse_feedback_auth_failure_subtitle)
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
        if (showRefresh) {
            TextButton(onClick = onRefresh) {
                Text(refreshLabel, color = iconTint)
            }
        }
    }
}

private data class CategoryVisuals(
    val color: Color,
    val border: Color,
    val tint: Color,
    val icon: ImageVector,
    val showRefresh: Boolean,
)

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
