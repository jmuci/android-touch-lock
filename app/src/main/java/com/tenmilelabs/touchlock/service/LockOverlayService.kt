package com.tenmilelabs.touchlock.service

import android.app.NotificationManager
import android.content.Context
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

    private var isServiceRunning = false

    @Suppress("MissingSuperCall")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_INIT -> initService()
            ACTION_START -> startLock()
            ACTION_STOP -> stopLock()
            ACTION_TOGGLE -> toggleLock()
            ACTION_DISMISS -> dismissService()
            null -> {
                // Service restarted by system (START_STICKY)
                // Re-initialize with idle state
                initService()
            }
        }
        return START_STICKY
    }

    private fun initService() {
        if (isServiceRunning) return

        startForeground(
            NOTIFICATION_ID,
            notificationManager.buildUnlockedNotification()
        )
        isServiceRunning = true
        _lockState.value = LockState.Unlocked
    }

    private fun startLock() {
        if (!isServiceRunning) {
            initService()
        }

        if (_lockState.value == LockState.Locked) return

        if (!permissionManager.hasPermission()) {
            return
        }

        overlayController.show {
            stopLock()
        }

        // Update notification to show unlock button
        val notificationManagerSys = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManagerSys.notify(NOTIFICATION_ID, notificationManager.buildLockedNotification())

        _lockState.value = LockState.Locked
    }

    private fun stopLock() {
        if (_lockState.value == LockState.Unlocked) return

        overlayController.hide()

        // Update notification to show lock button
        val notificationManagerSys = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManagerSys.notify(NOTIFICATION_ID, notificationManager.buildUnlockedNotification())

        _lockState.value = LockState.Unlocked
    }

    /**
     * Toggles lock state between locked and unlocked.
     * Called when user taps the notification body.
     */
    private fun toggleLock() {
        when (_lockState.value) {
            LockState.Unlocked -> startLock()
            LockState.Locked -> stopLock()
        }
    }

    private fun dismissService() {
        overlayController.hide()
        stopForeground(STOP_FOREGROUND_REMOVE)
        _lockState.value = LockState.Unlocked
        isServiceRunning = false
        stopSelf()
    }

    // Defensive stop. Prevents rare window leaks.
    override fun onDestroy() {
        overlayController.hide()
        super.onDestroy()
    }

    companion object {
        const val ACTION_INIT = "com.tenmilelabs.touchlock.INIT"
        const val ACTION_START = "com.tenmilelabs.touchlock.START"
        const val ACTION_STOP = "com.tenmilelabs.touchlock.STOP"
        const val ACTION_TOGGLE = "com.tenmilelabs.touchlock.TOGGLE"
        const val ACTION_DISMISS = "com.tenmilelabs.touchlock.DISMISS"
        const val NOTIFICATION_ID = 1

        private val _lockState = MutableStateFlow<LockState>(LockState.Unlocked)
        val lockState: StateFlow<LockState> = _lockState
    }
}
