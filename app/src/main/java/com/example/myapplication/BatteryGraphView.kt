package com.example.myapplication

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.TypedValue
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
        val C_ORANGE = Color.parseColor("#F5A623")
        val C_GRID = Color.parseColor("#22FFFFFF")
        val C_AXIS = Color.parseColor("#99FFFFFF")
        val C_MARKER_OUTER = Color.parseColor("#00C46A")
        val C_MARKER_INNER = Color.parseColor("#F2FFF8")
        val C_FILL_TOP = Color.parseColor("#6638D96F")
        val C_FILL_BOTTOM = Color.parseColor("#66D21E4A")
    }

    private var historicalPoints: List<Pair<Long, Float>> = emptyList()
    private var predictionEndMs: Long? = null

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style       = Paint.Style.STROKE
        strokeWidth = dp(2.2f)
        strokeCap   = Paint.Cap.ROUND
        strokeJoin  = Paint.Join.ROUND
        color       = C_ORANGE
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val predictionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.8f)
        color = C_ORANGE
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(dp(6f), dp(4f)), 0f)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = C_GRID
        strokeWidth = dp(0.8f)
    }
    private val axisTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = C_AXIS
        textSize = sp(10f)
        textAlign = Paint.Align.LEFT
    }
    private val endLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = C_ORANGE
        textSize = sp(12f)
        textAlign = Paint.Align.LEFT
    }

    private val outerPad = dp(8f)
    private val leftAxisGutter = dp(46f)
    private val rightPad = dp(8f)
    private val topPad = dp(6f)
    private val bottomPad = dp(10f)
    private val minHistoryFractionWithPrediction = 0.38f

    private val linePath = Path()
    private val fillPath = Path()
    private val predictionPath = Path()
    private var cachedFillShader: LinearGradient? = null
    private var cachedShaderTop = Float.NaN
    private var cachedShaderBottom = Float.NaN

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun sp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        value,
        resources.displayMetrics
    )

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

        val points = historicalPoints.takeLast(10).sortedBy { it.first }
        if (points.isEmpty()) return

        val chartLeft = outerPad + leftAxisGutter
        val chartTop = outerPad + topPad
        val chartRight = (w - outerPad - rightPad).coerceAtLeast(chartLeft + 1f)
        val chartBottom = (h - outerPad - bottomPad).coerceAtLeast(chartTop + 1f)
        val chartW = (chartRight - chartLeft).coerceAtLeast(1f)
        val chartH = (chartBottom - chartTop).coerceAtLeast(1f)

        fun yOf(level: Float): Float {
            val clamped = level.coerceIn(0f, 100f)
            return chartTop + chartH - (clamped / 100f) * chartH
        }

        val historyStartMs = points.first().first
        val historyEndMs = points.last().first
        val historySpanMs = (historyEndMs - historyStartMs).coerceAtLeast(1L)

        val forecastEndMs = predictionEndMs?.takeIf { it > historyEndMs }
        val hasForecast = forecastEndMs != null
        val forecastSpanMs = forecastEndMs?.let { (it - historyEndMs).coerceAtLeast(1L) } ?: 0L

        val naturalHistoryFraction = if (hasForecast) {
            (historySpanMs.toDouble() / (historySpanMs + forecastSpanMs).toDouble()).toFloat()
        } else {
            1f
        }

        val historyFraction = if (hasForecast) {
            naturalHistoryFraction.coerceIn(minHistoryFractionWithPrediction, 0.78f)
        } else {
            1f
        }

        val historyRight = chartLeft + chartW * historyFraction

        fun xOf(timestampMs: Long): Float {
            val clamped = timestampMs.coerceIn(historyStartMs, historyEndMs)
            val frac = ((clamped - historyStartMs).toDouble() / historySpanMs.toDouble()).toFloat()
            return chartLeft + frac * (historyRight - chartLeft)
        }

        // Subtle horizontal guides + percentage labels to mimic the reference readability.
        val axisLevels = floatArrayOf(75f, 50f, 25f, 0f)
        axisLevels.forEach { level ->
            val y = yOf(level)
            canvas.drawLine(chartLeft, y, chartRight, y, gridPaint)
            canvas.drawText("${level.toInt()}%", outerPad, y + axisTextPaint.textSize * 0.35f, axisTextPaint)
        }

        linePath.reset()
        linePath.moveTo(xOf(points.first().first), yOf(points.first().second))
        for (index in 1 until points.size) {
            linePath.lineTo(xOf(points[index].first), yOf(points[index].second))
        }

        fillPath.set(linePath)
        val latestX = xOf(points.last().first)
        fillPath.lineTo(latestX, chartBottom)
        fillPath.lineTo(xOf(points.first().first), chartBottom)
        fillPath.close()

        if (cachedFillShader == null || cachedShaderTop != chartTop || cachedShaderBottom != chartBottom) {
            cachedFillShader = LinearGradient(
                0f,
                chartTop,
                0f,
                chartBottom,
                C_FILL_TOP,
                C_FILL_BOTTOM,
                Shader.TileMode.CLAMP
            )
            cachedShaderTop = chartTop
            cachedShaderBottom = chartBottom
        }
        fillPaint.shader = cachedFillShader
        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(linePath, linePaint)

        // Draw predicted descent to zero as a dashed segment when ETA is available.
        if (forecastEndMs != null) {
            predictionPath.reset()
            predictionPath.moveTo(latestX, yOf(points.last().second))
            predictionPath.lineTo(chartRight, yOf(0f))
            canvas.drawPath(predictionPath, predictionPaint)

            val labelX = (chartRight - dp(20f)).coerceAtLeast(chartLeft)
            canvas.drawText("0%", labelX, yOf(0f) - dp(4f), endLabelPaint)
        }

        // Single prominent marker at the latest real sample.
        val latestY = yOf(points.last().second)
        markerPaint.color = C_MARKER_OUTER
        canvas.drawCircle(latestX, latestY, dp(5f), markerPaint)
        markerPaint.color = C_MARKER_INNER
        canvas.drawCircle(latestX, latestY, dp(2.8f), markerPaint)
    }
}
