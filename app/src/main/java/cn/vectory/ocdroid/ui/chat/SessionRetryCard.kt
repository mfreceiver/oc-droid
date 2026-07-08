package cn.vectory.ocdroid.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.model.SessionStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Whole seconds remaining until [next], or `-1` when there is no scheduled
 * FUTURE retry: returns -1 for a null [next] OR any due/overdue [next]
 * (`next <= now`). Extracted as a pure function so the countdown edge cases
 * are unit-testable without a Compose harness.
 *
 * A genuinely future [next] returns the (>= 0) whole-second count. Callers
 * route the -1 sentinel to the plain "Retrying" branch — a past deadline
 * showing "0s until retry" would be misleading. NOTE: the `next <= now` guard
 * is required because Kotlin integer division truncates toward zero, so a
 * naive `(next - now) / 1000` for a just-past deadline (e.g. 999 ms ago)
 * would yield 0, not a negative value.
 */
internal fun remainingSeconds(next: Long?, now: Long = System.currentTimeMillis()): Int {
    if (next == null || next <= now) return -1
    return ((next - now) / 1000).toInt()
}

/**
 * §session-retry: top-center floating card shown while the current session is
 * in [SessionStatus.isRetry]. Mirrors opencode's session-retry.tsx — an
 * indefinite spinner, the (truncated) server message, and a live countdown to
 * the next backoff attempt with the attempt index when present.
 *
 * Reuses the errorContainer/onErrorContainer language from [ErrorCard] so a
 * retry reads as a degraded-but-recovering state rather than a hard failure.
 *
 * This is an always-composed [BoxScope] overlay (same shape as
 * [ThinkingCapsuleOverlay]) — it owns its own [AnimatedVisibility] + TopCenter
 * alignment and toggles `visible` on `status?.isRetry`. A `lastRetry` snapshot
 * is kept so the card body REMAINS composed during the exit transition (when
 * status flips out of retry the live status is non-retry, so the content
 * lambda would otherwise emit nothing and the exit animation would be a
 * no-op); this is what makes the exit fade/shrink actually play, matching
 * [ThinkingCapsuleOverlay] whose content is unconditional.
 *
 * The countdown is keyed on [SessionStatus.next] so each new backoff window
 * (a new `next` epoch-ms) resets both the seed value and the 1s tick effect.
 * [remainingSeconds] returns the `-1` sentinel for a null OR due/overdue `next`
 * (`next <= now`), which renders the non-countdown "Retrying" branch — a past
 * deadline must never show a stale/zero countdown. A genuinely future `next`
 * yields >= 0 seconds, with a sub-second future window briefly showing "0s"
 * until the next tick crosses into "Retrying". The tick recomputes from
 * `next - now` each second rather than decrementing, so clock drift / a late
 * tick never desynchronises the displayed value from the real backoff deadline.
 *
 * Callers SHOULD pass `null` (or a status whose `isRetry` is false) to hide
 * the card; passing `null` to suppress it during an unrelated foreground
 * operation (e.g. compaction) is the supported way to express priority.
 */
@Composable
fun BoxScope.SessionRetryCard(status: SessionStatus?) {
    // Hold the last retry status so the body stays composed during the
    // AnimatedVisibility exit transition (see class KDoc).
    var lastRetry by remember { mutableStateOf<SessionStatus?>(null) }
    if (status?.isRetry == true) lastRetry = status
    AnimatedVisibility(
        visible = status?.isRetry == true,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = Modifier.align(Alignment.TopCenter)
    ) {
        lastRetry?.let { st -> RetryCardBody(status = st) }
    }
}

@Composable
private fun RetryCardBody(status: SessionStatus) {
    // Key state + effect on status.next so a new retry attempt (new backoff)
    // resets the countdown. -1 sentinel means next is null OR due/overdue
    // (no scheduled future retry) and renders the non-countdown branch.
    var remainingSec by remember(status.next) {
        mutableIntStateOf(remainingSeconds(status.next))
    }
    LaunchedEffect(status.next) {
        while (isActive) {
            remainingSec = remainingSeconds(status.next)
            delay(1000L)
        }
    }

    val onErrorContainer = MaterialTheme.colorScheme.onErrorContainer
    val retryingText = stringResource(R.string.chat_retrying)
    val attemptSuffix = status.attempt?.let {
        " · " + stringResource(R.string.chat_retry_attempt, it)
    } ?: ""
    val statusLine = if (remainingSec >= 0) {
        stringResource(R.string.chat_retry_in_seconds, remainingSec) + attemptSuffix
    } else {
        retryingText + attemptSuffix
    }

    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.error
            )
            Column {
                Text(
                    text = status.message?.take(80)?.trim()?.ifEmpty { null } ?: retryingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = onErrorContainer,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = statusLine,
                    style = MaterialTheme.typography.labelSmall,
                    color = onErrorContainer.copy(alpha = 0.8f),
                    maxLines = 1
                )
            }
        }
    }
}
