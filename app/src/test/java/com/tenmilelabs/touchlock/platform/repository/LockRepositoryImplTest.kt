package com.tenmilelabs.touchlock.platform.repository

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.tenmilelabs.touchlock.service.LockOverlayService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Before
import org.junit.Test

/**
 * Tests for [LockRepositoryImpl].
 *
 * Verifies the C2 fix:
 * - startDelayedLock() catches IllegalStateException from service start
 *   (prevents ForegroundServiceStartNotAllowedException crash on Android 12+)
 * - restoreNotification() uses startService() instead of startForegroundService()
 *   (avoids background start restriction since service is already running)
 * - Both methods fail gracefully without crashing
 *
 * Note: In JVM unit tests, Build.VERSION.SDK_INT is 0, so ContextCompat.startForegroundService()
 * delegates to context.startService(). Intent action values cannot be asserted in pure JVM
 * because Intent.setAction()/getAction() are stub methods (returnDefaultValues = true).
 * Intent action correctness is verified by code inspection and instrumented tests.
 *
 * Requires `unitTests.isReturnDefaultValues = true` in build.gradle.kts.
 */
class LockRepositoryImplTest {

    private lateinit var mockContext: Context
    private lateinit var repository: LockRepositoryImpl

    @Before
    fun setup() {
        mockContext = mockk(relaxed = true) {
            every { packageName } returns "com.tenmilelabs.touchlock"
            every { applicationContext } returns this
        }
        repository = LockRepositoryImpl(mockContext)
    }

    // -----------------------------------------------------------------------
    // startDelayedLock() — crash safety (C2 fix)
    // -----------------------------------------------------------------------

    @Test
    fun `startDelayedLock calls startService`() {
        repository.startDelayedLock()

        // ContextCompat.startForegroundService delegates to startService in JVM tests
        verify(exactly = 1) { mockContext.startService(any()) }
    }

    @Test
    fun `startDelayedLock does not crash when service start throws IllegalStateException`() {
        // Simulates ForegroundServiceStartNotAllowedException (extends IllegalStateException)
        // on Android 12+ when app is in background
        every { mockContext.startService(any()) } throws IllegalStateException(
            "startForegroundService() not allowed"
        )
        every { mockContext.startForegroundService(any()) } throws IllegalStateException(
            "startForegroundService() not allowed"
        )

        // Must not throw — the try-catch should swallow the exception
        repository.startDelayedLock()
    }

    @Test
    fun `startDelayedLock does not crash on ForegroundServiceStartNotAllowedException`() {
        // ForegroundServiceStartNotAllowedException is a subclass of IllegalStateException.
        // The try-catch in startDelayedLock catches IllegalStateException, covering both.
        every { mockContext.startService(any()) } throws IllegalStateException(
            "ForegroundServiceStartNotAllowedException: Service.startForeground() not called"
        )
        every { mockContext.startForegroundService(any()) } throws IllegalStateException(
            "ForegroundServiceStartNotAllowedException: Service.startForeground() not called"
        )

        repository.startDelayedLock()
    }

    // -----------------------------------------------------------------------
    // restoreNotification() — crash safety and method routing (C2 fix)
    // -----------------------------------------------------------------------

    @Test
    fun `restoreNotification uses startService not startForegroundService`() {
        repository.restoreNotification()

        // C2 fix: restoreNotification should use context.startService() since the
        // service is already running. Using startForegroundService() would crash on
        // Android 12+ if the app is in the background.
        verify(exactly = 1) { mockContext.startService(any()) }
        verify(exactly = 0) { mockContext.startForegroundService(any()) }
    }

    @Test
    fun `restoreNotification does not crash when startService throws IllegalStateException`() {
        every { mockContext.startService(any()) } throws IllegalStateException(
            "Not allowed to start service"
        )

        // Must not throw — the try-catch should swallow the exception
        repository.restoreNotification()
    }

    // -----------------------------------------------------------------------
    // observeLockState()
    // -----------------------------------------------------------------------

    @Test
    fun `observeLockState returns the service companion lockState flow`() {
        val flow = repository.observeLockState()
        // Must return the same instance as the service's companion object flow
        assertThat(flow).isSameInstanceAs(LockOverlayService.lockState)
    }
}
