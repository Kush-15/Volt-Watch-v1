package com.example.myapplication

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Lightweight OLS engine for battery time-remaining prediction.
 *
 * Rules implemented:
 * 1) Hybrid trigger: run only every 5 minutes, or when battery drops by >= 2%.
 * 2) OLS fit over recent samples with explicit slope/intercept math.
 * 3) Sanity gate: slope >= 0 returns INVALID_PREDICTION_HOURS.
 * 4) SMA smoothing over the latest 5 successful predictions.
 */
class PredictionEngine(
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private val predictionSmaWindow = ArrayDeque<Double>()

    fun reset() {
        predictionSmaWindow.clear()
    }

    /**
     * Decides whether prediction math should run for the current tick.
     */
    fun shouldRunPrediction(
        currentTimeMs: Long,
        lastRunTimeMs: Long,
        currentBatteryLevel: Float,
        lastBatteryLevel: Float
    ): Boolean {
        val elapsedMs = currentTimeMs - lastRunTimeMs
        val batteryDrop = lastBatteryLevel - currentBatteryLevel
        return elapsedMs >= MIN_RETRAIN_INTERVAL_MS || batteryDrop >= MIN_RETRAIN_DROP_PERCENT
    }

    /**
     * Fits y = m*x + b where:
     * - x: elapsed minutes from the first sample
     * - y: battery percentage
     *
     * Returns the smoothed prediction in hours by averaging the last 5 successful runs.
     * Returns INVALID_PREDICTION_HOURS when math is not reliable.
     */
    suspend fun predictRemainingHours(samples: List<BatterySample>): PredictionResult =
        withContext(computeDispatcher) {
            if (samples.size < 2) {
                return@withContext PredictionResult.invalid()
            }

            val firstTime = samples.first().timestampEpochMillis
            val n = samples.size.toDouble()

            var sumX = 0.0
            var sumY = 0.0
            var sumXY = 0.0
            var sumX2 = 0.0

            for (sample in samples) {
                val xMinutes = (sample.timestampEpochMillis - firstTime) / 60_000.0
                val y = sample.batteryLevel.toDouble()
                sumX += xMinutes
                sumY += y
                sumXY += xMinutes * y
                sumX2 += xMinutes * xMinutes
            }

            val denominator = (n * sumX2) - (sumX * sumX)
            if (abs(denominator) < 1e-12) {
                return@withContext PredictionResult.invalid()
            }

            val slope = ((n * sumXY) - (sumX * sumY)) / denominator
            val intercept = (sumY - (slope * sumX)) / n

            // If battery is flat/rising, OLS cannot estimate time-to-zero reliably.
            if (slope >= 0.0) {
                return@withContext PredictionResult(
                    slope = slope,
                    intercept = intercept,
                    rawHours = INVALID_PREDICTION_HOURS,
                    smoothedHours = INVALID_PREDICTION_HOURS
                )
            }

            val currentBattery = samples.last().batteryLevel.toDouble()
            val rawMinutesToZero = currentBattery / -slope
            val rawHours = rawMinutesToZero / 60.0

            if (rawHours.isNaN() || rawHours.isInfinite() || rawHours <= 0.0) {
                return@withContext PredictionResult(
                    slope = slope,
                    intercept = intercept,
                    rawHours = INVALID_PREDICTION_HOURS,
                    smoothedHours = INVALID_PREDICTION_HOURS
                )
            }

            if (predictionSmaWindow.size == SMA_WINDOW_SIZE) {
                predictionSmaWindow.removeFirst()
            }
            predictionSmaWindow.addLast(rawHours)

            val smoothedHours = predictionSmaWindow.average()
            PredictionResult(
                slope = slope,
                intercept = intercept,
                rawHours = rawHours,
                smoothedHours = smoothedHours
            )
        }

    companion object {
        const val MIN_RETRAIN_INTERVAL_MS = 300_000L
        const val MIN_RETRAIN_DROP_PERCENT = 2.0f
        const val SMA_WINDOW_SIZE = 5
        const val INVALID_PREDICTION_HOURS = -1.0
    }
}

data class PredictionResult(
    val slope: Double,
    val intercept: Double,
    val rawHours: Double,
    val smoothedHours: Double
) {
    companion object {
        fun invalid(): PredictionResult = PredictionResult(
            slope = Double.NaN,
            intercept = Double.NaN,
            rawHours = PredictionEngine.INVALID_PREDICTION_HOURS,
            smoothedHours = PredictionEngine.INVALID_PREDICTION_HOURS
        )
    }
}

