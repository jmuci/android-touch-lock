package com.tenmilelabs.touchlock.ui

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.tenmilelabs.touchlock.domain.model.LockState
import com.tenmilelabs.touchlock.domain.model.OrientationMode
import com.tenmilelabs.touchlock.domain.repository.ConfigRepository
import com.tenmilelabs.touchlock.domain.usecase.ObserveLockStateUseCase
import com.tenmilelabs.touchlock.domain.usecase.ObserveOrientationModeUseCase
import com.tenmilelabs.touchlock.domain.usecase.ObserveUsageTimerUseCase
import com.tenmilelabs.touchlock.domain.usecase.RestoreNotificationUseCase
import com.tenmilelabs.touchlock.domain.usecase.SetOrientationModeUseCase
import com.tenmilelabs.touchlock.domain.usecase.StartDelayedLockUseCase
import com.tenmilelabs.touchlock.domain.usecase.StartLockUseCase
import com.tenmilelabs.touchlock.domain.usecase.StopLockUseCase
import com.tenmilelabs.touchlock.domain.usecase.fakes.FakeClock
import com.tenmilelabs.touchlock.domain.usecase.fakes.FakeLockPreferences
import com.tenmilelabs.touchlock.domain.usecase.fakes.FakeLockRepository
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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

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
        every { overlayPermissionManager.hasPermission() } returns true
        every { notificationPermissionManager.areNotificationsAvailable() } returns true
        every { notificationPermissionManager.getNotificationIssueDescription() } returns ""

        viewModel = HomeViewModel(
            observeLockState = ObserveLockStateUseCase(fakeLockRepository),
            observeOrientationMode = ObserveOrientationModeUseCase(fakeConfigRepository),
            observeUsageTimer = observeUsageTimer,
            startLock = StartLockUseCase(fakeLockRepository),
            stopLock = StopLockUseCase(fakeLockRepository),
            startDelayedLock = StartDelayedLockUseCase(fakeLockRepository),
            setOrientationMode = SetOrientationModeUseCase(fakeConfigRepository),
            restoreNotification = RestoreNotificationUseCase(fakeLockRepository),
            overlayPermissionManager = overlayPermissionManager,
            notificationPermissionManager = notificationPermissionManager
        )
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
    fun `combined UI state updates atomically when lock state changes`() = runTest {
        viewModel.uiState.test {
            // Initial state
            val initial = awaitItem()
            assertThat(initial.lockState).isEqualTo(LockState.Unlocked)
            assertThat(initial.usageTimer.isRunning).isFalse()
            assertThat(initial.usageTimer.elapsedMillisToday).isEqualTo(0L)

            // Enable lock
            viewModel.onEnableClicked()
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
            viewModel.onDisableClicked()
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
    fun `ViewModel restores usage timer state from persistence after recreation`() = runTest {
        viewModel.uiState.test {
            awaitItem() // Initial

            // Accumulate some time
            viewModel.onEnableClicked()
            advanceTimeBy(100)
            awaitItem() // Lock started

            fakeClock.advanceTimeBy(5000)
            advanceTimeBy(5000)
            repeat(5) { awaitItem() } // Consume ticks

            viewModel.onDisableClicked()
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
            observeLockState = ObserveLockStateUseCase(fakeLockRepository),
            observeOrientationMode = ObserveOrientationModeUseCase(fakeConfigRepository),
            observeUsageTimer = newObserveUsageTimer,
            startLock = StartLockUseCase(fakeLockRepository),
            stopLock = StopLockUseCase(fakeLockRepository),
            startDelayedLock = StartDelayedLockUseCase(fakeLockRepository),
            setOrientationMode = SetOrientationModeUseCase(fakeConfigRepository),
            restoreNotification = RestoreNotificationUseCase(fakeLockRepository),
            overlayPermissionManager = overlayPermissionManager,
            notificationPermissionManager = notificationPermissionManager
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
     */
    @Test
    fun `multiple rapid lock state changes produce consistent UI state`() = runTest {
        viewModel.uiState.test {
            awaitItem() // Initial

            // Rapid enable/disable cycles
            viewModel.onEnableClicked()
            advanceTimeBy(100)
            val locked1 = awaitItem()
            assertThat(locked1.lockState).isEqualTo(LockState.Locked)
            assertThat(locked1.usageTimer.isRunning).isTrue()

            fakeClock.advanceTimeBy(1000)
            advanceTimeBy(1000)
            val tick1 = awaitItem()
            assertThat(tick1.usageTimer.elapsedMillisToday).isEqualTo(1000L)

            viewModel.onDisableClicked()
            advanceTimeBy(100)
            val unlocked1 = awaitItem()
            assertThat(unlocked1.lockState).isEqualTo(LockState.Unlocked)
            val timerStopped = awaitItem() // Timer stopped comes as a separate event
            assertThat(timerStopped.usageTimer.isRunning).isFalse()
            assertThat(timerStopped.usageTimer.elapsedMillisToday).isEqualTo(1000L)

            viewModel.onEnableClicked()
            advanceTimeBy(100)
            val locked2 = awaitItem()
            assertThat(locked2.lockState).isEqualTo(LockState.Locked)
            val timerStarted = awaitItem()
            assertThat(timerStarted.usageTimer.isRunning).isTrue()
            assertThat(timerStarted.usageTimer.elapsedMillisToday).isEqualTo(1000L) // Preserved

            fakeClock.advanceTimeBy(2000)
            advanceTimeBy(2000)
            repeat(2) { i ->
                val tick = awaitItem()
                assertThat(tick.usageTimer.elapsedMillisToday).isEqualTo(1000L + (i + 1) * 1000L)
            }
            observeUsageTimer.cancelForTesting()
        }
    }

    // Test fakes

    private class FakeConfigRepository : ConfigRepository {
        private val orientationMode = MutableStateFlow(OrientationMode.FOLLOW_SYSTEM)

        override fun observeOrientationMode(): Flow<OrientationMode> = orientationMode

        override suspend fun setOrientationMode(mode: OrientationMode) {
            orientationMode.value = mode
        }
    }
}
