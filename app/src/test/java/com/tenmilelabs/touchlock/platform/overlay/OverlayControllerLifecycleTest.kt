package com.tenmilelabs.touchlock.platform.overlay

import android.content.Context
import android.os.Build
import android.os.Looper
import android.view.WindowManager
import com.google.common.truth.Truth.assertThat
import com.tenmilelabs.touchlock.platform.accessibility.AccessibilityServiceHolder
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Robolectric-backed tests for the "the overlay window went away without us asking" detection in
 * [OverlayController.show] / [OverlayController.hide].
 *
 * Why Robolectric and not the mockk style of [OverlayControllerTest]: the whole behaviour under
 * test is a *timing* property of the real framework. `WindowManager.removeView()` does not deliver
 * `onViewDetachedFromWindow` synchronously — `ViewRootImpl.die(immediate = false)` posts `MSG_DIE`
 * and the detach is dispatched on a later main-looper message. Verified directly here by
 * [detach is delivered on a later main-looper message, not inside removeView]: any guard that is
 * raised and lowered synchronously around `removeView()` is therefore already lowered by the time
 * the callback runs. A mocked WindowManager never attaches a view at all and cannot express this.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class OverlayControllerLifecycleTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val accessibilityServiceHolder: AccessibilityServiceHolder = mockk(relaxed = true) {
        every { currentService() } returns null
    }
    private val controller = OverlayController(context, accessibilityServiceHolder)

    private fun attachedOverlayView(): OverlayView {
        val field = OverlayController::class.java.getDeclaredField("overlayView")
        field.isAccessible = true
        return requireNotNull(field.get(controller) as OverlayView?) { "no overlay attached" }
    }

    private fun idleMainLooper() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun `detach is delivered on a later main-looper message, not inside removeView`() {
        // Pins the platform behaviour every other test in this class depends on. If a future
        // framework/Robolectric change ever made removeView() dispatch detach synchronously, the
        // identity check in tryAddOverlayView() would still be correct, but the reasoning behind
        // it would no longer be load-bearing — and this test says so out loud.
        // The callback is irrelevant here — this test's subject is purely when the framework
        // delivers the detach, not what the controller does with it.
        assertThat(controller.show(onUnlockRequested = {})).isTrue()
        idleMainLooper()

        val view = attachedOverlayView()
        var detachedSynchronously = false
        view.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: android.view.View) {}
            override fun onViewDetachedFromWindow(v: android.view.View) { detachedSynchronously = true }
        })

        windowManager.removeView(view)
        assertThat(detachedSynchronously).isFalse() // nothing yet, still queued

        idleMainLooper()
        assertThat(detachedSynchronously).isTrue() // delivered a message later
    }

    @Test
    fun `an ordinary hide does not report the overlay as lost`() {
        var lost = 0
        controller.show(onUnlockRequested = { lost++ })
        idleMainLooper()

        controller.hide()
        idleMainLooper()

        assertThat(lost).isEqualTo(0)
    }

    @Test
    fun `a hide immediately followed by show does not report the overlay as lost`() {
        // The production path this guards: LockOverlayService's accessibility-connection collector
        // re-attaches the overlay with hide() + show() whenever Strong Lock connects or disconnects
        // mid-lock, and recreateOverlay() does the same when the debug flag changes. The *old*
        // window's detach lands after show() has already installed the new callback, so a guard
        // that can't tell "superseded by our own re-attach" from "the OS took our window away"
        // releases the lock the instant accessibility is toggled while locked.
        var lost = 0
        controller.show(onUnlockRequested = { lost++ })
        idleMainLooper()

        controller.hide()
        controller.show(onUnlockRequested = { lost++ })
        idleMainLooper()

        assertThat(lost).isEqualTo(0)
    }

    @Test
    fun `a window torn down behind our back still reports the overlay as lost`() {
        // The case the detach listener exists for: something other than hide() removed the window,
        // so nothing is protecting the screen any more and the lock must be released. Must keep
        // working after any fix to the superseded-window case above.
        var lost = 0
        controller.show(onUnlockRequested = { lost++ })
        idleMainLooper()

        windowManager.removeView(attachedOverlayView())
        idleMainLooper()

        assertThat(lost).isEqualTo(1)
    }
}
