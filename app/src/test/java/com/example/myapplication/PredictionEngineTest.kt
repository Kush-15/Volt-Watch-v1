package com.example.myapplication

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PredictionEngineTest {

    @Test
    fun shouldRun_whenFiveMinutesElapsed() {
        val engine = PredictionEngine()
        val now = 1_000_000L
        val shouldRun = engine.shouldRunPrediction(
            currentTimeMs = now,
            lastRunTimeMs = now - 300_000L,
            currentBatteryLevel = 80f,
            lastBatteryLevel = 81f
        )
        assertTrue(shouldRun)
    }

    @Test
    fun shouldRun_whenBatteryDropsByTwoPercent() {
        val engine = PredictionEngine()
        val now = 1_000_000L
        val shouldRun = engine.shouldRunPrediction(
            currentTimeMs = now,
            lastRunTimeMs = now - 60_000L,
            currentBatteryLevel = 78f,
            lastBatteryLevel = 80f
        )
        assertTrue(shouldRun)
    }

    @Test
    fun predict_returnsInvalid_whenSlopeIsPositive() = runBlocking {
        val engine = PredictionEngine()
        val samples = buildLinearSamples(
            count = 10,
            startLevel = 50f,
            deltaPerMinute = 0.2f
        )

        val result = engine.predictRemainingHours(samples)

        assertEquals(PredictionEngine.INVALID_PREDICTION_HOURS, result.rawHours, 0.0)
        assertEquals(PredictionEngine.INVALID_PREDICTION_HOURS, result.smoothedHours, 0.0)
    }

    @Test
    fun predict_smoothsOutput_withAsymmetricEma() = runBlocking {
        val engine = PredictionEngine()

        val r1 = engine.predictRemainingHours(buildLinearSamples(50, 80f, -0.30f)).smoothedHours
        val r2 = engine.predictRemainingHours(buildLinearSamples(50, 80f, -0.25f)).smoothedHours
        val r3 = engine.predictRemainingHours(buildLinearSamples(50, 80f, -0.20f)).smoothedHours
        val r4 = engine.predictRemainingHours(buildLinearSamples(50, 80f, -0.18f)).smoothedHours
        val r5 = engine.predictRemainingHours(buildLinearSamples(50, 80f, -0.16f)).smoothedHours

        assertTrue(r1 > 0.0)
        assertTrue(r2 > 0.0)
        assertTrue(r3 > 0.0)
        assertTrue(r4 > 0.0)
        assertTrue(r5 > 0.0)

        // EMA should damp sudden drops but still track slower-drain recovery.
        assertTrue(r5 in 4.0..10.0)
    }

    private fun buildLinearSamples(
        count: Int,
        startLevel: Float,
        deltaPerMinute: Float
    ): List<BatterySample> {
        val t0 = 1_700_000_000_000L
        return List(count) { idx ->
            BatterySample(
                timestampEpochMillis = t0 + idx * 60_000L,
                batteryLevel = startLevel + (idx * deltaPerMinute),
                voltage = 4000,
                servicesActive = true,
                foreground = false,
                isCharging = false
            )
        }
    }
}

