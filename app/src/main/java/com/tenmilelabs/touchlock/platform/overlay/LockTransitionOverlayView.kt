package com.tenmilelabs.touchlock.platform.overlay

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.annotation.VisibleForTesting
import com.tenmilelabs.touchlock.R
import timber.log.Timber
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The extra travel margin (in px) a [width] x [height] rectangle's sweep needs on each end so
 * that, once rotated by [angleDegrees] about its own center, it still starts and ends fully
 * off-screen — i.e. half of how much taller the rotated bounding box is than the original height.
 *
 * Extracted as a standalone, pure function so it's directly unit-testable: this is the exact
 * calculation an earlier version got wrong, using a blanket `width + height` safety margin
 * (~3500px on a typical phone) where the real rotated screen only needs ~200-300px. That meant
 * [GlassGlintView]'s sweep spent roughly 70% of its animated duration sitting fully off-screen,
 * before and after a brief crossing buried in the middle — which read, both live and in
 * screenshots, as the glint simply not rendering at all.
 */
@VisibleForTesting
internal fun rotatedVerticalMarginPx(width: Int, height: Int, angleDegrees: Float): Float {
    val angleRad = Math.toRadians(angleDegrees.toDouble())
    val rotatedBoundingHeight = width * abs(sin(angleRad)) + height * abs(cos(angleRad))
    return ((rotatedBoundingHeight - height) / 2).toFloat().coerceAtLeast(0f)
}

/**
 * Purely decorative, non-touchable overlay that plays a diagonal glass-glint sweep plus a brief
 * center app-icon fade, to sell the "there's a protective pane of glass over the screen" feel on
 * a lock/unlock transition. Added and removed as its own transient WindowManager window by
 * [OverlayController.playLockTransition] — never intercepts touches and never affects lock state
 * itself.
 */
class LockTransitionOverlayView(context: Context) : FrameLayout(context) {

    // Handler is used intentionally here instead of coroutines. This View is attached to
    // WindowManager as a system overlay, outside any Activity/Fragment lifecycle. No
    // LifecycleOwner is available and this view is short-lived, so lifecycle-scoped coroutines
    // cannot be used — same rationale as OverlayView/UnlockHandleView in this package.
    private val handler = Handler(Looper.getMainLooper())

    // Brief dark scrim, under the glint and icon: reads as a translucent pane settling over the
    // screen, and (deliberately) gives the white glint contrast to catch even over near-white
    // content — a pure-white glint on a light background would otherwise be nearly invisible.
    private val scrimView = View(context).apply {
        setBackgroundColor(Color.BLACK)
        alpha = 0f
    }
    private val glintView = GlassGlintView(context)
    private val iconView = ImageView(context).apply {
        setImageResource(R.mipmap.ic_launcher)
        alpha = 0f
    }

    private var scrimAnimator: Animator? = null
    private var glintAnimator: ValueAnimator? = null
    private var iconAnimator: Animator? = null
    private var completeRunnable: Runnable? = null

    init {
        isClickable = false
        isFocusable = false

        addView(scrimView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(glintView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val iconSizePx = dpToPx(ICON_SIZE_DP)
        addView(iconView, LayoutParams(iconSizePx, iconSizePx, Gravity.CENTER))
    }

    /**
     * Starts the scrim + glint sweep + icon fade. [onComplete] fires once, after
     * [TOTAL_DURATION_MS], so the caller can remove this view's window.
     */
    fun start(onComplete: () -> Unit) {
        // Deferred to post {} so the glint view's own size reflects the just-attached window's
        // real layout (needed to size its travel distance), not 0.
        post {
            val scrimIn = ObjectAnimator.ofFloat(scrimView, View.ALPHA, 0f, SCRIM_PEAK_ALPHA)
                .setDuration(SCRIM_FADE_IN_MS)
            val scrimOut = ObjectAnimator.ofFloat(scrimView, View.ALPHA, SCRIM_PEAK_ALPHA, 0f)
                .setDuration(SCRIM_FADE_OUT_MS).apply { startDelay = SCRIM_HOLD_UNTIL_MS }
            scrimAnimator = AnimatorSet().apply {
                playTogether(scrimIn, scrimOut)
                start()
            }

            glintAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = GLINT_DURATION_MS
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { glintView.setSweepFraction(it.animatedValue as Float) }
                start()
            }

            val fadeIn = ObjectAnimator.ofFloat(iconView, View.ALPHA, 0f, 1f).setDuration(ICON_FADE_MS)
            val fadeOut = ObjectAnimator.ofFloat(iconView, View.ALPHA, 1f, 0f).setDuration(ICON_FADE_MS).apply {
                startDelay = TOTAL_DURATION_MS - ICON_FADE_MS
            }
            iconAnimator = AnimatorSet().apply {
                playTogether(fadeIn, fadeOut)
                start()
            }

            val runnable = Runnable {
                Timber.d("LockTransitionOverlayView animation complete")
                onComplete()
            }
            completeRunnable = runnable
            handler.postDelayed(runnable, TOTAL_DURATION_MS)
        }
    }

    fun cleanup() {
        Timber.d("LockTransitionOverlayView.cleanup() called")
        completeRunnable?.let { handler.removeCallbacks(it) }
        completeRunnable = null
        scrimAnimator?.cancel()
        scrimAnimator = null
        glintAnimator?.cancel()
        glintAnimator = null
        iconAnimator?.cancel()
        iconAnimator = null
    }

    private fun dpToPx(dp: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        dp.toFloat(),
        context.resources.displayMetrics
    ).toInt()

    /**
     * Draws a diagonal glint — a bright, narrow core line flanked by a soft halo — swept across
     * the full screen by [setSweepFraction], to read as light catching a pane of glass rather
     * than a flat screen-wipe. Achieved by rotating the canvas [GLINT_ANGLE_DEGREES] and drawing
     * a horizontal band at an animated vertical offset in that rotated space: in unrotated
     * (screen) space, that band appears as a diagonal streak.
     *
     * The band's vertical travel range (see [verticalMarginPx] in [onDraw]) is sized from the
     * actual rotated bounding-box math, not a blanket oversized margin: an earlier version used
     * `width + height` as the off-screen safety margin on both ends of the sweep, which for a
     * typical phone screen is ~3500px versus the ~2600px the rotated screen actually needs —
     * meaning roughly 70% of the animated travel was spent fully off-screen, before and after a
     * brief ~200ms visible crossing buried in the middle of the animation. That crossing was easy
     * to miss both on-device and in screenshots, and is the main reason the very first version of
     * this glint effectively read as invisible. Horizontal padding (`overscanX`) stays a cheap
     * blanket oversize since it costs nothing but a few extra clipped pixels, not animated time.
     */
    private class GlassGlintView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var sweepFraction = 0f
        private var haloHeightPx = 0f
        private var verticalMarginPx = 0f

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (h <= 0 || w <= 0) return
            haloHeightPx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                HALO_HEIGHT_DP,
                resources.displayMetrics
            )
            verticalMarginPx = rotatedVerticalMarginPx(w, h, GLINT_ANGLE_DEGREES)
            paint.shader = LinearGradient(
                0f, 0f, 0f, haloHeightPx,
                intArrayOf(TRANSPARENT, HALO_WHITE, CORE_WHITE, CORE_WHITE, HALO_WHITE, TRANSPARENT),
                floatArrayOf(0f, 0.38f, 0.48f, 0.52f, 0.62f, 1f),
                Shader.TileMode.CLAMP
            )
        }

        /** [fraction] 0f..1f maps across the glint's full off-screen-to-off-screen travel. */
        fun setSweepFraction(fraction: Float) {
            sweepFraction = fraction
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (haloHeightPx <= 0f) return

            canvas.save()
            canvas.rotate(GLINT_ANGLE_DEGREES, width / 2f, height / 2f)

            val travelStart = -haloHeightPx - verticalMarginPx
            val travelEnd = height + verticalMarginPx
            val y = travelStart + (travelEnd - travelStart) * sweepFraction

            // Generous horizontal-only overscan so the rotated band still fully covers the
            // screen's width at every point along its sweep — cheap, since it's just extra pixels
            // clipped to the view's bounds, not extra animated travel time.
            val overscanX = height.toFloat()
            canvas.translate(0f, y)
            canvas.drawRect(-overscanX, 0f, width + overscanX, haloHeightPx, paint)
            canvas.restore()
        }

        companion object {
            private const val HALO_HEIGHT_DP = 200f
            private const val GLINT_ANGLE_DEGREES = 20f
            private const val TRANSPARENT = 0x00FFFFFF
            private const val HALO_WHITE = 0x26FFFFFF // ~15% white — soft ambient glow
            private const val CORE_WHITE = 0xE6FFFFFF.toInt() // ~90% white — the bright glint line
        }
    }

    companion object {
        private const val ICON_SIZE_DP = 96
        private const val GLINT_DURATION_MS = 1050L
        private const val ICON_FADE_MS = 450L
        private const val SCRIM_PEAK_ALPHA = 0.12f
        private const val SCRIM_FADE_IN_MS = 150L
        private const val SCRIM_FADE_OUT_MS = 250L
        private const val SCRIM_HOLD_UNTIL_MS = 1050L // scrim starts fading out as the glint finishes
        const val TOTAL_DURATION_MS = 3000L
    }
}
