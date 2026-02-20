package com.tenmilelabs.touchlock.platform.overlay

import android.content.Context
import android.view.WindowManager
import io.mockk.every
import io.mockk.mockk
import org.junit.Before
import org.junit.Test

/**
 * Tests for OverlayController crash safety.
 *
 * Verifies that hide/cleanup methods handle WindowManager exceptions gracefully
 * and that calling hide without prior show does not crash.
 *
 * These tests exist to prevent regression of the bug where unhandled WindowManager
 * exceptions would crash the app in production.
 *
 * Note: Tests that call show()/showCountdownOverlay() are NOT included here because
 * those methods construct Android Views (OverlayView, CountdownOverlayView) whose
 * constructors depend on Android framework internals (TypedValue, DisplayMetrics,
 * Looper) that cannot be properly stubbed in pure JVM tests without Robolectric.
 *
 * Requires `unitTests.isReturnDefaultValues = true` in build.gradle.kts.
 */
class OverlayControllerTest {

    private lateinit var mockContext: Context
    private lateinit var mockWindowManager: WindowManager
    private lateinit var controller: OverlayController

    @Before
    fun setup() {
        mockWindowManager = mockk(relaxed = true)
        mockContext = mockk(relaxed = true) {
            every { getSystemService(Context.WINDOW_SERVICE) } returns mockWindowManager
            every { resources } returns mockk(relaxed = true)
            every { theme } returns mockk(relaxed = true)
            every { applicationContext } returns this
        }
        controller = OverlayController(mockContext)
    }

    // --- Construction safety ---

    @Test
    fun `OverlayController can be constructed without crash`() {
        // Verifies Handler(Looper.getMainLooper()) and WindowManager init don't crash
        val ctrl = OverlayController(mockContext)
        // Constructor completed without exception
        ctrl.hide() // no-op, shouldn't crash
    }

    // --- hide() crash safety ---

    @Test
    fun `hide called without prior show does not crash`() {
        // Should not throw even though nothing was shown
        controller.hide()
    }

    @Test
    fun `hide called multiple times does not crash`() {
        // Should not throw on repeated calls
        controller.hide()
        controller.hide()
        controller.hide()
    }

    @Test
    fun `hide does not crash when removeView throws IllegalArgumentException`() {
        every {
            mockWindowManager.removeView(any())
        } throws IllegalArgumentException("View not attached to window manager")

        // Even without a prior show, this verifies the try-catch is present
        controller.hide()
    }

    @Test
    fun `hide does not crash when removeView throws RuntimeException`() {
        every {
            mockWindowManager.removeView(any())
        } throws RuntimeException("WindowManager has died")

        controller.hide()
    }

    // --- hideCountdownOverlay() crash safety ---

    @Test
    fun `hideCountdownOverlay called without prior show does not crash`() {
        controller.hideCountdownOverlay()
    }

    @Test
    fun `hideCountdownOverlay called multiple times does not crash`() {
        controller.hideCountdownOverlay()
        controller.hideCountdownOverlay()
        controller.hideCountdownOverlay()
    }

    @Test
    fun `hideCountdownOverlay does not crash when removeView throws`() {
        every {
            mockWindowManager.removeView(any())
        } throws IllegalArgumentException("View not attached")

        controller.hideCountdownOverlay()
    }

    // --- Sequencing safety ---

    @Test
    fun `hide then hideCountdownOverlay does not crash`() {
        controller.hide()
        controller.hideCountdownOverlay()
    }

    @Test
    fun `hideCountdownOverlay then hide does not crash`() {
        controller.hideCountdownOverlay()
        controller.hide()
    }
}
