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

            val signal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
            var lastInput: BannerCategoryInput? = null
            var deadlineJob: Job? = null

            launch {
                categoryInputFlow.collect { input ->
                    lastInput = input
                    signal.tryEmit(Unit)
                }
            }

            signal.collect {
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
                        signal.tryEmit(Unit)
                    }
                }
            }
        }
    }
}
