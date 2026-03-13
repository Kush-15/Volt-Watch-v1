package com.example.myapplication

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/**
 * Draws a simple battery icon on a canvas.
 * The fill level and color (green/amber/red) are driven by [setLevel].
 */
class BatteryIconView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var level: Float = -1f  // -1 = unknown

    private val shellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.parseColor("#80FFFFFF")
    }
    private val tipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#80FFFFFF")
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val unknownPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#40FFFFFF")
        style = Paint.Style.FILL
    }

    fun setLevel(percent: Float) {
        level = percent.coerceIn(0f, 100f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val tipW = w * 0.05f
        val bodyW = w - tipW - shellPaint.strokeWidth
        val bodyH = h - shellPaint.strokeWidth
        val r = bodyH * 0.2f

        val bodyRect = RectF(
            shellPaint.strokeWidth / 2,
            shellPaint.strokeWidth / 2,
            bodyW,
            h - shellPaint.strokeWidth / 2
        )
        // Shell outline
        canvas.drawRoundRect(bodyRect, r, r, shellPaint)

        // Terminal tip
        val tipRect = RectF(
            bodyW + 2f,
            h * 0.33f,
            bodyW + tipW,
            h * 0.67f
        )
        canvas.drawRoundRect(tipRect, 3f, 3f, tipPaint)

        // Fill
        val pad = shellPaint.strokeWidth * 1.5f
        val innerW = bodyW - pad * 2
        val innerH = bodyH - pad * 2

        if (level < 0f) {
            // Unknown state — draw faint block
            val fillRect = RectF(pad, pad, pad + innerW * 0.3f, pad + innerH)
            canvas.drawRoundRect(fillRect, r * 0.5f, r * 0.5f, unknownPaint)
            return
        }

        val fillW = (innerW * (level / 100f)).coerceAtLeast(4f)
        val fillRect = RectF(pad, pad, pad + fillW, pad + innerH)

        val fillColor = when {
            level > 50f -> Color.parseColor("#FF00C853")
            level > 20f -> Color.parseColor("#FFFF9800")
            else        -> Color.parseColor("#FFFF1744")
        }
        fillPaint.shader = LinearGradient(
            fillRect.left, 0f, fillRect.right, 0f,
            intArrayOf(fillColor, adjustAlpha(fillColor, 0.75f)),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(fillRect, r * 0.5f, r * 0.5f, fillPaint)
    }

    private fun adjustAlpha(color: Int, factor: Float): Int {
        val a = (Color.alpha(color) * factor).toInt().coerceIn(0, 255)
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }
}
