package com.example.myapplication

import kotlin.math.floor

object BatteryPredictionUiFormatter {
    // Lowered from 20 → 6 to match minSamplesToFit.  After unplugging, the OLS
    // session window resets, so we want predictions to appear quickly once there
    // is enough data.  The 24-hour sanity filter still guards against wild numbers.
    const val COLD_START_MIN_SAMPLES = 6
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
            return "24h+ remaining"
        }

        val totalMinutes = floor(hours * 60.0).toInt().coerceAtLeast(0)
        val displayHours = totalMinutes / 60
        val displayMinutes = totalMinutes % 60
        return "${displayHours}h ${displayMinutes}m remaining"
    }
}

