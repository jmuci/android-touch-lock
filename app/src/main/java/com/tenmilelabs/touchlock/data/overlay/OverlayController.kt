package com.tenmilelabs.touchlock.data.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import com.tenmilelabs.touchlock.data.overlay.UnlockHandleView.Companion.HANDLE_SIZE_DP
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OverlayController @Inject constructor(
    @param:ApplicationContext private val context: Context
) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val handler = Handler(Looper.getMainLooper())

    private var overlayView: OverlayView? = null
    private var unlockHandleView: UnlockHandleView? = null

    private val hideHandleRunnable = Runnable {
        hideUnlockHandle()
    }

    fun show(onUnlockRequested: () -> Unit) {
        if (overlayView != null) return

        overlayView = OverlayView(
            context = context,
            onUnlockRequested = onUnlockRequested,
            onDoubleTapDetected = {
                showUnlockHandle(onUnlockRequested)
            }
        )
        windowManager.addView(overlayView, fullScreenLayoutParams())
    }

    fun hide() {
        // Clean up unlock handle first
        hideUnlockHandle()
        handler.removeCallbacks(hideHandleRunnable)

        // Clean up main overlay
        overlayView?.let {
            it.cleanup()
            windowManager.removeView(it)
        }
        overlayView = null
    }

    private fun showUnlockHandle(onUnlockRequested: () -> Unit) {
        // Remove existing handle if present
        hideUnlockHandle()

        unlockHandleView = UnlockHandleView(context) {
            // When unlock is requested from handle
            onUnlockRequested()
        }

        windowManager.addView(unlockHandleView, handleLayoutParams())

        // Auto-hide after timeout
        handler.postDelayed(hideHandleRunnable, HANDLE_VISIBILITY_TIMEOUT_MS)
    }

    private fun hideUnlockHandle() {
        unlockHandleView?.let {
            it.cleanup()
            windowManager.removeView(it)
        }
        unlockHandleView = null
        handler.removeCallbacks(hideHandleRunnable)
    }

    private fun fullScreenLayoutParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
    }

    private fun handleLayoutParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            HANDLE_SIZE_DP.toInt(),
            HANDLE_SIZE_DP.toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }
    }

    companion object {
        private const val HANDLE_VISIBILITY_TIMEOUT_MS = 4000L // 4 seconds
    }
}

