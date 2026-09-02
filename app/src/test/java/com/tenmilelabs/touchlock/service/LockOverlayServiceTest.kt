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
import kotlinx.coroutines.runBlocking

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
 * - Idle self-dismiss: the service tears itself down after prolonged unlocked inactivity,
 *   which also breaks the START_STICKY chain that could otherwise let the OS silently
 *   resurrect the service (and its notification) with no user action involved.
 */
class LockOverlayServiceTest {

    // ---------------------------------------------------------------------------
    // Fakes & helpers
    // ---------------------------------------------------------------------------

    private class FakeConfigRepository(
        initialDebugVisible: Boolean = false,
        initialBackstopTimeoutMinutes: Int = 60,
        initialLastKnownLocked: Boolean = false,
    ) : ConfigRepository {
        private val debugFlow = MutableStateFlow(initialDebugVisible)
        private val backstopTimeoutFlow = MutableStateFlow(initialBackstopTimeoutMinutes)
        private var lastKnownLocked = initialLastKnownLocked

        override fun observeDebugOverlayVisible(): Flow<Boolean> = debugFlow

        override suspend fun setDebugOverlayVisible(visible: Boolean) {
            debugFlow.value = visible
        }

        override fun observeBackstopTimeoutMinutes(): Flow<Int> = backstopTimeoutFlow

        override suspend fun setBackstopTimeoutMinutes(minutes: Int) {
            backstopTimeoutFlow.value = minutes
        }

        override suspend fun getLastKnownLocked(): Boolean = lastKnownLocked

        override suspend fun setLastKnownLocked(locked: Boolean) {
            lastKnownLocked = locked
        }

        /** Test-only synchronous accessor, mirroring what a real collector would already hold. */
        fun currentBackstopTimeoutMinutes(): Int = backstopTimeoutFlow.value

        /** Test-only synchronous accessor for the persisted last-known-locked flag. */
        fun currentLastKnownLocked(): Boolean = lastKnownLocked
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
        private var isIdleDismissScheduled = false
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
                persistLastKnownLocked(false) // mirrors initService()
                scheduleIdleDismiss() // mirrors initService()'s unconditional scheduleIdleDismiss()
            }

            // Mirrors startLock()'s `if (!attached) { ...; return }` guard: addView can fail (e.g.
            // BadTokenException), and locking must not proceed — no state flip, no notification,
            // no backstop timeout — when the overlay didn't actually attach.
            val attached = overlayController.show(false) {}
            if (!attached) return

            notificationManager.buildLockedNotification()
            setLockState(LockState.Locked)
            persistLastKnownLocked(true)
            cancelIdleDismiss()
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
            persistLastKnownLocked(false)
            scheduleIdleDismiss()
        }

        /** Mirrors LockOverlayService.persistLastKnownLocked(). */
        fun persistLastKnownLocked(locked: Boolean) {
            runBlocking { configRepository.setLastKnownLocked(locked) }
        }

        /**
         * Mirrors the null-action branch of onStartCommand: the system revived the service via
         * START_STICKY with no pending intent, so the only signal available is whatever was last
         * persisted before the process died.
         */
        fun systemRestart() {
            val wasLocked = runBlocking { configRepository.getLastKnownLocked() }
            if (wasLocked) {
                isServiceRunning = true
                setLockState(LockState.Unlocked)
                scheduleIdleDismiss()
            } else {
                notificationManager.buildUnlockedNotification()
                dismissService()
            }
        }

        /**
         * Mirrors LockOverlayService's idle safety valve: after IDLE_DISMISS_TIMEOUT_MINUTES of
         * unlocked inactivity the service self-dismisses. This is what actually breaks the
         * START_STICKY chain that otherwise lets the OS silently resurrect the service (and its
         * notification) hours or days after the process was killed, with no user action involved.
         */
        fun scheduleIdleDismiss() {
            isIdleDismissScheduled = true
        }

        fun cancelIdleDismiss() {
            isIdleDismissScheduled = false
        }

        fun isIdleDismissScheduled() = isIdleDismissScheduled

        /** Simulates the idle-dismiss timer firing (as the delayed coroutine would). */
        fun triggerIdleDismiss() {
            if (!isIdleDismissScheduled) return
            dismissService()
        }

        /** Mirrors LockOverlayService.dismissService() decision logic. */
        fun dismissService() {
            overlayController.hide()
            isServiceRunning = false
            isBackstopTimeoutScheduled = false
            isIdleDismissScheduled = false
            setLockState(LockState.Unlocked)
            persistLastKnownLocked(false)
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

            if (!isServiceRunning) {
                isServiceRunning = true
                scheduleIdleDismiss() // mirrors initService()
            }
            cancelIdleDismiss()

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
            scheduleIdleDismiss()
        }

        fun isCountdownRunning() = isCountdownActive
        fun secondsRemaining() = countdownSecondsRemaining

        /**
         * Mirrors LockOverlayService.restoreNotification() decision logic — called when
         * MainActivity.onResume() sends ACTION_RESTORE_NOTIFICATION, e.g. after the user
         * force-dismissed the "ongoing" notification and reopened the app. Must reassert whichever
         * notification actually matches the current lock state, not just re-show the last one
         * built — that's the specific bug class this guards: a stale notification type surviving
         * a resume after the underlying state changed.
         */
        fun restoreNotification() {
            if (!isServiceRunning) {
                // Mirrors initService(): process was killed and restarted, so there's no running
                // service to restore into — start fresh, unlocked, rather than restoring anything.
                isServiceRunning = true
                setLockState(LockState.Unlocked)
                notificationManager.buildUnlockedNotification()
                persistLastKnownLocked(false)
                scheduleIdleDismiss()
                return
            }
            when (getLockState()) {
                LockState.Locked -> notificationManager.buildLockedNotification()
                LockState.Unlocked -> notificationManager.buildUnlockedNotification()
            }
            if (getLockState() == LockState.Unlocked) {
                scheduleIdleDismiss() // resume is a real usage signal — resets the idle clock
            }
        }
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

    // ---------------------------------------------------------------------------
    // Tests: restoreNotification() — the resume half of the dismissal/restore round trip (Task 5)
    // ---------------------------------------------------------------------------
    //
    // The full round trip described in the notification-dismissal fix has three parts: (1)
    // NotificationCompat.setAutoCancel(false) resists a tap-dismiss — a real Notification/
    // NotificationCompat.Builder behavior, unreachable from a pure JVM unit test without
    // Robolectric, so not re-verified here; (2) MainActivity.onResume() sends
    // ACTION_RESTORE_NOTIFICATION, covered by LockRepositoryImplTest; (3) the service reasserts
    // foreground state with a notification matching current lock state — that's restoreNotification()
    // below, and it had no coverage at all before this: nothing exercised the decision of *which*
    // notification a resume-triggered restore rebuilds, which is exactly where a stale notification
    // surviving a state change would hide.

    @org.junit.Test
    fun `restoreNotification while locked rebuilds the locked notification`() {
        val harness = ServiceHarness()
        harness.startLock()

        harness.restoreNotification()

        verify(exactly = 2) { harness.notificationManager.buildLockedNotification() } // startLock() + restore
    }

    @org.junit.Test
    fun `restoreNotification while unlocked rebuilds the unlocked notification, not the locked one`() {
        val harness = ServiceHarness()
        harness.startLock()
        harness.stopLock()

        harness.restoreNotification()

        verify(exactly = 1) { harness.notificationManager.buildLockedNotification() } // only from startLock()
        verify(atLeast = 1) { harness.notificationManager.buildUnlockedNotification() }
    }

    @org.junit.Test
    fun `restoreNotification initializes the service instead of restoring a stale state when the service isn't running`() {
        // Simulates the process having been killed and restarted: the service object is fresh
        // (isServiceRunning = false) but the app is being resumed into it via
        // ACTION_RESTORE_NOTIFICATION before any INIT/TOGGLE action ever arrives.
        val harness = ServiceHarness()

        harness.restoreNotification()

        assertThat(getLockState()).isEqualTo(LockState.Unlocked)
        verify { harness.notificationManager.buildUnlockedNotification() }
        verify(exactly = 0) { harness.notificationManager.buildLockedNotification() }
    }

    @org.junit.Test
    fun `restoreNotification after a full lock cycle correctly reflects the final unlocked state`() {
        // Guards against reading a stale/cached notification type rather than the live lock state
        // at the moment of restore — lock, unlock, then restore should reflect Unlocked, not
        // whatever was last shown while locked.
        val harness = ServiceHarness()
        harness.startLock()
        harness.stopLock()

        harness.restoreNotification()

        assertThat(getLockState()).isEqualTo(LockState.Unlocked)
        verify(exactly = 1) { harness.notificationManager.buildLockedNotification() } // only from startLock()
    }

    // ---------------------------------------------------------------------------
    // Tests: idle self-dismiss safety valve
    // ---------------------------------------------------------------------------
    //
    // Root cause under test: LockOverlayService returns START_STICKY, so if the OS kills the
    // process under memory pressure the system can restart the service later — at a time of its
    // own choosing, possibly hours or days on — with a null Intent. That path re-initializes the
    // service and shows the "unlocked" notification with no user action involved, which is
    // exactly the "notification appeared on its own" symptom being fixed. The idle-dismiss timer
    // bounds how long an unlocked, untouched service notification can persist and, by fully
    // stopping the service (not just hiding the notification), breaks the sticky-restart chain
    // so the OS has nothing left to resurrect.

    @org.junit.Test
    fun `idle dismiss is scheduled once the service first initializes`() {
        val harness = ServiceHarness()
        harness.startLock()
        harness.stopLock() // back to a plain unlocked-and-idle state

        assertThat(harness.isIdleDismissScheduled()).isTrue()
    }

    @org.junit.Test
    fun `startLock cancels the idle dismiss timer once locked`() {
        val harness = ServiceHarness()
        harness.startLock()

        assertThat(harness.isIdleDismissScheduled()).isFalse()
    }

    @org.junit.Test
    fun `stopLock reschedules the idle dismiss timer`() {
        val harness = ServiceHarness()
        harness.startLock()
        harness.stopLock()

        assertThat(harness.isIdleDismissScheduled()).isTrue()
    }

    @org.junit.Test
    fun `startDelayedLock cancels the idle dismiss timer while a countdown is in progress`() {
        val harness = ServiceHarness()
        harness.startLock()
        harness.stopLock()
        assertThat(harness.isIdleDismissScheduled()).isTrue()

        harness.startDelayedLock(durationSeconds = 5)

        assertThat(harness.isIdleDismissScheduled()).isFalse()
    }

    @org.junit.Test
    fun `cancelling a countdown reschedules the idle dismiss timer`() {
        val harness = ServiceHarness()
        harness.startDelayedLock(durationSeconds = 5)

        harness.cancelCountdown()

        assertThat(harness.isIdleDismissScheduled()).isTrue()
    }

    @org.junit.Test
    fun `idle dismiss firing while unlocked stops the service and hides the notification`() {
        val harness = ServiceHarness()
        harness.startLock()
        harness.stopLock()

        harness.triggerIdleDismiss()

        verify { harness.overlayController.hide() }
        assertThat(getLockState()).isEqualTo(LockState.Unlocked)
    }

    @org.junit.Test
    fun `idle dismiss is a no-op once the lock re-engages before it fires`() {
        val harness = ServiceHarness()
        harness.startLock()
        harness.stopLock()
        harness.startLock() // re-locked before the idle timer would have fired

        harness.triggerIdleDismiss()

        // Re-locking cancels the pending idle-dismiss job; a stale trigger must not tear down an
        // active lock.
        assertThat(getLockState()).isEqualTo(LockState.Locked)
    }

    @org.junit.Test
    fun `a system restart with no prior user action schedules idle dismiss, which eventually self-dismisses`() {
        // Simulates the actual bug: the process was killed by the OS and later revived via
        // START_STICKY (modeled here the same way as the existing "service isn't running" restore
        // test — restoreNotification()/initService() runs with no preceding user action). Before
        // this fix, the service would show the notification and never take itself down again.
        val harness = ServiceHarness()

        harness.restoreNotification() // mirrors the null-action / no-op restart path
        assertThat(harness.isIdleDismissScheduled()).isTrue()

        harness.triggerIdleDismiss()

        assertThat(getLockState()).isEqualTo(LockState.Unlocked)
        verify { harness.overlayController.hide() }
    }

    @org.junit.Test
    fun `restoreNotification while unlocked resets the idle dismiss clock`() {
        val harness = ServiceHarness()
        harness.startLock()
        harness.stopLock()
        harness.cancelIdleDismiss() // simulate time having already elapsed toward the old deadline

        harness.restoreNotification()

        assertThat(harness.isIdleDismissScheduled()).isTrue()
    }

    @org.junit.Test
    fun `restoreNotification while locked does not schedule an idle dismiss`() {
        val harness = ServiceHarness()
        harness.startLock()

        harness.restoreNotification()

        assertThat(harness.isIdleDismissScheduled()).isFalse()
    }

    // ---------------------------------------------------------------------------
    // Tests: system-triggered restart (null-action onStartCommand) checks persisted lock state
    // ---------------------------------------------------------------------------
    //
    // The idle-dismiss timer above bounds a *live* service's notification lifetime, but it lives
    // in memory: if the OS kills the process outright, the timer dies with it and a later
    // START_STICKY restart starts a fresh one, with no memory of how long the process was
    // actually idle beforehand. These tests cover the persisted last-known-lock-state check that
    // closes that gap: a restart after the process died while Unlocked has nothing to protect and
    // must not resurrect the notification at all, while a restart after dying while Locked keeps
    // behaving as a safety net, since there's no way to know how long ago that lock happened.

    @org.junit.Test
    fun `system restart after dying while unlocked does not resurrect the service`() {
        val harness = ServiceHarness()
        harness.startLock()
        harness.stopLock() // persists last-known-locked = false, then the process "dies"

        harness.systemRestart()

        assertThat(getLockState()).isEqualTo(LockState.Unlocked)
        verify { harness.overlayController.hide() }
    }

    @org.junit.Test
    fun `system restart after dying while locked re-initializes as a safety net`() {
        val harness = ServiceHarness()
        harness.startLock() // persists last-known-locked = true, then the process "dies"

        harness.systemRestart()

        // Deliberately resets to Unlocked rather than silently re-engaging the lock (see
        // startLock()'s existing "prevents unintended auto-locking" comment) — but the service
        // itself comes back, preserving the notification-as-safety-net behavior.
        assertThat(getLockState()).isEqualTo(LockState.Unlocked)
        assertThat(harness.isIdleDismissScheduled()).isTrue()
    }

    @org.junit.Test
    fun `a spurious system restart does not schedule a fresh idle dismiss window`() {
        // Guards against silently falling back to "always re-initialize": if this regressed to
        // calling scheduleIdleDismiss() unconditionally, a spurious restart would still buy the
        // notification a fresh 2 hours of unwanted lifetime instead of tearing down immediately.
        val harness = ServiceHarness()
        harness.startLock()
        harness.stopLock()

        harness.systemRestart()

        assertThat(harness.isIdleDismissScheduled()).isFalse()
    }

    @org.junit.Test
    fun `fresh install with no persisted lock state treats a restart as spurious`() {
        // No lock ever happened, so there's no persisted "true" to find — the default must be the
        // safe (spurious-restart) branch, not the safety-net one.
        val harness = ServiceHarness()

        harness.systemRestart()

        assertThat(getLockState()).isEqualTo(LockState.Unlocked)
        verify { harness.overlayController.hide() }
    }
}
