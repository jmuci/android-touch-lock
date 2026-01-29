package com.tenmilelabs.touchlock.domain.repository

import com.tenmilelabs.touchlock.domain.model.OrientationMode
import com.tenmilelabs.touchlock.platform.datastore.LockPreferences
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for lock preferences and usage data.
 * Abstracts storage implementation to enable testing.
 */
interface LockPreferencesRepository {
    val orientationMode: Flow<OrientationMode>
    val usageData: Flow<LockPreferences.UsageData?>
    
    suspend fun setOrientationMode(mode: OrientationMode)
    suspend fun getUsageData(date: String): LockPreferences.UsageData?
    suspend fun updateUsageData(data: LockPreferences.UsageData)
    suspend fun clearUsageData()
}
