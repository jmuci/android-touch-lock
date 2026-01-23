package com.tenmilelabs.touchlock.platform.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A non-blocking overlay that displays countdown timer.
 * Does NOT consume touch events - allows user to interact with underlying apps.
 * Used during the delayed lock setup phase.
 */
class CountdownOverlayView(context: Context) : LinearLayout(context) {

    private val countdownText: TextView

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        isClickable = false  // IMPORTANT: Don't consume touches
        isFocusable = false

        // Semi-transparent background
        background = createRoundedBackground()

        // Countdown text
        countdownText = TextView(context).apply {
            textSize = 48f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        addView(countdownText)

        // Label text
        val labelText = TextView(context).apply {
            text = "Locking..."
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding( dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8))
        }
        addView(labelText)

        // Set size
        val sizePx = dpToPx(180)
        layoutParams = LayoutParams(sizePx, sizePx)

        elevation = dpToPx(16).toFloat()
    }

    /**
     * Updates the displayed countdown value.
     * @param seconds Remaining seconds to display
     */
    fun updateCountdown(seconds: Int) {
        countdownText.text = seconds.toString()
    }

    private fun createRoundedBackground(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(16).toFloat()
            setColor(Color.parseColor("#AA000000")) // More opaque than unlock handle
        }
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }
}
