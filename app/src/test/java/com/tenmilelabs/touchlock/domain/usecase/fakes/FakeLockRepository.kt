package com.tenmilelabs.touchlock.domain.usecase.fakes

import com.tenmilelabs.touchlock.domain.model.LockState
import com.tenmilelabs.touchlock.domain.repository.LockRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Fake implementation of LockRepository for testing.
 * 
 * Features:
 * - Tracks call counts for all methods
 * - Allows manual state control via emitLockState()
 * - Optionally auto-updates state when startLock/stopLock/startDelayedLock are called
 */
class FakeLockRepository(
    private val autoUpdateState: Boolean = true
) : LockRepository {
    
    private val lockStateFlow = MutableStateFlow<LockState>(LockState.Unlocked)
    
    var startLockCallCount = 0
        private set
    
    var stopLockCallCount = 0
        private set
    
    var startDelayedLockCallCount = 0
        private set
    
    var restoreNotificationCallCount = 0
        private set

    override fun observeLockState(): Flow<LockState> = lockStateFlow

    override fun startLock() {
        startLockCallCount++
        if (autoUpdateState) {
            lockStateFlow.value = LockState.Locked
        }
    }

    override fun stopLock() {
        stopLockCallCount++
        if (autoUpdateState) {
            lockStateFlow.value = LockState.Unlocked
        }
    }

    override fun startDelayedLock() {
        startDelayedLockCallCount++
        if (autoUpdateState) {
            lockStateFlow.value = LockState.Locked
        }
    }

    override fun restoreNotification() {
        restoreNotificationCallCount++
    }

    /**
     * Manually emit a lock state for testing.
     * This always updates the state regardless of autoUpdateState setting.
     */
    fun emitLockState(state: LockState) {
        lockStateFlow.value = state
    }
    
    /**
     * Reset all call counters to zero.
     * Useful when reusing the same fake across multiple test cases.
     */
    fun resetCallCounts() {
        startLockCallCount = 0
        stopLockCallCount = 0
        startDelayedLockCallCount = 0
        restoreNotificationCallCount = 0
    }
}
