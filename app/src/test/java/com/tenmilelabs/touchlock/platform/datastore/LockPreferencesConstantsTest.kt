package com.tenmilelabs.touchlock.platform.datastore

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins the backstop-timeout safety-valve bounds. No DataStore I/O involved (these are plain
 * companion constants), so this runs as a fast JVM unit test rather than alongside the real
 * DataStore round-trip coverage in the instrumented `LockPreferencesTest`.
 */
class LockPreferencesConstantsTest {

    @Test
    fun `default backstop timeout is 60 minutes`() {
        assertThat(LockPreferences.DEFAULT_BACKSTOP_TIMEOUT_MINUTES).isEqualTo(60)
    }

    @Test
    fun `max backstop timeout is 120 minutes`() {
        assertThat(LockPreferences.MAX_BACKSTOP_TIMEOUT_MINUTES).isEqualTo(120)
    }

    @Test
    fun `default is within the allowed clamp range`() {
        assertThat(LockPreferences.DEFAULT_BACKSTOP_TIMEOUT_MINUTES).isAtLeast(1)
        assertThat(LockPreferences.DEFAULT_BACKSTOP_TIMEOUT_MINUTES).isAtMost(LockPreferences.MAX_BACKSTOP_TIMEOUT_MINUTES)
    }
}
