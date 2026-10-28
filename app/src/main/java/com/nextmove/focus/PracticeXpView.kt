package com.nextmove.focus

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import kotlin.math.max

/** A small animated practice-XP rail: progress is earned minutes, never arbitrary points. */
class PracticeXpView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rail = ContextCompat.getColor(context, R.color.surface_high)
    private val green = ContextCompat.getColor(context, R.color.focus_green)
    private val bright = ContextCompat.getColor(context, R.color.focus_green_bright)
    private var target = 0f
    private var displayed = 0f

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 720L
        startDelay = 120L
        interpolator = DecelerateInterpolator(1.7f)
        addUpdateListener {
            displayed = target * (it.animatedValue as Float)
            invalidate()
        }
    }

    fun setProgress(value: Float) {
        target = value.coerceIn(0f, 1f)
        displayed = 0f
        if (isAttachedToWindow) animator.start() else invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val side = dp(2).toFloat()
        val centerY = height / 2f
        val railHeight = dp(8).toFloat()
        val radius = railHeight / 2f
        val railRect = RectF(side, centerY - radius, width - side, centerY + radius)
        paint.color = rail
        canvas.drawRoundRect(railRect, radius, radius, paint)

        val end = side + (width - side * 2) * displayed
        if (end > side) {
            paint.color = green
            canvas.drawRoundRect(RectF(side, centerY - radius, end, centerY + radius), radius, radius, paint)
            paint.color = bright
            canvas.drawCircle(end, centerY, dp(7).toFloat(), paint)
            paint.color = green
            canvas.drawCircle(end, centerY, dp(3).toFloat(), paint)
        }
    }

    private fun dp(value: Int): Int = max(1, (value * resources.displayMetrics.density).toInt())
}
