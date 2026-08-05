package com.tenmilelabs.touchlock.platform.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.os.SystemClock
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
    private var dismissShadeJob: Job? = null

    // Continuously updated from TYPE_WINDOW_STATE_CHANGED events, locked or not, so that the
    // package in the foreground at the moment the lock engages is known.
    private var lastKnownForegroundPackage: String? = null

    // Captured when the lock engages; cleared when it releases. Null means "no active lock
    // session" and is itself part of the fail-open guard below.
    private var protectedPackageName: String? = null
    private var snapBackCount = 0

    // Set whenever we dispatch a global action of our own (e.g. dismissing the shade). Confirmed
    // on-device that this can itself generate a window-state-changed event that gets misread as
    // the user navigating away, cascading into false snap-back attempts and — after enough of
    // them — an unintended force-unlock. Any window-state event observed before this elapses is
    // treated as a likely side effect of our own action, not a real navigation, and ignored for
    // snap-back purposes.
    private var suppressSnapBackUntilElapsedMillis = 0L

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

        // Edge case: if the service (re)connects — e.g. Task 4's mid-lock recovery, or the user
        // re-enabling accessibility — while already Locked, the Unlocked->Locked capture in
        // onServiceConnected() can race ahead of the first window-state event and capture null.
        // Adopt the first package observed while locked as the protected one instead of leaving
        // snap-back permanently disabled for the rest of the session.
        if (protectedPackageName == null) {
            protectedPackageName = eventPackage
            return
        }
        val protected = protectedPackageName ?: return

        // Never suppress, never snap away from, an allowlisted package (Settings, dialer, alarm,
        // emergency alerts, our own package) — consulted before any suppression action, same as
        // onKeyEvent's BACK consumption below.
        if (allowlist.isAllowlisted(eventPackage)) return

        // The shade's root window reports a generic className (android.widget.FrameLayout on the
        // device this was verified on — confirmed via live device logs, not a guess) rather than
        // any OEM-specific class name, so class-name matching isn't reliable. Reading the actual
        // window title/type instead would need FLAG_RETRIEVE_INTERACTIVE_WINDOWS, which isn't
        // otherwise needed here. Treating any TYPE_WINDOW_STATE_CHANGED event from SystemUI itself
        // as "the shade might have opened, dismiss it" is broader than strictly necessary, but
        // performGlobalAction(DISMISS_NOTIFICATION_SHADE) is a documented no-op when the shade
        // isn't showing, so the false-positive cost is nil.
        //
        // Debounced rather than dismissed immediately: confirmed on-device that dismissing while
        // the pull-down gesture is still in progress just gets the shade re-opened by the same
        // still-moving finger (the shade tracks the drag directly), so the user never sees it
        // actually close until they lift their finger. Each new SystemUI event pushes the timer
        // out, so the dismiss only fires once the gesture has settled.
        if (eventPackage == SYSTEM_UI_PACKAGE) {
            dismissShadeJob?.cancel()
            dismissShadeJob = serviceScope.launch {
                delay(SHADE_DISMISS_DEBOUNCE_MILLIS)
                dismissShade()
            }
            return
        }

        // Focus moved away from SystemUI for any reason (including the user closing the shade
        // themselves) before the debounce fired — cancel it so a stray dismiss doesn't land on
        // whatever's now in front instead.
        dismissShadeJob?.cancel()
        dismissShadeJob = null

        if (eventPackage != null && eventPackage != protected) {
            if (SystemClock.elapsedRealtime() < suppressSnapBackUntilElapsedMillis) {
                Timber.d("Ignoring window-state event for $eventPackage — within grace window after our own dismissShade action, likely a side effect of it rather than real navigation")
                return
            }
            performSnapBack(protected)
        }
    }

    private fun dismissShade() {
        // Confirmed on-device (Samsung SM-S908U, Android 16) that GLOBAL_ACTION_BACK reliably
        // closes an already-expanded shade where GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE alone
        // sometimes doesn't — but also confirmed that firing it can itself generate a spurious
        // window-state-changed event, which cascaded into false snap-back attempts and, after
        // enough of them, an unintended force-unlock. Not worth that risk: stick to the narrower,
        // Play-policy-preferred action only. The grace window below covers the (smaller) risk that
        // this action alone can also occasionally trigger a spurious event.
        suppressSnapBackUntilElapsedMillis =
            SystemClock.elapsedRealtime() + SNAP_BACK_SUPPRESSION_AFTER_SELF_ACTION_MILLIS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val result = performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
            Timber.d("performGlobalAction(DISMISS_NOTIFICATION_SHADE) returned $result")
        }
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
        dismissShadeJob?.cancel()
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
        private const val MAX_SNAP_BACK_ATTEMPTS = 3
        private const val SHADE_DISMISS_DEBOUNCE_MILLIS = 1000L
        private const val SNAP_BACK_SUPPRESSION_AFTER_SELF_ACTION_MILLIS = 1500L
    }
}
