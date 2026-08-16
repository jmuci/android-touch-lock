package com.tenmilelabs.touchlock.platform.permission

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
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

    /**
     * Unlike [Settings.ACTION_MANAGE_OVERLAY_PERMISSION], the Accessibility Settings screen has no
     * public, documented way to deep-link to (or highlight) a specific service. These
     * ":settings:*" extras are an undocumented convention some AOSP-derived Settings builds honor
     * to jump straight to a service's own toggle screen -- not a supported contract, so this is
     * best-effort: OEMs that ignore it simply fall back to today's behavior, the plain top-level
     * accessibility list. Never rely on this landing correctly; the in-app disclosure screen
     * ([AccessibilityDisclosureScreen]) carries the guidance that actually has to work everywhere
     * -- a floating overlay drawn on top of this screen was tried and dropped, since Android
     * suppresses/blocks SYSTEM_ALERT_WINDOW overlays around the accessibility-grant flow
     * specifically, as an anti-tapjacking measure.
     */
    fun createSettingsIntent(): Intent {
        val componentName = ComponentName(context, TouchLockAccessibilityService::class.java)
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(EXTRA_FRAGMENT_ARG_KEY, componentName.flattenToString())
            putExtra(
                EXTRA_SHOW_FRAGMENT_ARGUMENTS,
                Bundle().apply { putString(EXTRA_FRAGMENT_ARG_KEY, componentName.flattenToString()) }
            )
        }
    }

    companion object {
        private const val EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
        private const val EXTRA_SHOW_FRAGMENT_ARGUMENTS = ":settings:show_fragment_args"
    }
}
