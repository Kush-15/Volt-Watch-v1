package com.example.myapplication

import org.junit.Assert.assertEquals
import org.junit.Test

class BatteryPredictionUiFormatterTest {

    @Test
    fun coldStart_showsLearningMessage_whenSampleCountBelow20() {
        val text = BatteryPredictionUiFormatter.remainingText(sampleCount = 19, rawPredictedHours = 3.5)
        assertEquals("Learning your habits...", text)
    }

    @Test
    fun chargingOrFlat_showsCalculating_whenHoursIsNullOrNonPositive() {
        assertEquals(
            "Calculating...",
            BatteryPredictionUiFormatter.remainingText(sampleCount = 20, rawPredictedHours = null)
        )
        assertEquals(
            "Calculating...",
            BatteryPredictionUiFormatter.remainingText(sampleCount = 20, rawPredictedHours = 0.0)
        )
        assertEquals(
            "Calculating...",
            BatteryPredictionUiFormatter.remainingText(sampleCount = 20, rawPredictedHours = -2.0)
        )
    }

    @Test
    fun idleOrUnrealistic_showsBatteryStable_whenHoursAbove48() {
        val text = BatteryPredictionUiFormatter.remainingText(sampleCount = 20, rawPredictedHours = 48.01)
        assertEquals("Battery Stable", text)
    }

    @Test
    fun normalOperation_formatsHoursAndMinutes() {
        val text = BatteryPredictionUiFormatter.remainingText(sampleCount = 20, rawPredictedHours = 2.75)
        assertEquals("2h 45m remaining", text)
    }
}

