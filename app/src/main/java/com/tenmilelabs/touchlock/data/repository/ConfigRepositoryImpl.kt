package com.tenmilelabs.touchlock.data.repository

import com.tenmilelabs.touchlock.data.datastore.LockPreferences
import com.tenmilelabs.touchlock.domain.model.OrientationMode
import com.tenmilelabs.touchlock.domain.repository.ConfigRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConfigRepositoryImpl @Inject constructor(
    private val lockPreferences: LockPreferences
) : ConfigRepository {

    override fun observeOrientationMode(): Flow<OrientationMode> {
        return lockPreferences.orientationMode
    }

    override suspend fun setOrientationMode(mode: OrientationMode) {
        lockPreferences.setOrientationMode(mode)
    }
}
