package com.yage.opencode_client.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt providers.
 *
 * - `OpenCodeRepository` 用 `@Inject constructor(TrafficTracker, TrafficLogger)` +
 *   `@Singleton` 标注，依赖均可解析（`TrafficTracker` 无参 `@Inject constructor`，
 *   `TrafficLogger` 构造器已带 `@param:ApplicationContext`），Hilt 自动构造，
 *   无需 `@Provides`。
 *
 * 当前模块无任何 `@Provides`，保留为占位以便后续扩展。
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule
