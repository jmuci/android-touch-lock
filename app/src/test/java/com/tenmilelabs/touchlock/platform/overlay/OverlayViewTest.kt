package com.tenmilelabs.touchlock.platform.overlay

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.abs

/**
 * [OverlayView] extends `FrameLayout` and reads `ViewConfiguration.get(context).scaledTouchSlop`,
 * both of which need a real Android runtime to construct — unavailable in a pure JVM test (same
 * constraint documented in `OverlayControllerTest`, which for the same reason doesn't test
 * `show()`). This harness mirrors the exact touch-slop drag-vs-long-press disambiguation from
 * `onTouchEvent()`'s `ACTION_MOVE` branch (`OverlayView.kt:98-107`) against a test-controlled slop
 * value, so the decision logic is covered without constructing a real `View`/`MotionEvent`.
 */
class OverlayViewTest {

    private class Harness(private val touchSlopPx: Int) {
        private var downX = 0f
        private var downY = 0f
        private var hasDown = false

        var longPressCancelled = false
            private set

        /** Mirrors ACTION_DOWN: records the origin and resets cancellation for the new gesture. */
        fun down(x: Float, y: Float) {
            downX = x
            downY = y
            hasDown = true
            longPressCancelled = false
        }

        /** Mirrors ACTION_MOVE's touch-slop check that cancels the pending long-press runnable. */
        fun move(x: Float, y: Float) {
            if (!hasDown) return
            val dx = abs(x - downX)
            val dy = abs(y - downY)
            if (dx > touchSlopPx || dy > touchSlopPx) {
                longPressCancelled = true
            }
        }
    }

    @Test
    fun `movement within touch slop on both axes does not cancel the long-press`() {
        val harness = Harness(touchSlopPx = 8)
        harness.down(100f, 100f)

        harness.move(105f, 103f)

        assertThat(harness.longPressCancelled).isFalse()
    }

    @Test
    fun `movement past touch slop on the x axis alone cancels the long-press`() {
        val harness = Harness(touchSlopPx = 8)
        harness.down(100f, 100f)

        harness.move(110f, 100f)

        assertThat(harness.longPressCancelled).isTrue()
    }

    @Test
    fun `movement past touch slop on the y axis alone cancels the long-press`() {
        val harness = Harness(touchSlopPx = 8)
        harness.down(100f, 100f)

        harness.move(100f, 110f)

        assertThat(harness.longPressCancelled).isTrue()
    }

    @Test
    fun `movement exactly at the touch slop threshold does not cancel`() {
        val harness = Harness(touchSlopPx = 8)
        harness.down(100f, 100f)

        harness.move(108f, 100f) // dx == touchSlopPx, condition is strictly greater-than

        assertThat(harness.longPressCancelled).isFalse()
    }

    @Test
    fun `movement is a no-op before any ACTION_DOWN is observed`() {
        val harness = Harness(touchSlopPx = 8)

        harness.move(500f, 500f)

        assertThat(harness.longPressCancelled).isFalse()
    }

    @Test
    fun `a new ACTION_DOWN resets cancellation for the next gesture`() {
        val harness = Harness(touchSlopPx = 8)
        harness.down(100f, 100f)
        harness.move(200f, 200f)
        assertThat(harness.longPressCancelled).isTrue()

        harness.down(0f, 0f)

        assertThat(harness.longPressCancelled).isFalse()
    }
}
