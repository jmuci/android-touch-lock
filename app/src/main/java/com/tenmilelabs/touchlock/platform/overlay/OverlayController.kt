package com.tenmilelabs.touchlock.platform.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.Surface
import android.view.WindowManager
import com.tenmilelabs.touchlock.domain.model.OrientationMode
import com.tenmilelabs.touchlock.platform.overlay.UnlockHandleView.Companion.HANDLE_SIZE_DP
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OverlayController @Inject constructor(
    @param:ApplicationContext private val context: Context
) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val handler = Handler(Looper.getMainLooper())

    private var overlayView: OverlayView? = null
    private var unlockHandleView: UnlockHandleView? = null
    private var countdownOverlayView: CountdownOverlayView? = null
    private var currentOrientationMode: OrientationMode = OrientationMode.FOLLOW_SYSTEM

    private val hideHandleRunnable = Runnable {
        hideUnlockHandle()
    }

    fun show(orientationMode: OrientationMode, onUnlockRequested: () -> Unit) {
        if (overlayView != null) return

        currentOrientationMode = orientationMode

        overlayView = OverlayView(
            context = context,
            onUnlockRequested = onUnlockRequested,
            onDoubleTapDetected = {
                showUnlockHandle(onUnlockRequested)
            }
        )
        windowManager.addView(overlayView, fullScreenLayoutParams(orientationMode))
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
            windowManager.removeView(it)
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

        windowManager.addView(countdownOverlayView, countdownLayoutParams())
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
            windowManager.removeView(it)
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

        windowManager.addView(unlockHandleView, handleLayoutParams())

        // Auto-hide after timeout
        handler.postDelayed(hideHandleRunnable, HANDLE_VISIBILITY_TIMEOUT_MS)
    }

    private fun hideUnlockHandle() {
        unlockHandleView?.let {
            it.cleanup()
            windowManager.removeView(it)
        }
        unlockHandleView = null
        handler.removeCallbacks(hideHandleRunnable)
    }

    @SuppressLint("RtlHardcoded")
    private fun fullScreenLayoutParams(orientationMode: OrientationMode): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    // FLAG_LAYOUT_NO_LIMITS allows the window to extend beyond screen bounds
                    // This helps with orientation changes
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            
            // Set screen orientation based on mode
            // Note: screenOrientation on WindowManager.LayoutParams is officially supported
            // but TYPE_APPLICATION_OVERLAY windows have limited ability to force system rotation.
            // This works best when the user has already rotated to the desired orientation.
            // For full orientation locking control, a transparent Activity would be needed.
            screenOrientation = when (orientationMode) {
                OrientationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                OrientationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                OrientationMode.FOLLOW_SYSTEM -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }

    private fun handleLayoutParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            HANDLE_SIZE_DP.toInt(),
            HANDLE_SIZE_DP.toInt(),
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
            x = 16 // 16dp from right
            y = 100 // 100dp from top
        }
    }

    companion object {
        private const val HANDLE_VISIBILITY_TIMEOUT_MS = 4000L // 4 seconds
    }
}

