package cn.vectory.ocdroid.service.notify

import cn.vectory.ocdroid.service.lifecycle.Layer

/**
 * FGS spec §4.1 / §7 — pure-Kotlin projection of a `(Layer, busyCount,
 * degraded)` triple into a [NotificationSpec] the Android Service can render
 * without re-deriving layout / copy / priority decisions.
 *
 * Pure on purpose (no `Context`, no `NotificationCompat` import beyond the
 * priority int constants inlined into [NotificationSpec] — see its companion):
 * unit tests cover every (Layer, count, degraded) cell of the §4.1 / §7 matrix
 * directly, without Robolectric.
 *
 * The Android `SessionStreamingService` is the only production caller; it
 * translates [NotificationSpec] into a [android.app.Notification] via
 * `NotificationCompat.Builder`.
 *
 * §4.1 / §7 mapping (FGS spec):
 *  - L1-busy  / L2-active (busy) → 「N tasks running」, ongoing, chronometer,
 *    close Action (§16-U1).
 *  - L1-idle                       → 「Connected」, LOW, NOT ongoing (normal
 *    started Service; no FGS slot).
 *  - L2-idle                       → 「Idle monitoring」, LOW, ongoing, close
 *    Action (the FGS shell stays so the notification cannot be swiped).
 *  - L3                            → not rendered (FGS torn down; no ongoing).
 *  - degraded (TOFU 未决, §5)      → 「Open app to confirm trust」, LOW, ongoing,
 *    Action target = Activity (§16-U1 / §5 note).
 *  - placeholder (§5 step 2)       → 「Restoring connection…」, LOW, ongoing,
 *    no Action (transient — replaced as soon as bootstrap completes).
 *
 * The chronometer base (§7: chronometer 起点 = earliest busy transition in the
 * aggregation, NOT notification rebuild time) is supplied by the caller — the
 * lifecycle controller / Service tracks when busy started and passes it in.
 */
object SessionStatusNotifier {

    /**
     * Builds the [NotificationSpec] for [layer] + [busyCount].
     *
     * @param layer The current layered-lifecycle state (§4.1).
     * @param busyCount Count of `Busy`/`Retry` sessions under the current
     *  identity (drives the 「N tasks」 copy). Caller computes it from
     *  [cn.vectory.ocdroid.service.status.StatusAggregator.statusByKey].
     * @param strings Localised copy bundle (production: built from
     *  `R.string.notify_session_*`; tests: a literal [NotificationStrings]).
     * @param busySinceMs The wall-clock millis of the earliest busy
     *  transition in the current aggregation, or null when not busy. Drives
     *  the chronometer base (§7).
     * @param degraded True when the bootstrap is in the §5 「未决 TOFU 且无
     *  Activity」 degraded state — the notification shows an Open-Activity
     *  hint and stays ongoing.
     */
    fun build(
        layer: Layer,
        busyCount: Int,
        strings: NotificationStrings,
        busySinceMs: Long?,
        degraded: Boolean,
    ): NotificationSpec {
        // §5 degraded overrides everything: stays ongoing, surfaces Open-app hint.
        if (degraded) {
            return NotificationSpec(
                title = strings.degradedTitle,
                content = strings.degradedContent,
                priority = NotificationSpec.PRIORITY_LOW,
                ongoing = true,
                showChronometer = false,
                chronometerBaseMs = null,
                showCloseAction = true, // §16-U1 close Action stays reachable
                degraded = true,
            )
        }
        return when (layer) {
            is Layer.L1 -> if (layer.busy) {
                busySpec(busyCount, strings, busySinceMs)
            } else {
                // §4.1 L1-idle: normal Service, no FGS slot, SSE alive. 「Connected」
                // LOW optional surface; NOT ongoing (user can dismiss).
                NotificationSpec(
                    title = strings.appName,
                    content = strings.connected,
                    priority = NotificationSpec.PRIORITY_LOW,
                    ongoing = false,
                    showChronometer = false,
                    chronometerBaseMs = null,
                    showCloseAction = false,
                    degraded = false,
                )
            }
            Layer.L2Active -> busySpec(busyCount, strings, busySinceMs)
            Layer.L2Idle -> NotificationSpec(
                title = strings.appName,
                content = strings.idleMonitoring,
                priority = NotificationSpec.PRIORITY_LOW,
                ongoing = true, // FGS shell kept; cannot be swiped (§4.1)
                showChronometer = false,
                chronometerBaseMs = null,
                showCloseAction = true, // §16-U1 close Action
                degraded = false,
            )
            Layer.L3 -> NotificationSpec(
                title = strings.appName,
                content = strings.idleMonitoring,
                priority = NotificationSpec.PRIORITY_LOW,
                ongoing = false, // FGS torn down — non-ongoing; will be cancelled by stopForeground
                showChronometer = false,
                chronometerBaseMs = null,
                showCloseAction = false,
                degraded = false,
            )
        }
    }

    /**
     * §5 step 2 START_STICKY cold-start placeholder. LOW + ongoing so the FGS
     * slot is held inside the 5s ANR window without a heads-up bubble. No
     * close Action — the placeholder is transient and replaced as soon as
     * bootstrap emits its first [LifecycleCommand].
     */
    fun buildPlaceholder(strings: NotificationStrings): NotificationSpec = NotificationSpec(
        title = strings.appName,
        content = strings.restoringConnection,
        priority = NotificationSpec.PRIORITY_LOW,
        ongoing = true,
        showChronometer = false,
        chronometerBaseMs = null,
        showCloseAction = false,
        degraded = false,
    )

    private fun busySpec(
        count: Int,
        strings: NotificationStrings,
        busySinceMs: Long?,
    ): NotificationSpec {
        // §9 (abort) + §16-U1 (close): single-busy could show Abort, multi-busy
        // jumps to the task list; the close Action is always present. CP5 ships
        // the copy + ongoing + chronometer only; the actions are CP8/Phase-1.
        val content = if (count <= 1) strings.busySingular
        else strings.busyPluralFormat.format(count)
        return NotificationSpec(
            title = strings.appName,
            content = content,
            priority = NotificationSpec.PRIORITY_LOW, // §7 channel is LOW
            ongoing = true,
            showChronometer = busySinceMs != null,
            chronometerBaseMs = busySinceMs,
            showCloseAction = true, // §16-U1
            degraded = false,
        )
    }
}

/**
 * Localised copy bundle. Constructed once by the Android Service from
 * `Context.getString(R.string.notify_session_*)`; passed into
 * [SessionStatusNotifier.build] for every (re)build so the notifier stays
 * pure-JVM testable.
 *
 * @property busyPluralFormat Format string taking a single integer count
 *  placeholder (`%1$d` in Android resources).
 */
data class NotificationStrings(
    val appName: String,
    val restoringConnection: String,
    val busySingular: String,
    val busyPluralFormat: String,
    val connected: String,
    val idleMonitoring: String,
    val degradedTitle: String,
    val degradedContent: String,
)

/**
 * Pure-data notification description (FGS spec §4.1 / §7). Built by
 * [SessionStatusNotifier]; applied to a `NotificationCompat.Builder` by the
 * Android Service.
 *
 * Priority constants are inlined as raw `Int`s (matching
 * `androidx.core.app.NotificationCompat.PRIORITY_*`) so this class has ZERO
 * Android-framework imports — pure-JVM unit tests load it without
 * Robolectric.
 *
 * @property priority One of [PRIORITY_MIN] / [PRIORITY_LOW] / [PRIORITY_DEFAULT]
 *  / [PRIORITY_HIGH] — matches the `NotificationCompat` constants.
 * @property ongoing `true` while the FGS slot is held (FGS ongoing notifications
 *  cannot be swiped by the user — §16-U1 relies on this).
 * @property showChronometer `true` for busy states (§7 chronometer base = the
 *  earliest busy transition).
 * @property chronometerBaseMs Wall-clock millis of the chronometer start, or
 *  null when no chronometer. The Service converts to elapsedRealtime when
 *  applying (the notifier stays clock-agnostic).
 * @property showCloseAction `true` to surface the §16-U1 「关闭后台」close
 *  Action. Wiring (PendingIntent + receiver) is CP8/U1; CP5 only carries the
 *  flag so the spec is decision-complete.
 * @property degraded `true` for the §5 「未决 TOFU 且无 Activity」 degraded state.
 */
data class NotificationSpec(
    val title: String,
    val content: String,
    val priority: Int,
    val ongoing: Boolean,
    val showChronometer: Boolean,
    val chronometerBaseMs: Long?,
    val showCloseAction: Boolean,
    val degraded: Boolean,
) {
    companion object {
        const val PRIORITY_MIN = -2
        const val PRIORITY_LOW = -1
        const val PRIORITY_DEFAULT = 0
        const val PRIORITY_HIGH = 1
    }
}
