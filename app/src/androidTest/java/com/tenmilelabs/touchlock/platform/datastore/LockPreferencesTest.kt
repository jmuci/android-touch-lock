package com.tenmilelabs.touchlock.platform.datastore

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented coverage for [LockPreferences]' backstop-timeout clamp — the "hard-clamped 1-120
 * min" safety valve described in the PR. Instrumented rather than a plain JVM unit test because
 * [LockPreferences] derives its DataStore from a `Context` extension property with no seam for
 * substituting a fake, and there's no Robolectric in this codebase; a real on-device Context (via
 * [ApplicationProvider]) is the only way to exercise the actual read/write/clamp path.
 *
 * Each test explicitly writes the value it reads back, rather than relying on an unset key
 * returning the default — the on-device DataStore file persists across test runs and isn't safely
 * clearable from here. The default value itself is pinned separately by the plain JVM
 * `LockPreferencesConstantsTest`.
 */
@RunWith(AndroidJUnit4::class)
class LockPreferencesTest {

    private lateinit var lockPreferences: LockPreferences

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        lockPreferences = LockPreferences(context)
    }

    @Test
    fun inRangeValueRoundTripsUnchanged() = runBlocking {
        lockPreferences.setBackstopTimeoutMinutes(45)

        assertThat(lockPreferences.backstopTimeoutMinutes.first()).isEqualTo(45)
    }

    @Test
    fun zeroIsClampedUpToOne() = runBlocking {
        lockPreferences.setBackstopTimeoutMinutes(0)

        assertThat(lockPreferences.backstopTimeoutMinutes.first()).isEqualTo(1)
    }

    @Test
    fun negativeValueIsClampedUpToOne() = runBlocking {
        lockPreferences.setBackstopTimeoutMinutes(-5)

        assertThat(lockPreferences.backstopTimeoutMinutes.first()).isEqualTo(1)
    }

    @Test
    fun valueAboveMaxIsClampedDownToMax() = runBlocking {
        lockPreferences.setBackstopTimeoutMinutes(999)

        assertThat(lockPreferences.backstopTimeoutMinutes.first())
            .isEqualTo(LockPreferences.MAX_BACKSTOP_TIMEOUT_MINUTES)
    }

    @Test
    fun lowerBoundValueRoundTripsUnchanged() = runBlocking {
        lockPreferences.setBackstopTimeoutMinutes(1)

        assertThat(lockPreferences.backstopTimeoutMinutes.first()).isEqualTo(1)
    }

    @Test
    fun upperBoundValueRoundTripsUnchanged() = runBlocking {
        lockPreferences.setBackstopTimeoutMinutes(LockPreferences.MAX_BACKSTOP_TIMEOUT_MINUTES)

        assertThat(lockPreferences.backstopTimeoutMinutes.first())
            .isEqualTo(LockPreferences.MAX_BACKSTOP_TIMEOUT_MINUTES)
    }
}
