package com.tenmilelabs.touchlock.domain.repository

import kotlinx.coroutines.flow.Flow

interface ConfigRepository {
    // Debug-only: For diagnosing overlay lifecycle issues
    fun observeDebugOverlayVisible(): Flow<Boolean>
    suspend fun setDebugOverlayVisible(visible: Boolean)
}
