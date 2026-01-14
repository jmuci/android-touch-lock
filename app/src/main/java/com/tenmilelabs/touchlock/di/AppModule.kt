package com.tenmilelabs.touchlock.di

import com.tenmilelabs.touchlock.data.repository.LockRepositoryImpl
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
}
