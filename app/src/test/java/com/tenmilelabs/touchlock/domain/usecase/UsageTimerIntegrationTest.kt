package com.tenmilelabs.touchlock.domain.usecase

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.tenmilelabs.touchlock.domain.model.LockState
import com.tenmilelabs.touchlock.domain.repository.LockRepository
import com.tenmilelabs.touchlock.domain.usecase.fakes.FakeClock
import com.tenmilelabs.touchlock.domain.usecase.fakes.FakeLockPreferences
import com.tenmilelabs.touchlock.domain.usecase.fakes.FakeLockRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Integration tests for usage timer behavior.
 * 
 * Tests critical timer functionality without UI or Android framework dependencies:
 * - Usage accumulation across lock/unlock cycles
 * - Daily reset at midnight
 * - Persistence and recovery
 * 
 * Why these tests matter:
 * - Timer bugs directly impact user trust (parental controls rely on accurate tracking)
 * - Date rollover is edge-case prone and hard to test manually
 * - These are deterministic, fast JVM tests that catch regressions early
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UsageTimerIntegrationTest {
    
    private lateinit var fakeClock: FakeClock
    private lateinit var fakeLockPreferences: FakeLockPreferences
    private lateinit var fakeLockRepository: FakeLockRepository
    private lateinit var observeUsageTimer: ObserveUsageTimerUseCase
    
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        
        fakeClock = FakeClock()
        fakeLockPreferences = FakeLockPreferences()
        fakeLockRepository = FakeLockRepository()
        
        // Start at a known time: 2024-01-15 10:00:00 AM
        fakeClock.setDate("2024-01-15")
        fakeClock.advanceTimeBy(10 * 60 * 60 * 1000) // 10 hours
        
        observeUsageTimer = ObserveUsageTimerUseCase(
            lockRepository = fakeLockRepository,
            lockPreferences = fakeLockPreferences,
            timeProvider = fakeClock,
            dispatcher = testDispatcher
        )
    }

    @After
    fun tearDown() {
        observeUsageTimer.cancelForTesting()
        Dispatchers.resetMain()
    }

    /**
     * Test: Usage time accumulates correctly across multiple lock/unlock cycles on the same day.
     * 
     * Why this matters:
     * Parents need accurate daily totals. If the timer resets between sessions
     * or doesn't accumulate correctly, the feature becomes useless.
     */
    @Test
    fun `usage accumulates across multiple lock and unlock cycles on same day`() = runTest {
        observeUsageTimer().test {
            // Initial state
            assertThat(awaitItem().elapsedMillisToday).isEqualTo(0L)

            // First lock session: 5 seconds
            fakeLockRepository.emitLockState(LockState.Locked)
            advanceTimeBy(100) // Allow state to propagate
            assertThat(awaitItem().isRunning).isTrue()
            
            // Simulate 5 seconds passing
            fakeClock.advanceTimeBy(5000)
            advanceTimeBy(5000) // Advance test scheduler to match fake clock
            // Each tick updates every 1000ms, so we should get 5 updates
            repeat(5) {
                val state = awaitItem()
                assertThat(state.elapsedMillisToday).isEqualTo((it + 1) * 1000L)
            }

            // Unlock - stops timer but preserves accumulated time
            fakeLockRepository.emitLockState(LockState.Unlocked)
            advanceTimeBy(100)
            val afterFirstUnlock = awaitItem()
            assertThat(afterFirstUnlock.isRunning).isFalse()
            assertThat(afterFirstUnlock.elapsedMillisToday).isEqualTo(5000L)

            // Wait 2 seconds while unlocked (should not accumulate)
            fakeClock.advanceTimeBy(2000)
            advanceTimeBy(2000)
            expectNoEvents() // No updates while unlocked

            // Second lock session: 3 seconds (should add to previous 5)
            fakeLockRepository.emitLockState(LockState.Locked)
            advanceTimeBy(100)
            val afterSecondLock = awaitItem()
            assertThat(afterSecondLock.isRunning).isTrue()
            assertThat(afterSecondLock.elapsedMillisToday).isEqualTo(5000L) // Preserved from before

            fakeClock.advanceTimeBy(3000)
            advanceTimeBy(3000)
            repeat(3) {
                val state = awaitItem()
                assertThat(state.elapsedMillisToday).isEqualTo(5000L + (it + 1) * 1000L)
            }

            // Unlock again
            fakeLockRepository.emitLockState(LockState.Unlocked)
            advanceTimeBy(100)
            val finalState = awaitItem()
            assertThat(finalState.isRunning).isFalse()
            assertThat(finalState.elapsedMillisToday).isEqualTo(8000L) // 5s + 3s

            // Verify persistence
            val persistedData = fakeLockPreferences.getCurrentUsageData()
            assertThat(persistedData?.accumulatedMillis).isEqualTo(8000L)
            assertThat(persistedData?.date).isEqualTo("2024-01-15")
            assertThat(persistedData?.lastStartTime).isNull() // Stopped
        }
    }

    /**
     * Test: Usage timer resets when the calendar day changes (midnight rollover).
     * 
     * Why this matters:
     * The "daily" usage limit is only meaningful if it resets at midnight.
     * This must work based on date comparison, not elapsed time, to handle
     * device time changes and app restarts correctly.
     */
    @Test
    fun `timer resets at midnight based on date change`() = runTest {
        observeUsageTimer().test {
            // Start with some accumulated time on Day 1
            assertThat(awaitItem().elapsedMillisToday).isEqualTo(0L)

            fakeLockRepository.emitLockState(LockState.Locked)
            advanceTimeBy(100)
            assertThat(awaitItem().isRunning).isTrue()

            // Accumulate 4 seconds on Jan 15
            fakeClock.advanceTimeBy(4000)
            advanceTimeBy(4000)
            repeat(4) {
                awaitItem() // Consume tick updates
            }

            // Cross midnight: advance to Jan 16, 12:01 AM
            // This simulates 23 hours and 56 minutes passing to get to midnight + 1 minute
            fakeClock.setDate("2024-01-16")
            fakeClock.advanceTimeBy(1 * 60 * 1000) // 1 minute past midnight

            // The next tick (1 second later) should detect the date change and reset
            advanceTimeBy(1000)
            val afterMidnight = awaitItem()
            
            // Timer should have reset to 0 for the new day
            assertThat(afterMidnight.elapsedMillisToday).isEqualTo(0L)
            assertThat(afterMidnight.isRunning).isTrue() // Still running, just reset

            // Verify new day starts fresh in persistence
            val persistedData = fakeLockPreferences.getCurrentUsageData()
            assertThat(persistedData?.date).isEqualTo("2024-01-16")
            assertThat(persistedData?.accumulatedMillis).isEqualTo(0L)

            // Continue accumulating on the new day
            fakeClock.advanceTimeBy(2000)
            advanceTimeBy(2000)
            repeat(2) {
                val state = awaitItem()
                assertThat(state.elapsedMillisToday).isEqualTo((it + 1) * 1000L)
            }
            
            // Stop timer to clean up

            fakeLockRepository.emitLockState(LockState.Unlocked)
            advanceTimeBy(100)
            awaitItem()
        }
    }


    /**
     * Test: the day rolls over even when midnight passes while the lock is NOT engaged.
     *
     * Why this matters:
     * `timer resets at midnight based on date change` above only ever crosses midnight *while
     * locked*, which is the one path the tick loop's own rollover check covers. The far more
     * common real-world shape is the opposite: the user stops using the app in the evening and
     * comes back the next day. Nothing runs in between, so if rollover is only ever detected from
     * inside the tick loop, yesterday's total silently becomes today's starting value — a
     * parental-controls counter that reads high forever.
     */
    @Test
    fun `timer resets when midnight passes while unlocked, not just mid-session`() = runTest {
        // Day 1: accumulate 4 seconds, then unlock for the night.
        fakeLockRepository.emitLockState(LockState.Locked)
        advanceTimeBy(100)
        fakeClock.advanceTimeBy(4000)
        advanceTimeBy(4000)

        fakeLockRepository.emitLockState(LockState.Unlocked)
        advanceTimeBy(100)
        assertThat(observeUsageTimer().first().elapsedMillisToday).isEqualTo(4000L)

        // Midnight passes with nothing running at all.
        fakeClock.setDate("2024-01-16")
        fakeClock.advanceTimeBy(9 * 60 * 60 * 1000) // next morning

        // Day 2: the very first lock of the new day must start today's count from zero.
        fakeLockRepository.emitLockState(LockState.Locked)
        advanceTimeBy(100)

        // Asserted on the settled state rather than one awaited emission: the rollover legitimately
        // publishes the reset and the isRunning flip as two separate values, and which of them a
        // single awaitItem() lands on is an implementation detail, not the invariant under test.
        val newDay = observeUsageTimer().first()
        val persisted = fakeLockPreferences.getCurrentUsageData()

        // Stop before asserting: a failure here would otherwise leave the tick loop's
        // `while (isActive) { delay(1000) }` running, and runTest's trailing advanceUntilIdle()
        // would spin on it forever instead of reporting the failure.
        fakeLockRepository.emitLockState(LockState.Unlocked)
        advanceTimeBy(100)

        assertThat(newDay.elapsedMillisToday).isEqualTo(0L)
        assertThat(newDay.isRunning).isTrue()
        assertThat(persisted?.date).isEqualTo("2024-01-16")
        assertThat(persisted?.accumulatedMillis).isEqualTo(0L)
    }

    /**
     * Test: Timer recovers correctly after app restart with persisted data.
     * 
     * Why this matters:
     * Android apps can be killed at any time. The timer must accurately
     * restore state from DataStore, including handling the case where
     * the app was killed while the lock was active.
     */
    @Test
    fun `timer restores accumulated time from previous session on same day`() = runTest {
        // Simulate a previous session: lock was active for 3 seconds, then stopped
        observeUsageTimer().test {
            awaitItem() // Initial state
            
            fakeLockRepository.emitLockState(LockState.Locked)
            advanceTimeBy(100)
            awaitItem() // Lock started

            fakeClock.advanceTimeBy(3000)
            advanceTimeBy(3000)
            repeat(3) { awaitItem() } // Consume ticks

            fakeLockRepository.emitLockState(LockState.Unlocked)
            advanceTimeBy(100)
            val beforeRestart = awaitItem()
            assertThat(beforeRestart.elapsedMillisToday).isEqualTo(3000L)
            
            cancelAndIgnoreRemainingEvents()
        }

        // Simulate app restart: create new use case instance with same dependencies
        // The persisted data should still be in fakeLockPreferences
        val restoredUsageTimer = ObserveUsageTimerUseCase(
            lockRepository = fakeLockRepository,
            lockPreferences = fakeLockPreferences,
            timeProvider = fakeClock,
            dispatcher = testDispatcher
        )

        // Allow initialization to complete
        advanceTimeBy(100)

        restoredUsageTimer().test {
            val restored = awaitItem()
            // Should restore the 3 seconds from before
            assertThat(restored.elapsedMillisToday).isEqualTo(3000L)
            assertThat(restored.isRunning).isFalse()

            // Continue accumulating
            fakeLockRepository.emitLockState(LockState.Locked)
            advanceTimeBy(100)
            assertThat(awaitItem().isRunning).isTrue()

            fakeClock.advanceTimeBy(2000)
            advanceTimeBy(2000)
            repeat(2) {
                val state = awaitItem()
                assertThat(state.elapsedMillisToday).isEqualTo(3000L + (it + 1) * 1000L)
            }
            fakeLockRepository.emitLockState(LockState.Unlocked)
            advanceTimeBy(100)
            awaitItem()
        }
        
        // Clean up the second instance
        restoredUsageTimer.cancelForTesting()
    }
}
