package com.tenmilelabs.touchlock.platform.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.widget.FrameLayout

/**
 * Full-screen overlay that blocks all touch input.
 * Detects double-tap to show unlock handle.
 * Detects long-press for direct unlock (legacy).
 * 
 * Note: System UI hiding (status bar, navigation bar) is handled by OverlayActivity,
 * since TYPE_APPLICATION_OVERLAY windows cannot control system UI visibility.
 * 
 * @param debugTintVisible Debug-only: When true, applies a visible tint to confirm overlay is attached
 */
class OverlayView(
    context: Context,
    private val onUnlockRequested: () -> Unit,
    private val onDoubleTapDetected: () -> Unit,
    debugTintVisible: Boolean = false
) : FrameLayout(context) {

    private val handler = Handler(Looper.getMainLooper())
    private var longPressTriggered = false

    // Double-tap detection
    private var lastTapTime = 0L
    private var tapCount = 0

    private val longPressRunnable = Runnable {
        longPressTriggered = true
        onUnlockRequested()
    }

    private val doubleTapResetRunnable = Runnable {
        tapCount = 0
    }

    init {
        // Debug-only: Apply visible tint to confirm overlay is attached (for lifecycle debugging)
        if (debugTintVisible) {
            setBackgroundColor(Color.argb(13, 255, 0, 0)) // ~5% red tint
        } else {
            setBackgroundColor(Color.TRANSPARENT)
        }
        isClickable = true
        isFocusable = false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                longPressTriggered = false
                handler.postDelayed(longPressRunnable, LONG_PRESS_DURATION_MS)

                // Detect double-tap
                val currentTime = System.currentTimeMillis()
                val timeSinceLastTap = currentTime - lastTapTime

                if (timeSinceLastTap < DOUBLE_TAP_TIMEOUT_MS) {
                    tapCount++
                    if (tapCount >= 2) {
                        // Double-tap detected!
                        handler.removeCallbacks(doubleTapResetRunnable)
                        handler.removeCallbacks(longPressRunnable)
                        tapCount = 0
                        onDoubleTapDetected()
                    }
                } else {
                    tapCount = 1
                }

                lastTapTime = currentTime
                handler.removeCallbacks(doubleTapResetRunnable)
                handler.postDelayed(doubleTapResetRunnable, DOUBLE_TAP_TIMEOUT_MS)
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
            }
        }
        return true // Always consume touch
    }

    fun cleanup() {
        handler.removeCallbacks(longPressRunnable)
        handler.removeCallbacks(doubleTapResetRunnable)
    }

    companion object {
        private const val LONG_PRESS_DURATION_MS = 1500L
        private const val DOUBLE_TAP_TIMEOUT_MS = 400L
    }
}