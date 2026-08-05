package com.tenmilelabs.touchlock.platform.accessibility

import android.view.KeyEvent
import com.tenmilelabs.touchlock.platform.time.TimeProvider
import javax.inject.Inject

/**
 * Detects the Volume-Up + Volume-Down held-together force-unlock combo.
 *
 * Pure decision logic, deliberately decoupled from AccessibilityService/CoroutineScope so it can
 * be unit tested with a fake clock. The caller is responsible for polling
 * [isHoldThresholdReached] while [isComboActive] is true (e.g. via a delayed coroutine), since
 * key events alone don't fire while both keys are held motionless.
 */
class VolumeComboDetector @Inject constructor(
    private val timeProvider: TimeProvider
) {
    private var comboStartedAtMillis: Long? = null
    private var volumeUpPressed = false
    private var volumeDownPressed = false

    /** Feed every KeyEvent seen by the caller. Never consumes — volume must stay functional. */
    fun onKeyEvent(keyCode: Int, action: Int) {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> volumeUpPressed = action == KeyEvent.ACTION_DOWN
            KeyEvent.KEYCODE_VOLUME_DOWN -> volumeDownPressed = action == KeyEvent.ACTION_DOWN
            else -> return
        }

        if (volumeUpPressed && volumeDownPressed) {
            if (comboStartedAtMillis == null) {
                comboStartedAtMillis = timeProvider.currentTimeMillis()
            }
        } else {
            comboStartedAtMillis = null
        }
    }

    fun isComboActive(): Boolean = volumeUpPressed && volumeDownPressed

    fun isHoldThresholdReached(): Boolean {
        val startedAt = comboStartedAtMillis ?: return false
        if (!isComboActive()) return false
        return timeProvider.currentTimeMillis() - startedAt >= HOLD_DURATION_MILLIS
    }

    companion object {
        const val HOLD_DURATION_MILLIS = 3000L
    }
}
