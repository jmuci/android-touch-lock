package com.tenmilelabs.touchlock.platform.permission

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.view.accessibility.AccessibilityManager
import com.google.common.truth.Truth.assertThat
import com.tenmilelabs.touchlock.platform.accessibility.TouchLockAccessibilityService
import io.mockk.every
import io.mockk.mockk
import org.junit.Test

class AccessibilityPermissionManagerTest {

    private fun serviceInfo(packageName: String, className: String): AccessibilityServiceInfo {
        val realServiceInfo = ServiceInfo().apply {
            this.packageName = packageName
            this.name = className
        }
        val realResolveInfo = ResolveInfo().apply {
            this.serviceInfo = realServiceInfo
        }
        return mockk<AccessibilityServiceInfo>(relaxed = true) {
            every { resolveInfo } returns realResolveInfo
        }
    }

    private fun managerWith(enabledServices: List<AccessibilityServiceInfo>): AccessibilityPermissionManager {
        val accessibilityManager = mockk<AccessibilityManager>(relaxed = true) {
            every { getEnabledAccessibilityServiceList(any()) } returns enabledServices
        }
        val context = mockk<Context>(relaxed = true) {
            every { getSystemService(Context.ACCESSIBILITY_SERVICE) } returns accessibilityManager
            every { packageName } returns OUR_PACKAGE
        }
        return AccessibilityPermissionManager(context)
    }

    @Test
    fun `isEnabled returns false when no services are enabled`() {
        val manager = managerWith(emptyList())

        assertThat(manager.isEnabled()).isFalse()
    }

    @Test
    fun `isEnabled returns false when a different service is enabled`() {
        val manager = managerWith(listOf(serviceInfo("com.other.app", "com.other.app.SomeService")))

        assertThat(manager.isEnabled()).isFalse()
    }

    @Test
    fun `isEnabled returns true when our service is in the enabled list`() {
        val manager = managerWith(
            listOf(
                serviceInfo("com.other.app", "com.other.app.SomeService"),
                serviceInfo(OUR_PACKAGE, OUR_SERVICE_CLASS)
            )
        )

        assertThat(manager.isEnabled()).isTrue()
    }

    companion object {
        private const val OUR_PACKAGE = "com.tenmilelabs.touchlock"
        private val OUR_SERVICE_CLASS = TouchLockAccessibilityService::class.java.name
    }
}
