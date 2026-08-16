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
    // package in the foreground at the moment the lock engages is known. Deliberately filtered:
    // only packages eligible to *be* the protected app land here — critically, this is what keeps
    // SystemUI from overwriting it when the shade is pulled right before locking (e.g. from the
    // persistent notification). Do not read it to answer "what is in front right now" — use
    // [currentForegroundPackage] for that.
    private var lastKnownForegroundPackage: String? = null

    // Every package seen in a window-state event, unfiltered — including SystemUI and allowlisted
    // packages. [lastKnownForegroundPackage] excludes allowlisted packages by construction, so
    // checking the allowlist against it can never match; suppression decisions that need to know
    // whether an allowlisted surface (Settings, dialer, emergency alert) is actually in front must
    // read this instead, or the allowlist escape hatch silently does nothing.
    private var currentForegroundPackage: String? = null

    // Captured when the lock engages; cleared when it releases. Null means either "no active lock
    // session" or "no eligible app was ever seen in the foreground this session to protect" (e.g.
    // the lock engaged while our own app — allowlisted — was still on screen, and no other app was
    // visited first). Both are treated the same by the fail-open guard in onAccessibilityEvent:
    // nothing to snap back to.
    private var protectedPackageName: String? = null

    // Timestamps (elapsedRealtime) of recent snap-back relaunches, oldest first. Confirmed
    // on-device that a flat per-session cap defeats itself under completely normal use: on
    // gesture-navigation devices (the modern default), 3-4 unrelated swipe-home gestures spread
    // over the course of watching a video exhaust a per-session counter and silently disable
    // Strong Lock with no warning. Rate-limiting within a rolling window instead means isolated,
    // spaced-out navigation attempts never accumulate, while a rapid real fight against the lock
    // still trips the release — see [performSnapBack].
    private val snapBackTimestamps = ArrayDeque<Long>()

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
                    snapBackTimestamps.clear()
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
            currentForegroundPackage = eventPackage
        }

        // SystemUI (the shade) and allowlisted packages (Settings, dialer, clock, emergency
        // alerts) are transient system surfaces, not an app the user is actually using — never
        // let one overwrite the last real foreground app. Otherwise locking right after pulling
        // the shade (e.g. from the persistent notification) captures "com.android.systemui" as
        // the protected package below; it has no launch intent, so every subsequent real app
        // switch reads as "navigation away" and snap-back silently fails.
        if (isEligibleProtectedCandidate(eventPackage)) {
            lastKnownForegroundPackage = eventPackage
        }

        // Fail-open: anything other than Locked — including unknown/unreachable — means nothing
        // else fires. Read lockState directly (synchronous, always current) rather than relying
        // solely on protectedPackageName, which is cleared asynchronously by the collector above
        // and could be briefly stale for one event-loop turn after unlock.
        if (LockOverlayService.lockState.value != LockState.Locked) return

        // No eligible app was captured as the protected package when the lock engaged (see the
        // collector in onServiceConnected()) — nothing to compare against or snap back to for the
        // rest of this session. Deliberately does not adopt whatever shows up next as the
        // protected package the way this used to: that event could just as easily be the user's
        // actual escape attempt (e.g. pressing Home right after locking while still inside our own
        // app, which has no eligible foreground package to capture in the first place) as it could
        // be the still-current app after a reconnect, and there's no reliable way to tell the two
        // apart from here. Failing to protect anything this session is safer than protecting the
        // wrong package for the rest of it.
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

    /** See the comment on the `lastKnownForegroundPackage` assignment in [onAccessibilityEvent]. */
    private fun isEligibleProtectedCandidate(packageName: String?): Boolean =
        packageName != null && packageName != SYSTEM_UI_PACKAGE && !allowlist.isAllowlisted(packageName)

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
        } else {
            // GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE doesn't exist before API 31; on API 26-30
            // this is a no-op and the shade stays open. Nav-bar blocking and snap-back still
            // apply. See the README's Strong Lock section for the disclosed limitation.
            Timber.d("Shade auto-dismiss unavailable below API 31 (running ${Build.VERSION.SDK_INT})")
        }
    }

    /**
     * Relaunches the protected package after the user navigates away while locked. Rate-limited
     * to [MAX_SNAP_BACK_ATTEMPTS] within a rolling [SNAP_BACK_RATE_LIMIT_WINDOW_MILLIS] window —
     * not a flat per-session cap, which confirmed on-device to defeat itself: a handful of
     * unrelated swipe-home gestures spread across a whole video session would exhaust it and
     * silently disable Strong Lock. Windowing means only a genuine rapid fight with the launcher
     * self-terminates by releasing the lock instead of looping; isolated attempts age out and
     * never accumulate.
     */
    private fun performSnapBack(protectedPackage: String) {
        val now = SystemClock.elapsedRealtime()
        snapBackTimestamps.addLast(now)
        while (snapBackTimestamps.isNotEmpty() &&
            now - snapBackTimestamps.first() > SNAP_BACK_RATE_LIMIT_WINDOW_MILLIS
        ) {
            snapBackTimestamps.removeFirst()
        }
        Timber.d(
            "Snap-back (${snapBackTimestamps.size} within the last " +
                "${SNAP_BACK_RATE_LIMIT_WINDOW_MILLIS}ms) to $protectedPackage"
        )

        if (snapBackTimestamps.size > MAX_SNAP_BACK_ATTEMPTS) {
            Timber.w("Snap-back limit reached within the rate-limit window, auto-releasing lock")
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
        // Must be the unfiltered package: an allowlisted surface (Settings, dialer, emergency
        // alert) in front means BACK stays functional so the user is never trapped in it.
        if (allowlist.isAllowlisted(currentForegroundPackage)) return false

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
        // Nothing to release when already unlocked, and starting a *stopped* service from the
        // background throws IllegalStateException — which would crash this accessibility service
        // and make the system disable it. Reachable in practice: the volume combo is fed to
        // [handleVolumeCombo] before the lock-state check in onKeyEvent, so it fires even with the
        // lock off and LockOverlayService dismissed.
        if (LockOverlayService.lockState.value != LockState.Locked) return

        val intent = Intent(this, LockOverlayService::class.java).apply {
            action = LockOverlayService.ACTION_FORCE_UNLOCK
        }
        try {
            startService(intent)
        } catch (e: Exception) {
            Timber.e(e, "Failed to start LockOverlayService for force unlock")
        }
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
        private const val SNAP_BACK_RATE_LIMIT_WINDOW_MILLIS = 5000L
        private const val SHADE_DISMISS_DEBOUNCE_MILLIS = 1000L
        private const val SNAP_BACK_SUPPRESSION_AFTER_SELF_ACTION_MILLIS = 1500L
    }
}
