package com.tenmilelabs.touchlock.platform.time

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    override fun currentTimeMillis(): Long {
        return System.currentTimeMillis()
    }

    override fun getCurrentDateString(): String {
        return dateFormat.format(Date())
    }
}
