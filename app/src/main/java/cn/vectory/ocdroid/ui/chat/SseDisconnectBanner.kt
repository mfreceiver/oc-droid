// SseDisconnectBanner.kt — §sse-feedback-ux (P2-1): persistent in-chat banner
// that surfaces a sustained SSE disconnect (or a debug REST-only mode) to the
// user. The REST-fallback machinery in AppCoreOrchestration keeps the DATA
// correct when SSE is down (auto-unanchor / force-refresh), but without this
// banner the user had no in-chat signal that live updates were paused or how
// long the outage had lasted — only a tiny home-page status dot and a
// transient staleNotice snackbar fired on the foreground-catch-up path.
//
// The banner is a PURE READ of [SseConnectionFeedback] (itself a pure
// projection of the authoritative connection slice — see
// [cn.vectory.ocdroid.ui.deriveSseConnectionFeedback]). It introduces NO
// writable truth. The Refresh action reuses the existing REST-fallback
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.ui.SseConnectionFeedback
import cn.vectory.ocdroid.ui.disconnectDurationMs
import cn.vectory.ocdroid.ui.showBanner
import cn.vectory.ocdroid.ui.theme.Dimens
import cn.vectory.ocdroid.ui.theme.StatusBanner

/**
 * §sse-feedback-ux (P2-1): persistent in-chat banner surfacing a sustained SSE
 * disconnect / debug REST-only mode.
 *
 * Renders NOTHING when [feedback] is not banner-worthy
 * ([SseConnectionFeedback.showBanner] == false), so the call site can collect
 * the [ChatViewModel.sseConnectionFeedback] flow unconditionally and defer all
 * visibility to this composable.
 *
 *  - [SseConnectionFeedback.Disconnected] → error-toned banner: "Live updates
 *    paused" + an elapsed-time subtitle ("just now" / "N min ago" / "N h ago")
 *    that refreshes every [cn.vectory.ocdroid.ui.SSE_FEEDBACK_TICK_MS] via the
 *    ViewModel ticker, plus a Refresh [TextButton].
 *  - [SseConnectionFeedback.Disabled] → same chrome, "Live updates off / REST-
 *    only (debug)" copy (the user chose REST-only via the debug toggle, so this
 *    is informational, not an error to alarm over).
 *
 * @param feedback  the latest derived SSE connection status.
 * @param onRefresh invoked by the Refresh button; ChatScaffold wires this to
 *                  [cn.vectory.ocdroid.ui.ChatViewModel.refreshCurrentSession]
 *                  (the route-aware REST-fallback recovery).
 * @param modifier  applied to the outer [StatusBanner].
 */
@Composable
internal fun SseDisconnectBanner(
    feedback: SseConnectionFeedback,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!feedback.showBanner) return

    val isDisabled = feedback is SseConnectionFeedback.Disabled
    val onContainer = MaterialTheme.colorScheme.onErrorContainer

    // All stringResource calls resolve unconditionally (stable call tree); the
    // subtitle is then picked by a plain (non-composable) helper so duration
    // formatting never branches the composable call graph.
    val title = stringResource(
        if (isDisabled) R.string.sse_feedback_disabled_title
        else R.string.sse_feedback_disconnected_title
    )
    val subtitle = if (isDisabled) {
        stringResource(R.string.sse_feedback_disabled_subtitle)
    } else {
        resolveDisconnectDurationLabel(
            durationMs = feedback.disconnectDurationMs() ?: 0L,
            justNow = stringResource(R.string.sse_feedback_disconnected_now),
            minutes = stringResource(R.string.sse_feedback_disconnected_minutes),
            hours = stringResource(R.string.sse_feedback_disconnected_hours),
        )
    }
    val refreshLabel = stringResource(R.string.common_refresh)

    StatusBanner(
        modifier = modifier,
        color = MaterialTheme.colorScheme.errorContainer,
        border = BorderStroke(Dimens.hairline, MaterialTheme.colorScheme.error),
        onClick = null,
    ) {
        Icon(
            imageVector = Icons.Default.CloudOff,
            // Decorative — the title text conveys the status. App a11y rule:
            // decorative icons keep contentDescription = null.
            contentDescription = null,
            tint = onContainer,
            modifier = Modifier.size(Dimens.iconStd),
        )
        Spacer(Modifier.width(Dimens.spacing2))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = onContainer,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = onContainer,
            )
        }
        TextButton(onClick = onRefresh) {
            Text(refreshLabel, color = onContainer)
        }
    }
}

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
