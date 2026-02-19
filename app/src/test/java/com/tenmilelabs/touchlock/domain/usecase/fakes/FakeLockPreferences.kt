package com.tenmilelabs.touchlock.domain.usecase.fakes

import com.tenmilelabs.touchlock.domain.model.OrientationMode
import com.tenmilelabs.touchlock.domain.model.UsageData
import com.tenmilelabs.touchlock.domain.repository.LockPreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Fake in-memory implementation of LockPreferencesRepository for testing.
 * Avoids DataStore complexity and provides deterministic behavior.
 */
class FakeLockPreferences : LockPreferencesRepository {
    private val _orientationMode = MutableStateFlow(OrientationMode.FOLLOW_SYSTEM)
    private val _usageData = MutableStateFlow<UsageData?>(null)

    override val orientationMode: Flow<OrientationMode> = _orientationMode

    override val usageData: Flow<UsageData?> = _usageData

    override suspend fun setOrientationMode(mode: OrientationMode) {
        _orientationMode.value = mode
    }

    override suspend fun getUsageData(date: String): UsageData? {
        val current = _usageData.value
        return if (current?.date == date) current else null
    }

    override suspend fun updateUsageData(data: UsageData) {
        _usageData.value = data
    }

    override suspend fun clearUsageData() {
        _usageData.value = null
    }

    /**
     * Test helper to get current stored data directly.
     */
    fun getCurrentUsageData(): UsageData? = _usageData.value
}
