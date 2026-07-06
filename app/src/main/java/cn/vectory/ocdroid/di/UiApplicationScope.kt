package cn.vectory.ocdroid.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * R-19 Sprint 3 P2-5: Hilt qualifier for the application-wide [CoroutineScope]
 * bound to [Dispatchers.Main.immediate].
 *
 * This is the scope that owns app-lifetime UI-state coroutines: SSE-driven
 * message loads, session-list refreshes, the foreground catch-up state
 * machine, etc. These coroutines mutate [cn.vectory.ocdroid.ui.SharedStateStore]
 * slices, which composables read on the Main thread — so the scope MUST be
 * Main-bound to keep slice writes single-threaded.
 *
 * Distinct from the existing [AppLifecycleMonitor]'s `@ApplicationScope`
 * (which is [Dispatchers.Default]-bound for background notification polling
 * — that scope must NOT touch UI slices). The two qualifiers keep the
 * dispatchers semantically distinct at the Hilt binding level so a VM that
 * needs to launch a slice-mutating coroutine cannot accidentally pick up the
 * Default-bound poller scope.
 *
 * AppCore + the 5 controllers + any VM that previously reached
 * `core.appScope` now inject this qualifier instead of constructing their
 * own scope, so there is exactly one app-lifetime scope per process.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class UiApplicationScope

/**
 * Provides the single application-wide [CoroutineScope] (Main.immediate) used
 * by [cn.vectory.ocdroid.ui.AppCore], the 5 controllers, and the migrated
 * ViewModels for app-lifetime coroutines that mutate UI slices.
 */
@Module
@InstallIn(SingletonComponent::class)
object UiApplicationScopeModule {
    @Provides
    @Singleton
    @UiApplicationScope
    fun provideUiApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
}
