package com.example.myapplication

import kotlin.math.floor

/**
 * Presentation-only mapper for remaining-time labels.
 * Keeps filtering rules in UI layer without changing OLS or persistence logic.
 */
object BatteryPredictionUiFormatter {
    const val COLD_START_MIN_SAMPLES = 20
    const val UNREALISTIC_HOURS_THRESHOLD = 24.0

    fun remainingText(sampleCount: Int, rawPredictedHours: Double?): String {
        if (sampleCount < COLD_START_MIN_SAMPLES) {
            return "Learning your habits..."
        }

        val hours = rawPredictedHours
        if (hours == null || hours.isNaN() || hours.isInfinite() || hours <= 0.0) {
            return "Calculating..."
        }

        if (hours > UNREALISTIC_HOURS_THRESHOLD) {
            return "Calculating..."
        }

        val totalMinutes = floor(hours * 60.0).toInt().coerceAtLeast(0)
        val displayHours = totalMinutes / 60
        val displayMinutes = totalMinutes % 60
        return "${displayHours}h ${displayMinutes}m remaining"
    }
}

