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
        Timber.d("TL::lifecycle MainActivity.onCreate() - Activity created, savedInstanceState=${savedInstanceState != null}")

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

    override fun onStart() {
        super.onStart()
        Timber.d("TL::lifecycle MainActivity.onStart() - Activity becoming visible")
    }

    override fun onResume() {
        super.onResume()
        Timber.d("TL::lifecycle MainActivity.onResume() - Activity in foreground, interactive")
    }

    override fun onPause() {
        super.onPause()
        Timber.d("TL::lifecycle MainActivity.onPause() - Activity losing focus, partially visible")
    }

    override fun onStop() {
        super.onStop()
        Timber.d("TL::lifecycle MainActivity.onStop() - Activity no longer visible")
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.d("TL::lifecycle MainActivity.onDestroy() - Activity being destroyed, isFinishing=$isFinishing")
    }

    override fun onRestart() {
        super.onRestart()
        Timber.d("TL::lifecycle MainActivity.onRestart() - Activity restarting after onStop()")
    }
}