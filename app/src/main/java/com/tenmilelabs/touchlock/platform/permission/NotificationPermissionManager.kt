package com.tenmilelabs.touchlock.platform.permission

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.tenmilelabs.touchlock.R
import com.tenmilelabs.touchlock.platform.notification.LockNotificationManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages notification permission state and provides recovery actions.
 * Detects when notifications are blocked at app or channel level.
 */
@Singleton
class NotificationPermissionManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) {

    /**
     * Checks if notifications are fully available for Touch Lock.
     * Returns false if:
     * - App-level notifications are disabled
     * - Touch Lock channel is blocked or has IMPORTANCE_NONE
     */
    fun areNotificationsAvailable(): Boolean {
        // Check app-level notification permission
        if (!areAppNotificationsEnabled()) {
            return false
        }

        if (!isChannelAvailable()) {
            return false
        }

        return true
    }

    /**
     * Checks if app-level notifications are enabled.
     */
    private fun areAppNotificationsEnabled(): Boolean {
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /**
     * Checks if the Touch Lock notification channel is available and visible.
     * Only relevant on API 26+ where channels exist.
     */
    private fun isChannelAvailable(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return true
        }

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = notificationManager.getNotificationChannel(LockNotificationManager.CHANNEL_ID)

        return when {
            channel == null -> {
                // Channel doesn't exist yet - will be created on first use
                true
            }

            channel.importance == NotificationManager.IMPORTANCE_NONE -> {
                // Channel is explicitly blocked
                false
            }

            else -> {
                // Channel exists and is not blocked
                true
            }
        }
    }

    /**
     * Creates an intent to open notification settings for the app.
     * On API 26+, this opens the app notification settings showing all channels.
     * Below API 26, opens app details settings.
     */
    fun createNotificationSettingsIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Open app notification settings (includes all channels)
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            // Fallback for pre-Oreo devices
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    /**
     * Describes the current notification availability issue for user-facing messages.
     */
    fun getNotificationIssueDescription(): String {
        return when {
            !areAppNotificationsEnabled() -> {
                context.getString(R.string.notifications_disabled_availability)
            }

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !isChannelAvailable() -> {
                context.getString(R.string.notifications_availability_channel_blocked)
            }

            else -> {
                context.getString(R.string.notifications_availability_enabled)
            }
        }
    }
}
