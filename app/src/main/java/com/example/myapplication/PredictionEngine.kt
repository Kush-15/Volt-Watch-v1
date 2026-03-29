package com.example.myapplication

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
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
    private var previousEtaHours: Double? = null

    fun reset() {
        previousEtaHours = null
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
    suspend fun predictRemainingHours(samples: List<BatterySample>): PredictionResult =
        try {
            withContext(computeDispatcher) {
                if (samples.size < MIN_SAMPLES_FOR_PREDICTION) {
                    return@withContext PredictionResult.invalid()
                }

            val window = samples.takeLast(OLS_WINDOW_SIZE)
            val anchors = buildOnePercentDropAnchors(window)

            if (anchors.size < 2) {
                return@withContext PredictionResult.invalid()
            }

            // Weighted OLS: assign higher weights to more recent samples
            val n = anchors.size
            var sumW = 0.0
            var sumWX = 0.0
            var sumWY = 0.0
            var sumWXY = 0.0
            var sumWX2 = 0.0
            var elapsedMinutes = 0.0

            // Linear weights: oldest=1, newest=n
            for (index in anchors.indices) {
                val sample = anchors[index]
                if (index > 0) {
                    val deltaMs = (sample.timestampEpochMillis - anchors[index - 1].timestampEpochMillis)
                        .coerceAtLeast(0L)
                    elapsedMinutes += deltaMs / 60_000.0
                }
                val xMinutes = elapsedMinutes
                val y = sample.batteryLevel.toDouble()
                val weight = (index + 1).toDouble() // Linear: 1, 2, ..., n
                sumW += weight
                sumWX += weight * xMinutes
                sumWY += weight * y
                sumWXY += weight * xMinutes * y
                sumWX2 += weight * xMinutes * xMinutes
            }

            val denominator = (sumW * sumWX2) - (sumWX * sumWX)
            if (abs(denominator) < 1e-12) {
                return@withContext PredictionResult.invalid()
            }

            val slope = ((sumW * sumWXY) - (sumWX * sumWY)) / denominator
            val intercept = (sumWY - (slope * sumWX)) / sumW

            // If battery is flat/rising, OLS cannot estimate time-to-zero reliably.
            if (slope >= 0.0) {
                return@withContext PredictionResult(
                    slope = slope,
                    intercept = intercept,
                    rawHours = INVALID_PREDICTION_HOURS,
                    smoothedHours = INVALID_PREDICTION_HOURS
                )
            }

            val currentBattery = anchors.last().batteryLevel.toDouble()
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

            // Bound raw ETA jumps against the previous UI ETA to avoid violent one-tick swings.
            val boundedRawHours = boundRawEta(rawHours, previousEtaHours)

            val alpha = when {
                previousEtaHours == null -> 1.0
                boundedRawHours > previousEtaHours!! -> EMA_ALPHA_RECOVERY
                else -> EMA_ALPHA_DECAY
            }

            val smoothedHours = previousEtaHours
                ?.let { (alpha * boundedRawHours) + ((1.0 - alpha) * it) }
                ?: boundedRawHours

            previousEtaHours = smoothedHours
            PredictionResult(
                slope = slope,
                intercept = intercept,
                rawHours = rawHours,
                smoothedHours = smoothedHours
            )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            PredictionResult.invalid()
        }

    companion object {
        const val MIN_RETRAIN_INTERVAL_MS = 300_000L
        const val MIN_RETRAIN_DROP_PERCENT = 2.0f
        const val OLS_WINDOW_SIZE = 20
        const val MIN_SAMPLES_FOR_PREDICTION = 5
        const val INVALID_PREDICTION_HOURS = -1.0
        private const val MIN_LEVEL_DROP_STEP_PERCENT = 1.0f
        private const val EMA_ALPHA_DECAY = 0.3
        private const val EMA_ALPHA_RECOVERY = 0.7
        private const val MIN_ETA_STEP_FACTOR = 0.6
        private const val MAX_ETA_STEP_FACTOR = 1.8
        private const val MAX_ABSOLUTE_ETA_HOURS = 48.0
    }

    private fun boundRawEta(rawHours: Double, previousHours: Double?): Double {
        val absoluteBounded = rawHours.coerceIn(0.01, MAX_ABSOLUTE_ETA_HOURS)
        val previous = previousHours ?: return absoluteBounded
        val minAllowed = previous * MIN_ETA_STEP_FACTOR
        val maxAllowed = previous * MAX_ETA_STEP_FACTOR
        return absoluteBounded.coerceIn(minAllowed, maxAllowed)
    }

    private fun buildOnePercentDropAnchors(samples: List<BatterySample>): List<BatterySample> {
        if (samples.isEmpty()) return emptyList()

        val anchors = ArrayList<BatterySample>(samples.size)
        var lastAnchor = samples.first()
        anchors.add(lastAnchor)

        for (index in 1 until samples.size) {
            val candidate = samples[index]
            val droppedBy = lastAnchor.batteryLevel - candidate.batteryLevel
            if (droppedBy >= MIN_LEVEL_DROP_STEP_PERCENT) {
                anchors.add(candidate)
                lastAnchor = candidate
            }
        }

        return anchors
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

