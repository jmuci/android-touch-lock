package com.tenmilelabs.touchlock.platform.repository

import com.google.common.truth.Truth.assertThat
import com.tenmilelabs.touchlock.platform.datastore.LockPreferences
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** [ConfigRepositoryImpl] is a pure delegation layer over [LockPreferences] — verifies the wiring. */
class ConfigRepositoryImplTest {

    private val lockPreferences = mockk<LockPreferences>(relaxed = true)
    private val repository = ConfigRepositoryImpl(lockPreferences)

    @Test
    fun `observeDebugOverlayVisible delegates to LockPreferences`() {
        val flow = MutableStateFlow(true)
        every { lockPreferences.debugOverlayVisible } returns flow

        assertThat(repository.observeDebugOverlayVisible()).isSameInstanceAs(flow)
    }

    @Test
    fun `setDebugOverlayVisible delegates to LockPreferences`() = runTest {
        repository.setDebugOverlayVisible(true)

        coVerify { lockPreferences.setDebugOverlayVisible(true) }
    }

    @Test
    fun `observeBackstopTimeoutMinutes delegates to LockPreferences`() {
        val flow = MutableStateFlow(45)
        every { lockPreferences.backstopTimeoutMinutes } returns flow

        assertThat(repository.observeBackstopTimeoutMinutes()).isSameInstanceAs(flow)
    }

    @Test
    fun `setBackstopTimeoutMinutes delegates to LockPreferences`() = runTest {
        repository.setBackstopTimeoutMinutes(45)

        coVerify { lockPreferences.setBackstopTimeoutMinutes(45) }
    }
}
