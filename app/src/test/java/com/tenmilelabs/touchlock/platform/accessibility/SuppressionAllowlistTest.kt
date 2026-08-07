package com.tenmilelabs.touchlock.platform.accessibility

import android.content.Context
import android.content.pm.PackageManager
import android.telecom.TelecomManager
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.Test

/**
 * Note: `Intent#resolveActivity(PackageManager)` — used internally to resolve the actual
 * Settings/Clock app packages — is one of the Android SDK "Stub!" methods that
 * `unitTests.isReturnDefaultValues = true` short-circuits to a default value in pure JVM tests,
 * so it never reaches our mocked PackageManager here. Only what's genuinely exercised in this
 * environment is covered: the own-package/emergency-package static entries, and
 * TelecomManager#defaultDialerPackage (a plain interface-shaped mock, not a stubbed SDK method
 * body). Settings/Clock resolution needs to be verified on-device.
 */
class SuppressionAllowlistTest {

    private fun allowlistWith(
        ownPackage: String = "com.tenmilelabs.touchlock",
        dialerPackage: String? = "com.google.android.dialer",
    ): SuppressionAllowlist {
        val packageManager = mockk<PackageManager>(relaxed = true)
        val telecomManager = mockk<TelecomManager>(relaxed = true) {
            every { defaultDialerPackage } returns dialerPackage
        }
        val context = mockk<Context>(relaxed = true) {
            every { this@mockk.packageName } returns ownPackage
            every { this@mockk.packageManager } returns packageManager
            every { getSystemService(Context.TELECOM_SERVICE) } returns telecomManager
        }
        return SuppressionAllowlist(context)
    }

    @Test
    fun `own package is always allowlisted`() {
        val allowlist = allowlistWith(ownPackage = "com.tenmilelabs.touchlock")
        assertThat(allowlist.isAllowlisted("com.tenmilelabs.touchlock")).isTrue()
    }

    @Test
    fun `default dialer package is allowlisted`() {
        val allowlist = allowlistWith(dialerPackage = "com.google.android.dialer")
        assertThat(allowlist.isAllowlisted("com.google.android.dialer")).isTrue()
    }

    @Test
    fun `known emergency alert packages are allowlisted`() {
        val allowlist = allowlistWith()
        assertThat(allowlist.isAllowlisted("com.android.cellbroadcastreceiver")).isTrue()
        assertThat(allowlist.isAllowlisted("com.google.android.cellbroadcastreceiver")).isTrue()
    }

    @Test
    fun `unrelated package is not allowlisted`() {
        val allowlist = allowlistWith()
        assertThat(allowlist.isAllowlisted("com.google.android.youtube")).isFalse()
    }

    @Test
    fun `null package is not allowlisted`() {
        val allowlist = allowlistWith()
        assertThat(allowlist.isAllowlisted(null)).isFalse()
    }
}
