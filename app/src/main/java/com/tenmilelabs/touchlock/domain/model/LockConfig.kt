package com.tenmilelabs.touchlock.domain.model

data class LockConfig(
    val orientation: OrientationMode,
    val unlockType: UnlockType
)

enum class OrientationMode(val title: String) {
    PORTRAIT("Portrait"),
    LANDSCAPE("Landscape"),
    FOLLOW_SYSTEM("Auto")
}

enum class UnlockType {
    LONG_PRESS, DOUBLE_TAP_HOLD
}