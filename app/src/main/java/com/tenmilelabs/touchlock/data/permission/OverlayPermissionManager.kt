package com.tenmilelabs.touchlock.data.permission

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OverlayPermissionManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) {

    fun hasPermission(): Boolean {
        return Settings.canDrawOverlays(context)
    }

    fun createSettingsIntent(): Intent {
        return Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            "package:${context.packageName}".toUri()
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun requirePermission(context: Context) {
        if (!hasPermission()) {
            context.startActivity(createSettingsIntent())
        }
    }
}