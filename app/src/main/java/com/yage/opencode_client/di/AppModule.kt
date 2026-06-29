package com.yage.opencode_client.di

import android.content.Context
import com.yage.opencode_client.util.TrafficLogger
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * R-19: Hilt provider 清理。
 *
 * - `OpenCodeRepository` 已用 `@Inject constructor(TrafficTracker, TrafficLogger)` +
 *   `@Singleton` 标注，且其依赖均可解析（`TrafficTracker` 有无参 `@Inject constructor`，
 *   `TrafficLogger` 见下方 provider），因此 Hilt 可自动构造 —— **已删除其冗余
 *   `@Provides`**。
 * - `TrafficLogger` 虽有 `@Inject constructor(context: Context)`，但其 `context` 参数
 *   是 **裸 `Context`**（缺少 `@ApplicationContext` 限定符），Hilt 无法自动解析。
 *   正确做法是在 `TrafficLogger` 构造器上加 `@ApplicationContext`，但该文件不在本次
 *   remediation 的可改文件范围内，故 **保留此 `@Provides`** 显式注入 ApplicationContext
 *   作为桥接。待后续把 `@ApplicationContext` 加到 `TrafficLogger` 构造器后即可一并删除。
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideTrafficLogger(
        @ApplicationContext context: Context
    ): TrafficLogger = TrafficLogger(context)
}
