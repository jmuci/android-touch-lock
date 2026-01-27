package com.tenmilelabs.touchlock.ui

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import com.tenmilelabs.touchlock.domain.model.OrientationMode

/**
 * Transparent Activity used solely to lock screen orientation when Touch Lock is active.
 * 
 * Since overlays (TYPE_APPLICATION_OVERLAY) cannot reliably force system rotation,
 * this Activity provides a transparent background that locks the orientation
 * according to the user's selected mode.
 * 
 * The overlay is shown on top of this Activity to block touches.
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
