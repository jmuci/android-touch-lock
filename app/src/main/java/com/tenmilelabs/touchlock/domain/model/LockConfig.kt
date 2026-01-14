package com.tenmilelabs.touchlock.domain.model

data class LockConfig(
    val orientation: OrientationMode,
    val unlockType: UnlockType
)

enum class OrientationMode {
    PORTRAIT, LANDSCAPE, FOLLOW_SYSTEM
}

enum class UnlockType {
    LONG_PRESS, DOUBLE_TAP_HOLD
}