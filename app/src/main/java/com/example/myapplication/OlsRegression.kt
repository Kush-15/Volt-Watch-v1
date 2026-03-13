package com.example.myapplication

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Ordinary Least Squares (OLS) regression with standardized feature scaling.
 *
 * Features are normalized (zero mean, unit variance) before fitting.
 * The slope returned by slopeForFeature(i) accounts for this scaling.
 *
 * Feature mask documentation:
 * - bit0: time (required, always enabled)
 * - bit1: voltage
 * - bit2: services active
 * - bit3: usage minutes
 *
 * Slope units:
 * - Feature 0 (time in minutes): slope is in percentage points per minute
 * - Feature 1 (voltage): slope depends on voltage scaling
 * - Feature 2 (services): slope is a coefficient for the binary indicator
 * - Feature 3 (usage): slope is a coefficient for usage minutes
 *
 * TOD (Time of Death) calculation:
 * 1. Get current battery level as a fraction: p_now = batteryLevel / 100.0
 * 2. Ensure slope < 0 (battery is draining)
 * 3. Solve: 0 = p_now + slope * t => t = -p_now / slope
 * 4. Convert to absolute time: tDeathEpochMillis = nowEpochMillis + (t * 60000.0).toLong()
 */
class OlsRegression(private val ridgeLambda: Double = 1e-6) {
    private var featureMeans: DoubleArray = DoubleArray(0)
    private var featureScales: DoubleArray = DoubleArray(0)
    private var coefficients: DoubleArray = DoubleArray(0)
    private var fitted = false

    // Supports any feature count >= 1 via dynamic centering/scaling and normal equation.
    fun fit(X: Array<DoubleArray>, y: DoubleArray): Boolean {
        if (X.isEmpty() || X.size != y.size) {
            fitted = false
            return false
        }

        val featureCount = X[0].size
        if (featureCount == 0) {
            fitted = false
            return false
        }

        for (row in X) {
            if (row.size != featureCount) {
                fitted = false
                return false
            }
        }

        featureMeans = DoubleArray(featureCount)
        featureScales = DoubleArray(featureCount)

        for (i in 0 until featureCount) {
            var sum = 0.0
            for (row in X) {
                sum += row[i]
            }
            featureMeans[i] = sum / X.size.toDouble()
        }

        for (i in 0 until featureCount) {
            var varianceSum = 0.0
            for (row in X) {
                val diff = row[i] - featureMeans[i]
                varianceSum += diff * diff
            }
            val variance = varianceSum / X.size.toDouble()
            val scale = sqrt(variance)
            featureScales[i] = if (scale == 0.0) 1.0 else scale
        }

        val normalized = Array(X.size) { rowIndex ->
            DoubleArray(featureCount + 1) { colIndex ->
                if (colIndex == 0) {
                    1.0
                } else {
                    val featureIndex = colIndex - 1
                    (X[rowIndex][featureIndex] - featureMeans[featureIndex]) / featureScales[featureIndex]
                }
            }
        }

        val xtx = Array(featureCount + 1) { DoubleArray(featureCount + 1) }
        val xty = DoubleArray(featureCount + 1)

        for (rowIndex in normalized.indices) {
            val row = normalized[rowIndex]
            for (i in row.indices) {
                xty[i] += row[i] * y[rowIndex]
                for (j in row.indices) {
                    xtx[i][j] += row[i] * row[j]
                }
            }
        }

        for (i in 1 until xtx.size) {
            xtx[i][i] += ridgeLambda
        }

        val inverted = invertMatrix(xtx) ?: run {
            fitted = false
            return false
        }

        coefficients = DoubleArray(featureCount + 1)
        for (i in coefficients.indices) {
            var value = 0.0
            for (j in xty.indices) {
                value += inverted[i][j] * xty[j]
            }
            coefficients[i] = value
        }

        for (value in coefficients) {
            if (value.isNaN() || value.isInfinite()) {
                fitted = false
                return false
            }
        }

        fitted = true
        return true
    }

    fun predict(x: DoubleArray): Double {
        if (!fitted || x.size != featureMeans.size) return Double.NaN
        var result = coefficients[0]
        for (i in x.indices) {
            val normalized = (x[i] - featureMeans[i]) / featureScales[i]
            result += coefficients[i + 1] * normalized
        }
        return result
    }

    fun slopeForFeature(featureIndex: Int): Double? {
        if (!fitted || featureIndex !in featureMeans.indices) return null
        val scale = featureScales[featureIndex]
        if (abs(scale) < 1e-12) return null
        return coefficients[featureIndex + 1] / scale
    }

    fun isFitted(): Boolean = fitted

    private fun invertMatrix(matrix: Array<DoubleArray>): Array<DoubleArray>? {
        val size = matrix.size
        val augmented = Array(size) { row ->
            DoubleArray(size * 2) { col ->
                when {
                    col < size -> matrix[row][col]
                    col - size == row -> 1.0
                    else -> 0.0
                }
            }
        }

        for (pivot in 0 until size) {
            var maxRow = pivot
            var maxValue = abs(augmented[pivot][pivot])
            for (row in pivot + 1 until size) {
                val value = abs(augmented[row][pivot])
                if (value > maxValue) {
                    maxValue = value
                    maxRow = row
                }
            }

            if (maxValue < 1e-12) return null

            if (maxRow != pivot) {
                val temp = augmented[pivot]
                augmented[pivot] = augmented[maxRow]
                augmented[maxRow] = temp
            }

            val pivotValue = augmented[pivot][pivot]
            for (col in 0 until size * 2) {
                augmented[pivot][col] /= pivotValue
            }

            for (row in 0 until size) {
                if (row == pivot) continue
                val factor = augmented[row][pivot]
                for (col in 0 until size * 2) {
                    augmented[row][col] -= factor * augmented[pivot][col]
                }
            }
        }

        val inverse = Array(size) { DoubleArray(size) }
        for (row in 0 until size) {
            for (col in 0 until size) {
                val value = augmented[row][col + size]
                if (value.isNaN() || value.isInfinite()) return null
                inverse[row][col] = value
            }
        }
        return inverse
    }
}
