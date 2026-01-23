package com.tenmilelabs.touchlock.di

import com.tenmilelabs.touchlock.platform.repository.ConfigRepositoryImpl
import com.tenmilelabs.touchlock.platform.repository.LockRepositoryImpl
import com.tenmilelabs.touchlock.domain.repository.ConfigRepository
import com.tenmilelabs.touchlock.domain.repository.LockRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import jakarta.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds
    @Singleton
    abstract fun bindLockRepository(
        impl: LockRepositoryImpl
    ): LockRepository

    @Binds
    @Singleton
    abstract fun bindConfigRepository(
        impl: ConfigRepositoryImpl
    ): ConfigRepository
}
