package com.tenmilelabs.touchlock.domain.repository

import kotlinx.coroutines.flow.Flow

interface ConfigRepository {
    // Debug-only: For diagnosing overlay lifecycle issues
    fun observeDebugOverlayVisible(): Flow<Boolean>
    suspend fun setDebugOverlayVisible(visible: Boolean)

    // Mandatory backstop auto-unlock timeout (safety valve), in minutes. Always clamped to a hard
    // ceiling — never settable to off/infinite.
    fun observeBackstopTimeoutMinutes(): Flow<Int>
    suspend fun setBackstopTimeoutMinutes(minutes: Int)
}
