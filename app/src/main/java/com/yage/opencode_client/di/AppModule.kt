package com.yage.opencode_client.di

import android.content.Context
import com.yage.opencode_client.data.repository.OpenCodeRepository
import com.yage.opencode_client.util.TrafficLogger
import com.yage.opencode_client.util.TrafficTracker
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideOpenCodeRepository(
        trafficTracker: TrafficTracker,
        trafficLogger: TrafficLogger
    ): OpenCodeRepository = OpenCodeRepository(trafficTracker, trafficLogger)

    @Provides
    @Singleton
    fun provideTrafficLogger(
        @ApplicationContext context: Context
    ): TrafficLogger = TrafficLogger(context)
}
