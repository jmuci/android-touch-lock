package com.tenmilelabs.touchlock.domain.usecase

import com.tenmilelabs.touchlock.domain.repository.ConfigRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Debug-only: Observes the overlay visibility debug setting.
 * Used to visually confirm when the overlay is attached (for diagnosing lifecycle issues).
 */
class ObserveDebugOverlayVisibleUseCase @Inject constructor(
    private val configRepository: ConfigRepository
) {
    operator fun invoke(): Flow<Boolean> = configRepository.observeDebugOverlayVisible()
}
