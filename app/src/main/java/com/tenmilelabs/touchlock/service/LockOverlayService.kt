package com.tenmilelabs.touchlock.service

import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.tenmilelabs.touchlock.platform.overlay.OverlayController
import com.tenmilelabs.touchlock.platform.permission.OverlayPermissionManager
import com.tenmilelabs.touchlock.domain.model.LockState
import com.tenmilelabs.touchlock.domain.model.OrientationMode
import com.tenmilelabs.touchlock.domain.repository.ConfigRepository
import com.tenmilelabs.touchlock.platform.notification.LockNotificationManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class LockOverlayService : LifecycleService() {

    @Inject lateinit var overlayController: OverlayController
    @Inject lateinit var notificationManager: LockNotificationManager
    @Inject lateinit var permissionManager: OverlayPermissionManager
    @Inject lateinit var configRepository: ConfigRepository

    private val handler = Handler(Looper.getMainLooper())
    private var isServiceRunning = false
    private var countdownSecondsRemaining = 0
    private var isCountdownActive = false
    private var debugOverlayVisible = false // Debug-only: for overlay lifecycle debugging

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Timber.d("onStartCommand called with action: ${intent?.action}, startId: $startId")
        when (intent?.action) {
            ACTION_INIT -> initService()
            ACTION_START -> startLock()
            ACTION_STOP -> stopLock()
            ACTION_TOGGLE -> toggleLock()
            ACTION_DELAYED_LOCK -> startDelayedLock()
            ACTION_CANCEL_COUNTDOWN -> cancelCountdown()
            ACTION_RESTORE_NOTIFICATION -> restoreNotification()
            ACTION_DISMISS -> dismissService()
            null -> {
                // Service restarted by system (START_STICKY)
                // Re-initialize with idle state
                Timber.d("onStartCommand called with null action (system restart), re-initializing")
                initService()
            }
        }
        return START_STICKY
    }

    private fun initService() {
        if (isServiceRunning) return

        Timber.d("Starting foreground service with unlocked notification")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notificationManager.buildUnlockedNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(
                NOTIFICATION_ID,
                notificationManager.buildUnlockedNotification()
            )
        }
        isServiceRunning = true
        _lockState.value = LockState.Unlocked
        Timber.d("Service initialized: isServiceRunning=$isServiceRunning, lockState=${_lockState.value}")

        // Debug-only: Observe debug overlay visibility flag for lifecycle debugging
        lifecycleScope.launch {
            configRepository.observeDebugOverlayVisible().collect { visible ->
                debugOverlayVisible = visible
                // If overlay is currently shown and debug flag changed, recreate it
                if (_lockState.value == LockState.Locked) {
                    recreateOverlay()
                }
            }
        }
    }

    private fun startLock() {
        Timber.d("startLock() called")
        if (!isServiceRunning) {
            initService()
        }

        if (_lockState.value == LockState.Locked) return

        if (!permissionManager.hasPermission()) {
            return
        }

        // Get current orientation mode and apply it
        lifecycleScope.launch {
            val orientationMode = configRepository.observeOrientationMode().firstOrNull() 
                ?: OrientationMode.FOLLOW_SYSTEM
            
            // Start transparent orientation lock activity if orientation is locked
            if (orientationMode != OrientationMode.FOLLOW_SYSTEM) {
                val intent = com.tenmilelabs.touchlock.ui.OrientationLockActivity.createStartIntent(
                    this@LockOverlayService,
                    orientationMode
                )
                startActivity(intent)
                
                // Small delay to let activity start before showing overlay
                handler.postDelayed({
                    overlayController.show(orientationMode, debugOverlayVisible) {
                        stopLock()
                    }
                }, 100)
            } else {
                // No orientation locking needed, show overlay directly
                overlayController.show(orientationMode, debugOverlayVisible) {
                    stopLock()
                }
            }

            // Reassert foreground state with locked notification
            assertForegroundState(notificationManager.buildLockedNotification())

            _lockState.value = LockState.Locked
        }
    }

    private fun stopLock() {
        Timber.d("stopLock() called")

        if (_lockState.value == LockState.Unlocked) return

        overlayController.hide()
        
        // Finish orientation lock activity if it's running
        finishOrientationLockActivity()

        // Reassert foreground state with unlocked notification
        assertForegroundState(notificationManager.buildUnlockedNotification())

        _lockState.value = LockState.Unlocked
    }
    
    private fun finishOrientationLockActivity() {
        Timber.d("finishOrientationLockActivity() sending ACTION_STOP broadcast")
        // Send broadcast to finish the activity
        val intent = Intent(com.tenmilelabs.touchlock.ui.OrientationLockActivity.ACTION_STOP)
        sendBroadcast(intent)
    }

    /**
     * Debug-only: Recreates the overlay to apply new debug settings.
     * Used when debug overlay visibility flag changes while locked.
     */
    private fun recreateOverlay() {
        Timber.d("recreateOverlay() called")
        lifecycleScope.launch {
            val orientationMode = configRepository.observeOrientationMode().firstOrNull()
                ?: OrientationMode.FOLLOW_SYSTEM
            
            overlayController.hide()
            overlayController.show(orientationMode, debugOverlayVisible) {
                stopLock()
            }
        }
    }

    /**
     * Toggles lock state between locked and unlocked.
     * Called when user taps the notification body.
     */
    private fun toggleLock() {
        Timber.d("toggleLock() called, current state: ${_lockState.value}")
        when (_lockState.value) {
            LockState.Unlocked -> startLock()
            LockState.Locked -> stopLock()
        }
    }

    /**
     * Restores the notification if it was dismissed by the user.
     * Called when the app comes to foreground to ensure notification visibility.
     */
    private fun restoreNotification() {
        Timber.d("restoreNotification() called")
        if (!isServiceRunning) {
            initService()
            return
        }

        // Reassert foreground state with current lock state's notification
        val notification = when (_lockState.value) {
            LockState.Locked -> notificationManager.buildLockedNotification()
            LockState.Unlocked -> notificationManager.buildUnlockedNotification()
        }

        assertForegroundState(notification)
    }

    /**
     * Starts a delayed lock with countdown.
     * Shows countdown overlay and schedules lock after delay.
     */
    private fun startDelayedLock() {
        Timber.d("startDelayedLock() called")
        if (!isServiceRunning) {
            initService()
        }

        // Cancel any existing countdown
        cancelCountdown()

        // Don't start if already locked
        if (_lockState.value == LockState.Locked) return

        if (!permissionManager.hasPermission()) {
            return
        }

        // Start countdown
        isCountdownActive = true
        countdownSecondsRemaining = COUNTDOWN_DURATION_SECONDS

        // Show countdown overlay
        overlayController.showCountdownOverlay(countdownSecondsRemaining)

        // Update notification
        assertForegroundState(notificationManager.buildCountdownNotification(countdownSecondsRemaining))

        // Start countdown timer
        handler.post(countdownRunnable)
    }

    /**
     * Cancels active countdown.
     */
    private fun cancelCountdown() {
        if (!isCountdownActive) return

        isCountdownActive = false
        handler.removeCallbacks(countdownRunnable)
        overlayController.hideCountdownOverlay()

        // Restore unlocked notification
        assertForegroundState(notificationManager.buildUnlockedNotification())
    }

    /**
     * Countdown tick runnable.
     */
    private val countdownRunnable = object : Runnable {
        override fun run() {
            if (!isCountdownActive) return

            countdownSecondsRemaining--
            Timber.d("Countdown tick: $countdownSecondsRemaining seconds remaining")

            if (countdownSecondsRemaining > 0) {
                // Update countdown display
                overlayController.updateCountdown(countdownSecondsRemaining)

                // Update notification every second
                assertForegroundState(notificationManager.buildCountdownNotification(countdownSecondsRemaining))

                // Schedule next tick
                handler.postDelayed(this, 1000)
            } else {
                // Countdown complete - engage lock
                Timber.d("Countdown complete (0 seconds), engaging lock")
                isCountdownActive = false
                overlayController.hideCountdownOverlay()
                startLock()
            }
        }
    }

    /**
     * Helper method to consistently assert foreground state.
     * Always use this instead of NotificationManager.notify() for foreground service notifications.
     */
    private fun assertForegroundState(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(
                NOTIFICATION_ID,
                notification
            )
        }
    }

    private fun dismissService() {
        Timber.d("dismissService() called: Hiding overlay, stopping foreground, and stopping self")
        overlayController.hide()
        stopForeground(STOP_FOREGROUND_REMOVE)
        _lockState.value = LockState.Unlocked
        isServiceRunning = false
        stopSelf()
        Timber.d("Service dismissed")
    }

    // Defensive stop. Prevents rare window leaks.
    override fun onDestroy() {
        Timber.d("onDestroy() called")
        Timber.d("Cleaning up: removing callbacks and hiding overlay")
        handler.removeCallbacks(countdownRunnable)
        overlayController.hide()
        Timber.d("Service destroyed")
        super.onDestroy()
    }

    // Log service binding
    override fun onUnbind(intent: Intent?): Boolean {
        Timber.d("onUnbind() called with intent action: ${intent?.action}")
        return super.onUnbind(intent)
    }

    override fun onRebind(intent: Intent?) {
        Timber.d("onRebind() called with intent action: ${intent?.action}")
        super.onRebind(intent)
    }

    companion object {
        const val ACTION_INIT = "com.tenmilelabs.touchlock.INIT"
        const val ACTION_START = "com.tenmilelabs.touchlock.START"
        const val ACTION_STOP = "com.tenmilelabs.touchlock.STOP"
        const val ACTION_TOGGLE = "com.tenmilelabs.touchlock.TOGGLE"
        const val ACTION_DELAYED_LOCK = "com.tenmilelabs.touchlock.DELAYED_LOCK"
        const val ACTION_CANCEL_COUNTDOWN = "com.tenmilelabs.touchlock.CANCEL_COUNTDOWN"
        const val ACTION_RESTORE_NOTIFICATION = "com.tenmilelabs.touchlock.RESTORE_NOTIFICATION"
        const val ACTION_DISMISS = "com.tenmilelabs.touchlock.DISMISS"
        const val NOTIFICATION_ID = 1

        private const val COUNTDOWN_DURATION_SECONDS = 10

        private val _lockState = MutableStateFlow<LockState>(LockState.Unlocked)
        val lockState: StateFlow<LockState> = _lockState
    }
}
