package com.tenmilelabs.touchlock.domain.usecase

import com.tenmilelabs.touchlock.domain.model.LockState
import com.tenmilelabs.touchlock.domain.model.UsageData
import com.tenmilelabs.touchlock.domain.model.UsageTimerState
import com.tenmilelabs.touchlock.domain.repository.LockPreferencesRepository
import com.tenmilelabs.touchlock.domain.repository.LockRepository
import com.tenmilelabs.touchlock.platform.time.TimeProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
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
    
    // SupervisorJob + a handler, not a bare Job(): every persistence call here goes through
    // DataStore, which can genuinely fail (IOException on a full disk or a corrupted prefs file).
    // Under a plain Job() such a failure cancelled the parent and therefore its siblings — taking
    // out the lock-state collector that is the only thing driving start/stop — and, with no
    // handler installed, reached the thread's default handler and brought the process down. This
    // class is a @Singleton whose scope is never rebuilt, so that failure was permanent and
    // silent for the rest of the process. Isolating here is the "child failure isolation is
    // explicitly needed" case: the tick loop dying must not stop the app observing the lock.
    private val scope = CoroutineScope(
        dispatcher + SupervisorJob() + CoroutineExceptionHandler { _, throwable ->
            Timber.e(throwable, "Usage timer coroutine failed; usage tracking may be degraded")
        }
    )
    
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

    /**
     * Begins a usage session. Suspends: everything except the per-second tick loop happens inline
     * in the caller, which is always the single lock-state collector in [init] (directly, or via
     * [loadTodayUsage]).
     *
     * That inlining is the point. Both this and [stopTimer] used to hand their bookkeeping to
     * their own `scope.launch`, so two transitions in quick succession raced: the writes ran
     * concurrently on [Dispatchers.Default] and whichever finished last won, regardless of which
     * transition actually happened last. A stop landing after a start persisted
     * `lastStartTime = null` and published `isRunning = false` while the tick loop was counting,
     * and a process death later in that session then recovered the whole session as "not running"
     * and discarded it. Running inline makes the collector's own sequencing the ordering
     * guarantee — one transition completes before the next is observed — with no shared-state
     * locking to get wrong. See UsageTimerConcurrencyTest.
     */
    private suspend fun startTimer() {
        if (tickJob?.isActive == true) return // Already running

        // Midnight may have passed with nothing running at all — the ordinary case, since the
        // usual pattern is to stop for the night and come back the next day. The loop below only
        // ever observes a rollover that happens mid-session, so without this check yesterday's
        // total silently becomes today's starting value.
        rollOverIfNewDay()

        // Save start time immediately
        saveUsageData(startTime = timeProvider.currentTimeMillis())

        _timerState.value = _timerState.value.copy(isRunning = true)

        tickJob = scope.launch {
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
     *
     * The suspending clear runs *before* either piece of in-memory state moves, so the whole
     * thing is effectively all-or-nothing: cancelled or thrown at the clear, [currentDate] still
     * holds the old day and the rollover simply happens again on the next tick. Advancing
     * [currentDate] first would instead mark the day as rolled over while storage still held
     * yesterday's row, and nothing would ever retry.
     */
    private suspend fun rollOverIfNewDay(): Boolean {
        val today = timeProvider.getCurrentDateString()
        if (today == currentDate) return false

        lockPreferences.clearUsageData()
        currentDate = today
        _timerState.value = _timerState.value.copy(elapsedMillisToday = 0L)
        return true
    }

    /** Ends a usage session. Suspends for the same reason [startTimer] does — see its doc. */
    private suspend fun stopTimer() {
        // cancelAndJoin, not cancel: the tick loop can be suspended mid-write, and letting it
        // finish unwinding before persisting the stop is what stops a half-completed tick from
        // landing on top of the record written below.
        tickJob?.cancelAndJoin()
        tickJob = null

        _timerState.value = _timerState.value.copy(isRunning = false)
        saveUsageData(stopTime = true)
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
