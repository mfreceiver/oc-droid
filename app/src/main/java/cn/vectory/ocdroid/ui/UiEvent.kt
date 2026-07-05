package cn.vectory.ocdroid.ui

/**
 * §R-17 batch2: one-shot UI events (error/success toasts). Replaces the
 * cross-domain error/successMessage fields on AppState. Consumed once
 * (SharedFlow), unlike state which is persistent. error/successMessage
 * writes that previously went through updateState { it.copy(error=...) }
 * now emit UiEvent.Error/Success via MainViewModel._uiEvents.
 */
sealed class UiEvent {
    data class Error(val message: String) : UiEvent()
    data class Success(val message: String) : UiEvent()
}

/** §R-17 batch2: passed to Actions free functions and controllers so they can
 *  emit UiEvents without holding a reference to MainViewModel._uiEvents. */
fun interface EventEmitter { fun emit(event: UiEvent) }
