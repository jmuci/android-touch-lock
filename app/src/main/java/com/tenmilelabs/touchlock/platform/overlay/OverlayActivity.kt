package com.tenmilelabs.touchlock.platform.overlay

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
 * Fullscreen Activity that hosts the touch-blocking overlay.
 * 
 * Why an Activity is required:
 * - TYPE_APPLICATION_OVERLAY windows cannot hide system UI or lock orientation
 * - Only Activities can control window.decorView.systemUiVisibility and requestedOrientation
 * 
 * Architecture:
 * - This Activity IS the overlay (not a separate WindowManager overlay)
 * - Simplified lifecycle: Activity lifecycle = Overlay lifecycle
 * - No WindowManager complexity
 * 
 * Lifecycle:
 * - Started by LockOverlayService when lock is enabled
 * - Finishes when lock is disabled via ACTION_STOP broadcast
 */
class OverlayActivity : Activity() {

    private var overlayView: OverlayView? = null

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_STOP) {
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Extract configuration from intent
        val orientationMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra(EXTRA_ORIENTATION_MODE, OrientationMode::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getSerializableExtra(EXTRA_ORIENTATION_MODE) as? OrientationMode
        } ?: OrientationMode.FOLLOW_SYSTEM
        
        val debugTintVisible = intent.getBooleanExtra(EXTRA_DEBUG_TINT, false)
        
        // Set screen orientation
        requestedOrientation = when (orientationMode) {
            OrientationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            OrientationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            OrientationMode.FOLLOW_SYSTEM -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        
        // Create and set the overlay as the Activity's content view
        overlayView = OverlayView(
            context = this,
            onUnlockRequested = {
                // Notify service to unlock via broadcast
                sendBroadcast(Intent(ACTION_UNLOCK_REQUESTED))
                finish()
            },
            onDoubleTapDetected = {
                // TODO: Show unlock handle when implemented
            },
            debugTintVisible = debugTintVisible
        )
        setContentView(overlayView)
        
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
        hideSystemUI()
    }
    
    /**
     * Hides system UI (status bar and navigation bar) using immersive mode.
     * Follows Android documentation recommendations using window.decorView.
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
            // Android 10 and below: Use decorView systemUiVisibility
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
        // Re-hide system UI when window regains focus (e.g., after notification shade)
        if (hasFocus) {
            hideSystemUI()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        overlayView?.cleanup()
        overlayView = null
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
        private const val EXTRA_DEBUG_TINT = "debug_tint"
        
        const val ACTION_STOP = "com.tenmilelabs.touchlock.OVERLAY_STOP"
        const val ACTION_UNLOCK_REQUESTED = "com.tenmilelabs.touchlock.UNLOCK_REQUESTED"
        
        fun createStartIntent(
            context: Context,
            orientationMode: OrientationMode,
            debugTintVisible: Boolean = false
        ): Intent {
            return Intent(context, OverlayActivity::class.java).apply {
                putExtra(EXTRA_ORIENTATION_MODE, orientationMode)
                putExtra(EXTRA_DEBUG_TINT, debugTintVisible)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
        }
    }
}
