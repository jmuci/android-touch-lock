package com.tenmilelabs.touchlock.data.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.MotionEvent
import android.widget.FrameLayout

class OverlayView(context: Context) : FrameLayout(context) {

    init {
        isClickable = true
        isFocusable = false
        setBackgroundColor(Color.TRANSPARENT)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // consume everything
        // detect unlock gesture here
        return true
    }
}
