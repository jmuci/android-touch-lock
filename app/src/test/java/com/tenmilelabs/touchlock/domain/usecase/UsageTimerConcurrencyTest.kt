package com.tenmilelabs.touchlock.domain.usecase

import com.google.common.truth.Truth.assertThat
import com.tenmilelabs.touchlock.domain.model.LockState
import com.tenmilelabs.touchlock.domain.model.UsageData
import com.tenmilelabs.touchlock.domain.repository.LockPreferencesRepository
import com.tenmilelabs.touchlock.domain.usecase.fakes.FakeClock
import com.tenmilelabs.touchlock.domain.usecase.fakes.FakeLockRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
 * Tests for what happens when lock-state changes arrive while the timer's own persistence write
 * is still in flight.
 *
 * Why a separate fake from [com.tenmilelabs.touchlock.domain.usecase.fakes.FakeLockPreferences]:
 * that one's `updateUsageData` completes inline, so it can never model the window that actually
 * exists in production. The real implementation writes through DataStore, whose `edit {}` performs
 * file I/O and genuinely suspends — and every line of [ObserveUsageTimerUseCase.startTimer] after
 * that write, including the assignment of the tick job itself, only runs once it resumes.
 * [GatedLockPreferences] reproduces exactly that: a write the test can hold open and release on
 * demand.
 *
 * The window is narrow in wall-clock terms (one DataStore write), so these are not everyday
 * scenarios — but stopLock() has several non-UI callers that need no human reaction time at all
 * (ACTION_SCREEN_OFF, the backstop timeout, the volume-combo force-unlock), and the failure is
 * silent and permanent when it does land: a usage counter that keeps climbing while unlocked, or
 * counts every second twice.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UsageTimerConcurrencyTest {

    /** Preferences fake whose write suspends until [releaseWrites], modelling DataStore's I/O. */
    private class GatedLockPreferences : LockPreferencesRepository {
        private val _usageData = MutableStateFlow<UsageData?>(null)
        private val writeGate = CompletableDeferred<Unit>()

        override val usageData: Flow<UsageData?> = _usageData

        override suspend fun getUsageData(date: String): UsageData? =
            _usageData.value?.takeIf { it.date == date }

        override suspend fun updateUsageData(data: UsageData) {
            writeGate.await()
            _usageData.value = data
        }

        override suspend fun clearUsageData() {
            _usageData.value = null
        }

        fun releaseWrites() = writeGate.complete(Unit)
    }

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeClock: FakeClock
    private lateinit var prefs: GatedLockPreferences
    private lateinit var lockRepository: FakeLockRepository
    private lateinit var useCase: ObserveUsageTimerUseCase

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeClock = FakeClock().apply {
            setDate("2024-01-15")
            advanceTimeBy(10 * 60 * 60 * 1000)
        }
        prefs = GatedLockPreferences()
        lockRepository = FakeLockRepository(autoUpdateState = false)
        useCase = ObserveUsageTimerUseCase(
            lockRepository = lockRepository,
            lockPreferences = prefs,
            timeProvider = fakeClock,
            dispatcher = testDispatcher
        )
    }

    @After
    fun tearDown() {
        useCase.cancelForTesting()
        Dispatchers.resetMain()
    }

    @Test
    fun `an unlock arriving while the start is still persisting stops the timer`() = runTest {
        advanceTimeBy(100) // let init/loadTodayUsage settle

        // Lock: startTimer() runs and blocks on the persistence write.
        lockRepository.emitLockState(LockState.Locked)
        advanceTimeBy(100)

        // Unlock before that write completes. Whatever bookkeeping startTimer() had not reached
        // yet must not survive this — the user is unlocked, so the timer must not be running.
        lockRepository.emitLockState(LockState.Unlocked)
        advanceTimeBy(100)

        prefs.releaseWrites()
        fakeClock.advanceTimeBy(3000)
        advanceTimeBy(3000)

        try {
            val state = useCase().first()
            assertThat(state.isRunning).isFalse()
            assertThat(state.elapsedMillisToday).isEqualTo(0L)
        } finally {
            // Guarantees no live tick loop survives the test body: runTest's trailing
            // advanceUntilIdle() would otherwise spin forever on `while (isActive) delay(1000)`
            // and hang instead of reporting the assertion failure.
            useCase.cancelForTesting()
        }
    }

    @Test
    fun `a second lock racing the same persistence write does not start a second tick loop`() = runTest {
        advanceTimeBy(100)

        lockRepository.emitLockState(LockState.Locked)
        advanceTimeBy(100) // startTimer() #1, blocked on the write

        lockRepository.emitLockState(LockState.Unlocked)
        advanceTimeBy(100)

        lockRepository.emitLockState(LockState.Locked)
        advanceTimeBy(100) // startTimer() #2, also blocked on the write

        prefs.releaseWrites()
        advanceTimeBy(100)

        fakeClock.advanceTimeBy(3000)
        advanceTimeBy(3000)

        try {
            val state = useCase().first()
            // Three seconds of wall clock is three seconds of usage, however many times the lock
            // was toggled while the write was in flight — not six.
            assertThat(state.elapsedMillisToday).isEqualTo(3000L)
        } finally {
            useCase.cancelForTesting()
        }
    }
}
