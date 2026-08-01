package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.di.UiApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * §C1/C2: Process-scoped owner of the banner hysteresis state machine.
 *
 * Replaces the old ChatViewModel approach (scan + WhileSubscribed + 30s ticker)
 * with:
 *  - C1: event-time clock + deadline-accurate transitions
 *  - C2: process-scoped state that never resets on subscription restart
 *  - C3: coherent BannerCategoryInput payload carried through all phases
 *
 * §b4-rev2 fixes:
 *  - 🔴2 (initial-snapshot race): the reducer is driven by a SINGLE collect over
 *    `merge(categoryInputFlow, deadlineTickFlow)`. categoryInputFlow is a
 *    distinctUntilChanged derivation of two StateFlows, so its FIRST emission is
 *    the current snapshot (StateFlow always replays current value to a new
 *    collector). There is no second-subscriber ordering gap, so an owner
 *    constructed while the connection is ALREADY Disconnected/SSE-stalled
 *    correctly drives the reducer from the first instant.
 *  - 🔴1 (Showing→PendingHide stuck): `computeHysteresisDeadlineMs` now returns
 *    sinceMs+minDisplayMs for Showing (see its doc). The deadline tick scheduled
 *    here re-runs the reducer when the min-display window elapses so a recovery
 *    that lands inside the min-display window does not leave the banner stuck.
 */
@Singleton
class BannerHysteresisOwner @Inject constructor(
    @UiApplicationScope private val scope: CoroutineScope,
    private val store: SharedStateStore,
) {
    private val _state = MutableStateFlow(BannerHysteresisState())
    internal val state: StateFlow<BannerHysteresisState> = _state.asStateFlow()

    init {
        scope.launch {
            val config = BannerHysteresisConfig()

            val categoryInputFlow = combine(
                store.connectionFlow,
                store.sseConnectedFlow,
            ) { conn, sseConnected ->
                val feedback = deriveSseConnectionFeedback(
                    phase = conn.connectionPhase,
                    disconnectedSince = conn.disconnectedSince,
                    sseConnected = sseConnected,
                    now = System.currentTimeMillis(),
                    mtlsDegradedError = conn.mtlsDegradedError,
                )
                val cat = feedback.bannerCategory(mtlsDegradedError = conn.mtlsDegradedError)
                if (cat != null) BannerCategoryInput(category = cat, authReason = conn.mtlsDegradedError)
                else null
            }
                .distinctUntilChanged()

            // The deadline tick re-feeds the LAST input so the reducer can advance timers.
            // It's a no-replay SharedFlow — fine here because it only ever fires AFTER
            // subscribe (it is merged with categoryInputFlow before the single collect, so
            // the collector is guaranteed to be active before any tick can be emitted).
            val deadlineTick = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

            var lastInput: BannerCategoryInput? = null
            var deadlineJob: Job? = null

            // Wrap categoryInputFlow so each emission updates lastInput BEFORE the reducer
            // runs. The deadlineTick branch carries Unit (no new input) — the reducer uses
            // the last captured input. Both feed the same downstream reducer step.
            val categoryWithCapture = categoryInputFlow.onEach { lastInput = it }.map { TICK_CATEGORY }
            val tickFlow = deadlineTick.onEach { /* no-op; uses lastInput */ }.map { TICK_DEADLINE }

            merge(categoryWithCapture, tickFlow).collect { source ->
                deadlineJob?.cancel()
                val now = System.currentTimeMillis()
                val newState = bannerHysteresisReducer(
                    prev = _state.value,
                    input = lastInput,
                    now = now,
                    config = config,
                )
                _state.value = newState
                val deadlineMs = computeHysteresisDeadlineMs(newState, now, config)
                if (deadlineMs != null && deadlineMs > now && isActive) {
                    deadlineJob = launch {
                        delay(deadlineMs - now)
                        deadlineTick.tryEmit(Unit)
                    }
                } else {
                    deadlineJob = null
                }
            }
        }
    }

    private companion object {
        // Tag values to distinguish the two merged sources (both just trigger a reducer step;
        // the actual input is always lastInput captured via onEach).
        private const val TICK_CATEGORY = 0
        private const val TICK_DEADLINE = 1
    }
}
