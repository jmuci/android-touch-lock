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
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Robolectric-backed tests for fullScreenLayoutParams()'s status-bar-safe bounds computation.
 *
 * Why Robolectric here and not the plain-JVM mockk style used elsewhere in this package
 * (OverlayControllerTest): confirmed by direct probe that WindowManager.LayoutParams' constructor
 * doesn't preserve field values under this project's `isReturnDefaultValues = true` unit-test
 * setup — every field silently reads back as its Java default regardless of what's passed in, so
 * a plain-JVM test asserting on the returned LayoutParams' width/height/x/y can't actually fail.
 * Robolectric provides a real (shadowed) Android framework, so LayoutParams, WindowInsets, Insets,
 * and WindowMetrics all behave like they do on a real device.
 *
 * What these tests do NOT and cannot cover: any given OEM's own platform-level reservation for
 * TYPE_ACCESSIBILITY_OVERLAY windows (confirmed present on one physical Samsung device, confirmed
 * absent on a stock/AOSP emulator build — not something Robolectric's shadow layer knows about or
 * simulates). What they DO lock in: this code's own arithmetic — the shrink applies in every
 * rotation including natural/ROTATION_0 (an on-device ANR was traced to a prior version that
 * skipped it there, trusting a platform reservation that doesn't exist on all builds), reads the
 * correct edge from WindowInsets.Type.statusBars(), scopes to TYPE_ACCESSIBILITY_OVERLAY only, and
 * falls back correctly pre-API-30 — so a future change can't silently break any of that without a
 * red test.
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
    fun `at natural rotation, the accessibility overlay is still shrunk by the live status-bar inset`() {
        // Confirmed on-device (stock/AOSP emulator build): the platform applies no reservation of
        // its own here — FLAG_LAYOUT_NO_LIMITS overrides fitInsetsTypes — so a prior version that
        // skipped this shrink at ROTATION_0, trusting a platform reservation that doesn't exist on
        // all builds, left this touchable window covering the real status-bar pixels a system
        // edge-swipe gesture (opening the notification shade) needs, which triggered a severe,
        // reproducible, system-wide ANR. Always shrinking here is the accepted tradeoff: on an OEM
        // build that also reserves this space, this now double-shrinks into a cosmetic-only dead
        // zone near the top edge, which is strictly preferable to the ANR.
        val manager = windowManagerReporting(
            bounds = Rect(0, 0, 1080, 2316),
            statusBarInsets = Insets.of(0, 79, 0, 0),
            rotationValue = Surface.ROTATION_0
        )

        val params = controller.fullScreenLayoutParams(manager, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)

        assertThat(params.width).isEqualTo(1080)
        assertThat(params.height).isEqualTo(2237) // 2316 - 79
        assertThat(params.x).isEqualTo(0)
        assertThat(params.y).isEqualTo(79)
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

    // --- navigationBarLayoutParams(): the nav-bar-only window, never the full screen ---

    @Suppress("DEPRECATION") // WindowMetrics(Rect, WindowInsets): no non-deprecated equivalent for
    // directly constructing a WindowMetrics in a test.
    private fun windowManagerReportingNavBar(bounds: Rect, navBarInsets: Insets): WindowManager {
        val windowInsets = WindowInsets.Builder()
            .setInsets(WindowInsets.Type.navigationBars(), navBarInsets)
            .build()
        val metrics = WindowMetrics(bounds, windowInsets)
        return mockk(relaxed = true) {
            every { currentWindowMetrics } returns metrics
        }
    }

    @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
    @Test
    fun `nav-bar overlay covers only the bottom strip, not the full screen`() {
        val manager = windowManagerReportingNavBar(
            bounds = Rect(0, 0, 1080, 2316),
            navBarInsets = Insets.of(0, 0, 0, 63)
        )

        val params = controller.navigationBarLayoutParams(manager)

        assertThat(params).isNotNull()
        assertThat(params!!.width).isEqualTo(1080)
        assertThat(params.height).isEqualTo(63)
        assertThat(params.x).isEqualTo(0)
        assertThat(params.y).isEqualTo(2316 - 63)
        assertThat(params.type).isEqualTo(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
    }

    @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
    @Test
    fun `nav-bar overlay covers a side strip when the inset is reported on the left or right`() {
        val manager = windowManagerReportingNavBar(
            bounds = Rect(0, 0, 2316, 1080),
            navBarInsets = Insets.of(0, 0, 48, 0)
        )

        val params = controller.navigationBarLayoutParams(manager)

        assertThat(params).isNotNull()
        assertThat(params!!.width).isEqualTo(48)
        assertThat(params.height).isEqualTo(1080)
        assertThat(params.x).isEqualTo(2316 - 48)
        assertThat(params.y).isEqualTo(0)
    }

    @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
    @Test
    fun `nav-bar overlay is not added when there is no navigation-bar inset to cover`() {
        val manager = windowManagerReportingNavBar(
            bounds = Rect(0, 0, 1080, 2316),
            navBarInsets = Insets.NONE
        )

        assertThat(controller.navigationBarLayoutParams(manager)).isNull()
    }

    @Config(sdk = [Build.VERSION_CODES.Q])
    @Test
    fun `below API 30, the nav-bar overlay falls back to a fixed bottom offset`() {
        val expectedNavBarHeight = legacyNavigationBarHeightPxForTest()
        val manager = legacyWindowManagerReporting(width = 1080, height = 2316)

        val params = controller.navigationBarLayoutParams(manager)

        if (expectedNavBarHeight <= 0) {
            assertThat(params).isNull()
        } else {
            assertThat(params).isNotNull()
            assertThat(params!!.width).isEqualTo(1080)
            assertThat(params.height).isEqualTo(expectedNavBarHeight)
            assertThat(params.y).isEqualTo(2316 - expectedNavBarHeight)
        }
    }

    private fun legacyNavigationBarHeightPxForTest(): Int {
        val resourceId = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (resourceId > 0) context.resources.getDimensionPixelSize(resourceId) else 0
    }

    // --- relayoutForCurrentBounds(): a single config change must reposition BOTH windows ---
    //
    // show() attaches the main overlay and the nav-bar overlay as two separate windows (see
    // resolveTarget()'s doc comment for why they're split). A live window doesn't recompute its
    // own LayoutParams on rotation, so relayoutForCurrentBounds() is what's responsible for
    // pushing fresh bounds to both — added incrementally in two separate changes (the main
    // overlay's relayout came first; the nav-bar overlay's was added later, alongside it). Nothing
    // above exercises them together in one call, which is the actual on-device code path and the
    // one a future edit to this method could regress by touching only one of the two blocks.
    //
    // Bypasses show() entirely (it constructs real Views/addView through a full WindowManager,
    // which needs the exact same setup as OverlayControllerTest's documented View-construction
    // limitation) by injecting fake attached-view state directly via reflection, the same
    // approach LockOverlayServiceTest uses for its own private companion state.

    private fun setPrivateField(name: String, value: Any?) {
        val field = OverlayController::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(controller, value)
    }

    @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
    @Test
    fun `relayoutForCurrentBounds repositions both the main overlay and the nav-bar overlay together`() {
        val mainManager = windowManagerReporting(
            bounds = Rect(0, 0, 2316, 1080),
            statusBarInsets = Insets.NONE,
            rotationValue = Surface.ROTATION_90
        )
        val navManager = windowManagerReportingNavBar(
            bounds = Rect(0, 0, 2316, 1080),
            navBarInsets = Insets.of(0, 0, 48, 0)
        )
        val mainView = mockk<OverlayView>(relaxed = true)
        val navView = mockk<OverlayView>(relaxed = true)

        setPrivateField("overlayView", mainView)
        setPrivateField("overlayWindowManager", mainManager)
        setPrivateField("overlayWindowType", WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        setPrivateField("navBarOverlayView", navView)
        setPrivateField("navBarOverlayWindowManager", navManager)

        controller.relayoutForCurrentBounds()

        verify { mainManager.updateViewLayout(mainView, any()) }
        verify { navManager.updateViewLayout(navView, any()) }
    }

    @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
    @Test
    fun `relayoutForCurrentBounds does not touch the nav-bar manager when no nav-bar overlay is attached`() {
        // The common case: accessibility disconnected (or never connected), so only the main
        // overlay exists. Guards the null-safety of the nav-bar block specifically.
        val mainManager = windowManagerReporting(
            bounds = Rect(0, 0, 1080, 2316),
            statusBarInsets = Insets.NONE,
            rotationValue = Surface.ROTATION_0
        )
        val mainView = mockk<OverlayView>(relaxed = true)

        setPrivateField("overlayView", mainView)
        setPrivateField("overlayWindowManager", mainManager)
        setPrivateField("overlayWindowType", WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)

        controller.relayoutForCurrentBounds() // must not throw despite navBarOverlayView being null

        verify { mainManager.updateViewLayout(mainView, any()) }
    }

    @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
    @Test
    fun `relayoutForCurrentBounds does nothing when no overlay is attached at all`() {
        // hide() has already cleared every field, or show() was never called — relayout is
        // reachable here only via a stray config-change callback racing teardown.
        val navManager = windowManagerReportingNavBar(
            bounds = Rect(0, 0, 1080, 2316),
            navBarInsets = Insets.of(0, 0, 0, 63)
        )
        setPrivateField("navBarOverlayView", mockk<OverlayView>(relaxed = true))
        setPrivateField("navBarOverlayWindowManager", navManager)
        // overlayView left null — the early `val view = overlayView ?: return` must fire first,
        // before ever reaching the nav-bar block below it.

        controller.relayoutForCurrentBounds()

        verify(exactly = 0) { navManager.updateViewLayout(any(), any()) }
    }
}
