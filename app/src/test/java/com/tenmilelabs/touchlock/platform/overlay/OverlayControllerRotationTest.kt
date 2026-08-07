package com.tenmilelabs.touchlock.platform.overlay

import android.content.Context
import android.graphics.Insets
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.view.Display
import android.view.Surface
import android.view.WindowInsets
import android.view.WindowManager
import android.view.WindowMetrics
import com.google.common.truth.Truth.assertThat
import com.tenmilelabs.touchlock.platform.accessibility.AccessibilityServiceHolder
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Robolectric-backed tests for fullScreenLayoutParams()'s rotation-aware, status-bar-safe bounds
 * computation.
 *
 * Why Robolectric here and not the plain-JVM mockk style used elsewhere in this package
 * (OverlayControllerTest): confirmed by direct probe that WindowManager.LayoutParams' constructor
 * doesn't preserve field values under this project's `isReturnDefaultValues = true` unit-test
 * setup — every field silently reads back as its Java default regardless of what's passed in, so
 * a plain-JVM test asserting on the returned LayoutParams' width/height/x/y can't actually fail.
 * Robolectric provides a real (shadowed) Android framework, so LayoutParams, WindowInsets, Insets,
 * and WindowMetrics all behave like they do on a real device.
 *
 * What these tests do NOT and cannot cover: the real device's undocumented platform-level
 * reservation for TYPE_ACCESSIBILITY_OVERLAY windows (confirmed only via on-device testing on a
 * physical Samsung device and an emulator — not something Robolectric's shadow layer knows about
 * or simulates), including the double-stacking interaction between that platform reservation and
 * this code's own inset that motivated the ROTATION_0 special case below. What they DO lock in:
 * this code's own arithmetic — the ROTATION_0 special case, reading the correct edge from
 * WindowInsets.Type.statusBars(), scoping to TYPE_ACCESSIBILITY_OVERLAY only, and the pre-API-30
 * fallback — so a future change can't silently break any of that without a red test.
 */
@RunWith(RobolectricTestRunner::class)
class OverlayControllerRotationTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val accessibilityServiceHolder: AccessibilityServiceHolder = mockk(relaxed = true) {
        every { currentService() } returns null
    }
    private val controller = OverlayController(context, accessibilityServiceHolder)

    @Suppress("DEPRECATION") // WindowMetrics(Rect, WindowInsets) and defaultDisplay: no non-deprecated
    // equivalent for directly constructing a WindowMetrics / stubbing rotation in a test.
    private fun windowManagerReporting(
        bounds: Rect,
        statusBarInsets: Insets,
        rotationValue: Int
    ): WindowManager {
        val windowInsets = WindowInsets.Builder()
            .setInsets(WindowInsets.Type.statusBars(), statusBarInsets)
            .build()
        val metrics = WindowMetrics(bounds, windowInsets)
        val display = mockk<Display>(relaxed = true) {
            every { rotation } returns rotationValue
        }
        return mockk(relaxed = true) {
            every { currentWindowMetrics } returns metrics
            every { defaultDisplay } returns display
        }
    }

    @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
    @Test
    fun `at natural rotation, the status-bar inset is not applied for the accessibility overlay`() {
        // Confirmed on-device: applying this at ROTATION_0 too, on top of the platform's own
        // reservation, double-stacks into a real dead zone (150px instead of 75px) — the platform
        // already handles this edge correctly at natural orientation, so this code must not.
        val manager = windowManagerReporting(
            bounds = Rect(0, 0, 1080, 2316),
            statusBarInsets = Insets.of(0, 79, 0, 0), // deliberately non-zero, to prove it's ignored
            rotationValue = Surface.ROTATION_0
        )

        val params = controller.fullScreenLayoutParams(manager, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)

        assertThat(params.width).isEqualTo(1080)
        assertThat(params.height).isEqualTo(2316)
        assertThat(params.x).isEqualTo(0)
        assertThat(params.y).isEqualTo(0)
    }

    @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
    @Test
    fun `away from natural rotation, the accessibility overlay is shrunk by the live status-bar inset`() {
        val manager = windowManagerReporting(
            bounds = Rect(0, 0, 2316, 1080),
            statusBarInsets = Insets.of(0, 79, 0, 0), // status bar reported on the top edge
            rotationValue = Surface.ROTATION_90
        )

        val params = controller.fullScreenLayoutParams(manager, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)

        assertThat(params.width).isEqualTo(2316)
        assertThat(params.height).isEqualTo(1001) // 1080 - 79
        assertThat(params.x).isEqualTo(0)
        assertThat(params.y).isEqualTo(79)
    }

    @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
    @Test
    fun `the status-bar inset only ever applies to the accessibility overlay, never the application overlay`() {
        // TYPE_APPLICATION_OVERLAY can never intercept status-bar input in the first place (see
        // resolveTarget()'s doc comment) — applying this inset there would be a pointless, purely
        // cosmetic shrink with no functional purpose, so the guard must exclude it.
        val manager = windowManagerReporting(
            bounds = Rect(0, 0, 2316, 1080),
            statusBarInsets = Insets.of(0, 79, 0, 0),
            rotationValue = Surface.ROTATION_90
        )

        val params = controller.fullScreenLayoutParams(manager, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)

        assertThat(params.width).isEqualTo(2316)
        assertThat(params.height).isEqualTo(1080)
        assertThat(params.y).isEqualTo(0)
    }

    @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
    @Test
    fun `an inset reported on a different edge shrinks the corresponding side, not always top`() {
        // Sanity check that the arithmetic reads all four edges, not just top — e.g. a
        // right-side inset (as seen on-device at ROTATION_270) must shrink width from the right,
        // not silently no-op because the code only ever looked at .top.
        val manager = windowManagerReporting(
            bounds = Rect(0, 0, 2316, 1080),
            statusBarInsets = Insets.of(0, 0, 79, 0),
            rotationValue = Surface.ROTATION_270
        )

        val params = controller.fullScreenLayoutParams(manager, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)

        assertThat(params.width).isEqualTo(2237) // 2316 - 79
        assertThat(params.height).isEqualTo(1080)
        assertThat(params.x).isEqualTo(0)
        assertThat(params.y).isEqualTo(0)
    }

    @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
    @Test
    fun `bounds are real display pixels, never the MATCH_PARENT sentinel`() {
        // The whole point of this fix: MATCH_PARENT triggered an implicit, non-rotation-aware
        // system reservation that this code deliberately sidesteps by requesting real bounds.
        val manager = windowManagerReporting(
            bounds = Rect(0, 0, 1080, 2316),
            statusBarInsets = Insets.NONE,
            rotationValue = Surface.ROTATION_0
        )

        val params = controller.fullScreenLayoutParams(manager, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)

        assertThat(params.width).isNotEqualTo(WindowManager.LayoutParams.MATCH_PARENT)
        assertThat(params.height).isNotEqualTo(WindowManager.LayoutParams.MATCH_PARENT)
    }

    // --- Pre-API-30 fallback: no live per-edge inset query available below API 30 ---

    @Config(sdk = [Build.VERSION_CODES.Q])
    @Test
    fun `below API 30, the accessibility overlay falls back to a fixed top offset`() {
        val expectedStatusBarHeight = legacyStatusBarHeightPxForTest()
        val manager = legacyWindowManagerReporting(width = 1080, height = 2316)

        val params = controller.fullScreenLayoutParams(manager, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)

        assertThat(params.width).isEqualTo(1080)
        assertThat(params.height).isEqualTo(2316 - expectedStatusBarHeight)
        assertThat(params.x).isEqualTo(0)
        assertThat(params.y).isEqualTo(expectedStatusBarHeight)
    }

    @Config(sdk = [Build.VERSION_CODES.Q])
    @Test
    fun `below API 30, the application overlay is never offset`() {
        val manager = legacyWindowManagerReporting(width = 1080, height = 2316)

        val params = controller.fullScreenLayoutParams(manager, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)

        assertThat(params.width).isEqualTo(1080)
        assertThat(params.height).isEqualTo(2316)
        assertThat(params.y).isEqualTo(0)
    }

    @Suppress("DEPRECATION") // getRealSize/defaultDisplay: mirrors the pre-API-30 production fallback.
    private fun legacyWindowManagerReporting(width: Int, height: Int): WindowManager {
        val display = mockk<Display>(relaxed = true) {
            every { getRealSize(any()) } answers {
                firstArg<Point>().apply { x = width; y = height }
            }
        }
        return mockk(relaxed = true) {
            every { defaultDisplay } returns display
        }
    }

    private fun legacyStatusBarHeightPxForTest(): Int {
        val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) context.resources.getDimensionPixelSize(resourceId) else 0
    }
}
