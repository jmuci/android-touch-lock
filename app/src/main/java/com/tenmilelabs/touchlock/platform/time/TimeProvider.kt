package com.tenmilelabs.touchlock.platform.time

import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Abstraction for time-related operations.
 * Allows deterministic testing of time-dependent behavior.
 */
interface TimeProvider {
    /**
     * Returns the current time in milliseconds since epoch.
     */
    fun currentTimeMillis(): Long

    /**
     * Returns the current date formatted as yyyy-MM-dd.
     */
    fun getCurrentDateString(): String
}

/**
 * Production implementation using system time.
 */
@Singleton
class SystemTimeProvider @Inject constructor() : TimeProvider {
    override fun currentTimeMillis(): Long {
        return System.currentTimeMillis()
    }

    override fun getCurrentDateString(): String {
        return LocalDate.now().toString()
    }
}
