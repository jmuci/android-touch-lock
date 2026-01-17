package com.tenmilelabs.touchlock.service

import android.content.Intent
import androidx.lifecycle.LifecycleService
import com.tenmilelabs.touchlock.data.overlay.OverlayController
import com.tenmilelabs.touchlock.data.permission.OverlayPermissionManager
import com.tenmilelabs.touchlock.domain.model.LockState
import com.tenmilelabs.touchlock.notification.LockNotificationManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@AndroidEntryPoint
class LockOverlayService : LifecycleService() {

    @Inject lateinit var overlayController: OverlayController
    @Inject lateinit var notificationManager: LockNotificationManager

    @Inject lateinit var permissionManager: OverlayPermissionManager


    @Suppress("MissingSuperCall")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startLock()
            ACTION_STOP -> stopLock()
        }
        return START_STICKY
    }

    private fun startLock() {
        if (!permissionManager.hasPermission()) {
            stopSelf()
            return
        }

        overlayController.show()
        startForeground(
            NOTIFICATION_ID,
            notificationManager.buildLockedNotification()
        )
        _lockState.value = LockState.Locked
    }

    private fun stopLock() {
        overlayController.hide()
        stopForeground(STOP_FOREGROUND_REMOVE)
        _lockState.value = LockState.Unlocked
        stopSelf()
    }

    companion object {
        const val ACTION_START = "com.tenmilelabs.touchlock.START"
        const val ACTION_STOP = "com.tenmilelabs.touchlock.STOP"
        const val NOTIFICATION_ID = 1

        private val _lockState = MutableStateFlow<LockState>(LockState.Unlocked)
        val lockState: StateFlow<LockState> = _lockState
    }
}
