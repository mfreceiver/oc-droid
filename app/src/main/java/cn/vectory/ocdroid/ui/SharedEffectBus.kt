package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.ui.controller.ControllerEffect
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * R-17 batch3: singleton event bus shared across the 6 domain ViewModels.
 * Controllers emit [ControllerEffect]s via [effects]; OrchestratorVM collects
 * and dispatches them (calling its own methods or writing slices directly).
 *
 * HiltViewModels cannot inject each other (ViewModels are not @Inject-able
 * dependencies), so cross-VM coordination flows through this singleton channel
 * rather than direct VM-to-VM references. See
 * docs/tech-debt/batch3-vm-split-design.md §Hilt 调整.
 *
 * Buffer semantics: extraBufferCapacity=64 (well above the 16-effect sealed
 * class cardinality) so a burst of SSE-driven effects does not drop. No replay
 * (one-shot semantics match the original callback calls).
 */
@Singleton
class SharedEffectBus @Inject constructor() {
    val effects: MutableSharedFlow<ControllerEffect> =
        MutableSharedFlow(extraBufferCapacity = 64)
    val effectsConsumed: SharedFlow<ControllerEffect> = effects.asSharedFlow()

    /** UiEvent broadcast channel (Error/Success toasts). Replaces per-VM _uiEvents. */
    val uiEvents: MutableSharedFlow<UiEvent> = MutableSharedFlow(extraBufferCapacity = 16)
    val uiEventsConsumed: SharedFlow<UiEvent> = uiEvents.asSharedFlow()
}
