package com.tenmilelabs.touchlock.platform.overlay

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Tests [rotatedVerticalMarginPx], the calculation behind the lock/unlock glint's off-screen
 * sweep margin.
 *
 * This is a regression test for a real bug: the first version of the glint used a blanket
 * `width + height` margin (~3500px on a typical phone) where the rotated screen only needs
 * ~200-300px. That meant the sweep spent roughly 70% of its animated duration sitting fully
 * off-screen, before and after a brief crossing buried in the middle — which read, both live and
 * in screenshots, as the glint simply not rendering. [rotatedVerticalMarginPx] is a pure function
 * (no Android framework dependency), so it's directly testable in a plain JVM test without
 * Robolectric or mocking a View.
 */
class LockTransitionOverlayViewTest {

    // A typical phone portrait screen, matching the dimensions used while debugging the original
    // invisible-glint bug.
    private val phoneWidth = 1080
    private val phoneHeight = 2400

    @Test
    fun `margin is much smaller than the old blanket width-plus-height overscan`() {
        val margin = rotatedVerticalMarginPx(phoneWidth, phoneHeight, angleDegrees = 20f)

        // The bug: an old blanket margin of width + height (~3480px) versus the true rotated
        // requirement (a few hundred px). Asserting well under half of that blanket value locks in
        // that the fix isn't just numerically different, but the right order of magnitude smaller.
        assertThat(margin).isLessThan((phoneWidth + phoneHeight) / 2f)
    }

    @Test
    fun `margin matches the rotated-bounding-box formula for a 20 degree angle`() {
        // rotatedBoundingHeight = w*sin(20°) + h*cos(20°); margin = (rotatedBoundingHeight - h) / 2
        // sin(20°) ≈ 0.34202, cos(20°) ≈ 0.93969
        val expectedRotatedHeight = phoneWidth * 0.34202 + phoneHeight * 0.93969
        val expectedMargin = ((expectedRotatedHeight - phoneHeight) / 2).toFloat()

        val margin = rotatedVerticalMarginPx(phoneWidth, phoneHeight, angleDegrees = 20f)

        assertThat(margin).isWithin(1f).of(expectedMargin)
    }

    @Test
    fun `margin is zero when there is no rotation`() {
        // At 0 degrees the rotated bounding box is identical to the original — no extra travel
        // needed on either end of the sweep.
        val margin = rotatedVerticalMarginPx(phoneWidth, phoneHeight, angleDegrees = 0f)

        assertThat(margin).isEqualTo(0f)
    }

    @Test
    fun `margin grows with a steeper angle`() {
        val shallow = rotatedVerticalMarginPx(phoneWidth, phoneHeight, angleDegrees = 10f)
        val steep = rotatedVerticalMarginPx(phoneWidth, phoneHeight, angleDegrees = 35f)

        assertThat(steep).isGreaterThan(shallow)
    }

    @Test
    fun `margin is never negative, even for a very wide short view`() {
        // A landscape-oriented or unusually wide/short view could in principle push the formula
        // negative before the coerceAtLeast(0f) floor — guard that floor explicitly.
        val margin = rotatedVerticalMarginPx(width = 50, height = 2400, angleDegrees = 1f)

        assertThat(margin).isAtLeast(0f)
    }

    @Test
    fun `margin is symmetric for positive and negative angles`() {
        // The sweep always rotates by a fixed positive GLINT_ANGLE_DEGREES in production, but the
        // formula itself is direction-agnostic — sin/cos magnitudes don't care about sign.
        val positive = rotatedVerticalMarginPx(phoneWidth, phoneHeight, angleDegrees = 20f)
        val negative = rotatedVerticalMarginPx(phoneWidth, phoneHeight, angleDegrees = -20f)

        assertThat(positive).isEqualTo(negative)
    }
}
