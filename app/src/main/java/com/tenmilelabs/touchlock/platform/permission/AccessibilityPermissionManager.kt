package com.tenmilelabs.touchlock.platform.permission

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.tenmilelabs.touchlock.platform.accessibility.TouchLockAccessibilityService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AccessibilityPermissionManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) {

    private val accessibilityManager: AccessibilityManager
        get() = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager

    fun isEnabled(): Boolean {
        val enabledServices =
            accessibilityManager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        return enabledServices.any {
            it.resolveInfo.serviceInfo.packageName == context.packageName &&
                it.resolveInfo.serviceInfo.name == TouchLockAccessibilityService::class.java.name
        }
    }

    fun createSettingsIntent(): Intent {
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
