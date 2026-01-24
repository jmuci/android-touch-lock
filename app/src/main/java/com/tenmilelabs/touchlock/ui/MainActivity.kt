package com.tenmilelabs.touchlock.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.tenmilelabs.touchlock.platform.permission.NotificationPermissionManager
import com.tenmilelabs.touchlock.platform.permission.OverlayPermissionManager
import com.tenmilelabs.touchlock.ui.screens.home.HomeViewModel
import com.tenmilelabs.touchlock.ui.screens.home.HomeScreen
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var overlayPermissionManager: OverlayPermissionManager

    @Inject
    lateinit var notificationPermissionManager: NotificationPermissionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val viewModel: HomeViewModel = hiltViewModel()

            HomeScreen(
                viewModel = viewModel,
                onRequestOverlayPermission = {
                    startActivity(overlayPermissionManager.createSettingsIntent())
                },
                onRequestNotificationPermission = {
                    startActivity(notificationPermissionManager.createNotificationSettingsIntent())
                }
            )
        }
    }
}