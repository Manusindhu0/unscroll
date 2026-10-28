package com.nextmove.focus

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** A brief, lightweight canvas burst for the moment a focus promise is kept. */
class FocusCelebrationView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val green = ContextCompat.getColor(context, R.color.focus_green)
    private val yellow = ContextCompat.getColor(context, R.color.focus_yellow)
    private var progress = 0f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1_100L
        interpolator = DecelerateInterpolator(1.7f)
        addUpdateListener {
            progress = it.animatedValue as Float
            invalidate()
        }
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
        val centerX = width / 2f
        val centerY = height * .62f
        val fade = (1f - progress).coerceIn(0f, 1f)
        val lift = progress * dp(18)

        paint.style = Paint.Style.FILL
        paint.color = green
        paint.alpha = (105 * fade).toInt()
        canvas.drawCircle(centerX, centerY - lift, dp(46) + progress * dp(17), paint)
        paint.alpha = 255
        canvas.drawCircle(centerX, centerY - lift, dp(23).toFloat(), paint)
        paint.color = Color.rgb(10, 10, 11)
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = dp(24).toFloat()
        canvas.drawText("✓", centerX, centerY - lift + dp(8), paint)

        repeat(18) { index ->
            val angle = Math.toRadians((index * 20 - 105).toDouble())
            val distance = dp(30) + progress * (dp(52) + (index % 3) * dp(8))
            val x = centerX + cos(angle).toFloat() * distance
            val y = centerY - lift + sin(angle).toFloat() * distance
            paint.color = if (index % 3 == 0) yellow else green
            paint.alpha = (235 * fade).toInt()
            canvas.drawCircle(x, y, (dp(2) + index % 2).toFloat(), paint)
        }
        paint.alpha = 255
    }

    private fun dp(value: Int): Int = max(1, (value * resources.displayMetrics.density).toInt())
}
