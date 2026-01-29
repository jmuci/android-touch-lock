package com.tenmilelabs.touchlock.di

import com.tenmilelabs.touchlock.platform.repository.ConfigRepositoryImpl
import com.tenmilelabs.touchlock.platform.repository.LockRepositoryImpl
import com.tenmilelabs.touchlock.domain.repository.ConfigRepository
import com.tenmilelabs.touchlock.domain.repository.LockPreferencesRepository
import com.tenmilelabs.touchlock.domain.repository.LockRepository
import com.tenmilelabs.touchlock.platform.datastore.LockPreferences
import com.tenmilelabs.touchlock.platform.time.SystemTimeProvider
import com.tenmilelabs.touchlock.platform.time.TimeProvider
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

    @Binds
    @Singleton
    abstract fun bindTimeProvider(
        impl: SystemTimeProvider
    ): TimeProvider

    @Binds
    @Singleton
    abstract fun bindLockPreferencesRepository(
        impl: LockPreferences
    ): LockPreferencesRepository
}
