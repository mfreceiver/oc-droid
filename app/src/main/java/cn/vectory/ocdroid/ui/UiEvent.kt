package cn.vectory.ocdroid.ui

import androidx.annotation.StringRes

/**
 * §R-17 batch2: one-shot UI events (error/success toasts). Replaces the
 * cross-domain error/successMessage fields on AppState. Consumed once
 * (SharedFlow), unlike state which is persistent. error/successMessage
 * writes that previously went through updateState { it.copy(error=...) }
 * now emit UiEvent.Error/Success via MainViewModel._uiEvents.
 *
 * §R18 Phase 2-G: Error/Success now carry a `@StringRes resId` + format
 * args instead of a hardcoded String. Composable collectors resolve via
 * `LocalContext.current.getString(resId, *args.toTypedArray())`; the
 * app-lifetime AppCore collector does the same with its injected
 * `@ApplicationContext` for the [cn.vectory.ocdroid.di.AppLifecycleMonitor]
 * notification path. The Debug variant keeps a raw String because it is
 * developer-facing (not user-visible) — i18n does not apply.
 */
sealed class UiEvent {
    data class Error(@StringRes val resId: Int, val args: List<Any> = emptyList()) : UiEvent()
    data class Success(@StringRes val resId: Int, val args: List<Any> = emptyList()) : UiEvent()
    /**
     * §grouping-rewrite item 4: neutral, NON-FATAL informational event.
     * Same shape as [Error] / [Success] (so collectors resolve it through
     * the same `@StringRes resId` + format-args path). Used by
     * `AppCoreOrchestration.executeCommand` when the command POST raises a
     * `java.net.SocketTimeoutException` waiting for the server's ACK: the
     * command may legitimately still be running server-side, and SSE
     * (separate client, read timeout 0) will deliver the results — the user
     * sees a neutral "command submitted, processing…" snackbar instead of a
     * red "command failed" one. True HTTP 4xx/5xx failures still go through
     * [Error].
     */
    data class Info(@StringRes val resId: Int, val args: List<Any> = emptyList()) : UiEvent()
    /** Developer-facing debug event; message stays a raw String (not user-visible). */
    data class Debug(val message: String) : UiEvent()
}

/**
 * §R18 Phase 2-G: Resolve a [UiEvent]'s localized message via the given
 * [android.content.Context]. This is a plain (non-`@Composable`) top-level
 * function on purpose: the Compose lint rule `LocalContextGetResourceValueCall`
 * flags `context.getString(...)` calls that lexically appear inside a
 * `@Composable` function (because they bypass Compose recomposition on
 * configuration change). Snackbar/event collectors run inside a
 * `LaunchedEffect` coroutine on event arrival — the resolved String is
 * consumed immediately by `SnackbarHostState.showSnackbar` (an ephemeral
 * side effect), never held across recompositions, so the recomposition
 * concern does not apply. Hoisting the resolution into this non-composable
 * helper satisfies lint while keeping the call site readable.
 */
fun UiEvent.resolveMessage(context: android.content.Context): String = when (this) {
    is UiEvent.Error -> context.getString(resId, *args.toTypedArray())
    is UiEvent.Success -> context.getString(resId, *args.toTypedArray())
    is UiEvent.Info -> context.getString(resId, *args.toTypedArray())
    is UiEvent.Debug -> message
}

/** §R-17 batch2: passed to Actions free functions and controllers so they can
 *  emit UiEvents without holding a reference to MainViewModel._uiEvents. */
fun interface EventEmitter { fun emit(event: UiEvent) }
