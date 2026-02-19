package com.tenmilelabs.touchlock.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.tenmilelabs.touchlock.platform.permission.NotificationPermissionManager
import com.tenmilelabs.touchlock.platform.permission.OverlayPermissionManager
import com.tenmilelabs.touchlock.service.LockOverlayService
import com.tenmilelabs.touchlock.ui.screens.home.HomeViewModel
import com.tenmilelabs.touchlock.ui.screens.home.HomeScreen
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var overlayPermissionManager: OverlayPermissionManager

    @Inject
    lateinit var notificationPermissionManager: NotificationPermissionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.d("MainActivity.onCreate() called")

        setContent {
            val viewModel: HomeViewModel = hiltViewModel()

            HomeScreen(
                viewModel = viewModel,
                onRequestOverlayPermission = {
                    Timber.d("onRequestOverlayPermission clicked, starting settings")
                    startActivity(overlayPermissionManager.createSettingsIntent())
                },
                onRequestNotificationPermission = {
                    Timber.d("onRequestNotificationPermission clicked, starting settings")
                    startActivity(notificationPermissionManager.createNotificationSettingsIntent())
                }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        Timber.d("MainActivity.onResume() called, restoring service notification")
        // Restore the foreground service notification in case it was dismissed while app was in background
        // This ensures the service maintains foreground status on Android 12+ where users can force-dismiss notifications
        val restoreIntent = Intent(this, LockOverlayService::class.java).apply {
            action = LockOverlayService.ACTION_RESTORE_NOTIFICATION
        }
        startService(restoreIntent)
    }
}