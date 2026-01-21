package com.tenmilelabs.touchlock.domain.usecase

import com.tenmilelabs.touchlock.domain.model.OrientationMode
import com.tenmilelabs.touchlock.domain.repository.ConfigRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ObserveOrientationModeUseCase @Inject constructor(
    private val configRepository: ConfigRepository
) {
    operator fun invoke(): Flow<OrientationMode> {
        return configRepository.observeOrientationMode()
    }
}
