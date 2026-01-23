package com.tenmilelabs.touchlock.platform.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.tenmilelabs.touchlock.R

/**
 * A small, square overlay that appears after double-tap.
 * Displays a lock icon and "Press and hold to unlock" text.
 * Requires press-and-hold to trigger unlock.
 */
class UnlockHandleView(
    context: Context,
    private val onUnlockRequested: () -> Unit
) : LinearLayout(context) {

    private val handler = Handler(Looper.getMainLooper())
    private var longPressTriggered = false

    private val longPressRunnable = Runnable {
        longPressTriggered = true
        onUnlockRequested()
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true

        // Set size to 300dp
        val sizePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            HANDLE_SIZE_DP,
            context.resources.displayMetrics
        ).toInt()

        layoutParams = LayoutParams(sizePx, sizePx)

        // Set rounded background with semi-transparent color
        background = createRoundedBackground()

        // Add lock icon
        val iconView = ImageView(context).apply {
            setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_lock_24))
            setColorFilter(Color.WHITE)
            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(4)
            }
        }
        addView(iconView)

        // Add text
        val textView = TextView(context).apply {
            text = "Press and\nhold to\nunlock"
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setLineSpacing(0f, 0.9f)
        }
        addView(textView)

        elevation = dpToPx(8).toFloat()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                longPressTriggered = false
                handler.postDelayed(longPressRunnable, PRESS_AND_HOLD_DURATION_MS)
                // Visual feedback
                alpha = 0.8f
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                alpha = 1f
            }
        }
        return true // Always consume touch
    }

    fun cleanup() {
        handler.removeCallbacks(longPressRunnable)
    }

    private fun createRoundedBackground(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(12).toFloat()
            setColor(Color.parseColor("#88000000")) // Semi-transparent black
        }
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }

    companion object {
        const val HANDLE_SIZE_DP = 300f
        private const val PRESS_AND_HOLD_DURATION_MS = 1000L
    }
}
