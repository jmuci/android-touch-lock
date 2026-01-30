package com.tenmilelabs.touchlock.platform.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import com.tenmilelabs.touchlock.domain.model.OrientationMode
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

    private var overlayView: OverlayView? = null
    private var unlockHandleView: UnlockHandleView? = null
    private var countdownOverlayView: CountdownOverlayView? = null
    private var currentOrientationMode: OrientationMode = OrientationMode.FOLLOW_SYSTEM

    private val hideHandleRunnable = Runnable {
        hideUnlockHandle()
    }

    /**
     * Shows the full-screen touch-blocking overlay via WindowManager.
     * Used as fallback when OverlayActivity goes to background (e.g., Home button pressed).
     *
     * Note: WindowManager overlays cannot hide system UI or lock orientation,
     * but they persist across Home button presses.
     */
    fun showOverlay(
        orientationMode: OrientationMode,
        debugTintVisible: Boolean = false,
        onUnlockRequested: () -> Unit,
        onDoubleTapDetected: () -> Unit
    ) {
        if (overlayView != null) {
            Timber.d("TL::lifecycle OverlayController.showOverlay() - Overlay already shown, skipping")
            return
        }

        Timber.d("TL::lifecycle OverlayController.showOverlay() - Showing WindowManager overlay")

        currentOrientationMode = orientationMode

        overlayView = OverlayView(
            context = context,
            onUnlockRequested = onUnlockRequested,
            onDoubleTapDetected = onDoubleTapDetected,
            debugTintVisible = debugTintVisible
        )
        windowManager.addView(overlayView, fullScreenLayoutParams(orientationMode))
    }

    /**
     * Hides the full-screen touch-blocking overlay.
     * Called when OverlayActivity comes back to foreground.
     */
    fun hideOverlay() {
        overlayView?.let {
            Timber.d("TL::lifecycle OverlayController.hideOverlay() - Hiding WindowManager overlay")
            it.cleanup()
            windowManager.removeView(it)
        }
        overlayView = null
    }

    /**
     * Checks if the WindowManager overlay is currently shown.
     */
    fun isOverlayShown(): Boolean = overlayView != null

    @Deprecated("Use showOverlay instead", ReplaceWith("showOverlay(orientationMode, debugTintVisible, onUnlockRequested, {})"))
    fun show(
        orientationMode: OrientationMode,
        debugTintVisible: Boolean = false,
        onUnlockRequested: () -> Unit
    ) {
        showOverlay(orientationMode, debugTintVisible, onUnlockRequested) {
            showUnlockHandle(onUnlockRequested)
        }
    }

    fun hide() {
        // Clean up countdown overlay first
        hideCountdownOverlay()

        // Clean up unlock handle
        hideUnlockHandle()
        handler.removeCallbacks(hideHandleRunnable)

        // Clean up main overlay
        hideOverlay()
    }

    /**
     * Shows the unlock handle via WindowManager.
     * This persists even when OverlayActivity is in background.
     *
     * @param onUnlockRequested Callback when user completes unlock gesture
     * @param autoHide If true, handle will auto-hide after timeout. Set to false for persistent display.
     */
    fun showPersistentUnlockHandle(onUnlockRequested: () -> Unit, autoHide: Boolean = true) {
        // Remove existing handle if present
        hideUnlockHandle()

        Timber.d("TL::lifecycle OverlayController.showPersistentUnlockHandle() - Showing unlock handle, autoHide=$autoHide")

        unlockHandleView = UnlockHandleView(context) {
            // When unlock is requested from handle
            onUnlockRequested()
        }

        windowManager.addView(unlockHandleView, handleLayoutParams())

        // Auto-hide after timeout only if requested
        if (autoHide) {
            handler.postDelayed(hideHandleRunnable, HANDLE_VISIBILITY_TIMEOUT_MS)
        }
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
                    // FLAG_LAYOUT_IN_SCREEN: Allows the overlay to extend into system UI areas.
                    // Combined with immersive mode (set on the view), this creates true fullscreen.
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    // FLAG_LAYOUT_NO_LIMITS: Allows the overlay to extend beyond screen bounds.
                    // Required for the overlay to properly fill the entire display when system UI is hidden.
                    // Note: System UI hiding is handled by the view itself using WindowInsetsController
                    // (Android 11+) or systemUiVisibility flags (Android 10 and below).
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
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
        val sizePx = dpToPx(HANDLE_SIZE_DP)
        return WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }
    }

    private fun dpToPx(dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            context.resources.displayMetrics
        ).toInt()
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

