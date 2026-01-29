package com.tenmilelabs.touchlock.platform.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tenmilelabs.touchlock.domain.model.OrientationMode
import com.tenmilelabs.touchlock.domain.repository.LockPreferencesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "lock_preferences")

@Singleton
class LockPreferences @Inject constructor(
    @param:ApplicationContext private val context: Context
) : LockPreferencesRepository {
    private val dataStore = context.dataStore

    private object Keys {
        val ORIENTATION_MODE = stringPreferencesKey("orientation_mode")
        val USAGE_DATE = stringPreferencesKey("usage_date") // Format: yyyy-MM-dd
        val USAGE_ACCUMULATED_MILLIS = longPreferencesKey("usage_accumulated_millis")
        val USAGE_LAST_START_TIME = longPreferencesKey("usage_last_start_time")
    }

    override val orientationMode: Flow<OrientationMode> = dataStore.data
        .map { preferences ->
            val modeName = preferences[Keys.ORIENTATION_MODE] ?: OrientationMode.FOLLOW_SYSTEM.name
            OrientationMode.valueOf(modeName)
        }

    override suspend fun setOrientationMode(mode: OrientationMode) {
        dataStore.edit { preferences ->
            preferences[Keys.ORIENTATION_MODE] = mode.name
        }
    }

    /**
     * Retrieves usage timer data for a specific date.
     * Returns null if no data exists for the given date.
     */
    override suspend fun getUsageData(date: String): UsageData? {
        val preferences = dataStore.data.map { prefs ->
            val storedDate = prefs[Keys.USAGE_DATE]
            if (storedDate == date) {
                UsageData(
                    date = storedDate,
                    accumulatedMillis = prefs[Keys.USAGE_ACCUMULATED_MILLIS] ?: 0L,
                    lastStartTime = prefs[Keys.USAGE_LAST_START_TIME]
                )
            } else {
                null
            }
        }
        return preferences.map { it }.first()
    }

    /**
     * Observes usage data, emitting updates when data changes.
     */
    override val usageData: Flow<UsageData?> = dataStore.data
        .map { preferences ->
            val storedDate = preferences[Keys.USAGE_DATE]
            if (storedDate != null) {
                UsageData(
                    date = storedDate,
                    accumulatedMillis = preferences[Keys.USAGE_ACCUMULATED_MILLIS] ?: 0L,
                    lastStartTime = preferences[Keys.USAGE_LAST_START_TIME]
                )
            } else {
                null
            }
        }

    /**
     * Updates usage timer data.
     */
    override suspend fun updateUsageData(data: UsageData) {
        dataStore.edit { preferences ->
            preferences[Keys.USAGE_DATE] = data.date
            preferences[Keys.USAGE_ACCUMULATED_MILLIS] = data.accumulatedMillis
            if (data.lastStartTime != null) {
                preferences[Keys.USAGE_LAST_START_TIME] = data.lastStartTime
            } else {
                preferences.remove(Keys.USAGE_LAST_START_TIME)
            }
        }
    }

    /**
     * Clears usage data (typically when starting a new day).
     */
    override suspend fun clearUsageData() {
        dataStore.edit { preferences ->
            preferences.remove(Keys.USAGE_DATE)
            preferences.remove(Keys.USAGE_ACCUMULATED_MILLIS)
            preferences.remove(Keys.USAGE_LAST_START_TIME)
        }
    }

    /**
     * Data class representing stored usage data.
     */
    data class UsageData(
        val date: String, // Format: yyyy-MM-dd
        val accumulatedMillis: Long,
        val lastStartTime: Long? // System.currentTimeMillis() when lock was started, null if stopped
    )
}
