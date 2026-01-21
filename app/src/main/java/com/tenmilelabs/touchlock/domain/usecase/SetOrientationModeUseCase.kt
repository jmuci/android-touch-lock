package com.tenmilelabs.touchlock.domain.usecase

import com.tenmilelabs.touchlock.domain.model.OrientationMode
import com.tenmilelabs.touchlock.domain.repository.ConfigRepository
import javax.inject.Inject

class SetOrientationModeUseCase @Inject constructor(
    private val configRepository: ConfigRepository
) {
    suspend operator fun invoke(mode: OrientationMode) {
        configRepository.setOrientationMode(mode)
    }
}
