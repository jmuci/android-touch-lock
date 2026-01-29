package com.tenmilelabs.touchlock.domain.usecase.fakes

import com.tenmilelabs.touchlock.platform.time.TimeProvider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Fake clock for deterministic testing.
 * Allows tests to control time progression and date changes.
 */
class FakeClock : TimeProvider {
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    
    // Current simulated time in milliseconds since epoch
    private var currentTime: Long = 0L

    /**
     * Sets the current time to a specific value.
     * @param millis Time in milliseconds since epoch
     */
    fun setTimeMillis(millis: Long) {
        currentTime = millis
    }

    /**
     * Advances time by the specified duration.
     * @param millis Duration to advance in milliseconds
     */
    fun advanceTimeBy(millis: Long) {
        currentTime += millis
    }

    /**
     * Sets the current date using a string in yyyy-MM-dd format.
     * Time is set to midnight (00:00:00).
     */
    fun setDate(dateString: String) {
        val date = dateFormat.parse(dateString)
        currentTime = date?.time ?: 0L
    }

    override fun currentTimeMillis(): Long {
        return currentTime
    }

    override fun getCurrentDateString(): String {
        return dateFormat.format(Date(currentTime))
    }
}
