package com.tenmilelabs.touchlock.data.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.widget.FrameLayout

class OverlayView(
    context: Context,
    private val onUnlockRequested: () -> Unit
) : FrameLayout(context) {

    private val handler = Handler(Looper.getMainLooper())
    private var longPressTriggered = false

    private val longPressRunnable = Runnable {
        longPressTriggered = true
        onUnlockRequested()
    }

    init {
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = true
        isFocusable = false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                longPressTriggered = false
                handler.postDelayed(longPressRunnable, LONG_PRESS_DURATION_MS)
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
            }
        }
        return true // Always consume touch
    }

    companion object {
        private const val LONG_PRESS_DURATION_MS = 1500L
    }
}