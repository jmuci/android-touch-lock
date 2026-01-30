package com.tenmilelabs.touchlock.ui

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import com.tenmilelabs.touchlock.domain.model.OrientationMode

/**
 * Transparent fullscreen Activity used to:
 * 1. Hide system UI (status bar and navigation bar) to prevent gesture navigation
 * 2. Lock screen orientation (when not set to FOLLOW_SYSTEM)
 * 
 * Why an Activity is required:
 * TYPE_APPLICATION_OVERLAY windows cannot hide system UI or lock orientation due to Android
 * security restrictions. Only Activities can use window.decorView.systemUiVisibility and
 * requestedOrientation to control these behaviors.
 * 
 * Lifecycle:
 * - Started when lock is enabled (always, regardless of orientation mode)
 * - Finishes when lock is disabled via ACTION_STOP broadcast
 * - The overlay is shown on top of this Activity to block touches
 */
class OrientationLockActivity : Activity() {

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_STOP) {
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Set orientation based on intent extra
        val orientationMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra(EXTRA_ORIENTATION_MODE, OrientationMode::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getSerializableExtra(EXTRA_ORIENTATION_MODE) as? OrientationMode
        } ?: OrientationMode.FOLLOW_SYSTEM
        
        requestedOrientation = when (orientationMode) {
            OrientationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            OrientationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            OrientationMode.FOLLOW_SYSTEM -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        
        // Register receiver to listen for stop signal
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stopReceiver, IntentFilter(ACTION_STOP), RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(stopReceiver, IntentFilter(ACTION_STOP))
        }
    }
    
    override fun onResume() {
        super.onResume()
        // Hide system UI after window is fully initialized
        // window.insetsController is available after onResume()
        hideSystemUI()
    }
    
    /**
     * Hides system UI following Android documentation recommendations.
     * Uses window.decorView as per official guidance.
     */
    @Suppress("DEPRECATION")
    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ (API 30+): Use WindowInsetsController
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            // Android 10 and below: Use decorView systemUiVisibility as per Android docs
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }
    }
    
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Re-hide system UI when window regains focus (e.g., after notification shade is closed)
        if (hasFocus) {
            hideSystemUI()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(stopReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver already unregistered
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Prevent back button from closing this Activity
        // The overlay unlock mechanism should be used instead
    }

    companion object {
        private const val EXTRA_ORIENTATION_MODE = "orientation_mode"
        
        const val ACTION_START = "com.tenmilelabs.touchlock.ORIENTATION_LOCK_START"
        const val ACTION_STOP = "com.tenmilelabs.touchlock.ORIENTATION_LOCK_STOP"
        
        fun createStartIntent(context: Context, orientationMode: OrientationMode): Intent {
            return Intent(context, OrientationLockActivity::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_ORIENTATION_MODE, orientationMode)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
        }
    }
}
