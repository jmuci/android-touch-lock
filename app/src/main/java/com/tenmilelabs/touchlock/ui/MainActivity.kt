package com.tenmilelabs.touchlock.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.tenmilelabs.touchlock.platform.permission.AccessibilityPermissionManager
import com.tenmilelabs.touchlock.platform.permission.NotificationPermissionManager
import com.tenmilelabs.touchlock.platform.permission.OverlayPermissionManager
import com.tenmilelabs.touchlock.service.LockOverlayService
import com.tenmilelabs.touchlock.ui.screens.home.HomeScreen
import com.tenmilelabs.touchlock.ui.screens.home.HomeViewModel
import com.tenmilelabs.touchlock.ui.theme.TouchLockTheme
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var overlayPermissionManager: OverlayPermissionManager

    @Inject
    lateinit var notificationPermissionManager: NotificationPermissionManager

    @Inject
    lateinit var accessibilityPermissionManager: AccessibilityPermissionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.d("MainActivity.onCreate() called")
        // targetSdk 35+ draws edge-to-edge whether or not we opt in, so opt in explicitly: this is
        // what picks light/dark system bar icons to contrast with the content behind them.
        // Composables inset themselves via safeDrawingPadding().
        enableEdgeToEdge()
        startLockService()

        setContent {
            val viewModel: HomeViewModel = hiltViewModel()

            // Surface supplies the themed background colour. Without it the content draws straight
            // onto the window background, which is light in every configuration, so dark-mode text
            // would be light-on-light.
            TouchLockTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    HomeScreen(
                        viewModel = viewModel,
                        onRequestOverlayPermission = {
                            Timber.d("onRequestOverlayPermission clicked, starting settings")
                            startActivity(overlayPermissionManager.createSettingsIntent())
                        },
                        onRequestNotificationPermission = {
                            Timber.d("onRequestNotificationPermission clicked, starting settings")
                            startActivity(notificationPermissionManager.createNotificationSettingsIntent())
                        },
                        onRequestAccessibilityPermission = {
                            Timber.d("onRequestAccessibilityPermission clicked, starting settings")
                            startActivity(accessibilityPermissionManager.createSettingsIntent())
                        }
                    )
                }
            }
        }
    }

    /**
     * Starts the lock service from a foreground context (Activity), which is required on
     * Android 12+ to avoid ForegroundServiceStartNotAllowedException. Starting from
     * Application.onCreate() is unsafe because the process may be created in the background.
     */
    private fun startLockService() {
        val intent = Intent(this, LockOverlayService::class.java).apply {
            action = LockOverlayService.ACTION_INIT
        }
        ContextCompat.startForegroundService(this, intent)
    }

    override fun onResume() {
        super.onResume()
        Timber.d("MainActivity.onResume() called, restoring service notification")
        // Restore the foreground service notification in case it was dismissed while app was in background
        // This ensures the service maintains foreground status on Android 12+ where users can force-dismiss notifications
        val restoreIntent = Intent(this, LockOverlayService::class.java).apply {
            action = LockOverlayService.ACTION_RESTORE_NOTIFICATION
        }
        try {
            startService(restoreIntent)
        } catch (e: IllegalStateException) {
            Timber.w(e, "Could not restore notification - service may not be running")
        }
    }
}