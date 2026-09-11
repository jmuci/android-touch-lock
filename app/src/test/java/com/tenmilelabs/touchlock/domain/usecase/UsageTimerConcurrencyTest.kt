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
 *
 * On the dispatcher: these run on a single-threaded [StandardTestDispatcher] while production uses
 * the multi-threaded [kotlinx.coroutines.Dispatchers.Default], so they cannot by themselves prove
 * anything about true parallelism. They don't need to. [ObserveUsageTimerUseCase] does all of its
 * start/stop bookkeeping inline in the one lock-state collector rather than in per-transition
 * `launch`es, so the collector's own sequencing — not a lock, and not the scheduler — is what
 * orders these operations, and there is no second writer to interleave with. What these tests pin
 * is exactly that property: `a stop already in flight...` below fails the moment any of this work
 * moves back off the collector into its own coroutine.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UsageTimerConcurrencyTest {

    /** Preferences fake whose write suspends while the gate is closed, modelling DataStore's I/O. */
    private class GatedLockPreferences(gateClosedInitially: Boolean = true) : LockPreferencesRepository {
        private val _usageData = MutableStateFlow<UsageData?>(null)
        private var writeGate: CompletableDeferred<Unit>? =
            if (gateClosedInitially) CompletableDeferred() else null

        /** Every write in the order it actually committed — the ordering under test. */
        val committedWrites = mutableListOf<UsageData>()

        override val usageData: Flow<UsageData?> = _usageData

        override suspend fun getUsageData(date: String): UsageData? =
            _usageData.value?.takeIf { it.date == date }

        override suspend fun updateUsageData(data: UsageData) {
            writeGate?.await()
            _usageData.value = data
            committedWrites += data
        }

        override suspend fun clearUsageData() {
            _usageData.value = null
        }

        fun closeGate() {
            writeGate = CompletableDeferred()
        }

        fun releaseWrites() {
            writeGate?.complete(Unit)
            writeGate = null
        }
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

    @Test
    fun `a stop already in flight completes before a following start is observed`() = runTest {
        // The inverse of the two tests above, and the one that actually pins the ordering: a stop
        // whose persistence is still in flight must not be overtaken by the start that follows it.
        // When both transitions ran in their own launch, the two writes raced on Dispatchers.Default
        // and whichever finished last won regardless of which transition happened last — leaving
        // lastStartTime = null persisted while the tick loop counted, so a later process death
        // recovered the session as "not running" and discarded it.
        prefs.releaseWrites() // let the setup phase and the first start commit normally
        advanceTimeBy(100)

        lockRepository.emitLockState(LockState.Locked)
        advanceTimeBy(100) // start committed, timer running

        // Unlock with the next write held open: the stop is now mid-flight.
        prefs.closeGate()
        lockRepository.emitLockState(LockState.Unlocked)
        advanceTimeBy(100)

        // Re-lock while the stop is still blocked.
        lockRepository.emitLockState(LockState.Locked)
        advanceTimeBy(100)

        prefs.releaseWrites()
        advanceTimeBy(100)

        try {
            val committed = prefs.committedWrites.toList()
            val state = useCase().first()

            // The last write must be the re-lock, never the stop it followed.
            assertThat(committed.last().lastStartTime).isNotNull()
            assertThat(state.isRunning).isTrue()
        } finally {
            useCase.cancelForTesting()
        }
    }
}
