package com.tenmilelabs.touchlock.platform.repository

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.tenmilelabs.touchlock.domain.model.LockState
import com.tenmilelabs.touchlock.domain.repository.LockRepository
import com.tenmilelabs.touchlock.service.LockOverlayService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LockRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val context: Context
) : LockRepository {

    override fun startDelayedLock() {
        val intent = Intent(context, LockOverlayService::class.java).apply {
            action = LockOverlayService.ACTION_DELAYED_LOCK
        }
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: IllegalStateException) {
            Timber.w(e, "Failed to start foreground service for delayed lock")
        }
    }

    override fun restoreNotification() {
        val intent = Intent(context, LockOverlayService::class.java).apply {
            action = LockOverlayService.ACTION_RESTORE_NOTIFICATION
        }
        try {
            context.startService(intent)
        } catch (e: IllegalStateException) {
            Timber.w(e, "Failed to restore notification")
        }
    }

    override fun observeLockState(): Flow<LockState> {
        return LockOverlayService.lockState
    }
}
