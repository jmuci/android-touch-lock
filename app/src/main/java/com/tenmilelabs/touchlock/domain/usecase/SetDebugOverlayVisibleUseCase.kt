package com.tenmilelabs.touchlock.domain.usecase

import com.tenmilelabs.touchlock.domain.repository.ConfigRepository
import javax.inject.Inject

/**
 * Debug-only: Sets the overlay visibility debug flag.
 * When enabled, overlay gets a visible tint for lifecycle debugging.
 */
class SetDebugOverlayVisibleUseCase @Inject constructor(
    private val configRepository: ConfigRepository
) {
    suspend operator fun invoke(visible: Boolean) {
        configRepository.setDebugOverlayVisible(visible)
    }
}
