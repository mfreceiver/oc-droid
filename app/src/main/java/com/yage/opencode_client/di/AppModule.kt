package com.yage.opencode_client.di

import com.yage.opencode_client.data.repository.OpenCodeRepository
import com.yage.opencode_client.util.TrafficTracker
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideOpenCodeRepository(
        trafficTracker: TrafficTracker
    ): OpenCodeRepository = OpenCodeRepository(trafficTracker)
}
