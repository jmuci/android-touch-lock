package com.tenmilelabs.touchlock.domain.repository

import com.tenmilelabs.touchlock.domain.model.OrientationMode
import com.tenmilelabs.touchlock.domain.model.UsageData
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for lock preferences and usage data.
 * Abstracts storage implementation to enable testing.
 */
interface LockPreferencesRepository {
    val orientationMode: Flow<OrientationMode>
    val usageData: Flow<UsageData?>

    suspend fun setOrientationMode(mode: OrientationMode)
    suspend fun getUsageData(date: String): UsageData?
    suspend fun updateUsageData(data: UsageData)
    suspend fun clearUsageData()
}
