package com.tenmilelabs.touchlock.data.repository

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.tenmilelabs.touchlock.domain.model.LockState
import com.tenmilelabs.touchlock.domain.repository.LockRepository
import com.tenmilelabs.touchlock.service.LockOverlayService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LockRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val context: Context
) : LockRepository {

    override fun startLock() {
        val intent = Intent(context, LockOverlayService::class.java).apply {
            action = LockOverlayService.ACTION_START
        }
        ContextCompat.startForegroundService(context, intent)
    }

    override fun stopLock() {
        val intent = Intent(context, LockOverlayService::class.java).apply {
            action = LockOverlayService.ACTION_STOP
        }
        ContextCompat.startForegroundService(context, intent)
    }

    override fun startDelayedLock() {
        val intent = Intent(context, LockOverlayService::class.java).apply {
            action = LockOverlayService.ACTION_DELAYED_LOCK
        }
        ContextCompat.startForegroundService(context, intent)
    }

    override fun restoreNotification() {
        val intent = Intent(context, LockOverlayService::class.java).apply {
            action = LockOverlayService.ACTION_RESTORE_NOTIFICATION
        }
        ContextCompat.startForegroundService(context, intent)
    }

    override fun observeLockState(): Flow<LockState> {
        return LockOverlayService.lockState
    }
}
