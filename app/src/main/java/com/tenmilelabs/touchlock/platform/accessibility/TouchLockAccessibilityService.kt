package com.tenmilelabs.touchlock.platform.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
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

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var forceUnlockPollJob: Job? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Timber.d("TouchLockAccessibilityService connected")
        holder.attach(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Populated in a later task (shade dismissal, snap-back).
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        handleVolumeCombo(event)
        // Populated in a later task: BACK consumption while locked.
        return false
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
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Timber.d("TouchLockAccessibilityService destroyed")
        holder.detach()
        serviceScope.cancel()
        super.onDestroy()
    }
}
