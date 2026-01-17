package com.tenmilelabs.touchlock.domain.usecase


import com.tenmilelabs.touchlock.domain.model.LockState
import com.tenmilelabs.touchlock.domain.repository.LockRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ObserveLockStateUseCase @Inject constructor(
    private val repository: LockRepository
) {
    operator fun invoke(): Flow<LockState> {
        return repository.observeLockState()
    }
}