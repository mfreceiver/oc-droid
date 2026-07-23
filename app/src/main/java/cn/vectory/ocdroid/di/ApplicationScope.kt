package cn.vectory.ocdroid.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Hilt qualifier for the application-wide [CoroutineScope] tied to the
 * Application process (vs the Activity-scoped `viewModelScope`). The
 * background notification poller (§18) runs on this scope so it survives
 * Activity destruction while the process is alive. Best-effort (D1): when
 * the OS reclaims the process the scope is cancelled along with it.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/**
 * Hilt module that provides the application-wide [CoroutineScope] used by
 * [AppLifecycleMonitor] for best-effort background polling (§18, D1).
 */
@Module
@InstallIn(SingletonComponent::class)
object ApplicationScopeModule {
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
