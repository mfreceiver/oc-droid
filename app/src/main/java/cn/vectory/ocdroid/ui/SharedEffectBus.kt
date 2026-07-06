package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.ui.controller.ControllerEffect
import cn.vectory.ocdroid.util.DebugLog
import kotlinx.coroutines.channels.BufferOverflow
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
 * R18 Phase 3 Wave 1 (P1-3 + P1-8 + P2-10): buffer semantics split.
 *  - **effects** (business commands): [BufferOverflow.SUSPEND] + a large
 *    buffer (256) so a burst of SSE-driven effects does not drop AND FIFO
 *    order is preserved across the producer. Suspend [emitEffect] is the
 *    preferred write API for code already inside a coroutine; the
 *    synchronous [tryEmitEffect] is used by non-suspend multi-emit call sites
 *    (where wrapping in `scope.launch` would reorder emissions). When the
 *    buffer ever fills (only realistically achievable in tests),
 *    [tryEmitEffect] returns false, increments [droppedEffectCount], and
 *    logs a warning — never silently drops. No replay (one-shot semantics
 *    match the original callback calls).
 *  - **uiEvents** (ephemeral UI feedback toasts): explicit
 *    [BufferOverflow.DROP_OLDEST] + 64 buffer. UI feedback is ephemeral so
 *    dropping the oldest is preferable to suspending the producer (which
 *    could stall SSE collection on a toast storm).
 *
 * §back-compat deviation: `uiEvents` is kept typed as [MutableSharedFlow]
 * (not downgraded to a read-only [SharedFlow] as the literal spec proposed)
 * because many out-of-scope callers (`SessionViewModel` /
 * `OrchestratorViewModel` / `AppCoreOrchestration` / `ChatViewModel`) still
 * write through `effectBus.uiEvents.tryEmit(...)`. The new
 * [tryEmitUiEvent] / [emitUiEvent] wrappers are the preferred API for
 * in-scope controllers; existing out-of-scope callsites keep compiling
 * unchanged.
 */
@Singleton
class SharedEffectBus @Inject constructor() {
    // 业务命令：满时挂起 producer (SUSPEND)，保持 FIFO 顺序，不丢。
    private val _effects = MutableSharedFlow<ControllerEffect>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.SUSPEND
    )
    val effects: SharedFlow<ControllerEffect> = _effects.asSharedFlow()
    /** §back-compat: existing tests + AppCore read this alias; keep it. */
    val effectsConsumed: SharedFlow<ControllerEffect> get() = effects

    // UI 事件：可丢 (DROP_OLDEST 显式声明，UI feedback 是 ephemeral)。
    // §back-compat: kept as MutableSharedFlow so out-of-scope
    // `effectBus.uiEvents.tryEmit(...)` callsites still compile.
    private val _uiEvents = MutableSharedFlow<UiEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val uiEvents: MutableSharedFlow<UiEvent> = _uiEvents
    val uiEventsConsumed: SharedFlow<UiEvent> = _uiEvents.asSharedFlow()

    private val droppedEffects = java.util.concurrent.atomic.AtomicLong(0)

    /** Suspend producer: enqueues [effect] FIFO, suspends if buffer is full. */
    suspend fun emitEffect(effect: ControllerEffect) = _effects.emit(effect)

    /**
     * Synchronous producer: returns false (and bumps [droppedEffectCount]) if
     * the buffer is full. Used by non-suspend call sites where wrapping in
     * `scope.launch { emitEffect(...) }` would reorder multi-emissions.
     */
    fun tryEmitEffect(effect: ControllerEffect): Boolean {
        val ok = _effects.tryEmit(effect)
        if (!ok) {
            droppedEffects.incrementAndGet()
            DebugLog.w("EffectBus", "dropped effect=$effect")
        }
        return ok
    }

    /** Suspend producer for UiEvents (rarely needed — UI feedback is fire-and-forget). */
    suspend fun emitUiEvent(event: UiEvent) = _uiEvents.emit(event)

    /** Synchronous producer for UiEvents; honours DROP_OLDEST. */
    fun tryEmitUiEvent(event: UiEvent): Boolean = _uiEvents.tryEmit(event)

    /** Diagnostic: total [ControllerEffect]s dropped by [tryEmitEffect] since process start. */
    fun droppedEffectCount(): Long = droppedEffects.get()
}
