package com.example.myapplication

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class HistoryChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F5A623")
    }
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#333333")
    }

    private var intensities: List<Float> = List(24) { 0f }
    private var dataReady: Boolean = false

    fun setLoading() {
        dataReady = false
        invalidate()
    }

    fun setIntensities(values: List<Float>) {
        val normalized = values
            .take(24)
            .let { if (it.size == 24) it else it + List(24 - it.size) { 0f } }
            .map { if (it.isFinite()) it.coerceIn(0f, 1f) else 0f }
        val hasSignal = normalized.any { it > 0f }
        intensities = if (hasSignal) normalized else List(24) { 0.05f }
        dataReady = true
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f || !dataReady) return

        val barWidth = ((w / 24f) - 1f).coerceAtLeast(1f)
        val minHeight = 2f * resources.displayMetrics.density
        val drawableHeight = (h - (10f * resources.displayMetrics.density)).coerceAtLeast(minHeight)
        for (index in 0 until 24) {
            val intensity = intensities.getOrNull(index)?.coerceIn(0f, 1f) ?: 0f
            val left = index * (w / 24f)
            val right = left + barWidth
            val barHeight = (drawableHeight * intensity).coerceAtLeast(minHeight)
            val top = h - barHeight
            val rect = RectF(left, top, right, h)

            barPaint.alpha = (76 + (179 * intensity)).toInt().coerceIn(76, 255)
            canvas.drawRoundRect(rect, 2f, 2f, barPaint)
        }
    }
}

