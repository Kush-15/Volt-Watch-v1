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
 *
 * Performance notes:
 *  - LinearGradient shaders for the history curves are created once in onSizeChanged()
 *    and reused every frame — shader creation is expensive.
 *  - Color.parseColor() results are cached as companion-object constants.
 *  - Path objects are reused (reset + rebuild) instead of being allocated per draw.
 */
class BatteryGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    // ---- Cached colour constants (parseColor is not cheap) ----
    private companion object {
        val C_GREEN        = Color.parseColor("#FF00C853")
        val C_AMBER        = Color.parseColor("#FFFF9800")
        val C_RED          = Color.parseColor("#FFFF1744")
        val C_GREEN_FILL   = Color.parseColor("#8000C853")
        val C_AMBER_FILL   = Color.parseColor("#80FF9800")
        val C_RED_FILL     = Color.parseColor("#80FF1744")
        val C_PRED_LINE    = Color.parseColor("#80FF9800")
        val C_PRED_F_START = Color.parseColor("#30FF9800")
        val C_PRED_F_END   = Color.parseColor("#00FF9800")
        val C_TOD_LABEL    = Color.parseColor("#FFFF9800")
        val C_GRID         = Color.parseColor("#1AFFFFFF")
        val C_AXIS         = Color.parseColor("#80FFFFFF")
        val C_DOT_SHADOW   = Color.parseColor("#33000000")
    }

    // ---- Data ----
    private var historicalPoints: List<Pair<Long, Float>> = emptyList()
    private var predictionEndMs: Long? = null

    // ---- Paints ----
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = C_GRID
        strokeWidth = 1f
        style       = Paint.Style.STROKE
    }
    private val axisLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color     = C_AXIS
        textSize  = 28f
        textAlign = Paint.Align.RIGHT
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style       = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap   = Paint.Cap.ROUND
        strokeJoin  = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val predictionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = C_PRED_LINE
        style       = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap   = Paint.Cap.ROUND
        pathEffect  = DashPathEffect(floatArrayOf(18f, 12f), 0f)
    }
    private val predictionFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val todLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color     = C_TOD_LABEL
        textSize  = 26f
        textAlign = Paint.Align.CENTER
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val padL = 72f
    private val padR = 24f
    private val padT = 24f

    // ---- Reused Path objects — reset() before each rebuild ----
    private val histPath = Path()
    private val linePath = Path()
    private val predPath = Path()

    // ---- Cached shaders (rebuilt in onSizeChanged) ----
    private var cachedFillShader: LinearGradient? = null
    private var cachedLineShader: LinearGradient? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildShaders(h.toFloat())
    }

    private fun rebuildShaders(h: Float) {
        val chartH = h - padT - 48f
        val top    = padT
        val bottom = padT + chartH
        cachedFillShader = LinearGradient(
            0f, top, 0f, bottom,
            intArrayOf(C_GREEN_FILL, C_AMBER_FILL, C_RED_FILL),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        cachedLineShader = LinearGradient(
            0f, top, 0f, bottom,
            intArrayOf(C_GREEN, C_AMBER, C_RED),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    fun setData(points: List<Pair<Long, Float>>, predictionEpochMs: Long?) {
        historicalPoints = points
        predictionEndMs  = predictionEpochMs
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w      = width.toFloat()
        val h      = height.toFloat()
        val chartW = w - padL - padR
        val chartH = h - padT - 48f

        drawGrid(canvas, chartW, chartH)

        if (historicalPoints.isEmpty()) return

        val minMs   = historicalPoints.first().first
        val maxMs   = predictionEndMs?.let { maxOf(historicalPoints.last().first, it) }
                      ?: historicalPoints.last().first
        val rangeMs = (maxMs - minMs).coerceAtLeast(1L).toFloat()

        fun xOf(ms: Long)    = padL + ((ms - minMs).toFloat() / rangeMs) * chartW
        fun yOf(level: Float) = padT + chartH - (level / 100f) * chartH

        val firstX = xOf(historicalPoints.first().first)
        val firstY = yOf(historicalPoints.first().second)

        // ---- Historical gradient fill (reuse cached shader) ----
        histPath.reset()
        histPath.moveTo(firstX, padT + chartH)
        histPath.lineTo(firstX, firstY)
        for (pt in historicalPoints.drop(1)) histPath.lineTo(xOf(pt.first), yOf(pt.second))
        val lastHistX = xOf(historicalPoints.last().first)
        histPath.lineTo(lastHistX, padT + chartH)
        histPath.close()

        fillPaint.shader = cachedFillShader
        canvas.drawPath(histPath, fillPaint)

        // ---- Historical stroke (reuse cached shader) ----
        linePath.reset()
        linePath.moveTo(firstX, firstY)
        for (pt in historicalPoints.drop(1)) linePath.lineTo(xOf(pt.first), yOf(pt.second))
        linePaint.shader = cachedLineShader
        canvas.drawPath(linePath, linePaint)

        // ---- Prediction dotted line ----
        val predMs = predictionEndMs
        if (predMs != null && predMs > historicalPoints.last().first) {
            val lastX = xOf(historicalPoints.last().first)
            val lastY = yOf(historicalPoints.last().second)
            val predX = xOf(predMs)
            val predY = yOf(0f)

            predPath.reset()
            predPath.moveTo(lastX, padT + chartH)
            predPath.lineTo(lastX, lastY)
            predPath.lineTo(predX, predY)
            predPath.lineTo(predX, padT + chartH)
            predPath.close()

            // Prediction fill gradient depends on lastY — rebuilt per-draw but
            // only when the prediction line is actually visible (cheap path).
            predictionFillPaint.shader = LinearGradient(
                0f, lastY, 0f, padT + chartH,
                intArrayOf(C_PRED_F_START, C_PRED_F_END),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawPath(predPath, predictionFillPaint)
            canvas.drawLine(lastX, lastY, predX, predY, predictionPaint)
            canvas.drawText("0%", predX, predY - 8f, todLabelPaint)
        }

        // ---- Dot at the latest sample ----
        val latestLevel = historicalPoints.last().second
        val latestX     = xOf(historicalPoints.last().first)
        val latestY     = yOf(latestLevel)
        val dotColor    = when {
            latestLevel > 50f -> C_GREEN
            latestLevel > 20f -> C_AMBER
            else              -> C_RED
        }
        dotPaint.color = C_DOT_SHADOW
        canvas.drawCircle(latestX, latestY, 16f, dotPaint)
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
