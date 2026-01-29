package com.tenmilelabs.touchlock.domain.repository

import com.tenmilelabs.touchlock.domain.model.OrientationMode
import kotlinx.coroutines.flow.Flow

interface ConfigRepository {
    fun observeOrientationMode(): Flow<OrientationMode>
    suspend fun setOrientationMode(mode: OrientationMode)
    
    // Debug-only: For diagnosing overlay lifecycle issues
    fun observeDebugOverlayVisible(): Flow<Boolean>
    suspend fun setDebugOverlayVisible(visible: Boolean)
}
