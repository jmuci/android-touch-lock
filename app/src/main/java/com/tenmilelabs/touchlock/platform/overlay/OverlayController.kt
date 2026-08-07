package com.tenmilelabs.touchlock.platform.overlay

import android.annotation.SuppressLint
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
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

    private var overlayView: OverlayView? = null
    private var overlayWindowManager: WindowManager? = null
    private var overlayWindowType: Int? = null

    // Recomputes and reapplies the overlay's bounds on every configuration change while it's
    // attached. Necessary because fullScreenLayoutParams() computes bounds once, and a live
    // window doesn't recompute its own LayoutParams on rotation — confirmed on-device that
    // without this, rotating while locked leaves the window's dimensions from the *previous*
    // orientation, collapsing the touch-blocked area into a corner of the new one rather than
    // covering the new orientation's full bounds. Registered on the application Context, which is
    // notified of config changes independent of which WindowManager the overlay itself uses.
    private var configCallbacks: ComponentCallbacks? = null

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
    @VisibleForTesting
    internal fun resolveTarget(): Pair<WindowManager, Int> {
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
            manager.addView(view, fullScreenLayoutParams(manager, type))
            overlayView = view
            overlayWindowManager = manager
            overlayWindowType = type
            registerRotationListener()
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to add overlay view (type=$type)")
            false
        }
    }

    private fun registerRotationListener() {
        if (configCallbacks != null) return
        val callbacks = object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) = relayoutForCurrentBounds()

            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION") // Required override; unused, no memory to trim here.
            override fun onLowMemory() {}
        }
        context.registerComponentCallbacks(callbacks)
        configCallbacks = callbacks
    }

    private fun unregisterRotationListener() {
        configCallbacks?.let { context.unregisterComponentCallbacks(it) }
        configCallbacks = null
    }

    private fun relayoutForCurrentBounds() {
        val view = overlayView ?: return
        val manager = overlayWindowManager ?: return
        val type = overlayWindowType ?: return
        try {
            manager.updateViewLayout(view, fullScreenLayoutParams(manager, type))
        } catch (e: Exception) {
            Timber.e(e, "Failed to relayout overlay view after configuration change")
        }
    }

    fun hide() {
        // Clean up countdown overlay first
        hideCountdownOverlay()

        // Clean up unlock handle
        hideUnlockHandle()

        unregisterRotationListener()

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
        overlayWindowType = null
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

    /**
     * Explicit real display bounds rather than MATCH_PARENT, which — confirmed on-device,
     * empirically, after MATCH_PARENT + a manual y-offset left a same-size gap on the wrong edge
     * post-rotation — turns out to trigger an implicit system-bar-avoidance reservation of its
     * own for TYPE_ACCESSIBILITY_OVERLAY specifically. That reservation is tied to the *device's*
     * natural-orientation top edge rather than the display's current rotated top, so in landscape
     * it lands on whichever physical edge that maps to (the nav-bar side, not the status bar) —
     * and it persists identically whether or not a y-offset is set, and whether or not
     * `fitInsetsTypes` is explicitly zeroed, so it isn't something addressable through public
     * LayoutParams fields. Requesting the display's actual pixel bounds directly sidesteps it
     * entirely. Since a live window doesn't recompute this on its own, [relayoutForCurrentBounds]
     * reapplies it on every configuration change.
     */
    @SuppressLint("RtlHardcoded")
    private fun fullScreenLayoutParams(manager: WindowManager, type: Int): WindowManager.LayoutParams {
        val bounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            manager.currentWindowMetrics.bounds
        } else {
            val point = Point()
            @Suppress("DEPRECATION")
            manager.defaultDisplay.getRealSize(point)
            Rect(0, 0, point.x, point.y)
        }
        return WindowManager.LayoutParams(
            bounds.width(),
            bounds.height(),
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
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
