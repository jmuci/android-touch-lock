package com.tenmilelabs.touchlock.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.tenmilelabs.touchlock.domain.model.LockState
import com.tenmilelabs.touchlock.domain.repository.ConfigRepository
import com.tenmilelabs.touchlock.platform.accessibility.AccessibilityServiceHolder
import com.tenmilelabs.touchlock.platform.datastore.LockPreferences
import com.tenmilelabs.touchlock.platform.haptics.HapticController
import com.tenmilelabs.touchlock.platform.notification.LockNotificationManager
import com.tenmilelabs.touchlock.platform.overlay.OverlayController
import com.tenmilelabs.touchlock.platform.permission.OverlayPermissionManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class LockOverlayService : LifecycleService() {

    @Inject
    lateinit var overlayController: OverlayController
    @Inject
    lateinit var notificationManager: LockNotificationManager
    @Inject
    lateinit var permissionManager: OverlayPermissionManager
    @Inject
    lateinit var configRepository: ConfigRepository
    @Inject
    lateinit var hapticController: HapticController
    @Inject
    lateinit var accessibilityServiceHolder: AccessibilityServiceHolder

    private var isServiceRunning = false
    private var debugOverlayVisible = false // Debug-only: for overlay lifecycle debugging
    private var backstopTimeoutMinutes = LockPreferences.DEFAULT_BACKSTOP_TIMEOUT_MINUTES
    private var wasAccessibilityConnected = false
    private var countdownJob: Job? = null
    private var backstopTimeoutJob: Job? = null

    // Safety valve: screen off always releases suppression, independent of the accessibility
    // service or any other lock-escape mechanism.
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Timber.d("ACTION_SCREEN_OFF received, releasing lock (safety valve)")
            stopLock()
        }
    }

    override fun onCreate() {
        super.onCreate()
        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Timber.d("onStartCommand called with action: ${intent?.action}, startId: $startId")
        when (intent?.action) {
            ACTION_INIT -> initService()
            ACTION_TOGGLE -> toggleLock()
            ACTION_DELAYED_LOCK -> startDelayedLock()
            ACTION_CANCEL_COUNTDOWN -> cancelCountdown()
            ACTION_RESTORE_NOTIFICATION -> restoreNotification()
            ACTION_DISMISS -> dismissService()
            ACTION_FORCE_UNLOCK -> forceUnlock()
            null -> {
                // Service restarted by system (START_STICKY)
                // Re-initialize with idle (unlocked) state. Configuration is persisted in DataStore.
                // This prevents unintended auto-locking after system restart while preserving settings.
                Timber.d("onStartCommand called with null action (system restart), re-initializing to unlocked state")
                initService()
            }
        }
        return START_STICKY
    }

    private fun initService() {
        if (isServiceRunning) return

        Timber.d("Starting foreground service with unlocked notification")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
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
            configRepository.observeDebugOverlayVisible()
                .distinctUntilChanged()
                .collect { visible ->
                    debugOverlayVisible = visible
                    // If overlay is currently shown and debug flag changed, recreate it
                    if (_lockState.value == LockState.Locked) {
                        recreateOverlay()
                    }
                }
        }

        lifecycleScope.launch {
            configRepository.observeBackstopTimeoutMinutes()
                .distinctUntilChanged()
                .collect { minutes ->
                    backstopTimeoutMinutes = minutes
                }
        }

        // Mid-lock recovery: if the accessibility service disconnects while locked (e.g. the user
        // disables it from Settings), the system tears down its 2032 overlay window and touch
        // blocking would otherwise silently vanish while lockState still says Locked. Re-attach
        // via the application-overlay fallback rather than leaving the device unblocked.
        lifecycleScope.launch {
            accessibilityServiceHolder.isConnected.collect { isConnected ->
                if (wasAccessibilityConnected && !isConnected && _lockState.value == LockState.Locked) {
                    Timber.w("Accessibility service disconnected while locked; re-attaching overlay")
                    overlayController.hide()
                    overlayController.show(debugOverlayVisible) { stopLock() }
                }
                if (isConnected != wasAccessibilityConnected && _lockState.value == LockState.Locked) {
                    assertForegroundState(notificationManager.buildLockedNotification(isConnected))
                }
                wasAccessibilityConnected = isConnected
            }
        }
    }

    private fun startLock() {
        Timber.d("startLock() called")

        if (_lockState.value == LockState.Locked) return

        // Cancel any pending countdown before the permission check below, not after: startLock()
        // is what the countdown calls when it reaches zero, and cancelCountdown() is what restores
        // the unlocked notification. Checking permission first left the countdown notification
        // ("Locking in 1 seconds...") stuck forever whenever permission was revoked mid-countdown,
        // since the early return below was reached before cancelCountdown() ever ran.
        cancelCountdown()

        if (!permissionManager.hasPermission()) {
            Timber.w("Cannot start lock: overlay permission not granted")
            return
        }

        if (!isServiceRunning) {
            initService()
        }

        val attached = overlayController.show(debugOverlayVisible) { stopLock() }
        if (!attached) {
            Timber.e("startLock: overlay addView failed; aborting lock")
            return
        }
        overlayController.playLockTransition()
        assertForegroundState(
            notificationManager.buildLockedNotification(accessibilityServiceHolder.isConnected.value)
        )
        _lockState.value = LockState.Locked
        hapticController.vibrateOnLock()

        // Safety valve: mandatory backstop auto-unlock, regardless of any other escape mechanism.
        backstopTimeoutJob?.cancel()
        backstopTimeoutJob = lifecycleScope.launch {
            delay(backstopTimeoutMinutes * MILLIS_PER_MINUTE)
            Timber.d("Backstop timeout reached ($backstopTimeoutMinutes min), auto-unlocking")
            stopLock()
        }
    }

    private fun stopLock() {
        Timber.d("stopLock() called")

        if (_lockState.value == LockState.Unlocked) return

        // Cancel any pending countdown/backstop callbacks FIRST, before transitioning state
        cancelCountdown()
        backstopTimeoutJob?.cancel()
        backstopTimeoutJob = null

        overlayController.hide()
        overlayController.playLockTransition()

        // Reassert foreground state with unlocked notification
        assertForegroundState(notificationManager.buildUnlockedNotification())

        _lockState.value = LockState.Unlocked
        hapticController.vibrateOnUnlock()
    }

    /**
     * Safety valve: Vol-Up + Vol-Down held for 3s (detected by the accessibility service, when
     * enabled) force-unlocks regardless of overlay/accessibility state. Delegates to the same
     * stopLock() path — already a no-op when unlocked.
     */
    private fun forceUnlock() {
        Timber.d("forceUnlock() called (safety valve)")
        stopLock()
    }

    /**
     * Debug-only: Recreates the overlay to apply new debug settings.
     * Used when debug overlay visibility flag changes while locked.
     */
    private fun recreateOverlay() {
        Timber.d("recreateOverlay() called")
        overlayController.hide()
        overlayController.show(debugOverlayVisible) { stopLock() }
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
            LockState.Locked ->
                notificationManager.buildLockedNotification(accessibilityServiceHolder.isConnected.value)
            LockState.Unlocked -> notificationManager.buildUnlockedNotification()
        }

        assertForegroundState(notification)
    }

    /**
     * Starts a delayed lock with countdown.
     * Shows countdown overlay and counts down to lock engagement.
     */
    private fun startDelayedLock() {
        Timber.d("startDelayedLock() called")

        // Cancel any existing countdown
        cancelCountdown()

        // Don't start if already locked
        if (_lockState.value == LockState.Locked) return

        if (!permissionManager.hasPermission()) {
            Timber.w("Cannot start delayed lock: overlay permission not granted")
            return
        }

        if (!isServiceRunning) {
            initService()
        }

        countdownJob = lifecycleScope.launch {
            var secondsRemaining = COUNTDOWN_DURATION_SECONDS

            overlayController.showCountdownOverlay(secondsRemaining)
            assertForegroundState(notificationManager.buildCountdownNotification(secondsRemaining))

            repeat(COUNTDOWN_DURATION_SECONDS) {
                delay(1000)
                secondsRemaining--
                Timber.d("Countdown tick: $secondsRemaining seconds remaining")

                if (secondsRemaining > 0) {
                    overlayController.updateCountdown(secondsRemaining)
                    assertForegroundState(
                        notificationManager.buildCountdownNotification(
                            secondsRemaining
                        )
                    )
                } else {
                    // Countdown complete - engage lock
                    Timber.d("Countdown complete (0 seconds), engaging lock")
                    overlayController.hideCountdownOverlay()
                    startLock()
                }
            }
        }
    }

    /**
     * Cancels active countdown.
     */
    private fun cancelCountdown() {
        if (countdownJob == null) return

        countdownJob?.cancel()
        countdownJob = null
        overlayController.hideCountdownOverlay()

        // Restore unlocked notification
        assertForegroundState(notificationManager.buildUnlockedNotification())
    }

    /**
     * Helper method to consistently assert foreground state.
     * Always use this instead of NotificationManager.notify() for foreground service notifications.
     */
    private fun assertForegroundState(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
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
        countdownJob?.cancel()
        countdownJob = null
        backstopTimeoutJob?.cancel()
        backstopTimeoutJob = null
        try {
            unregisterReceiver(screenOffReceiver)
        } catch (e: IllegalArgumentException) {
            Timber.w(e, "screenOffReceiver was not registered")
        }
        overlayController.hide()
        Timber.d("Service destroyed")
        super.onDestroy()
    }

    companion object {
        const val ACTION_INIT = "com.tenmilelabs.touchlock.INIT"
        const val ACTION_TOGGLE = "com.tenmilelabs.touchlock.TOGGLE"
        const val ACTION_DELAYED_LOCK = "com.tenmilelabs.touchlock.DELAYED_LOCK"
        const val ACTION_CANCEL_COUNTDOWN = "com.tenmilelabs.touchlock.CANCEL_COUNTDOWN"
        const val ACTION_RESTORE_NOTIFICATION = "com.tenmilelabs.touchlock.RESTORE_NOTIFICATION"
        const val ACTION_DISMISS = "com.tenmilelabs.touchlock.DISMISS"
        const val ACTION_FORCE_UNLOCK = "com.tenmilelabs.touchlock.FORCE_UNLOCK"
        const val NOTIFICATION_ID = 1

        private const val COUNTDOWN_DURATION_SECONDS = 10
        private const val MILLIS_PER_MINUTE = 60_000L

        // This makes the lock state process‑global for the service, rather than tied to a specific instance, making it survive
        // service recreation even if the service is re-started, as long as the app process is alive.
        private val _lockState = MutableStateFlow<LockState>(LockState.Unlocked)
        val lockState: StateFlow<LockState> = _lockState
    }
}
