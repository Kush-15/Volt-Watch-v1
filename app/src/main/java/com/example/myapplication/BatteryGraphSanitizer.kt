package com.example.myapplication

/**
 * Cleans visual graph points so short upward anomalies do not render as sharp mountains.
 */
object BatteryGraphSanitizer {

    /**
     * Returns timestamp/level points ready for graph rendering.
     *
     * Strategy:
     * 1) Detect sudden upward spikes above [spikeThresholdPercent].
     * 2) If next point returns to baseline, interpolate current point.
     * 3) Otherwise clamp to previous clean value.
     */
    fun buildDisplayPoints(
        samples: List<BatterySample>,
        spikeThresholdPercent: Float = 0.9f
    ): List<Pair<Long, Float>> {
        if (samples.isEmpty()) return emptyList()

        val cleanLevels = FloatArray(samples.size)
        cleanLevels[0] = samples[0].batteryLevel

        for (i in 1 until samples.size) {
            val previousClean = cleanLevels[i - 1]
            val currentRaw = samples[i].batteryLevel
            val upwardDelta = currentRaw - previousClean

            cleanLevels[i] = if (upwardDelta > spikeThresholdPercent) {
                val nextRaw = samples.getOrNull(i + 1)?.batteryLevel
                val canInterpolate = nextRaw != null && nextRaw <= previousClean + spikeThresholdPercent
                if (canInterpolate) {
                    (previousClean + nextRaw) / 2f
                } else {
                    previousClean
                }
            } else {
                currentRaw
            }
        }

        return samples.mapIndexed { index, sample ->
            Pair(sample.timestampEpochMillis, cleanLevels[index])
        }
    }
}

