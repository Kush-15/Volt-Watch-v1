package com.example.myapplication

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
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
        color = Color.parseColor("#F5A623")
    }
    private val tipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
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
        val stroke = 4f
        val tipW = 6f
        val tipH = 12f
        val bodyW = w - tipW - stroke
        val bodyH = h
        val r = 6f

        val bodyRect = RectF(
            stroke / 2,
            stroke / 2,
            bodyW,
            bodyH - stroke / 2
        )

        val accentColor = when {
            level > 50f -> Color.parseColor("#7BC850")
            level > 20f -> Color.parseColor("#F5A623")
            else -> Color.parseColor("#E05555")
        }

        shellPaint.color = accentColor
        canvas.drawRoundRect(bodyRect, r, r, shellPaint)

        val tipRect = RectF(
            bodyW + 1f,
            (h - tipH) / 2f,
            bodyW + 1f + tipW,
            (h + tipH) / 2f
        )
        tipPaint.color = adjustAlpha(accentColor, 0.5f)
        canvas.drawRoundRect(tipRect, 0f, 3f, tipPaint)

        val pad = 3f
        val innerLeft = bodyRect.left + pad
        val innerTop = bodyRect.top + pad
        val innerW = (bodyRect.width() - pad * 2f).coerceAtLeast(0f)
        val innerH = (bodyRect.height() - pad * 2f).coerceAtLeast(0f)

        if (level < 0f) {
            val fillRect = RectF(innerLeft, innerTop, innerLeft + innerW * 0.3f, innerTop + innerH)
            unknownPaint.color = adjustAlpha(accentColor, 0.25f)
            canvas.drawRoundRect(fillRect, 4f, 4f, unknownPaint)
            return
        }

        val fillW = (innerW * (level / 100f)).coerceAtLeast(2f)
        val fillRect = RectF(innerLeft, innerTop, innerLeft + fillW, innerTop + innerH)
        fillPaint.color = accentColor
        canvas.drawRoundRect(fillRect, 4f, 4f, fillPaint)
    }

    private fun adjustAlpha(color: Int, factor: Float): Int {
        val a = (Color.alpha(color) * factor).toInt().coerceIn(0, 255)
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }
}
