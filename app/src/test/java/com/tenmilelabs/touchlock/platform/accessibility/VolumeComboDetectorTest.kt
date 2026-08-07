package com.tenmilelabs.touchlock.platform.accessibility

import android.view.KeyEvent
import com.google.common.truth.Truth.assertThat
import com.tenmilelabs.touchlock.domain.usecase.fakes.FakeClock
import org.junit.Before
import org.junit.Test

class VolumeComboDetectorTest {

    private lateinit var clock: FakeClock
    private lateinit var detector: VolumeComboDetector

    @Before
    fun setup() {
        clock = FakeClock()
        clock.setTimeMillis(0L)
        detector = VolumeComboDetector(clock)
    }

    private fun press(keyCode: Int) = detector.onKeyEvent(keyCode, KeyEvent.ACTION_DOWN)
    private fun release(keyCode: Int) = detector.onKeyEvent(keyCode, KeyEvent.ACTION_UP)

    @Test
    fun `combo is not active when no keys pressed`() {
        assertThat(detector.isComboActive()).isFalse()
    }

    @Test
    fun `combo is not active with only volume up pressed`() {
        press(KeyEvent.KEYCODE_VOLUME_UP)
        assertThat(detector.isComboActive()).isFalse()
    }

    @Test
    fun `combo is not active with only volume down pressed`() {
        press(KeyEvent.KEYCODE_VOLUME_DOWN)
        assertThat(detector.isComboActive()).isFalse()
    }

    @Test
    fun `combo is active when both volume keys are pressed`() {
        press(KeyEvent.KEYCODE_VOLUME_UP)
        press(KeyEvent.KEYCODE_VOLUME_DOWN)
        assertThat(detector.isComboActive()).isTrue()
    }

    @Test
    fun `threshold not reached immediately when combo starts`() {
        press(KeyEvent.KEYCODE_VOLUME_UP)
        press(KeyEvent.KEYCODE_VOLUME_DOWN)
        assertThat(detector.isHoldThresholdReached()).isFalse()
    }

    @Test
    fun `threshold not reached before hold duration elapses`() {
        press(KeyEvent.KEYCODE_VOLUME_UP)
        press(KeyEvent.KEYCODE_VOLUME_DOWN)
        clock.advanceTimeBy(VolumeComboDetector.HOLD_DURATION_MILLIS - 1)
        assertThat(detector.isHoldThresholdReached()).isFalse()
    }

    @Test
    fun `threshold reached once hold duration elapses`() {
        press(KeyEvent.KEYCODE_VOLUME_UP)
        press(KeyEvent.KEYCODE_VOLUME_DOWN)
        clock.advanceTimeBy(VolumeComboDetector.HOLD_DURATION_MILLIS)
        assertThat(detector.isHoldThresholdReached()).isTrue()
    }

    @Test
    fun `releasing either key before threshold cancels the hold`() {
        press(KeyEvent.KEYCODE_VOLUME_UP)
        press(KeyEvent.KEYCODE_VOLUME_DOWN)
        clock.advanceTimeBy(VolumeComboDetector.HOLD_DURATION_MILLIS - 1)

        release(KeyEvent.KEYCODE_VOLUME_DOWN)

        assertThat(detector.isComboActive()).isFalse()
        assertThat(detector.isHoldThresholdReached()).isFalse()
    }

    @Test
    fun `re-pressing after a release restarts the hold timer`() {
        press(KeyEvent.KEYCODE_VOLUME_UP)
        press(KeyEvent.KEYCODE_VOLUME_DOWN)
        clock.advanceTimeBy(VolumeComboDetector.HOLD_DURATION_MILLIS - 1)
        release(KeyEvent.KEYCODE_VOLUME_DOWN)

        press(KeyEvent.KEYCODE_VOLUME_DOWN)
        clock.advanceTimeBy(VolumeComboDetector.HOLD_DURATION_MILLIS - 1)

        assertThat(detector.isHoldThresholdReached()).isFalse()

        clock.advanceTimeBy(1)
        assertThat(detector.isHoldThresholdReached()).isTrue()
    }

    @Test
    fun `unrelated key events do not affect combo state`() {
        press(KeyEvent.KEYCODE_VOLUME_UP)
        detector.onKeyEvent(KeyEvent.KEYCODE_BACK, KeyEvent.ACTION_DOWN)
        assertThat(detector.isComboActive()).isFalse()
    }
}
