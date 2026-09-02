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

    // Last known lock state, persisted across process death so a system-triggered service
    // restart (START_STICKY) can tell a spurious restart from one where the lock was in effect.
    suspend fun getLastKnownLocked(): Boolean
    suspend fun setLastKnownLocked(locked: Boolean)
}
