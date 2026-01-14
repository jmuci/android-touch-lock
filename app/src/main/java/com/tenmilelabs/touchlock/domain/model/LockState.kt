package com.tenmilelabs.touchlock.domain.model

sealed interface LockState {
    object Unlocked : LockState
    object Locked : LockState
}