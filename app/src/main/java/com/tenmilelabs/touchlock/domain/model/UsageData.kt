package com.tenmilelabs.touchlock.domain.model

/**
 * Data class representing stored usage data.
 */
data class UsageData(
    val date: String, // Format: yyyy-MM-dd
    val accumulatedMillis: Long,
    val lastStartTime: Long? // System.currentTimeMillis() when lock was started, null if stopped
)
