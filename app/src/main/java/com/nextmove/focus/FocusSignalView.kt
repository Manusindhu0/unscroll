package com.nextmove.focus

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.content.ContextCompat
import kotlin.math.max
import kotlin.math.min

/**
 * A clockless reverse focus meter. It begins as three full red segments, then
 * drains smoothly while its colour moves from red to yellow to green. At the end
 * it refills green and opens its lock instead of exposing a numeric countdown.
 */
class FocusSignalView(context: Context) : View(context) {
    private val red = ContextCompat.getColor(context, R.color.focus_red)
    private val yellow = ContextCompat.getColor(context, R.color.focus_yellow)
    private val green = ContextCompat.getColor(context, R.color.focus_green)
    private val rail = ContextCompat.getColor(context, R.color.surface_high)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var roundStartedAt = 0L
    private var roundEndsAt = 0L
    private var completionMode = false
    private var completionProgress = 0f

    private val pulse = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1_650L
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener { invalidate() }
    }
    private val completionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1_050L
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener {
            completionProgress = it.animatedValue as Float
            invalidate()
        }
    }

    fun bind(session: FocusSession) {
        roundStartedAt = session.startedAt
        roundEndsAt = session.endsAt
        completionMode = false
        completionProgress = 0f
        completionAnimator.cancel()
        invalidate()
    }

    fun playCompletion() {
        if (completionMode) return
        completionMode = true
        completionProgress = 0f
        completionAnimator.start()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!pulse.isStarted) pulse.start()
    }

    override fun onDetachedFromWindow() {
        pulse.cancel()
        completionAnimator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val width = width.toFloat()
        val centerX = width / 2f
        val centerY = height * .40f
        val roundProgress = progressNow()
        val active = if (completionMode) green else journeyColor(roundProgress)
        val breath = pulse.animatedValue as? Float ?: 0f
        val unlock = if (completionMode) completionProgress else 0f

        // A quiet colour field makes the state readable at a glance without
        // inviting the user to repeatedly check a numeric timer.
        val glowRadius = dp(112) + breath * dp(18) + unlock * dp(32)
        paint.shader = RadialGradient(
            centerX,
            centerY,
            glowRadius,
            intArrayOf(withAlpha(active, if (completionMode) 72 else 42), Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(centerX, centerY, glowRadius, paint)
        paint.shader = null

        // Calm breathing rings during focus, then a brighter unlock bloom.
        paint.style = Paint.Style.FILL
        paint.color = active
        paint.alpha = if (completionMode) (32 + unlock * 48).toInt() else (18 + breath * 24).toInt()
        canvas.drawCircle(centerX, centerY, dp(62) + breath * dp(11) + unlock * dp(14), paint)
        paint.alpha = if (completionMode) (72 + unlock * 36).toInt() else (45 + breath * 32).toInt()
        canvas.drawCircle(centerX, centerY, dp(43) + breath * dp(4) + unlock * dp(5), paint)
        paint.alpha = 255
        canvas.drawCircle(centerX, centerY, (dp(29) + unlock * dp(3)).toFloat(), paint)

        drawLock(canvas, centerX, centerY, active, unlock)
        drawReverseMeter(canvas, width, roundProgress, active, unlock)
        if (completionMode) drawCelebrationDots(canvas, centerX, centerY, unlock)
    }

    private fun drawLock(canvas: Canvas, centerX: Float, centerY: Float, active: Int, unlock: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(3).toFloat()
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = Color.rgb(10, 10, 11)
        val shackle = RectF(centerX - dp(9), centerY - dp(13), centerX + dp(9), centerY + dp(7))
        canvas.save()
        // The shackle pivots off its right foot as the round is completed.
        canvas.rotate(-28f * unlock, centerX + dp(9), centerY + dp(7))
        canvas.drawArc(shackle, 185f, 170f, false, paint)
        canvas.restore()

        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(
            RectF(centerX - dp(13), centerY - dp(1), centerX + dp(13), centerY + dp(17)),
            dp(5).toFloat(), dp(5).toFloat(), paint
        )
        paint.color = active
        canvas.drawCircle(centerX, centerY + dp(8), dp(2).toFloat(), paint)
    }

    private fun drawReverseMeter(
        canvas: Canvas,
        width: Float,
        roundProgress: Float,
        active: Int,
        unlock: Float
    ) {
        val railY = height * .84f
        val side = dp(30).toFloat()
        val segmentGap = dp(8).toFloat()
        val segmentWidth = (width - side * 2 - segmentGap * 2) / 3f
        // During a round, all three start full and drain right-to-left. On completion,
        // a green wave refills them to celebrate the unlocked phone.
        var remainingFill = if (completionMode) completionProgress * segmentWidth * 3f
        else (1f - roundProgress) * segmentWidth * 3f

        repeat(3) { index ->
            val left = side + index * (segmentWidth + segmentGap)
            paint.color = rail
            paint.alpha = 255
            canvas.drawRoundRect(
                RectF(left, railY, left + segmentWidth, railY + dp(7)),
                dp(99).toFloat(), dp(99).toFloat(), paint
            )

            val filledWidth = min(segmentWidth, max(0f, remainingFill))
            if (filledWidth > 0f) {
                paint.color = active
                canvas.drawRoundRect(
                    RectF(left, railY, left + filledWidth, railY + dp(7)),
                    dp(99).toFloat(), dp(99).toFloat(), paint
                )
            }
            remainingFill -= segmentWidth
        }
        paint.alpha = 255
    }

    private fun drawCelebrationDots(canvas: Canvas, centerX: Float, centerY: Float, unlock: Float) {
        if (unlock <= 0f) return
        paint.style = Paint.Style.FILL
        paint.color = green
        paint.alpha = ((1f - unlock) * 190).toInt().coerceAtLeast(0)
        val distance = dp(53) + unlock * dp(20)
        listOf(-145f, -35f, 28f, 145f).forEachIndexed { index, angle ->
            val radians = Math.toRadians(angle.toDouble())
            val x = centerX + kotlin.math.cos(radians).toFloat() * distance
            val y = centerY + kotlin.math.sin(radians).toFloat() * distance
            canvas.drawCircle(x, y, (dp(2) + index % 2).toFloat(), paint)
        }
        paint.alpha = 255
    }

    private fun progressNow(): Float {
        if (roundEndsAt <= roundStartedAt) return 0f
        return ((System.currentTimeMillis() - roundStartedAt).toFloat() /
            (roundEndsAt - roundStartedAt).toFloat()).coerceIn(0f, 1f)
    }

    private fun journeyColor(progress: Float): Int = if (progress < .5f) {
        blend(red, yellow, progress / .5f)
    } else {
        blend(yellow, green, (progress - .5f) / .5f)
    }

    private fun blend(from: Int, to: Int, amount: Float): Int {
        val safeAmount = amount.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(from) + (Color.red(to) - Color.red(from)) * safeAmount).toInt(),
            (Color.green(from) + (Color.green(to) - Color.green(from)) * safeAmount).toInt(),
            (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * safeAmount).toInt()
        )
    }

    private fun withAlpha(source: Int, alpha: Int): Int = Color.argb(
        alpha.coerceIn(0, 255),
        Color.red(source),
        Color.green(source),
        Color.blue(source)
    )

    private fun dp(value: Int): Int = max(1, (value * resources.displayMetrics.density).toInt())
}
