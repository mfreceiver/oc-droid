package cn.vectory.ocdroid.di

import cn.vectory.ocdroid.data.repository.http.EspTofuPinStore
import cn.vectory.ocdroid.data.repository.http.TofuPinStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * §tofu R2: binds the ESP-backed [EspTofuPinStore] as the app-wide
 * [TofuPinStore] singleton. Unit tests inject [cn.vectory.ocdroid.data.repository.http.InMemoryTofuPinStore]
 * directly (no Hilt).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class TofuModule {
    @Binds
    @Singleton
    abstract fun bindTofuPinStore(impl: EspTofuPinStore): TofuPinStore
}
