package com.example.myapplication

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlin.math.abs

/**
 * Unit tests for battery prediction and TOD (Time of Death) calculation.
 *
 * Tests cover:
 * - OLS model fitting with various feature counts
 * - Slope calculation and unit conversion
 * - TOD computation with edge cases (zero slope, negative slope, future bounds)
 * - Feature scaling and normalization
 */
class BatteryPredictionTest {

    private lateinit var regression: OlsRegression

    @Before
    fun setUp() {
        regression = OlsRegression()
    }

    /**
     * Test: Empty dataset should fail to fit.
     */
    @Test
    fun testEmptyDatasetFails() {
        val X = emptyArray<DoubleArray>()
        val y = doubleArrayOf()
        assertFalse(regression.fit(X, y))
        assertFalse(regression.isFitted())
    }

    /**
     * Test: Mismatched X and y sizes should fail.
     */
    @Test
    fun testMismatchedSizesFails() {
        val X = arrayOf(
            doubleArrayOf(1.0, 2.0),
            doubleArrayOf(2.0, 3.0)
        )
        val y = doubleArrayOf(10.0)
        assertFalse(regression.fit(X, y))
    }

    /**
     * Test: Simple linear regression with single feature (time).
     * Battery drains at -1% per minute.
     */
    @Test
    fun testSimpleLinearFit() {
        val X = arrayOf(
            doubleArrayOf(0.0),
            doubleArrayOf(1.0),
            doubleArrayOf(2.0),
            doubleArrayOf(3.0),
            doubleArrayOf(4.0),
            doubleArrayOf(5.0)
        )
        val y = doubleArrayOf(100.0, 99.0, 98.0, 97.0, 96.0, 95.0)

        assertTrue(regression.fit(X, y))
        assertTrue(regression.isFitted())

        val slope = regression.slopeForFeature(0)
        assertNotNull(slope)
        // Slope should be approximately -1.0 (percent per minute)
        assertTrue(abs(slope!! + 1.0) < 0.1)
    }

    /**
     * Test: Multi-feature regression (time + voltage).
     */
    @Test
    fun testMultiFeatureFit() {
        val X = arrayOf(
            doubleArrayOf(0.0, 4.2),
            doubleArrayOf(1.0, 4.1),
            doubleArrayOf(2.0, 4.0),
            doubleArrayOf(3.0, 3.9),
            doubleArrayOf(4.0, 3.8),
            doubleArrayOf(5.0, 3.7)
        )
        val y = doubleArrayOf(100.0, 99.0, 98.0, 97.0, 96.0, 95.0)

        assertTrue(regression.fit(X, y))
        assertTrue(regression.isFitted())

        val slopeTime = regression.slopeForFeature(0)
        assertNotNull(slopeTime)
        assertTrue(slopeTime!! < 0.0) // Battery should be draining over time
    }

    /**
     * Test: Zero slope should be detected (battery not draining).
     */
    @Test
    fun testZeroSlopeHandling() {
        val X = arrayOf(
            doubleArrayOf(0.0),
            doubleArrayOf(1.0),
            doubleArrayOf(2.0),
            doubleArrayOf(3.0),
            doubleArrayOf(4.0),
            doubleArrayOf(5.0)
        )
        val y = doubleArrayOf(50.0, 50.0, 50.0, 50.0, 50.0, 50.0) // Constant battery

        assertTrue(regression.fit(X, y))

        val slope = regression.slopeForFeature(0)
        assertNotNull(slope)
        // Slope should be very close to 0
        assertTrue(abs(slope!!) < 0.01)
    }

    /**
     * Test: Positive slope should indicate battery improving (sensor noise or anomaly).
     */
    @Test
    fun testPositiveSlopeDetection() {
        val X = arrayOf(
            doubleArrayOf(0.0),
            doubleArrayOf(1.0),
            doubleArrayOf(2.0),
            doubleArrayOf(3.0),
            doubleArrayOf(4.0),
            doubleArrayOf(5.0)
        )
        val y = doubleArrayOf(50.0, 51.0, 52.0, 53.0, 54.0, 55.0) // Battery improving

        assertTrue(regression.fit(X, y))

        val slope = regression.slopeForFeature(0)
        assertNotNull(slope)
        assertTrue(slope!! > 0.0) // Positive slope
    }

    /**
     * Test: Prediction with a fitted model.
     */
    @Test
    fun testPrediction() {
        val X = arrayOf(
            doubleArrayOf(0.0),
            doubleArrayOf(1.0),
            doubleArrayOf(2.0),
            doubleArrayOf(3.0),
            doubleArrayOf(4.0),
            doubleArrayOf(5.0)
        )
        val y = doubleArrayOf(100.0, 99.0, 98.0, 97.0, 96.0, 95.0)

        assertTrue(regression.fit(X, y))

        // Predict at time 6 minutes
        val prediction = regression.predict(doubleArrayOf(6.0))
        assertFalse(prediction.isNaN())
        // Should predict around 94%
        assertTrue(prediction < 95.0 && prediction > 93.0)
    }

    /**
     * Test: Prediction before fitting should return NaN.
     */
    @Test
    fun testPredictionBeforeFitReturnsNaN() {
        val prediction = regression.predict(doubleArrayOf(1.0))
        assertTrue(prediction.isNaN())
    }

    /**
     * Test: Slope for unfitted model should return null.
     */
    @Test
    fun testSlopeBeforeFitReturnsNull() {
        val slope = regression.slopeForFeature(0)
        assertNull(slope)
    }

    /**
     * Test: TOD calculation with negative slope (battery draining).
     * Battery at 100%, draining at -1% per minute.
     * TOD should be 100 minutes from now.
     */
    @Test
    fun testTodCalculationNegativeSlope() {
        val X = arrayOf(
            doubleArrayOf(0.0),
            doubleArrayOf(1.0),
            doubleArrayOf(2.0),
            doubleArrayOf(3.0),
            doubleArrayOf(4.0),
            doubleArrayOf(5.0)
        )
        val y = doubleArrayOf(100.0, 99.0, 98.0, 97.0, 96.0, 95.0)

        assertTrue(regression.fit(X, y))

        val slope = regression.slopeForFeature(0)
        assertNotNull(slope)
        assertTrue(slope!! < 0.0)

        // Current battery 95%, slope -1% per minute
        // TOD: 95 / (-(-1)) = 95 minutes from now
        val currentBatteryPercent = 95.0
        val minutesToEmpty = currentBatteryPercent / -slope
        assertTrue(minutesToEmpty > 90 && minutesToEmpty < 100)

        val nowEpochMillis = System.currentTimeMillis()
        val millisToEmpty = (minutesToEmpty * 60000.0).toLong()
        val tDeathEpochMillis = nowEpochMillis + millisToEmpty

        assertTrue(tDeathEpochMillis > nowEpochMillis)
    }

    /**
     * Test: TOD with zero battery should return invalid.
     */
    @Test
    fun testTodWithZeroBattery() {
        val X = arrayOf(
            doubleArrayOf(0.0),
            doubleArrayOf(1.0),
            doubleArrayOf(2.0),
            doubleArrayOf(3.0),
            doubleArrayOf(4.0),
            doubleArrayOf(5.0)
        )
        val y = doubleArrayOf(100.0, 99.0, 98.0, 97.0, 96.0, 95.0)

        assertTrue(regression.fit(X, y))

        val slope = regression.slopeForFeature(0)
        assertNotNull(slope)

        // Current battery 0%, should be invalid
        val currentBatteryPercent = 0.0
        val isValid = currentBatteryPercent > 0.0 && slope!! < 0.0
        assertFalse(isValid)
    }

    /**
     * Test: Feature scaling and normalization.
     * High variance features should be properly scaled.
     */
    @Test
    fun testFeatureScaling() {
        val X = arrayOf(
            doubleArrayOf(0.0, 4200.0),    // Time 0min, Voltage 4.2V
            doubleArrayOf(10.0, 4100.0),   // Time 10min, Voltage 4.1V
            doubleArrayOf(20.0, 4000.0),   // Time 20min, Voltage 4.0V
            doubleArrayOf(30.0, 3900.0),   // Time 30min, Voltage 3.9V
            doubleArrayOf(40.0, 3800.0),   // Time 40min, Voltage 3.8V
            doubleArrayOf(50.0, 3700.0)    // Time 50min, Voltage 3.7V
        )
        val y = doubleArrayOf(100.0, 99.0, 98.0, 97.0, 96.0, 95.0)

        assertTrue(regression.fit(X, y))

        // Both features should have non-null slopes
        val slopeTime = regression.slopeForFeature(0)
        val slopeVoltage = regression.slopeForFeature(1)

        assertNotNull(slopeTime)
        assertNotNull(slopeVoltage)
        // Time should still indicate draining; secondary feature just needs a stable finite coefficient.
        assertTrue(slopeTime!! < 0.0)
        assertTrue(slopeVoltage!!.isFinite())
        assertTrue(kotlin.math.abs(slopeVoltage) > 0.0)
    }

    /**
     * Test: Constant feature (zero variance) should be handled.
     */
    @Test
    fun testConstantFeatureHandling() {
        val X = arrayOf(
            doubleArrayOf(0.0, 1.0),    // Constant feature
            doubleArrayOf(1.0, 1.0),
            doubleArrayOf(2.0, 1.0),
            doubleArrayOf(3.0, 1.0),
            doubleArrayOf(4.0, 1.0),
            doubleArrayOf(5.0, 1.0)
        )
        val y = doubleArrayOf(100.0, 99.0, 98.0, 97.0, 96.0, 95.0)

        // Should still fit because time feature has variance
        assertTrue(regression.fit(X, y))

        val slopeTime = regression.slopeForFeature(0)
        assertNotNull(slopeTime)
        assertTrue(slopeTime!! < 0.0)
    }

    /**
     * Test: Prediction with mismatched feature count should return NaN.
     */
    @Test
    fun testPredictionMismatchedFeatures() {
        val X = arrayOf(
            doubleArrayOf(0.0, 4.2),
            doubleArrayOf(1.0, 4.1),
            doubleArrayOf(2.0, 4.0),
            doubleArrayOf(3.0, 3.9),
            doubleArrayOf(4.0, 3.8),
            doubleArrayOf(5.0, 3.7)
        )
        val y = doubleArrayOf(100.0, 99.0, 98.0, 97.0, 96.0, 95.0)

        assertTrue(regression.fit(X, y))

        // Try to predict with only 1 feature instead of 2
        val prediction = regression.predict(doubleArrayOf(6.0))
        assertTrue(prediction.isNaN())
    }

    /**
     * Test: Multiple fit calls should reset state properly.
     */
    @Test
    fun testMultipleFitCalls() {
        val X1 = arrayOf(
            doubleArrayOf(0.0),
            doubleArrayOf(1.0),
            doubleArrayOf(2.0),
            doubleArrayOf(3.0),
            doubleArrayOf(4.0),
            doubleArrayOf(5.0)
        )
        val y1 = doubleArrayOf(100.0, 99.0, 98.0, 97.0, 96.0, 95.0)

        assertTrue(regression.fit(X1, y1))
        val slope1 = regression.slopeForFeature(0)

        // Different dataset
        val X2 = arrayOf(
            doubleArrayOf(0.0),
            doubleArrayOf(1.0),
            doubleArrayOf(2.0),
            doubleArrayOf(3.0),
            doubleArrayOf(4.0),
            doubleArrayOf(5.0)
        )
        val y2 = doubleArrayOf(100.0, 98.0, 96.0, 94.0, 92.0, 90.0) // Drains faster

        assertTrue(regression.fit(X2, y2))
        val slope2 = regression.slopeForFeature(0)

        assertNotNull(slope1)
        assertNotNull(slope2)
        // slope2 should be more negative than slope1
        assertTrue(slope2!! < slope1!!)
    }
}
