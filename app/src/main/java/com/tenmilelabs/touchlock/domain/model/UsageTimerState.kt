package com.tenmilelabs.touchlock.domain.model

/**
 * Represents the current state of the daily usage timer.
 * 
 * @property elapsedMillisToday Total milliseconds the lock has been active today
 * @property isRunning Whether the timer is currently running (lock is active)
 */
data class UsageTimerState(
    val elapsedMillisToday: Long = 0L,
    val isRunning: Boolean = false
) {
    companion object {
        val INITIAL = UsageTimerState(elapsedMillisToday = 0L, isRunning = false)
    }
}
