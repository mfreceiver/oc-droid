package cn.vectory.ocdroid.di

import cn.vectory.ocdroid.data.repository.http.ClientIdStore
import cn.vectory.ocdroid.data.repository.http.EspClientIdStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * §B (slimapi-v2-adapt-traffic-plan §B): binds the ESP-backed
 * [EspClientIdStore] as the app-wide [ClientIdStore] singleton. Unit tests
 * inject [cn.vectory.ocdroid.data.repository.http.InMemoryClientIdStore]
 * directly (no Hilt) — mirrors [TofuModule].
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ClientIdModule {
    @Binds
    @Singleton
    abstract fun bindClientIdStore(impl: EspClientIdStore): ClientIdStore
}
