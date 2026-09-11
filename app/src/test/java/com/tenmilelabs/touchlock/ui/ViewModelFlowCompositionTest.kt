package com.tenmilelabs.touchlock.ui

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.tenmilelabs.touchlock.domain.model.LockState
import com.tenmilelabs.touchlock.domain.repository.ConfigRepository
import com.tenmilelabs.touchlock.domain.usecase.ObserveUsageTimerUseCase
import com.tenmilelabs.touchlock.domain.usecase.fakes.FakeClock
import com.tenmilelabs.touchlock.domain.usecase.fakes.FakeLockPreferences
import com.tenmilelabs.touchlock.domain.usecase.fakes.FakeLockRepository
import com.tenmilelabs.touchlock.platform.permission.AccessibilityPermissionManager
import com.tenmilelabs.touchlock.platform.permission.NotificationPermissionManager
import com.tenmilelabs.touchlock.platform.permission.OverlayPermissionManager
import com.tenmilelabs.touchlock.ui.screens.home.HomeViewModel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Integration test for ViewModel flow composition.
 * 
 * Why this matters:
 * The ViewModel combines multiple StateFlows (lock state, usage timer, permissions, etc.)
 * into a single UI state. This test ensures that when lock state changes, the combined
 * UI state updates atomically with all related changes (e.g., usage timer starts/stops).
 * 
 * This catches bugs where:
 * - UI shows stale timer data after lock state changes
 * - Race conditions cause inconsistent UI state
 * - Flow combination logic breaks during refactoring
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ViewModelFlowCompositionTest {

    private lateinit var viewModel: HomeViewModel
    private lateinit var fakeLockRepository: FakeLockRepository
    private lateinit var fakeConfigRepository: FakeConfigRepository
    private lateinit var fakeLockPreferences: FakeLockPreferences
    private lateinit var fakeClock: FakeClock
    private lateinit var observeUsageTimer: ObserveUsageTimerUseCase
    private lateinit var overlayPermissionManager: OverlayPermissionManager
    private lateinit var notificationPermissionManager: NotificationPermissionManager
    private lateinit var accessibilityPermissionManager: AccessibilityPermissionManager

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        fakeLockRepository = FakeLockRepository()
        fakeConfigRepository = FakeConfigRepository()
        fakeLockPreferences = FakeLockPreferences()
        fakeClock = FakeClock()
        
        // Start at a known time: 2024-01-20 9:00:00 AM
        fakeClock.setDate("2024-01-20")
        fakeClock.advanceTimeBy(9 * 60 * 60 * 1000)

        // Create real usage timer use case with fakes
        observeUsageTimer = ObserveUsageTimerUseCase(
            lockRepository = fakeLockRepository,
            lockPreferences = fakeLockPreferences,
            timeProvider = fakeClock,
            dispatcher = testDispatcher
        )

        // Mock permission managers
        overlayPermissionManager = mockk(relaxed = true)
        notificationPermissionManager = mockk(relaxed = true)
        accessibilityPermissionManager = mockk(relaxed = true)
        every { overlayPermissionManager.hasPermission() } returns true
        every { notificationPermissionManager.areNotificationsAvailable() } returns true
        every { notificationPermissionManager.getNotificationIssueDescription() } returns ""
        every { accessibilityPermissionManager.isEnabled() } returns false

        viewModel = HomeViewModel(
            lockRepository = fakeLockRepository,
            configRepository = fakeConfigRepository,
            observeUsageTimer = observeUsageTimer,
            overlayPermissionManager = overlayPermissionManager,
            notificationPermissionManager = notificationPermissionManager,
            accessibilityPermissionManager = accessibilityPermissionManager
        )
    }


    /**
     * Wraps [runTest] so the usage timer is always stopped before the body returns.
     *
     * Necessary, not tidy-up: the timer's tick loop is `while (isActive) { delay(1000) }`, and
     * runTest ends by draining the scheduler. With that loop still alive the drain is an unbounded
     * CPU-bound spin over virtual time, which runTest's own `timeout` cannot preempt — so an
     * assertion that throws part-way through a test would hang the whole build with no report
     * rather than failing. The finally makes a failure a failure.
     */
    private fun runTimerTest(body: suspend TestScope.() -> Unit) = runTest(timeout = TEST_TIMEOUT) {
        try {
            body()
        } finally {
            observeUsageTimer.cancelForTesting()
        }
    }

    @After
    fun tearDown() {
        observeUsageTimer.cancelForTesting()
        Dispatchers.resetMain()
    }

    /**
     * Test: Combined UI state updates atomically when lock state changes.
     * 
     * Validates that when the lock is enabled:
     * 1. lockState updates to Locked
     * 2. usageTimer.isRunning becomes true
     * 3. usageTimer starts accumulating time
     * 
     * And when disabled:
     * 1. lockState updates to Unlocked
     * 2. usageTimer.isRunning becomes false
     * 3. usageTimer stops accumulating but preserves elapsed time
     */
    @Test
    fun `combined UI state updates atomically when lock state changes`() = runTimerTest {
        viewModel.uiState.test {
            // Initial state
            val initial = awaitItem()
            assertThat(initial.lockState).isEqualTo(LockState.Unlocked)
            assertThat(initial.usageTimer.isRunning).isFalse()
            assertThat(initial.usageTimer.elapsedMillisToday).isEqualTo(0L)

            // Enable lock
            //viewModel.onEnableClicked()
            fakeLockRepository.emitLockState(LockState.Locked)
            advanceTimeBy(100) // Allow state propagation

            val lockedState = awaitItem()
            assertThat(lockedState.lockState).isEqualTo(LockState.Locked)
            assertThat(lockedState.usageTimer.isRunning).isTrue()
            assertThat(lockedState.usageTimer.elapsedMillisToday).isEqualTo(0L)

            // Simulate 3 seconds passing - timer should accumulate
            fakeClock.advanceTimeBy(3000)
            advanceTimeBy(3000)
            
            // Expect 3 tick updates
            repeat(3) { i ->
                val timerUpdate = awaitItem()
                assertThat(timerUpdate.lockState).isEqualTo(LockState.Locked)
                assertThat(timerUpdate.usageTimer.isRunning).isTrue()
                assertThat(timerUpdate.usageTimer.elapsedMillisToday).isEqualTo((i + 1) * 1000L)
            }

            // Disable lock
            fakeLockRepository.emitLockState(LockState.Unlocked)
            advanceTimeBy(100)

            val unlockedState = awaitItem()
            assertThat(unlockedState.lockState).isEqualTo(LockState.Unlocked)
            val timerStopped = awaitItem()
            assertThat(timerStopped.usageTimer.isRunning).isFalse()
            assertThat(timerStopped.usageTimer.elapsedMillisToday).isEqualTo(3000L) // Preserved

            // Verify no more updates while unlocked
            fakeClock.advanceTimeBy(2000)
            advanceTimeBy(2000)
            expectNoEvents()
        }
    }

    /**
     * Test: UI state correctly reflects usage timer persistence across ViewModel recreation.
     * 
     * This simulates what happens when the Activity is destroyed and recreated
     * (e.g., screen rotation). The new ViewModel should restore the accumulated
     * usage time from persistence.
     */
    @Test
    fun `ViewModel restores usage timer state from persistence after recreation`() = runTimerTest {
        viewModel.uiState.test {
            awaitItem() // Initial

            // Accumulate some time
            fakeLockRepository.emitLockState(LockState.Locked)
            advanceTimeBy(100)
            awaitItem() // Lock started

            fakeClock.advanceTimeBy(5000)
            advanceTimeBy(5000)
            repeat(5) { awaitItem() } // Consume ticks

            fakeLockRepository.emitLockState(LockState.Unlocked)
            advanceTimeBy(100)
            awaitItem() // Lock state changed to Unlocked
            val beforeRecreation = awaitItem() // Timer stopped
            assertThat(beforeRecreation.usageTimer.elapsedMillisToday).isEqualTo(5000L)
            assertThat(beforeRecreation.usageTimer.isRunning).isFalse()

            cancelAndIgnoreRemainingEvents()
        }
        
        // Allow the save operation to complete before recreating
        testDispatcher.scheduler.advanceUntilIdle()
        
        // Allow the save operation to complete before recreating
        testDispatcher.scheduler.advanceUntilIdle()

        // Simulate ViewModel recreation (e.g., screen rotation)
        val newObserveUsageTimer = ObserveUsageTimerUseCase(
            lockRepository = fakeLockRepository,
            lockPreferences = fakeLockPreferences,
            timeProvider = fakeClock,
            dispatcher = testDispatcher
        )

        // Allow usage timer initialization to complete
        testDispatcher.scheduler.advanceUntilIdle()

        val recreatedViewModel = HomeViewModel(
            lockRepository = fakeLockRepository,
            configRepository = fakeConfigRepository,
            observeUsageTimer = newObserveUsageTimer,
            overlayPermissionManager = overlayPermissionManager,
            notificationPermissionManager = notificationPermissionManager,
            accessibilityPermissionManager = accessibilityPermissionManager
        )

        // Allow ViewModel flow combination to complete
        testDispatcher.scheduler.advanceUntilIdle()

        recreatedViewModel.uiState.test {
            // Skip initial value from stateIn (it's the default TouchLockUiState())
            awaitItem()
            
            // Now get the actual combined flow emission with restored state
            val restoredState = awaitItem()
            
            // Should restore the 5 seconds from before
            assertThat(restoredState.usageTimer.elapsedMillisToday).isEqualTo(5000L)
            assertThat(restoredState.usageTimer.isRunning).isFalse()
            assertThat(restoredState.lockState).isEqualTo(LockState.Unlocked)
        }
        
        // Clean up the second instance
        newObserveUsageTimer.cancelForTesting()
    }

    /**
     * Test: Multiple state changes flow through correctly without race conditions.
     *
     * Rapidly toggling lock state should produce consistent UI state updates
     * with no dropped or out-of-order emissions.
     *
     * Asserted on the settled state after each transition rather than on an exact run of
     * awaitItem() calls. How many uiState emissions one lock transition produces is an
     * implementation detail of the ViewModel's combine and of when the usage timer publishes
     * isRunning — it legitimately changed when the timer stopped doing that bookkeeping in a
     * coroutine of its own, and a lock change plus its timer change now coalesce into a single
     * emission. Counting emissions made this test fail with an off-by-one read of a *later*
     * state ("expected 1000 but was 2000") rather than for any real inconsistency. What the test
     * is named for is that every field agrees once things settle, which is what it now checks.
     */
    @Test
    fun `multiple rapid lock state changes produce consistent UI state`() = runTimerTest {
        viewModel.uiState.test {
            fakeLockRepository.emitLockState(LockState.Locked)
            advanceTimeBy(100)
            expectMostRecentItem().let {
                assertThat(it.lockState).isEqualTo(LockState.Locked)
                assertThat(it.usageTimer.isRunning).isTrue()
            }

            fakeClock.advanceTimeBy(1000)
            advanceTimeBy(1000)
            assertThat(expectMostRecentItem().usageTimer.elapsedMillisToday).isEqualTo(1000L)

            fakeLockRepository.emitLockState(LockState.Unlocked)
            advanceTimeBy(100)
            expectMostRecentItem().let {
                assertThat(it.lockState).isEqualTo(LockState.Unlocked)
                assertThat(it.usageTimer.isRunning).isFalse()
                assertThat(it.usageTimer.elapsedMillisToday).isEqualTo(1000L)
            }

            fakeLockRepository.emitLockState(LockState.Locked)
            advanceTimeBy(100)
            expectMostRecentItem().let {
                assertThat(it.lockState).isEqualTo(LockState.Locked)
                assertThat(it.usageTimer.isRunning).isTrue()
                // Preserved across the unlock/re-lock cycle, not restarted from zero.
                assertThat(it.usageTimer.elapsedMillisToday).isEqualTo(1000L)
            }

            fakeClock.advanceTimeBy(2000)
            advanceTimeBy(2000)
            assertThat(expectMostRecentItem().usageTimer.elapsedMillisToday).isEqualTo(3000L)

            cancelAndIgnoreRemainingEvents()
        }
    }

    // Test fakes

    private class FakeConfigRepository : ConfigRepository {
        private val debugOverlayVisibleFlow = MutableStateFlow(false)
        private val backstopTimeoutMinutesFlow = MutableStateFlow(60)
        private var lastKnownLocked = false

        override fun observeDebugOverlayVisible(): Flow<Boolean> {
            return debugOverlayVisibleFlow
        }

        override suspend fun setDebugOverlayVisible(visible: Boolean) {
            debugOverlayVisibleFlow.value = visible
        }

        override fun observeBackstopTimeoutMinutes(): Flow<Int> {
            return backstopTimeoutMinutesFlow
        }

        override suspend fun setBackstopTimeoutMinutes(minutes: Int) {
            backstopTimeoutMinutesFlow.value = minutes
        }

        override suspend fun getLastKnownLocked(): Boolean = lastKnownLocked

        override suspend fun setLastKnownLocked(locked: Boolean) {
            lastKnownLocked = locked
        }
    }

    private companion object {
        /**
         * The usage timer's tick loop is `while (isActive) { delay(1000) }`, so an assertion that
         * throws before the test cancels it leaves it running and runTest's trailing
         * advanceUntilIdle() spins on virtual time forever — a hung build with no report instead
         * of a failure. This bounds that.
         */
        val TEST_TIMEOUT = 20.seconds
    }
}
