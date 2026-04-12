package com.example.myapplication

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/**
 * Flat orange history line used on the Home screen.
 * The view intentionally avoids grids, axes, gradients, and secondary lines so it
 * matches the minimal design brief.
 */
class BatteryGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private companion object {
        val C_ORANGE = Color.parseColor("#FFF5A623")
        val C_ORANGE_FILL = Color.parseColor("#20F5A623")
    }

    private var historicalPoints: List<Pair<Long, Float>> = emptyList()
    private var predictionEndMs: Long? = null

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style       = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap   = Paint.Cap.ROUND
        strokeJoin  = Paint.Join.ROUND
        color       = C_ORANGE
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = C_ORANGE_FILL
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val pad = 8f

    private val histPath = Path()

    fun setData(points: List<Pair<Long, Float>>, predictionEpochMs: Long?) {
        historicalPoints = points
        predictionEndMs  = predictionEpochMs
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()

        if (historicalPoints.isEmpty()) return

        val points = historicalPoints.takeLast(6)
        if (points.size == 1) {
            val singleX = w - pad
            val singleY = h - pad - ((points.first().second / 100f) * (h - pad * 2f))
            dotPaint.color = C_ORANGE
            canvas.drawCircle(singleX, singleY, 3f, dotPaint)
            return
        }

        val chartW = (w - pad * 2f).coerceAtLeast(1f)
        val chartH = (h - pad * 2f).coerceAtLeast(1f)

        fun xOf(index: Int) = pad + (index.toFloat() / (points.lastIndex.coerceAtLeast(1)).toFloat()) * chartW
        fun yOf(level: Float) = pad + chartH - (level / 100f) * chartH

        histPath.reset()
        histPath.moveTo(xOf(0), yOf(points.first().second))
        for (index in 1 until points.size) {
            histPath.lineTo(xOf(index), yOf(points[index].second))
        }

        val fillPath = Path(histPath)
        fillPath.lineTo(xOf(points.lastIndex), h - pad)
        fillPath.lineTo(xOf(0), h - pad)
        fillPath.close()

        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(histPath, linePaint)

        points.forEachIndexed { index, point ->
            val x = xOf(index)
            val y = yOf(point.second)
            dotPaint.color = C_ORANGE
            canvas.drawCircle(x, y, 3f, dotPaint)
        }
    }
}
