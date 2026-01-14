package com.tenmilelabs.touchlock.domain.repository

import com.tenmilelabs.touchlock.domain.model.LockState
import kotlinx.coroutines.flow.Flow

interface LockRepository {
    fun observeLockState(): Flow<LockState>
    fun startLock()
    fun stopLock()
}
