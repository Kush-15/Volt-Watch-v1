package com.example.myapplication

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/**
 * Custom View that renders two things:
 *  1. Historical battery level line + gradient fill (solid green→amber→red gradient line)
 *  2. Prediction dotted line from the last known point down to 0% at TOD
 */
class BatteryGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    // ---- Data ----
    private var historicalPoints: List<Pair<Long, Float>> = emptyList() // (epochMs, batteryLevel 0-100)
    private var predictionEndMs: Long? = null   // epoch ms when battery hits 0

    // ---- Paints ----
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1AFFFFFF")
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }
    private val axisLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#80FFFFFF")
        textSize = 28f
        textAlign = Paint.Align.RIGHT
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val predictionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#80FF9800") // semi-transparent amber
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(18f, 12f), 0f)
    }
    private val predictionFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val todLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFF9800")
        textSize = 26f
        textAlign = Paint.Align.CENTER
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val padL = 72f
    private val padR = 24f
    private val padT = 24f

    fun setData(points: List<Pair<Long, Float>>, predictionEpochMs: Long?) {
        historicalPoints = points
        predictionEndMs = predictionEpochMs
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val chartW = w - padL - padR
        val chartH = h - padT - 48f

        drawGrid(canvas, chartW, chartH)

        if (historicalPoints.isEmpty()) return

        val minMs = historicalPoints.first().first
        // x-range covers historical + prediction if available
        val maxMs = predictionEndMs?.let { maxOf(historicalPoints.last().first, it) }
            ?: historicalPoints.last().first
        val rangeMs = (maxMs - minMs).coerceAtLeast(1L).toFloat()

        fun xOf(ms: Long) = padL + ((ms - minMs).toFloat() / rangeMs) * chartW
        fun yOf(level: Float) = padT + chartH - (level / 100f) * chartH

        // --- Historical gradient fill ---
        val histPath = Path()
        val firstX = xOf(historicalPoints.first().first)
        val firstY = yOf(historicalPoints.first().second)
        histPath.moveTo(firstX, padT + chartH) // bottom-left
        histPath.lineTo(firstX, firstY)
        for (pt in historicalPoints.drop(1)) {
            histPath.lineTo(xOf(pt.first), yOf(pt.second))
        }
        val lastHistX = xOf(historicalPoints.last().first)
        histPath.lineTo(lastHistX, padT + chartH)
        histPath.close()

        fillPaint.shader = LinearGradient(
            0f, padT, 0f, padT + chartH,
            intArrayOf(
                Color.parseColor("#8000C853"),  // green top
                Color.parseColor("#80FF9800"),  // amber mid
                Color.parseColor("#80FF1744")   // red bottom
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(histPath, fillPaint)

        // --- Historical line (gradient stroke) ---
        linePaint.shader = LinearGradient(
            0f, padT, 0f, padT + chartH,
            intArrayOf(
                Color.parseColor("#FF00C853"),
                Color.parseColor("#FFFF9800"),
                Color.parseColor("#FFFF1744")
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        val linePath = Path()
        linePath.moveTo(firstX, firstY)
        for (pt in historicalPoints.drop(1)) {
            linePath.lineTo(xOf(pt.first), yOf(pt.second))
        }
        canvas.drawPath(linePath, linePaint)

        // --- Prediction dotted line ---
        val predMs = predictionEndMs
        if (predMs != null && predMs > historicalPoints.last().first) {
            val lastX = xOf(historicalPoints.last().first)
            val lastY = yOf(historicalPoints.last().second)
            val predX = xOf(predMs)
            val predY = yOf(0f)

            // Fill under prediction
            val predPath = Path()
            predPath.moveTo(lastX, padT + chartH)
            predPath.lineTo(lastX, lastY)
            predPath.lineTo(predX, predY)
            predPath.lineTo(predX, padT + chartH)
            predPath.close()
            predictionFillPaint.shader = LinearGradient(
                0f, lastY, 0f, padT + chartH,
                intArrayOf(Color.parseColor("#30FF9800"), Color.parseColor("#00FF9800")),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawPath(predPath, predictionFillPaint)

            // Dotted line
            canvas.drawLine(lastX, lastY, predX, predY, predictionPaint)

            // "0%" label at end
            canvas.drawText("0%", predX, predY - 8f, todLabelPaint)
        }

        // --- Dot at the latest sample ---
        val latestX = xOf(historicalPoints.last().first)
        val latestLevel = historicalPoints.last().second
        val latestY = yOf(latestLevel)
        val dotColor = when {
            latestLevel > 50f -> Color.parseColor("#FF00C853")
            latestLevel > 20f -> Color.parseColor("#FFFF9800")
            else              -> Color.parseColor("#FFFF1744")
        }
        dotPaint.color = Color.parseColor("#33000000")
        canvas.drawCircle(latestX, latestY, 16f, dotPaint) // shadow
        dotPaint.color = dotColor
        canvas.drawCircle(latestX, latestY, 11f, dotPaint)
        dotPaint.color = Color.WHITE
        canvas.drawCircle(latestX, latestY, 5f, dotPaint)
    }

    private fun drawGrid(canvas: Canvas, chartW: Float, chartH: Float) {
        val levels = listOf(0, 25, 50, 75, 100)
        for (level in levels) {
            val y = padT + chartH - (level / 100f) * chartH
            canvas.drawLine(padL, y, padL + chartW, y, gridPaint)
            canvas.drawText("$level%", padL - 8f, y + 9f, axisLabelPaint)
        }
    }
}
