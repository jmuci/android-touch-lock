package com.tenmilelabs.touchlock.platform.overlay

import android.annotation.SuppressLint
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.graphics.Insets
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowInsets
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

    private var navBarOverlayView: OverlayView? = null
    private var navBarOverlayWindowManager: WindowManager? = null

    /**
     * Resolves the connected accessibility service's own WindowManager + TYPE_ACCESSIBILITY_OVERLAY
     * (needed to cover the navigation bar — adding a 2032 window via the app's own WindowManager
     * throws BadTokenException, it must go through the service's WindowManager instance), or the
     * application WindowManager + TYPE_APPLICATION_OVERLAY when no service is connected. Resolved
     * per-call rather than cached, since accessibility connection state can change between calls.
     *
     * Used ONLY by [showNavBarOverlay] below. The main touch-blocking overlay, the unlock handle,
     * and the countdown popup are never nav-bar-adjacent and don't need this elevated window type —
     * an earlier version routed all of them through this same resolution, which made the *entire*
     * full-screen touch-blocking overlay a touchable TYPE_ACCESSIBILITY_OVERLAY whenever Strong
     * Lock was connected. Confirmed on-device that this collides with the system's own edge-swipe
     * gesture monitor when the notification shade is opened with a real drag gesture, causing a
     * severe (17-20s) WindowManagerService/InputDispatcher stall and a system-wide ANR — reproduced
     * with the main overlay covering the full screen, and confirmed absent when no overlay window
     * was present at all during the same gesture. Scoping the elevated window type to only the
     * small nav-bar-sized strip below removes that window from underneath the status bar / shade
     * area entirely, which is also what this service's own architecture documentation always
     * described this overlay as being scoped to.
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

        // Always the application overlay — never elevated to TYPE_ACCESSIBILITY_OVERLAY. See the
        // doc comment on resolveTarget() for why: this window spans the full screen, and making it
        // touchable at the accessibility-overlay layer is what caused the system-wide ANR.
        if (!tryAddOverlayView(view, appWindowManager, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)) {
            return false
        }

        // Nav-bar tap blocking, scoped to just that strip, is the one thing that still needs the
        // elevated window type — added as a second, separate window rather than by elevating the
        // main overlay above. Best-effort: failure here (or accessibility not being connected at
        // all, the default-mode case) doesn't fail the lock, it just narrows to the pre-existing,
        // documented limitation of not blocking nav-bar taps.
        showNavBarOverlay(onUnlockRequested)
        return true
    }

    private fun showNavBarOverlay(onUnlockRequested: () -> Unit) {
        hideNavBarOverlay()

        val (manager, type) = resolveTarget()
        if (type != WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) return

        val params = navigationBarLayoutParams(manager) ?: return
        val view = OverlayView(
            context = context,
            onDoubleTapDetected = { showUnlockHandle(onUnlockRequested) }
        )
        try {
            manager.addView(view, params)
            navBarOverlayView = view
            navBarOverlayWindowManager = manager
        } catch (e: Exception) {
            Timber.e(e, "Failed to add nav-bar overlay view")
        }
    }

    private fun hideNavBarOverlay() {
        navBarOverlayView?.let {
            it.cleanup()
            try {
                (navBarOverlayWindowManager ?: appWindowManager).removeView(it)
            } catch (e: Exception) {
                Timber.e(e, "Failed to remove nav-bar overlay view")
            }
        }
        navBarOverlayView = null
        navBarOverlayWindowManager = null
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

        // Nav bar position/size can change on rotation (e.g. moves to a side edge on some
        // 3-button-nav devices in landscape) — recompute rather than reuse the params it was
        // originally added with.
        navBarOverlayView?.let { navView ->
            val navManager = navBarOverlayWindowManager ?: return@let
            val params = navigationBarLayoutParams(navManager) ?: return@let
            try {
                navManager.updateViewLayout(navView, params)
            } catch (e: Exception) {
                Timber.e(e, "Failed to relayout nav-bar overlay view after configuration change")
            }
        }
    }

    fun hide() {
        // Clean up countdown overlay first
        hideCountdownOverlay()

        // Clean up unlock handle
        hideUnlockHandle()

        // Clean up nav-bar overlay
        hideNavBarOverlay()

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

        // Never nav-bar-adjacent — always the application overlay, same as the main touch-blocking
        // overlay. See the doc comment on resolveTarget() for why this must not be elevated.
        try {
            appWindowManager.addView(
                view,
                countdownLayoutParams(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            )
            countdownOverlayView = view
            countdownWindowManager = appWindowManager
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

        // Never nav-bar-adjacent — always the application overlay, same as the main touch-blocking
        // overlay. See the doc comment on resolveTarget() for why this must not be elevated.
        try {
            appWindowManager.addView(
                view,
                handleLayoutParams(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            )
            unlockHandleView = view
            unlockHandleWindowManager = appWindowManager
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
     * post-rotation — turns out to trigger an implicit system-bar-avoidance reservation on some
     * OEM builds for TYPE_ACCESSIBILITY_OVERLAY specifically.
     *
     * An earlier version of this method trusted that platform reservation and only shrunk our own
     * request away from status-bar insets outside portrait/ROTATION_0, on the theory that the
     * platform already handled portrait and self-shrinking there too would double up into a dead
     * zone. **That assumption doesn't hold everywhere**: confirmed via `dumpsys window` on a
     * stock/AOSP build (Pixel emulator image, API 36) that the platform applies no such
     * reservation at all — `fitInsetsTypes` is set but overridden by our own
     * `FLAG_LAYOUT_NO_LIMITS`, so the *applied* frame matched our *requested* full-bounds frame
     * exactly, status bar included. With Strong Lock active, that leaves this touchable
     * TYPE_ACCESSIBILITY_OVERLAY window covering the exact pixels a real edge-swipe-down gesture
     * (opening the notification shade) needs to be recognized on — confirmed on-device this
     * triggers a severe (17-20s) WindowManagerService/InputDispatcher stall and a system-wide ANR,
     * not merely a swallowed gesture. That is a strictly worse failure mode than the cosmetic
     * double-inset dead zone this method used to avoid.
     *
     * So: always shrink our own request by the live, rotation-correct WindowInsets.Type.statusBars()
     * inset on API 30+ for the accessibility-overlay type, in every rotation including portrait,
     * regardless of what any given OEM's platform reservation independently does. On an OEM build
     * where the platform *also* reserves this space (as previously confirmed on one Samsung
     * device), this now double-shrinks in portrait — a bounded, cosmetic-only gap near the top
     * edge — which is the accepted tradeoff for eliminating a reproducible, system-wide ANR on
     * other builds. Below API 30, there's no live per-edge inset query available, so this falls
     * back to the historical fixed-top-offset behavior — correct in portrait, not rotation-aware
     * in landscape on these older API levels, the same kind of disclosed narrower-on-old-API-levels
     * limitation already documented elsewhere in this codebase (see the shade-auto-dismiss API
     * gate in TouchLockAccessibilityService).
     *
     * Since a live window doesn't recompute any of this on its own, [relayoutForCurrentBounds]
     * reapplies it on every configuration change.
     */
    @SuppressLint("RtlHardcoded")
    @VisibleForTesting
    internal fun fullScreenLayoutParams(manager: WindowManager, type: Int): WindowManager.LayoutParams {
        val bounds: Rect
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = manager.currentWindowMetrics
            bounds = Rect(metrics.bounds)
            if (type == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) {
                val statusBarInsets = metrics.windowInsets.getInsets(WindowInsets.Type.statusBars())
                bounds.left += statusBarInsets.left
                bounds.top += statusBarInsets.top
                bounds.right -= statusBarInsets.right
                bounds.bottom -= statusBarInsets.bottom
            }
        } else {
            val point = Point()
            @Suppress("DEPRECATION")
            manager.defaultDisplay.getRealSize(point)
            bounds = Rect(0, 0, point.x, point.y)
            if (type == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) {
                bounds.top += legacyStatusBarHeightPx()
            }
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
            x = bounds.left
            y = bounds.top
        }
    }

    /**
     * Status bar height in px, for the pre-API-30 fallback in [fullScreenLayoutParams] only —
     * below API 30 there's no live per-edge WindowInsets query available. Resource lookup rather
     * than the deprecated WindowInsets APIs so it works uniformly from minSdk 26.
     */
    private fun legacyStatusBarHeightPx(): Int {
        val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) context.resources.getDimensionPixelSize(resourceId) else 0
    }

    /**
     * LayoutParams for a small window covering just the navigation bar strip — never the full
     * screen — so the elevated TYPE_ACCESSIBILITY_OVERLAY window type this requires never overlaps
     * the status bar / notification-shade area. Returns null when there's nothing to cover (no
     * navigation-bar inset reported at all, e.g. some gesture-nav configurations), in which case
     * [showNavBarOverlay] skips adding a window — consistent with the existing documented
     * limitation that gesture-nav swipes aren't blocked by this overlay, only reacted to via
     * snap-back.
     */
    @VisibleForTesting
    internal fun navigationBarLayoutParams(manager: WindowManager): WindowManager.LayoutParams? {
        val bounds: Rect
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val full = Rect(manager.currentWindowMetrics.bounds)
            val navInsets = manager.currentWindowMetrics.windowInsets.getInsets(WindowInsets.Type.navigationBars())
            bounds = navigationBarStrip(full, navInsets) ?: return null
        } else {
            val point = Point()
            @Suppress("DEPRECATION")
            manager.defaultDisplay.getRealSize(point)
            val height = legacyNavigationBarHeightPx()
            if (height <= 0) return null
            bounds = Rect(0, point.y - height, point.x, point.y)
        }
        return WindowManager.LayoutParams(
            bounds.width(),
            bounds.height(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = bounds.left
            y = bounds.top
        }
    }

    /**
     * Reduces the full display bounds to just the edge strip where the navigation-bar inset is
     * reported — bottom on the vast majority of devices/orientations, but left/right on some
     * 3-button-nav devices in landscape. Null when no edge reports a nonzero inset.
     */
    @VisibleForTesting
    internal fun navigationBarStrip(full: Rect, navInsets: Insets): Rect? = when {
        navInsets.bottom > 0 -> Rect(full.left, full.bottom - navInsets.bottom, full.right, full.bottom)
        navInsets.left > 0 -> Rect(full.left, full.top, full.left + navInsets.left, full.bottom)
        navInsets.right > 0 -> Rect(full.right - navInsets.right, full.top, full.right, full.bottom)
        navInsets.top > 0 -> Rect(full.left, full.top, full.right, full.top + navInsets.top)
        else -> null
    }

    /**
     * Navigation bar height in px, for the pre-API-30 fallback in [navigationBarLayoutParams] only
     * — below API 30 there's no live per-edge WindowInsets query available. Resource lookup rather
     * than the deprecated WindowInsets APIs so it works uniformly from minSdk 26.
     */
    private fun legacyNavigationBarHeightPx(): Int {
        val resourceId = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (resourceId > 0) context.resources.getDimensionPixelSize(resourceId) else 0
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
