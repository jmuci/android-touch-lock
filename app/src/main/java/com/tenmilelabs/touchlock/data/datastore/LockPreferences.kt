package com.tenmilelabs.touchlock.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tenmilelabs.touchlock.domain.model.OrientationMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "lock_preferences")

@Singleton
class LockPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.dataStore

    private object Keys {
        val ORIENTATION_MODE = stringPreferencesKey("orientation_mode")
    }

    val orientationMode: Flow<OrientationMode> = dataStore.data
        .map { preferences ->
            val modeName = preferences[Keys.ORIENTATION_MODE] ?: OrientationMode.FOLLOW_SYSTEM.name
            OrientationMode.valueOf(modeName)
        }

    suspend fun setOrientationMode(mode: OrientationMode) {
        dataStore.edit { preferences ->
            preferences[Keys.ORIENTATION_MODE] = mode.name
        }
    }
}
