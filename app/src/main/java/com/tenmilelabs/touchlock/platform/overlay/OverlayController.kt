package com.tenmilelabs.touchlock.platform.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import com.tenmilelabs.touchlock.platform.overlay.UnlockHandleView.Companion.HANDLE_SIZE_DP
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OverlayController @Inject constructor(
    @param:ApplicationContext private val context: Context
) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val handler = Handler(Looper.getMainLooper())

    // Use windowManager display metrics rather than context.resources.displayMetrics so that
    // dp→px conversions are correct on foldables and multi-display setups (H1 fix).
    @Suppress("DEPRECATION")
    private val displayMetrics get() = windowManager.defaultDisplay.let { display ->
        android.util.DisplayMetrics().also { display.getRealMetrics(it) }
    }

    private fun dpToPx(dp: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, displayMetrics).toInt()

    private var overlayView: OverlayView? = null
    private var unlockHandleView: UnlockHandleView? = null
    private var countdownOverlayView: CountdownOverlayView? = null

    private val hideHandleRunnable = Runnable {
        hideUnlockHandle()
    }

    fun show(
        debugTintVisible: Boolean = false,
        onUnlockRequested: () -> Unit
    ): Boolean {
        if (overlayView != null) return true

        overlayView = OverlayView(
            context = context,
            onUnlockRequested = onUnlockRequested,
            onDoubleTapDetected = {
                showUnlockHandle(onUnlockRequested)
            },
            debugTintVisible = debugTintVisible
        )
        return try {
            windowManager.addView(overlayView, fullScreenLayoutParams())
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to add overlay view")
            overlayView = null
            false
        }
    }

    fun hide() {
        // Clean up countdown overlay first
        hideCountdownOverlay()

        // Clean up unlock handle
        hideUnlockHandle()
        handler.removeCallbacks(hideHandleRunnable)

        // Clean up main overlay
        overlayView?.let {
            it.cleanup()
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Timber.e(e, "Failed to remove overlay view")
            }
        }
        overlayView = null
    }

    /**
     * Shows countdown overlay (non-blocking, displays timer).
     */
    fun showCountdownOverlay(initialSeconds: Int) {
        // Remove existing countdown if present
        hideCountdownOverlay()

        countdownOverlayView = CountdownOverlayView(context).apply {
            updateCountdown(initialSeconds)
        }

        try {
            windowManager.addView(countdownOverlayView, countdownLayoutParams())
        } catch (e: Exception) {
            Timber.e(e, "Failed to add countdown overlay view")
            countdownOverlayView = null
        }
    }

    /**
     * Updates the countdown display.
     */
    fun updateCountdown(seconds: Int) {
        countdownOverlayView?.updateCountdown(seconds)
    }

    /**
     * Hides countdown overlay.
     */
    fun hideCountdownOverlay() {
        countdownOverlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Timber.e(e, "Failed to remove countdown overlay view")
            }
        }
        countdownOverlayView = null
    }

    private fun showUnlockHandle(onUnlockRequested: () -> Unit) {
        // Remove existing handle if present
        hideUnlockHandle()

        unlockHandleView = UnlockHandleView(context) {
            // When unlock is requested from handle
            onUnlockRequested()
        }

        try {
            windowManager.addView(unlockHandleView, handleLayoutParams())
        } catch (e: Exception) {
            Timber.e(e, "Failed to add unlock handle view")
            unlockHandleView = null
            return
        }

        // Auto-hide after timeout
        handler.postDelayed(hideHandleRunnable, HANDLE_VISIBILITY_TIMEOUT_MS)
    }

    private fun hideUnlockHandle() {
        unlockHandleView?.let {
            it.cleanup()
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Timber.e(e, "Failed to remove unlock handle view")
            }
        }
        unlockHandleView = null
        handler.removeCallbacks(hideHandleRunnable)
    }

    @SuppressLint("RtlHardcoded")
    private fun fullScreenLayoutParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
        }
    }

    private fun handleLayoutParams(): WindowManager.LayoutParams {
        val handleSizePx = dpToPx(HANDLE_SIZE_DP)
        return WindowManager.LayoutParams(
            handleSizePx,
            handleSizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }
    }

    private fun countdownLayoutParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, // IMPORTANT: Don't intercept touches
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER
            x = dpToPx(16f)  // 16dp from center
            y = dpToPx(100f) // 100dp from top
        }
    }

    companion object {
        private const val HANDLE_VISIBILITY_TIMEOUT_MS = 4000L // 4 seconds
    }
}
