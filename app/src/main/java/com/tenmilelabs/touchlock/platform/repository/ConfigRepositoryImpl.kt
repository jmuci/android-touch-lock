package com.tenmilelabs.touchlock.platform.repository

import com.tenmilelabs.touchlock.domain.repository.ConfigRepository
import com.tenmilelabs.touchlock.platform.datastore.LockPreferences
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConfigRepositoryImpl @Inject constructor(
    private val lockPreferences: LockPreferences
) : ConfigRepository {

    override fun observeDebugOverlayVisible(): Flow<Boolean> {
        return lockPreferences.debugOverlayVisible
    }

    override suspend fun setDebugOverlayVisible(visible: Boolean) {
        lockPreferences.setDebugOverlayVisible(visible)
    }
}
