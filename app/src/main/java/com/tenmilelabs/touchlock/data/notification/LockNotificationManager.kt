package com.tenmilelabs.touchlock.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.tenmilelabs.touchlock.R
import com.tenmilelabs.touchlock.service.LockOverlayService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LockNotificationManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createNotificationChannel()
    }

    fun buildUnlockedNotification(): Notification {
        val toggleIntent = Intent(context, LockOverlayService::class.java).apply {
            action = LockOverlayService.ACTION_TOGGLE
        }

        val togglePendingIntent = PendingIntent.getService(
            context,
            0,
            toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_lock_open_24)
            .setContentTitle("Touch Lock ready")
            .setContentText("Tap to lock")
            .setContentIntent(togglePendingIntent)
            .setOngoing(true)
            .build()
    }

    fun buildLockedNotification(): Notification {
        val toggleIntent = Intent(context, LockOverlayService::class.java).apply {
            action = LockOverlayService.ACTION_TOGGLE
        }

        val togglePendingIntent = PendingIntent.getService(
            context,
            0,
            toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_lock_24)
            .setContentTitle("Touch Lock active")
            .setContentText("Tap to unlock")
            .setContentIntent(togglePendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Touch Lock",
            NotificationManager.IMPORTANCE_LOW
        )
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "touch_lock_channel"
    }
}
