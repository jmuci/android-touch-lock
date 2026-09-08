package com.tenmilelabs.touchlock.domain.usecase

import com.tenmilelabs.touchlock.domain.model.LockState
import com.tenmilelabs.touchlock.domain.model.UsageData
import com.tenmilelabs.touchlock.domain.model.UsageTimerState
import com.tenmilelabs.touchlock.domain.repository.LockPreferencesRepository
import com.tenmilelabs.touchlock.domain.repository.LockRepository
import com.tenmilelabs.touchlock.platform.time.TimeProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Observes and manages the daily usage timer.
 * Handles:
 * - Real-time timer updates while lock is active
 * - Persistence of accumulated time
 * - Midnight rollover
 * - App restart recovery
 *
 * IMPORTANT: This class MUST remain a @Singleton to prevent memory leaks.
 * It creates a CoroutineScope that lives for the lifetime of the instance and launches
 * long-running coroutines that collect from repositories. If this were not a singleton,
 * multiple instances would create uncancellable scopes that would leak memory and hold
 * references to repositories indefinitely.
 */
@Singleton
class ObserveUsageTimerUseCase(
    private val lockRepository: LockRepository,
    private val lockPreferences: LockPreferencesRepository,
    private val timeProvider: TimeProvider,
    dispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    @Inject
    constructor(
        lockRepository: LockRepository,
        lockPreferences: LockPreferencesRepository,
        timeProvider: TimeProvider
    ) : this(lockRepository, lockPreferences, timeProvider, Dispatchers.Default)
    
    private val scope = CoroutineScope(dispatcher + Job())
    
    private val _timerState = MutableStateFlow(UsageTimerState.INITIAL)
    private var tickJob: Job? = null
    private var currentDate: String = timeProvider.getCurrentDateString()

    init {
        scope.launch {
            // IMPORTANT: Load persisted data FIRST before observing lock state changes
            // to prevent race condition where stopTimer() overwrites restored data
            loadTodayUsage()
            
            // Now observe lock state changes
            lockRepository.observeLockState().collect { lockState ->
                when (lockState) {
                    LockState.Locked -> startTimer()
                    LockState.Unlocked -> stopTimer()
                }
            }
        }
    }

    operator fun invoke(): Flow<UsageTimerState> = _timerState

    private suspend fun loadTodayUsage() {
        val today = timeProvider.getCurrentDateString()
        val usageData = lockPreferences.getUsageData(today)

        if (usageData != null) {
            // Same day, restore accumulated time
            val accumulatedMillis = usageData.accumulatedMillis
            val additionalMillis = if (usageData.lastStartTime != null) {
                // Timer was running when app was killed, calculate elapsed time since then
                timeProvider.currentTimeMillis() - usageData.lastStartTime
            } else {
                0L
            }

            _timerState.value = UsageTimerState(
                elapsedMillisToday = accumulatedMillis + additionalMillis,
                isRunning = usageData.lastStartTime != null
            )

            // If lock was active when app was killed, restart timer
            if (usageData.lastStartTime != null) {
                val currentLockState = lockRepository.observeLockState().first()
                if (currentLockState == LockState.Locked) {
                    startTimer()
                } else {
                    // Lock is not active but we had a start time, save corrected state
                    saveUsageData(stopTime = true)
                }
            }
        } else {
            // New day or first run, start fresh
            _timerState.value = UsageTimerState.INITIAL
            lockPreferences.clearUsageData()
        }

        currentDate = today
    }

    private fun startTimer() {
        if (tickJob?.isActive == true) return // Already running

        // Assigned synchronously, before the coroutine's first suspension point. The tick job used
        // to be assigned *inside* this coroutine, after an awaited persistence write — and a
        // DataStore write really does suspend. For the whole duration of that write tickJob was
        // still null, so the guard above waved a second startTimer() straight through (two tick
        // loops counting the same second, one of them untracked and therefore uncancellable), and
        // a stopTimer() arriving in that window cancelled nothing and left the timer running while
        // unlocked. Cancelling this job now also correctly abandons the start it was persisting.
        // See UsageTimerConcurrencyTest.
        tickJob = scope.launch {
            // Midnight may have passed with nothing running at all — the ordinary case, since the
            // usual pattern is to stop for the night and come back the next day. The loop below
            // only ever observes a rollover that happens mid-session, so without this check
            // yesterday's total silently becomes today's starting value.
            rollOverIfNewDay()

            // Save start time immediately
            saveUsageData(startTime = timeProvider.currentTimeMillis())

            _timerState.value = _timerState.value.copy(isRunning = true)

            while (isActive) { // isActive Returns true when the coroutine is still active. Available within coroutine scopes
                delay(1000) // Update every second and throws CancellationException if cancelled. This would be enough to cancel

                if (rollOverIfNewDay()) {
                    // Re-anchor the persisted start time to the new day, so a process death later
                    // today recovers against today's clock rather than yesterday's.
                    saveUsageData(startTime = timeProvider.currentTimeMillis())
                } else {
                    // Increment elapsed time
                    _timerState.value = _timerState.value.copy(
                        elapsedMillisToday = _timerState.value.elapsedMillisToday + 1000
                    )
                }
            }
        }
    }

    /**
     * Resets today's counter if the calendar day has changed since [currentDate], clearing the
     * previous day's persisted usage. Returns true when a rollover actually happened.
     */
    private suspend fun rollOverIfNewDay(): Boolean {
        val today = timeProvider.getCurrentDateString()
        if (today == currentDate) return false

        currentDate = today
        _timerState.value = _timerState.value.copy(elapsedMillisToday = 0L)
        lockPreferences.clearUsageData()
        return true
    }

    private fun stopTimer() {
        tickJob?.cancel()
        tickJob = null

        scope.launch {
            _timerState.value = _timerState.value.copy(isRunning = false)
            saveUsageData(stopTime = true)
        }
    }

    private suspend fun saveUsageData(
        startTime: Long? = null,
        stopTime: Boolean = false
    ) {
        val today = timeProvider.getCurrentDateString()
        val currentState = _timerState.value

        val data = UsageData(
            date = today,
            accumulatedMillis = currentState.elapsedMillisToday,
            lastStartTime = when {
                stopTime -> null
                startTime != null -> startTime
                else -> null
            }
        )

        lockPreferences.updateUsageData(data)
    }

    /**
     * Cancels all coroutines and cleans up resources.
     * 
     * FOR TESTING ONLY: This method should ONLY be called in test teardown to prevent
     * scope leaks when creating multiple instances. In production, this class is a
     * singleton and the scope should live for the app lifetime.
     */
    internal fun cancelForTesting() {
        scope.cancel()
    }
}
