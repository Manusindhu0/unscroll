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

/** A compact, animated seven-day focus rhythm for the private profile page. */
class FocusWeekView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val green = ContextCompat.getColor(context, R.color.focus_green)
    private val greenMuted = ContextCompat.getColor(context, R.color.focus_green_muted)
    private val muted = ContextCompat.getColor(context, R.color.muted)
    private val rail = ContextCompat.getColor(context, R.color.surface_high)
    private val divider = ContextCompat.getColor(context, R.color.divider)
    private var days: List<WeeklyFocusDay> = emptyList()
    private var reveal = 0f

    private val revealAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 620L
        interpolator = DecelerateInterpolator(1.55f)
        addUpdateListener {
            reveal = it.animatedValue as Float
            invalidate()
        }
    }

    fun setDays(value: List<WeeklyFocusDay>) {
        days = value
        reveal = 0f
        if (isAttachedToWindow) revealAnimator.start() else invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (days.isNotEmpty()) revealAnimator.start()
    }

    override fun onDetachedFromWindow() {
        revealAnimator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (days.isEmpty()) return
        val maxMinutes = max(25, days.maxOf { it.minutes })
        val side = dp(4).toFloat()
        val gap = dp(10).toFloat()
        val barWidth = (width - side * 2 - gap * (days.size - 1)) / days.size
        val baseline = height - dp(24).toFloat()
        val usableHeight = baseline - dp(14)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1).toFloat()
        paint.color = divider
        canvas.drawLine(side, baseline, width - side, baseline, paint)
        paint.style = Paint.Style.FILL

        days.forEachIndexed { index, day ->
            val left = side + index * (barWidth + gap)
            val top = dp(14).toFloat()
            paint.color = rail
            canvas.drawRoundRect(RectF(left, top, left + barWidth, baseline), barWidth / 2, barWidth / 2, paint)

            if (day.minutes > 0) {
                val fullHeight = (day.minutes.toFloat() / maxMinutes * usableHeight).coerceAtLeast(dp(7).toFloat())
                val fillHeight = fullHeight * reveal
                paint.color = if (day.isToday) green else greenMuted
                canvas.drawRoundRect(
                    RectF(left, baseline - fillHeight, left + barWidth, baseline),
                    barWidth / 2, barWidth / 2, paint
                )
                if (reveal > .72f) {
                    paint.alpha = (((reveal - .72f) / .28f).coerceIn(0f, 1f) * 255).toInt()
                    paint.color = if (day.isToday) green else muted
                    paint.textSize = dp(10).toFloat()
                    paint.textAlign = Paint.Align.CENTER
                    canvas.drawText(
                        "${day.minutes}m",
                        left + barWidth / 2,
                        (baseline - fullHeight - dp(6)).coerceAtLeast(dp(11).toFloat()),
                        paint
                    )
                    paint.alpha = 255
                }
            }

            paint.color = if (day.isToday) green else muted
            paint.textSize = dp(11).toFloat()
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText(day.label, left + barWidth / 2, height - dp(5).toFloat(), paint)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
