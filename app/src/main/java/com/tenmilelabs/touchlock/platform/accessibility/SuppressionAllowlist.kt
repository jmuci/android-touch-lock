package com.tenmilelabs.touchlock.platform.accessibility

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.provider.Settings
import android.telecom.TelecomManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Packages that must always stay reachable while locked: never suppressed (BACK consumption,
 * shade dismissal) and never snapped away from. Resolved dynamically via
 * PackageManager/TelecomManager where possible so it adapts to the device's actual Settings /
 * dialer / clock apps instead of hardcoding package names that vary by OEM.
 */
@Singleton
class SuppressionAllowlist @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val allowlistedPackages: Set<String> by lazy { buildAllowlist() }

    fun isAllowlisted(packageName: String?): Boolean {
        if (packageName == null) return false
        return packageName in allowlistedPackages
    }

    private fun buildAllowlist(): Set<String> {
        val packages = mutableSetOf(context.packageName)

        resolveActivityPackage(Intent(Settings.ACTION_SETTINGS))?.let { packages += it }
        resolveActivityPackage(Intent(AlarmClock.ACTION_SHOW_ALARMS))?.let { packages += it }

        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        telecomManager?.defaultDialerPackage?.let { packages += it }
        // The in-call UI isn't reliably resolvable via a public intent; the default dialer above
        // covers the common case where the dialer app also hosts the in-call screen.

        // Cell broadcast / emergency alerts: no single cross-OEM resolvable intent, so list the
        // known AOSP/OEM package names directly.
        packages += KNOWN_EMERGENCY_ALERT_PACKAGES

        return packages
    }

    private fun resolveActivityPackage(intent: Intent): String? {
        return try {
            intent.resolveActivity(context.packageManager)?.packageName
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private val KNOWN_EMERGENCY_ALERT_PACKAGES = setOf(
            "com.android.cellbroadcastreceiver",
            "com.google.android.cellbroadcastreceiver",
            "com.android.cellbroadcastservice"
        )
    }
}
