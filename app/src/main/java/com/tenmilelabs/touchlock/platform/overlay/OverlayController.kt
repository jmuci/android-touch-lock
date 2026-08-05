package com.tenmilelabs.touchlock.platform.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import androidx.annotation.VisibleForTesting
import com.tenmilelabs.touchlock.platform.accessibility.AccessibilityServiceHolder
import com.tenmilelabs.touchlock.platform.overlay.UnlockHandleView.Companion.HANDLE_SIZE_DP
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OverlayController @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val accessibilityServiceHolder: AccessibilityServiceHolder
) {

    private val appWindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var hideHandleJob: Job? = null

    @VisibleForTesting
    internal val displayMetrics: DisplayMetrics get() = context.resources.displayMetrics

    @VisibleForTesting
    internal fun dpToPx(dp: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, displayMetrics).toInt()

    /**
     * Status bar height in px, used to inset accessibility-overlay windows so the status bar and
     * its privacy/camera/mic/cast indicators are never covered. Resource lookup rather than
     * WindowInsets.Type.statusBars() so it works uniformly from minSdk 26.
     */
    @VisibleForTesting
    internal fun statusBarHeightPx(): Int {
        val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) context.resources.getDimensionPixelSize(resourceId) else 0
    }

    private var overlayView: OverlayView? = null
    private var overlayWindowManager: WindowManager? = null

    private var unlockHandleView: UnlockHandleView? = null
    private var unlockHandleWindowManager: WindowManager? = null

    private var countdownOverlayView: CountdownOverlayView? = null
    private var countdownWindowManager: WindowManager? = null

    /**
     * Resolves which manager/window-type a new overlay window should be added with: the
     * connected accessibility service's own WindowManager + TYPE_ACCESSIBILITY_OVERLAY (which can
     * cover the navigation bar) when available, else the application WindowManager +
     * TYPE_APPLICATION_OVERLAY (today's behavior, unchanged). Resolved per-call rather than
     * cached, since accessibility connection state can change between calls — and adding a 2032
     * window via the app's own WindowManager throws BadTokenException, it must go through the
     * service's WindowManager instance.
     */
    private fun resolveTarget(): Pair<WindowManager, Int> {
        val service = accessibilityServiceHolder.currentService()
        return if (service != null) {
            (service.getSystemService(Context.WINDOW_SERVICE) as WindowManager) to
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            appWindowManager to WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }
    }

    fun show(
        debugTintVisible: Boolean = false,
        onUnlockRequested: () -> Unit
    ): Boolean {
        if (overlayView != null) return true

        val view = OverlayView(
            context = context,
            onUnlockRequested = onUnlockRequested,
            onDoubleTapDetected = {
                showUnlockHandle(onUnlockRequested)
            },
            debugTintVisible = debugTintVisible
        )

        val (manager, type) = resolveTarget()
        if (tryAddOverlayView(view, manager, type)) return true

        if (type == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) {
            // Fail safely: fall back to the application-overlay path rather than failing the lock.
            Timber.w("Accessibility overlay failed, falling back to application overlay")
            return tryAddOverlayView(view, appWindowManager, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        }
        return false
    }

    private fun tryAddOverlayView(view: OverlayView, manager: WindowManager, type: Int): Boolean {
        return try {
            manager.addView(view, fullScreenLayoutParams(type))
            overlayView = view
            overlayWindowManager = manager
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to add overlay view (type=$type)")
            false
        }
    }

    fun hide() {
        // Clean up countdown overlay first
        hideCountdownOverlay()

        // Clean up unlock handle
        hideUnlockHandle()

        // Clean up main overlay
        overlayView?.let {
            it.cleanup()
            try {
                (overlayWindowManager ?: appWindowManager).removeView(it)
            } catch (e: Exception) {
                Timber.e(e, "Failed to remove overlay view")
            }
        }
        overlayView = null
        overlayWindowManager = null
    }

    /**
     * Shows countdown overlay (non-blocking, displays timer).
     */
    fun showCountdownOverlay(initialSeconds: Int) {
        // Remove existing countdown if present
        hideCountdownOverlay()

        val view = CountdownOverlayView(context).apply {
            updateCountdown(initialSeconds)
        }

        val (manager, type) = resolveTarget()
        try {
            manager.addView(view, countdownLayoutParams(type))
            countdownOverlayView = view
            countdownWindowManager = manager
        } catch (e: Exception) {
            Timber.e(e, "Failed to add countdown overlay view")
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
                (countdownWindowManager ?: appWindowManager).removeView(it)
            } catch (e: Exception) {
                Timber.e(e, "Failed to remove countdown overlay view")
            }
        }
        countdownOverlayView = null
        countdownWindowManager = null
    }

    private fun showUnlockHandle(onUnlockRequested: () -> Unit) {
        // Remove existing handle if present
        hideUnlockHandle()

        val view = UnlockHandleView(context) {
            // When unlock is requested from handle
            onUnlockRequested()
        }

        val (manager, type) = resolveTarget()
        try {
            manager.addView(view, handleLayoutParams(type))
            unlockHandleView = view
            unlockHandleWindowManager = manager
        } catch (e: Exception) {
            Timber.e(e, "Failed to add unlock handle view")
            return
        }

        // Auto-hide after timeout
        hideHandleJob = scope.launch {
            delay(HANDLE_VISIBILITY_TIMEOUT_MS)
            hideUnlockHandle()
        }
    }

    private fun hideUnlockHandle() {
        unlockHandleView?.let {
            it.cleanup()
            try {
                (unlockHandleWindowManager ?: appWindowManager).removeView(it)
            } catch (e: Exception) {
                Timber.e(e, "Failed to remove unlock handle view")
            }
        }
        unlockHandleView = null
        unlockHandleWindowManager = null
        hideHandleJob?.cancel()
        hideHandleJob = null
        // We cancel itself to make sure we don't leave any dangling
        // coroutines that might have been on suspend state (eg. supsended with delay)
    }

    @SuppressLint("RtlHardcoded")
    private fun fullScreenLayoutParams(type: Int): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            if (type == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) {
                // Inset the top edge so the status bar and its privacy/cast/record indicators
                // are never covered. Extends through the nav bar at the bottom, unlike the
                // application-overlay path which can never intercept input there.
                y = statusBarHeightPx()
            }
        }
    }

    private fun handleLayoutParams(type: Int): WindowManager.LayoutParams {
        val handleSizePx = dpToPx(HANDLE_SIZE_DP)
        return WindowManager.LayoutParams(
            handleSizePx,
            handleSizePx,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }
    }

    private fun countdownLayoutParams(type: Int): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
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
