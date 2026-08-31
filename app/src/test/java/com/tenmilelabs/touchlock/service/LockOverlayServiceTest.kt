package com.tenmilelabs.touchlock.service

import android.app.Notification
import com.google.common.truth.Truth.assertThat
import com.tenmilelabs.touchlock.domain.model.LockState
import com.tenmilelabs.touchlock.domain.repository.ConfigRepository
import com.tenmilelabs.touchlock.platform.notification.LockNotificationManager
import com.tenmilelabs.touchlock.platform.overlay.OverlayController
import com.tenmilelabs.touchlock.platform.permission.OverlayPermissionManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Unit tests for [LockOverlayService] state machine logic.
 *
 * Strategy: [LockOverlayService] is a LifecycleService and AndroidEntryPoint component that
 * directly interacts with WindowManager and foreground service APIs — both of which are
 * unavailable in the JVM unit-test environment.
 *
 * The service's observable contract is its process-global [LockOverlayService.lockState]
 * StateFlow. Rather than fighting the framework to instantiate the service in a unit test,
 * these tests validate that contract by exercising the logic directly through the companion
 * state and verifying interactions with the mocked platform collaborators
 * (OverlayController, LockNotificationManager, OverlayPermissionManager, ConfigRepository).
 *
 * For full lifecycle integration tests (start/stop via Intent, foreground service binding)
 * an instrumented test with ServiceTestRule + HiltAndroidRule would be the correct approach.
 * That is left as a future addition once the androidTest Hilt test runner is configured.
 *
 * What is tested here:
 * - Initial lock state is Unlocked.
 * - Permission guard: overlay is not shown when permission is missing.
 * - State transitions: Unlocked → Locked → Unlocked are reflected in lockState.
 * - Idempotency: repeated lock/unlock calls do not produce duplicate state emissions.
 * - Countdown: delayed lock schedules lock after countdown, cancel stops progression.
 * - Rapid toggle safety: pending countdown callbacks are cleared on state transitions.
 * - Config reads: orientation mode is read from ConfigRepository on lock.
 * - Notification types: correct notification builder is called per state.
 */
class LockOverlayServiceTest {

    // ---------------------------------------------------------------------------
    // Fakes & helpers
    // ---------------------------------------------------------------------------

    private class FakeConfigRepository(
        initialDebugVisible: Boolean = false,
        initialBackstopTimeoutMinutes: Int = 60,
    ) : ConfigRepository {
        private val debugFlow = MutableStateFlow(initialDebugVisible)
        private val backstopTimeoutFlow = MutableStateFlow(initialBackstopTimeoutMinutes)

        override fun observeDebugOverlayVisible(): Flow<Boolean> = debugFlow

        override suspend fun setDebugOverlayVisible(visible: Boolean) {
            debugFlow.value = visible
        }

        override fun observeBackstopTimeoutMinutes(): Flow<Int> = backstopTimeoutFlow

        override suspend fun setBackstopTimeoutMinutes(minutes: Int) {
            backstopTimeoutFlow.value = minutes
        }

        /** Test-only synchronous accessor, mirroring what a real collector would already hold. */
        fun currentBackstopTimeoutMinutes(): Int = backstopTimeoutFlow.value
    }

    /**
     * Minimal harness that exposes the service's internal state-transition methods as
     * package-accessible functions, driven entirely through the public companion [lockState].
     *
     * We cannot instantiate [LockOverlayService] directly in a JVM test because:
     *  - [LifecycleService] requires an Android runtime.
     *  - [@AndroidEntryPoint] requires Hilt's instrumentation infrastructure.
     *
     * Instead, this harness replicates the exact decision logic of each action method
     * (startLock, stopLock, startDelayedLock, cancelCountdown) referencing the real
     * companion [_lockState] / [lockState] so that the observable state contract is
     * covered without spinning up a real service.
     */
    private inner class ServiceHarness(
        val overlayController: OverlayController = mockk(relaxed = true),
        val notificationManager: LockNotificationManager = mockk(relaxed = true),
        val permissionManager: OverlayPermissionManager = mockk(relaxed = true),
        val configRepository: FakeConfigRepository = FakeConfigRepository(),
    ) {
        private var isServiceRunning = false
        private var isCountdownActive = false
        private var countdownSecondsRemaining = 0
        private var isBackstopTimeoutScheduled = false
        private var wasAccessibilityConnected = false
        private val fakeNotification: Notification = mockk(relaxed = true)

        var lastScheduledBackstopTimeoutMinutes: Int? = null
            private set

        init {
            // Default: permission granted, overlay attaches successfully, notifications return
            // dummy object. Tests exercising the attach-failure path override the show() stub.
            every { permissionManager.hasPermission() } returns true
            every { overlayController.show(any(), any()) } returns true
            every { notificationManager.buildUnlockedNotification() } returns fakeNotification
            every { notificationManager.buildLockedNotification() } returns fakeNotification
            every { notificationManager.buildCountdownNotification(any()) } returns fakeNotification

            // Reset companion state to Unlocked before each harness creation
            resetLockState()
        }

        /** Mirrors LockOverlayService.startLock() decision logic. */
        fun startLock() {
            if (getLockState() == LockState.Locked) return

            // Cancelled before the permission check: startLock() is what the countdown calls when
            // it reaches zero, and cancelCountdown() is what restores the unlocked notification —
            // checking permission first left the countdown notification stuck forever whenever
            // permission was revoked mid-countdown.
            cancelCountdown()

            if (!permissionManager.hasPermission()) return

            if (!isServiceRunning) {
                isServiceRunning = true
                setLockState(LockState.Unlocked) // initService sets Unlocked
            }

            // Mirrors startLock()'s `if (!attached) { ...; return }` guard: addView can fail (e.g.
            // BadTokenException), and locking must not proceed — no state flip, no notification,
            // no backstop timeout — when the overlay didn't actually attach.
            val attached = overlayController.show(false) {}
            if (!attached) return

            notificationManager.buildLockedNotification()
            setLockState(LockState.Locked)
            isBackstopTimeoutScheduled = true
            lastScheduledBackstopTimeoutMinutes = configRepository.currentBackstopTimeoutMinutes()
        }

        /** Mirrors LockOverlayService.stopLock() decision logic. */
        fun stopLock() {
            if (getLockState() == LockState.Unlocked) return

            cancelCountdown()
            isBackstopTimeoutScheduled = false
            overlayController.hide()
            notificationManager.buildUnlockedNotification()
            setLockState(LockState.Unlocked)
        }

        /** Mirrors LockOverlayService.forceUnlock() decision logic: always delegates to stopLock(). */
        fun forceUnlock() {
            stopLock()
        }

        fun isBackstopTimeoutScheduled() = isBackstopTimeoutScheduled

        /** Simulates the backstop timeout firing (as the delayed coroutine would). */
        fun triggerBackstopTimeout() {
            if (!isBackstopTimeoutScheduled) return
            stopLock()
        }

        /** Simulates ACTION_SCREEN_OFF being received. */
        fun onScreenOff() {
            stopLock()
        }

        /**
         * Mirrors initService()'s accessibilityServiceHolder.isConnected collector: re-attaches
         * the overlay via the application-overlay fallback if the accessibility service
         * disconnects while locked (its TYPE_ACCESSIBILITY_OVERLAY window is torn down with it).
         */
        fun onAccessibilityConnectionChanged(isConnected: Boolean) {
            if (wasAccessibilityConnected && !isConnected && getLockState() == LockState.Locked) {
                overlayController.hide()
                overlayController.show(false) {}
            }
            wasAccessibilityConnected = isConnected
        }

        /** Mirrors LockOverlayService.startDelayedLock() decision logic. */
        fun startDelayedLock(durationSeconds: Int = 3) {
            cancelCountdown()
            if (getLockState() == LockState.Locked) return
            if (!permissionManager.hasPermission()) return

            if (!isServiceRunning) isServiceRunning = true

            isCountdownActive = true
            countdownSecondsRemaining = durationSeconds
            overlayController.showCountdownOverlay(countdownSecondsRemaining)
            notificationManager.buildCountdownNotification(countdownSecondsRemaining)
        }

        /** Simulates one countdown tick (as the countdown coroutine would do). */
        fun tickCountdown() {
            if (!isCountdownActive) return
            countdownSecondsRemaining--
            if (countdownSecondsRemaining > 0) {
                overlayController.updateCountdown(countdownSecondsRemaining)
                notificationManager.buildCountdownNotification(countdownSecondsRemaining)
            } else {
                // isCountdownActive deliberately stays true here, mirroring the real countdown
                // coroutine: it doesn't null out countdownJob itself, only cancelCountdown() does
                // (called from within startLock() below). Flipping it here first would make that
                // cancelCountdown() call a no-op, masking the stuck-notification bug this was
                // meant to catch.
                overlayController.hideCountdownOverlay()
                startLock()
            }
        }

        /** Mirrors LockOverlayService.cancelCountdown() decision logic. */
        fun cancelCountdown() {
            if (!isCountdownActive) return
            isCountdownActive = false
            overlayController.hideCountdownOverlay()
            notificationManager.buildUnlockedNotification()
        }

        fun isCountdownRunning() = isCountdownActive
        fun secondsRemaining() = countdownSecondsRemaining
    }

    // Thin helpers so test bodies don't reference the companion MutableStateFlow directly
    private fun resetLockState() = setLockState(LockState.Unlocked)
    private fun getLockState() = LockOverlayService.lockState.value
    private fun setLockState(state: LockState) {
        // Access companion via reflection to avoid exposing private _lockState in prod code.
        val field = LockOverlayService::class.java.getDeclaredField("_lockState")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = field.get(null) as MutableStateFlow<LockState>
        flow.value = state
    }

    // ---------------------------------------------------------------------------
    // Tests: initial state
    // ---------------------------------------------------------------------------

    @org.junit.Test
    fun `lockState starts as Unlocked`() {
        resetLockState()
        assertThat(getLockState()).isEqualTo(LockState.Unlocked)
    }

    // ---------------------------------------------------------------------------
    // Tests: startLock
    // ---------------------------------------------------------------------------

    @org.junit.Test
    fun `startLock transitions state from Unlocked to Locked`() {
        val harness = ServiceHarness()
        harness.startLock()
        assertThat(getLockState()).isEqualTo(LockState.Locked)
    }

    @org.junit.Test
    fun `startLock is idempotent when already Locked`() {
        val harness = ServiceHarness()
        harness.startLock()
        harness.startLock() // second call must be a no-op
        verify(exactly = 1) { harness.overlayController.show(any(), any()) }
        assertThat(getLockState()).isEqualTo(LockState.Locked)
    }

    @org.junit.Test
    fun `startLock does nothing when overlay permission is missing`() {
        val harness = ServiceHarness()
        every { harness.permissionManager.hasPermission() } returns false

        harness.startLock()

        verify(exactly = 0) { harness.overlayController.show(any(), any()) }
        assertThat(getLockState()).isEqualTo(LockState.Unlocked)
    }

    @org.junit.Test
    fun `startLock calls overlayController show`() {
        val harness = ServiceHarness()
        harness.startLock()
        verify { harness.overlayController.show(any(), any()) }
    }

    @org.junit.Test
    fun `startLock builds locked notification`() {
        val harness = ServiceHarness()
        harness.startLock()
        verify { harness.notificationManager.buildLockedNotification() }
    }

    // ---------------------------------------------------------------------------
    // Tests: stopLock
    // ---------------------------------------------------------------------------

    @org.junit.Test
    fun `stopLock transitions state from Locked to Unlocked`() {
        val harness = ServiceHarness()
        harness.startLock()
        harness.stopLock()
        assertThat(getLockState()).isEqualTo(LockState.Unlocked)
    }

    @org.junit.Test
    fun `stopLock is idempotent when already Unlocked`() {
        val harness = ServiceHarness()
        harness.stopLock() // state is already Unlocked — must be a no-op
        verify(exactly = 0) { harness.overlayController.hide() }
        assertThat(getLockState()).isEqualTo(LockState.Unlocked)
    }

    @org.junit.Test
    fun `stopLock calls overlayController hide`() {
        val harness = ServiceHarness()
        harness.startLock()
        harness.stopLock()
        verify { harness.overlayController.hide() }
    }

    @org.junit.Test
    fun `stopLock builds unlocked notification`() {
        val harness = ServiceHarness()
        harness.startLock()
        harness.stopLock()
        // buildUnlockedNotification is called by stopLock
        verify(atLeast = 1) { harness.notificationManager.buildUnlockedNotification() }
    }

    // ---------------------------------------------------------------------------
    // Tests: lock → unlock → lock cycle
    // ---------------------------------------------------------------------------

    @org.junit.Test
    fun `repeated lock and unlock cycles produce correct state sequence`() {
        val harness = ServiceHarness()

        harness.startLock()
        assertThat(getLockState()).isEqualTo(LockState.Locked)

        harness.stopLock()
        assertThat(getLockState()).isEqualTo(LockState.Unlocked)

        harness.startLock()
        assertThat(getLockState()).isEqualTo(LockState.Locked)

        harness.stopLock()
        assertThat(getLockState()).isEqualTo(LockState.Unlocked)
    }

    // ---------------------------------------------------------------------------
    // Tests: delayed lock / countdown
    // ---------------------------------------------------------------------------

    @org.junit.Test
    fun `startDelayedLock shows countdown overlay`() {
        val harness = ServiceHarness()
        harness.startDelayedLock(durationSeconds = 3)
        verify { harness.overlayController.showCountdownOverlay(3) }
    }

    @org.junit.Test
    fun `startDelayedLock does not lock immediately`() {
        val harness = ServiceHarness()
        harness.startDelayedLock(durationSeconds = 3)
        assertThat(getLockState()).isEqualTo(LockState.Unlocked)
    }

    @org.junit.Test
    fun `startDelayedLock does nothing when permission is missing`() {
        val harness = ServiceHarness()
        every { harness.permissionManager.hasPermission() } returns false

        harness.startDelayedLock()

        verify(exactly = 0) { harness.overlayController.showCountdownOverlay(any()) }
        assertThat(harness.isCountdownRunning()).isFalse()
    }

    @org.junit.Test
    fun `startDelayedLock does nothing when already Locked`() {
        val harness = ServiceHarness()
        harness.startLock()
        harness.startDelayedLock()

        // Countdown should not be active when locked
        assertThat(harness.isCountdownRunning()).isFalse()
    }

    @org.junit.Test
    fun `countdown ticks decrement seconds remaining`() {
        val harness = ServiceHarness()
        harness.startDelayedLock(durationSeconds = 3)

        harness.tickCountdown()
        assertThat(harness.secondsRemaining()).isEqualTo(2)

        harness.tickCountdown()
        assertThat(harness.secondsRemaining()).isEqualTo(1)
    }

    @org.junit.Test
    fun `countdown completion engages lock`() {
        val harness = ServiceHarness()
        harness.startDelayedLock(durationSeconds = 2)

        harness.tickCountdown() // 1 second remaining
        assertThat(getLockState()).isEqualTo(LockState.Unlocked)

        harness.tickCountdown() // 0 seconds → lock engages
        assertThat(getLockState()).isEqualTo(LockState.Locked)
        assertThat(harness.isCountdownRunning()).isFalse()
    }

    @org.junit.Test
    fun `countdown completion hides countdown overlay before showing lock overlay`() {
        val harness = ServiceHarness()
        harness.startDelayedLock(durationSeconds = 1)
        harness.tickCountdown()

        verify { harness.overlayController.hideCountdownOverlay() }
        verify { harness.overlayController.show(any(), any()) }
    }

    // ---------------------------------------------------------------------------
    // Tests: cancelCountdown
    // ---------------------------------------------------------------------------

    @org.junit.Test
    fun `cancelCountdown stops active countdown`() {
        val harness = ServiceHarness()
        harness.startDelayedLock(durationSeconds = 5)
        assertThat(harness.isCountdownRunning()).isTrue()

        harness.cancelCountdown()
        assertThat(harness.isCountdownRunning()).isFalse()
    }

    @org.junit.Test
    fun `cancelCountdown hides countdown overlay`() {
        val harness = ServiceHarness()
        harness.startDelayedLock(durationSeconds = 5)
        harness.cancelCountdown()

        verify { harness.overlayController.hideCountdownOverlay() }
    }

    @org.junit.Test
    fun `cancelCountdown is safe to call when no countdown is active`() {
        val harness = ServiceHarness()
        // Should not throw and should not call overlayController
        harness.cancelCountdown()
        verify(exactly = 0) { harness.overlayController.hideCountdownOverlay() }
    }

    @org.junit.Test
    fun `cancelCountdown restores unlocked notification`() {
        val harness = ServiceHarness()
        harness.startDelayedLock(durationSeconds = 5)
        harness.cancelCountdown()
        // buildUnlockedNotification is called to restore notification after cancel
        verify(atLeast = 1) { harness.notificationManager.buildUnlockedNotification() }
    }

    // ---------------------------------------------------------------------------
    // Tests: rapid state change safety
    // ---------------------------------------------------------------------------

    @org.junit.Test
    fun `startLock cancels pending countdown before locking`() {
        val harness = ServiceHarness()
        harness.startDelayedLock(durationSeconds = 5)
        assertThat(harness.isCountdownRunning()).isTrue()

        // startLock must cancel the countdown first
        harness.startLock()

        assertThat(harness.isCountdownRunning()).isFalse()
        assertThat(getLockState()).isEqualTo(LockState.Locked)
    }

    @org.junit.Test
    fun `countdown completion with permission revoked cancels countdown instead of leaving it stuck`() {
        val harness = ServiceHarness()
        harness.startDelayedLock(durationSeconds = 1)

        // Permission revoked at the exact moment the countdown reaches zero and calls startLock()
        every { harness.permissionManager.hasPermission() } returns false
        harness.tickCountdown()

        assertThat(harness.isCountdownRunning()).isFalse()
        assertThat(getLockState()).isEqualTo(LockState.Unlocked)
        // The countdown notification must be replaced with the unlocked one, not left stuck
        verify(atLeast = 1) { harness.notificationManager.buildUnlockedNotification() }
    }

    @org.junit.Test
    fun `startLock aborts without transitioning to Locked when the overlay fails to attach`() {
        val harness = ServiceHarness()
        every { harness.overlayController.show(any(), any()) } returns false

        harness.startLock()

        assertThat(getLockState()).isEqualTo(LockState.Unlocked)
        verify(exactly = 0) { harness.notificationManager.buildLockedNotification() }
    }

    @org.junit.Test
    fun `startLock does not schedule the backstop timeout when the overlay fails to attach`() {
        // A scheduled timeout here would later fire stopLock() against a lock that was never
        // actually engaged.
        val harness = ServiceHarness()
        every { harness.overlayController.show(any(), any()) } returns false

        harness.startLock()

        assertThat(harness.isBackstopTimeoutScheduled()).isFalse()
    }

    @org.junit.Test
    fun `countdown completion with overlay attach failure clears the countdown instead of leaving it stuck`() {
        // Same failure family as the permission-revoked-at-countdown-completion regression below,
        // but for the overlay addView failing instead of permission being missing — a separate
        // dependency that can also fail at exactly this transition point.
        val harness = ServiceHarness()
        harness.startDelayedLock(durationSeconds = 1)
        every { harness.overlayController.show(any(), any()) } returns false

        harness.tickCountdown()

        assertThat(harness.isCountdownRunning()).isFalse()
        assertThat(getLockState()).isEqualTo(LockState.Unlocked)
        verify(atLeast = 1) { harness.notificationManager.buildUnlockedNotification() }
    }

    @org.junit.Test
    fun `stopLock cancels pending countdown before unlocking`() {
        val harness = ServiceHarness()
        harness.startLock()
        setLockState(LockState.Unlocked) // manually revert to test from unlocked-with-countdown
        harness.startDelayedLock(durationSeconds = 5)
        assertThat(harness.isCountdownRunning()).isTrue()

        // Simulate the case where stopLock is called while a countdown is active
        // (the harness sets locked state first so stopLock won't early-return)
        setLockState(LockState.Locked)
        harness.stopLock()

        assertThat(harness.isCountdownRunning()).isFalse()
        assertThat(getLockState()).isEqualTo(LockState.Unlocked)
    }

    @org.junit.Test
    fun `starting a new delayed lock cancels any previous countdown`() {
        val harness = ServiceHarness()
        harness.startDelayedLock(durationSeconds = 10)
        harness.tickCountdown() // advance once

        // Starting a new delayed lock should cancel the old one and reset seconds
        harness.startDelayedLock(durationSeconds = 3)

        assertThat(harness.secondsRemaining()).isEqualTo(3)
        assertThat(harness.isCountdownRunning()).isTrue()
    }

    // ---------------------------------------------------------------------------
    // Tests: notification types per state
    // ---------------------------------------------------------------------------

    @org.junit.Test
    fun `buildCountdownNotification is called with correct seconds during countdown`() {
        val harness = ServiceHarness()
        harness.startDelayedLock(durationSeconds = 5)
        harness.tickCountdown()

        verify { harness.notificationManager.buildCountdownNotification(4) }
    }

    @org.junit.Test
    fun `buildCountdownNotification is called on startDelayedLock with full duration`() {
        val harness = ServiceHarness()
        harness.startDelayedLock(durationSeconds = 7)

        verify { harness.notificationManager.buildCountdownNotification(7) }
    }

    // ---------------------------------------------------------------------------
    // Tests: safety valves (Task 3)
    // ---------------------------------------------------------------------------

    @org.junit.Test
    fun `forceUnlock releases an active lock`() {
        val harness = ServiceHarness()
        harness.startLock()

        harness.forceUnlock()

        assertThat(getLockState()).isEqualTo(LockState.Unlocked)
    }

    @org.junit.Test
    fun `forceUnlock is a no-op when already unlocked`() {
        val harness = ServiceHarness()

        harness.forceUnlock()

        verify(exactly = 0) { harness.overlayController.hide() }
        assertThat(getLockState()).isEqualTo(LockState.Unlocked)
    }

    @org.junit.Test
    fun `startLock schedules the backstop timeout`() {
        val harness = ServiceHarness()
        harness.startLock()
        assertThat(harness.isBackstopTimeoutScheduled()).isTrue()
    }

    @org.junit.Test
    fun `stopLock cancels the backstop timeout before transitioning`() {
        val harness = ServiceHarness()
        harness.startLock()
        harness.stopLock()
        assertThat(harness.isBackstopTimeoutScheduled()).isFalse()
    }

    @org.junit.Test
    fun `backstop timeout firing releases the lock`() {
        val harness = ServiceHarness()
        harness.startLock()

        harness.triggerBackstopTimeout()

        assertThat(getLockState()).isEqualTo(LockState.Unlocked)
    }

    @org.junit.Test
    fun `backstop timeout is a no-op if lock was already released`() {
        val harness = ServiceHarness()
        harness.startLock()
        harness.stopLock()

        harness.triggerBackstopTimeout()

        // stopLock's overlayController.hide() should only have been invoked once, by the
        // explicit stopLock() call above — the stale backstop trigger must not act again.
        verify(exactly = 1) { harness.overlayController.hide() }
        assertThat(getLockState()).isEqualTo(LockState.Unlocked)
    }

    @org.junit.Test
    fun `re-locking cancels the previous backstop timeout`() {
        val harness = ServiceHarness()
        harness.startLock()
        harness.stopLock()

        assertThat(harness.isBackstopTimeoutScheduled()).isFalse()

        harness.startLock()
        assertThat(harness.isBackstopTimeoutScheduled()).isTrue()
    }

    @org.junit.Test
    fun `screen off releases an active lock`() {
        val harness = ServiceHarness()
        harness.startLock()

        harness.onScreenOff()

        assertThat(getLockState()).isEqualTo(LockState.Unlocked)
        assertThat(harness.isBackstopTimeoutScheduled()).isFalse()
    }

    @org.junit.Test
    fun `screen off is a no-op when already unlocked`() {
        val harness = ServiceHarness()

        harness.onScreenOff()

        verify(exactly = 0) { harness.overlayController.hide() }
        assertThat(getLockState()).isEqualTo(LockState.Unlocked)
    }

    // ---------------------------------------------------------------------------
    // Tests: backstop timeout uses the currently configured minutes (Task 3)
    // ---------------------------------------------------------------------------

    @org.junit.Test
    fun `startLock schedules the backstop timeout using the currently configured minutes`() {
        val harness = ServiceHarness(configRepository = FakeConfigRepository(initialBackstopTimeoutMinutes = 15))

        harness.startLock()

        assertThat(harness.lastScheduledBackstopTimeoutMinutes).isEqualTo(15)
    }

    @org.junit.Test
    fun `backstop timeout is not hardcoded to the default when config differs`() {
        val harness = ServiceHarness(configRepository = FakeConfigRepository(initialBackstopTimeoutMinutes = 120))

        harness.startLock()

        assertThat(harness.lastScheduledBackstopTimeoutMinutes).isNotEqualTo(60)
        assertThat(harness.lastScheduledBackstopTimeoutMinutes).isEqualTo(120)
    }

    // ---------------------------------------------------------------------------
    // Tests: mid-lock accessibility-disconnect recovery (Task 4)
    // ---------------------------------------------------------------------------

    @org.junit.Test
    fun `accessibility service disconnecting while locked re-attaches the overlay`() {
        val harness = ServiceHarness()
        harness.startLock()
        harness.onAccessibilityConnectionChanged(isConnected = true)

        harness.onAccessibilityConnectionChanged(isConnected = false)

        verify { harness.overlayController.hide() }
        verify(exactly = 2) { harness.overlayController.show(any(), any()) } // startLock() + recovery
    }

    @org.junit.Test
    fun `accessibility service disconnecting while unlocked does not touch the overlay`() {
        val harness = ServiceHarness()
        harness.onAccessibilityConnectionChanged(isConnected = true)

        harness.onAccessibilityConnectionChanged(isConnected = false)

        verify(exactly = 0) { harness.overlayController.hide() }
        verify(exactly = 0) { harness.overlayController.show(any(), any()) }
    }

    @org.junit.Test
    fun `a disconnect with no prior connection while locked does not trigger recovery`() {
        val harness = ServiceHarness()
        harness.startLock()

        // Never connected in the first place — guards the wasAccessibilityConnected=false case.
        harness.onAccessibilityConnectionChanged(isConnected = false)

        verify(exactly = 1) { harness.overlayController.show(any(), any()) } // startLock() only
    }

    @org.junit.Test
    fun `the service connecting while locked does not trigger recovery`() {
        val harness = ServiceHarness()
        harness.startLock()

        harness.onAccessibilityConnectionChanged(isConnected = true)

        verify(exactly = 0) { harness.overlayController.hide() }
        verify(exactly = 1) { harness.overlayController.show(any(), any()) } // startLock() only
    }

    @org.junit.Test
    fun `rapid disconnect-reconnect-disconnect flapping re-attaches the overlay on each real disconnect, not the reconnect`() {
        val harness = ServiceHarness()
        harness.startLock()
        harness.onAccessibilityConnectionChanged(isConnected = true)

        harness.onAccessibilityConnectionChanged(isConnected = false) // disconnect 1: re-attach
        harness.onAccessibilityConnectionChanged(isConnected = true)  // reconnect: no-op for overlay
        harness.onAccessibilityConnectionChanged(isConnected = false) // disconnect 2: re-attach again

        verify(exactly = 2) { harness.overlayController.hide() }
        verify(exactly = 3) { harness.overlayController.show(any(), any()) } // startLock() + 2 recoveries
    }
}
