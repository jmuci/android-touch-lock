package com.tenmilelabs.touchlock.platform.time

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Tests for [SystemTimeProvider].
 *
 * Verifies the C1 fix: replacing SimpleDateFormat (not thread-safe) with
 * java.time.LocalDate.now().toString() (thread-safe).
 *
 * The key property under test is that concurrent calls to getCurrentDateString()
 * always return well-formed ISO-8601 date strings (yyyy-MM-dd) and never produce
 * corrupted output from interleaved internal state.
 */
class SystemTimeProviderTest {

    private val provider = SystemTimeProvider()

    @Test
    fun `getCurrentDateString returns ISO-8601 date format`() {
        val dateString = provider.getCurrentDateString()

        // Must match yyyy-MM-dd pattern
        assertThat(dateString).matches("\\d{4}-\\d{2}-\\d{2}")
    }

    @Test
    fun `getCurrentDateString is consistent across sequential calls`() {
        val first = provider.getCurrentDateString()
        val second = provider.getCurrentDateString()

        // Both calls within the same test should return the same date
        assertThat(first).isEqualTo(second)
    }

    @Test
    fun `currentTimeMillis returns positive value`() {
        val time = provider.currentTimeMillis()
        assertThat(time).isGreaterThan(0L)
    }

    @Test
    fun `currentTimeMillis is monotonically non-decreasing`() {
        val first = provider.currentTimeMillis()
        val second = provider.currentTimeMillis()
        assertThat(second).isAtLeast(first)
    }

    /**
     * Regression test for C1: SimpleDateFormat thread-safety bug.
     *
     * SimpleDateFormat.format() uses shared internal Calendar state. When called
     * concurrently from multiple threads, the Calendar fields get corrupted mid-format,
     * producing malformed date strings (wrong month, wrong day, wrong year, or
     * truncated output).
     *
     * This test hammers getCurrentDateString() from 8 threads with 500 iterations each.
     * With the old SimpleDateFormat implementation, this reliably produces corrupted
     * output (e.g., "2024-01-152024", "2024-13-01", empty strings).
     * With LocalDate.now().toString(), this is inherently safe.
     */
    @Test
    fun `getCurrentDateString is thread-safe under concurrent access`() {
        val threadCount = 8
        val iterationsPerThread = 500
        val executor = Executors.newFixedThreadPool(threadCount)
        val startLatch = CountDownLatch(1) // ensures all threads start simultaneously
        val results = ConcurrentHashMap.newKeySet<String>()
        val errors = ConcurrentHashMap.newKeySet<String>()
        val datePattern = Regex("\\d{4}-\\d{2}-\\d{2}")

        repeat(threadCount) {
            executor.submit {
                startLatch.await() // wait for all threads to be ready
                repeat(iterationsPerThread) {
                    val dateString = provider.getCurrentDateString()
                    results.add(dateString)
                    if (!datePattern.matches(dateString)) {
                        errors.add(dateString)
                    }
                }
            }
        }

        // Release all threads at once to maximize contention
        startLatch.countDown()

        executor.shutdown()
        executor.awaitTermination(10, TimeUnit.SECONDS)

        // All results must be valid dates — no corrupted output
        assertThat(errors).isEmpty()

        // All threads should have produced the same date (test runs within a single day)
        assertThat(results).hasSize(1)
    }
}
