package com.example.myapplication

import org.junit.Assert.assertEquals
import org.junit.Test

class BatteryGraphSanitizerTest {

    @Test
    fun buildDisplayPoints_clampsSingleUpwardSpike() {
        val samples = listOf(
            sample(t = 0L, level = 80f),
            sample(t = 60_000L, level = 79.5f),
            sample(t = 120_000L, level = 81.2f), // spike
            sample(t = 180_000L, level = 79.2f)
        )

        val points = BatteryGraphSanitizer.buildDisplayPoints(samples, spikeThresholdPercent = 0.8f)

        assertEquals(4, points.size)
        assertEquals(79.35f, points[2].second, 0.0001f)
    }

    private fun sample(t: Long, level: Float): BatterySample = BatterySample(
        timestampEpochMillis = t,
        batteryLevel = level,
        voltage = 4000,
        servicesActive = true,
        foreground = false,
        isCharging = false
    )
}

