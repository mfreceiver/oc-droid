package cn.vectory.ocdroid.di

import cn.vectory.ocdroid.data.repository.CatalogRepository
import cn.vectory.ocdroid.data.repository.ConnectionRepository
import cn.vectory.ocdroid.data.repository.FileVcsRepository
import cn.vectory.ocdroid.data.repository.InteractionRepository
import cn.vectory.ocdroid.data.repository.MessageRepository
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.data.repository.SessionRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Phase B: binds the 6 narrow repository interfaces to the [OpenCodeRepository] @Singleton composite. */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryInterfaceModule {
    @Binds @Singleton
    abstract fun bindConnectionRepository(impl: OpenCodeRepository): ConnectionRepository

    @Binds @Singleton
    abstract fun bindSessionRepository(impl: OpenCodeRepository): SessionRepository

    @Binds @Singleton
    abstract fun bindMessageRepository(impl: OpenCodeRepository): MessageRepository

    @Binds @Singleton
    abstract fun bindInteractionRepository(impl: OpenCodeRepository): InteractionRepository

    @Binds @Singleton
    abstract fun bindCatalogRepository(impl: OpenCodeRepository): CatalogRepository

    @Binds @Singleton
    abstract fun bindFileVcsRepository(impl: OpenCodeRepository): FileVcsRepository
}
