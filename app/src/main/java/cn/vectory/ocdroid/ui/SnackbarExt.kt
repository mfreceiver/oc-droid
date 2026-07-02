package cn.vectory.ocdroid.ui

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Shows a snackbar for a precise [durationMillis] (default 3s). M3's
 * [SnackbarDuration] enum only offers Short (~1.5s) / Long (~10s) / Indefinite,
 * so this uses Indefinite + a coroutine timeout that dismisses after the
 * requested duration.
 *
 * If [actionLabel] is supplied and the user taps it, [onAction] fires
 * immediately (the snackbar dismisses itself on action tap); the timeout job is
 * then cancelled so the caller resumes promptly instead of waiting the full
 * duration. If the timeout fires first, the snackbar is dismissed and
 * [onAction] is not invoked.
 *
 * Unifies the previously ad-hoc Long/Short durations hardcoded at each call
 * site, and replaces the top-aligned StaleNoticeBanner (now a timed snackbar
 * carrying a reload action).
 */
suspend fun SnackbarHostState.showTimed(
    message: String,
    durationMillis: Long = 3000L,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    coroutineScope {
        val showJob = launch {
            val result = showSnackbar(
                message = message,
                actionLabel = actionLabel,
                duration = SnackbarDuration.Indefinite
            )
            if (result == SnackbarResult.ActionPerformed) {
                onAction?.invoke()
            }
        }
        val timeoutJob = launch {
            delay(durationMillis)
            // No-op if the snackbar already left (action tap / superseded).
            currentSnackbarData?.dismiss()
        }
        showJob.join()
        timeoutJob.cancel()
    }
}
