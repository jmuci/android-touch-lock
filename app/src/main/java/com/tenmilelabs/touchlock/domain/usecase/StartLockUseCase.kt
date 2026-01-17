package com.tenmilelabs.touchlock.domain.usecase

import com.tenmilelabs.touchlock.domain.repository.LockRepository
import javax.inject.Inject

class StartLockUseCase @Inject constructor(
    private val repository: LockRepository
) {
    operator fun invoke() {
        repository.startLock()
    }
}