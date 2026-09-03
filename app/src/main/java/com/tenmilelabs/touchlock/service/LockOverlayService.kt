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
    private var idleDismissJob: Job? = null
    private var permissionWatchJob: Job? = null

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
                // Service restarted by system (START_STICKY) with no pending intent to redeliver.
                // We don't know how long the process was dead, so check what it was actually doing
                // when it died: if nothing was locked, this restart is spurious — there's nothing
                // to protect, and resurrecting the notification here is exactly the "it popped up
                // on its own" bug. Satisfy the startForeground() contract Android imposes on this
                // kind of restart, then immediately tear back down instead of leaving it running.
                // If it *was* locked, we don't know whether that was seconds or hours before the
                // kill, so keep behaving as a safety net: re-initialize to unlocked (deliberately
                // not auto-re-locking — see initService() below) and let the idle timer bound it.
                Timber.d("onStartCommand called with null action (system restart)")
                lifecycleScope.launch {
                    if (configRepository.getLastKnownLocked()) {
                        Timber.d("Last known state was Locked; re-initializing as a safety net")
                        initService()
                    } else {
                        Timber.d("Last known state was Unlocked; spurious restart, dismissing immediately")
                        assertForegroundState(notificationManager.buildUnlockedNotification())
                        dismissService()
                    }
                }
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
        persistLastKnownLocked(false)
        Timber.d("Service initialized: isServiceRunning=$isServiceRunning, lockState=${_lockState.value}")

        // Idle safety valve: if the system revives this service (START_STICKY) after killing the
        // process, or the app is simply never touched again, the notification would otherwise
        // persist forever. Self-dismiss after IDLE_DISMISS_TIMEOUT_MINUTES of unlocked inactivity;
        // startLock()/startDelayedLock() cancel this once real use begins.
        scheduleIdleDismiss()

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
        //
        // Symmetrically, a *reconnect* while already locked (accessibility re-enabled, or the
        // service process simply recreated) needs the same hide()+show() cycle: show() only adds
        // the elevated nav-bar overlay when accessibility is connected at call time (see
        // OverlayController.resolveTarget()), so without re-running it here the nav-bar tap
        // blocking silently never returns even though the notification already reports connected.
        lifecycleScope.launch {
            accessibilityServiceHolder.isConnected.collect { isConnected ->
                if (isConnected != wasAccessibilityConnected && _lockState.value == LockState.Locked) {
                    Timber.w("Accessibility service ${if (isConnected) "reconnected" else "disconnected"} while locked; re-attaching overlay")
                    overlayController.hide()
                    overlayController.show(debugOverlayVisible) { stopLock() }
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
        persistLastKnownLocked(true)
        hapticController.vibrateOnLock()
        cancelIdleDismiss()

        // Safety valve: mandatory backstop auto-unlock, regardless of any other escape mechanism.
        backstopTimeoutJob?.cancel()
        backstopTimeoutJob = lifecycleScope.launch {
            delay(backstopTimeoutMinutes * MILLIS_PER_MINUTE)
            Timber.d("Backstop timeout reached ($backstopTimeoutMinutes min), auto-unlocking")
            stopLock()
        }

        // Confirmed on-device that the OS can force-remove the overlay's SYSTEM_ALERT_WINDOW
        // (e.g. the user revokes "Display over other apps" from Settings while locked, or an OEM
        // auto-revokes it) without the window ever notifying the app via a normal View detach
        // callback — the window simply disappears from WindowManagerService's own registry while
        // the app process gets no signal at all. Without this, lockState stays Locked and the
        // notification keeps claiming the screen is protected while touches pass straight through
        // to whatever app is underneath. Poll rather than rely on a callback, since there's no
        // reliable callback for this specific teardown path.
        permissionWatchJob?.cancel()
        permissionWatchJob = lifecycleScope.launch {
            while (true) {
                delay(PERMISSION_POLL_INTERVAL_MILLIS)
                if (!permissionManager.hasPermission()) {
                    Timber.w("Overlay permission lost while locked; releasing lock")
                    stopLock()
                    break
                }
            }
        }
    }

    private fun stopLock() {
        Timber.d("stopLock() called")

        if (_lockState.value == LockState.Unlocked) return

        // Cancel any pending countdown/backstop callbacks FIRST, before transitioning state
        cancelCountdown()
        backstopTimeoutJob?.cancel()
        backstopTimeoutJob = null
        permissionWatchJob?.cancel()
        permissionWatchJob = null

        overlayController.hide()
        overlayController.playLockTransition()

        // Reassert foreground state with unlocked notification
        assertForegroundState(notificationManager.buildUnlockedNotification())

        _lockState.value = LockState.Unlocked
        persistLastKnownLocked(false)
        hapticController.vibrateOnUnlock()
        scheduleIdleDismiss()
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

        // The app coming to the foreground is a real usage signal; reset the idle clock.
        if (_lockState.value == LockState.Unlocked) {
            scheduleIdleDismiss()
        }
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
        cancelIdleDismiss()

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
        scheduleIdleDismiss()
    }

    /**
     * Idle safety valve: schedules a self-dismiss after IDLE_DISMISS_TIMEOUT_MINUTES of unlocked
     * inactivity. Cancels and replaces any previously scheduled dismiss.
     */
    private fun scheduleIdleDismiss() {
        idleDismissJob?.cancel()
        idleDismissJob = lifecycleScope.launch {
            delay(IDLE_DISMISS_TIMEOUT_MINUTES * MILLIS_PER_MINUTE)
            Timber.d("Idle timeout reached ($IDLE_DISMISS_TIMEOUT_MINUTES min unlocked), dismissing service")
            dismissService()
        }
    }

    private fun cancelIdleDismiss() {
        idleDismissJob?.cancel()
        idleDismissJob = null
    }

    /**
     * Persists the current lock state so a future START_STICKY restart (which delivers a null
     * Intent, with no memory of what this process was doing before it died) can tell a spurious
     * restart from one where the lock was genuinely in effect. Fire-and-forget: best effort is
     * enough here, this is a bound on stray notification lifetime, not a correctness guarantee.
     */
    private fun persistLastKnownLocked(locked: Boolean) {
        lifecycleScope.launch { configRepository.setLastKnownLocked(locked) }
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
        persistLastKnownLocked(false)
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
        idleDismissJob?.cancel()
        idleDismissJob = null
        permissionWatchJob?.cancel()
        permissionWatchJob = null
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
        private const val IDLE_DISMISS_TIMEOUT_MINUTES = 120L
        private const val PERMISSION_POLL_INTERVAL_MILLIS = 2000L

        // This makes the lock state process‑global for the service, rather than tied to a specific instance, making it survive
        // service recreation even if the service is re-started, as long as the app process is alive.
        private val _lockState = MutableStateFlow<LockState>(LockState.Unlocked)
        val lockState: StateFlow<LockState> = _lockState
    }
}
