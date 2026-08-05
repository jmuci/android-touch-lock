package com.tenmilelabs.touchlock.platform.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.tenmilelabs.touchlock.domain.model.LockState
import com.tenmilelabs.touchlock.service.LockOverlayService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class TouchLockAccessibilityService : AccessibilityService() {

    @Inject
    lateinit var holder: AccessibilityServiceHolder

    @Inject
    lateinit var volumeComboDetector: VolumeComboDetector

    @Inject
    lateinit var allowlist: SuppressionAllowlist

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var forceUnlockPollJob: Job? = null
    private var lockStateCollectorJob: Job? = null

    // Continuously updated from TYPE_WINDOW_STATE_CHANGED events, locked or not, so that the
    // package in the foreground at the moment the lock engages is known.
    private var lastKnownForegroundPackage: String? = null

    // Captured when the lock engages; cleared when it releases. Null means "no active lock
    // session" and is itself part of the fail-open guard below.
    private var protectedPackageName: String? = null
    private var snapBackCount = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        Timber.d("TouchLockAccessibilityService connected")
        holder.attach(this)

        lockStateCollectorJob = serviceScope.launch {
            LockOverlayService.lockState.collect { state ->
                if (state == LockState.Locked && protectedPackageName == null) {
                    protectedPackageName = lastKnownForegroundPackage
                    snapBackCount = 0
                    Timber.d("Lock engaged, protecting package: $protectedPackageName")
                } else if (state != LockState.Locked) {
                    protectedPackageName = null
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val eventPackage = e.packageName?.toString()
        if (eventPackage != null) {
            lastKnownForegroundPackage = eventPackage
        }

        // Fail-open: anything other than Locked — including unknown/unreachable — means nothing
        // else fires. Read lockState directly (synchronous, always current) rather than relying
        // solely on protectedPackageName, which is cleared asynchronously by the collector above
        // and could be briefly stale for one event-loop turn after unlock.
        if (LockOverlayService.lockState.value != LockState.Locked) return
        val protected = protectedPackageName ?: return

        // Never suppress, never snap away from, an allowlisted package (Settings, dialer, alarm,
        // emergency alerts, our own package) — consulted before any suppression action, same as
        // onKeyEvent's BACK consumption below.
        if (allowlist.isAllowlisted(eventPackage)) return

        if (isSystemUiShade(e)) {
            dismissShade()
            return
        }

        if (eventPackage != null && eventPackage != protected) {
            performSnapBack(protected)
        }
    }

    /**
     * Best-effort heuristic: matches known AOSP/OEM class-name conventions for the notification
     * shade's root window. Not validated on-device — needs real-device confirmation, and likely
     * OEM-specific tuning, before this is relied on in production.
     */
    private fun isSystemUiShade(event: AccessibilityEvent): Boolean {
        if (event.packageName?.toString() != SYSTEM_UI_PACKAGE) return false
        val className = event.className?.toString() ?: return false
        return SHADE_CLASS_NAME_HINTS.any { className.contains(it, ignoreCase = true) }
    }

    private fun dismissShade() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val result = performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        Timber.d("performGlobalAction(DISMISS_NOTIFICATION_SHADE) returned $result")
    }

    /**
     * Relaunches the protected package after the user navigates away while locked. Rate-limited
     * to [MAX_SNAP_BACK_ATTEMPTS] per lock session — beyond that, a fight with the launcher
     * self-terminates by releasing the lock instead of looping.
     */
    private fun performSnapBack(protectedPackage: String) {
        snapBackCount++
        Timber.d("Snap-back #$snapBackCount to $protectedPackage")

        if (snapBackCount > MAX_SNAP_BACK_ATTEMPTS) {
            Timber.w("Snap-back limit reached, auto-releasing lock")
            triggerForceUnlock()
            return
        }

        val launchIntent = packageManager.getLaunchIntentForPackage(protectedPackage)
        if (launchIntent == null) {
            Timber.w("No launch intent for $protectedPackage, cannot snap back")
            return
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(launchIntent)
        } catch (e: Exception) {
            Timber.e(e, "Failed to snap back to $protectedPackage")
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        handleVolumeCombo(event)

        // Fail-open: anything other than Locked — including unknown/unreachable — consumes
        // nothing.
        if (LockOverlayService.lockState.value != LockState.Locked) return false
        if (allowlist.isAllowlisted(lastKnownForegroundPackage)) return false

        return event.keyCode == KeyEvent.KEYCODE_BACK
    }

    /**
     * Vol-Up + Vol-Down held together for [VolumeComboDetector.HOLD_DURATION_MILLIS] force-unlocks
     * regardless of lock/overlay state. Never consumes the events — volume stays functional and is
     * the escape channel.
     */
    private fun handleVolumeCombo(event: KeyEvent) {
        volumeComboDetector.onKeyEvent(event.keyCode, event.action)

        if (volumeComboDetector.isComboActive()) {
            if (forceUnlockPollJob == null) {
                forceUnlockPollJob = serviceScope.launch {
                    delay(VolumeComboDetector.HOLD_DURATION_MILLIS)
                    if (volumeComboDetector.isHoldThresholdReached()) {
                        Timber.d("Volume combo held, forcing unlock")
                        triggerForceUnlock()
                    }
                    forceUnlockPollJob = null
                }
            }
        } else {
            forceUnlockPollJob?.cancel()
            forceUnlockPollJob = null
        }
    }

    private fun triggerForceUnlock() {
        val intent = Intent(this, LockOverlayService::class.java).apply {
            action = LockOverlayService.ACTION_FORCE_UNLOCK
        }
        startService(intent)
    }

    override fun onInterrupt() {
        // No-op: no ongoing feedback to interrupt.
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Timber.d("TouchLockAccessibilityService unbound")
        holder.detach()
        forceUnlockPollJob?.cancel()
        lockStateCollectorJob?.cancel()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Timber.d("TouchLockAccessibilityService destroyed")
        holder.detach()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        private val SHADE_CLASS_NAME_HINTS = listOf(
            "NotificationShadeWindowView",
            "NotificationPanelView",
            "ShadeWindowView",
            "StatusBarWindowView"
        )
        private const val MAX_SNAP_BACK_ATTEMPTS = 3
    }
}
