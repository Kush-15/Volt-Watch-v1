package com.example.myapplication

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Data Preparation Layer: All session, filtering, and data quality logic lives here.
 *
 * SessionManager is responsible for:
 * 1. Fetching raw samples from the repository
 * 2. Filtering out charging rows
 * 3. Removing duplicate battery levels (keep only rows where battery dropped >= 1%)
 * 4. Detecting and cutting at the latest charging/session reset boundary
 * 5. Validating minimum sample count
 * 6. Returning a clean window of last 20 samples to PredictionEngine
 *
 * Non-negotiable rule: SessionManager has ZERO database writes, ZERO ML math, ZERO UI logic.
 * It only transforms data passed from BatteryRepository into a clean format for PredictionEngine.
 */
class SessionManager(
    private val repository: BatteryRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private val logTag = "SessionManager"
    private var sessionBoundaryReferenceBattery: Float? = null

    /**
     * Main entry point: Prepare clean samples for ML prediction.
     *
     * Flow:
     * 1. Fetch raw session window from repository (everything since last charge)
     * 2. Filter out charging rows
     * 3. Remove duplicate levels (1% drop threshold)
     * 4. Detect session reset boundary and keep only the most recent continuous block
     * 5. Validate sample count (need >= MIN_SAMPLES for ML)
     * 6. Return trimmed to WINDOW_SIZE
     */
    suspend fun prepareCleanSamplesForPrediction(
        currentSystemBatteryLevel: Float
    ): List<BatterySample> = withContext(ioDispatcher) {
        // Step 1: Fetch raw session data from storage layer.
        val rawSession = repository.getRecentDischargingWindow(currentSystemBatteryLevel)
        Log.d(
            logTag,
            "prepareCleanSamples rawCount=${rawSession.size} systemLevel=${currentSystemBatteryLevel.toInt()}%"
        )
        if (rawSession.isEmpty()) {
            Log.w(logTag, "prepareCleanSamples -> 0 (repository returned empty)")
            return@withContext emptyList()
        }

        // Step 2: Filter out all charging rows (isCharging = 1 must not touch ML).
        val nonChargingOnly = rawSession.filter { !it.isCharging }
        Log.d(logTag, "prepareCleanSamples afterChargingRemoval=${nonChargingOnly.size}")
        if (nonChargingOnly.isEmpty()) {
            Log.w(logTag, "prepareCleanSamples -> 0 (all rows were charging markers)")
            return@withContext emptyList()
        }

        // Step 3: Remove duplicates by keeping only rows where battery dropped >= 1%.
        val deduplicated = removeDuplicateLevels(nonChargingOnly)
        Log.d(logTag, "prepareCleanSamples afterDuplicateRemoval=${deduplicated.size}")
        if (deduplicated.isEmpty()) {
            Log.w(logTag, "prepareCleanSamples -> 0 (all rows dropped as duplicates)")
            return@withContext emptyList()
        }

        // Step 4: Detect session boundary; keep only the latest continuous discharge block.
        val afterSessionBoundaryCut = keepMostRecentContinuousBlock(deduplicated)
        Log.d(logTag, "prepareCleanSamples afterSessionBoundaryCut=${afterSessionBoundaryCut.size}")
        if (afterSessionBoundaryCut.isEmpty()) {
            Log.w(logTag, "prepareCleanSamples -> 0 (session-boundary isolation removed all rows)")
            return@withContext emptyList()
        }

        // Step 5: Validate minimum sample count.
        val hasPredictionMinimum = afterSessionBoundaryCut.size >= MIN_SAMPLES_FOR_PREDICTION
        Log.d(
            logTag,
            "prepareCleanSamples minimumGate passed=$hasPredictionMinimum count=${afterSessionBoundaryCut.size} min=$MIN_SAMPLES_FOR_PREDICTION"
        )
        if (!hasPredictionMinimum) {
            // Keep returning the cleaned rows so UI can display true sample progress during cold start.
            val coldStartWindow = afterSessionBoundaryCut.takeLast(WINDOW_SIZE)
            Log.d(logTag, "prepareCleanSamples finalCount=${coldStartWindow.size} (cold-start window)")
            return@withContext coldStartWindow
        }

        // Step 6: Trim to the window size used by ML (last 20 samples = most recent 20% drain).
        val finalWindow = afterSessionBoundaryCut.takeLast(WINDOW_SIZE)
        Log.d(logTag, "prepareCleanSamples finalCount=${finalWindow.size}")
        finalWindow
    }

    /**
     * Remove duplicate battery levels.
     * Logic: Keep a sample only if its battery level is strictly < previous sample's level.
     * This ensures each row represents at least a 1% drop, no flat lines.
     */
    private fun removeDuplicateLevels(chronological: List<BatterySample>): List<BatterySample> {
        if (chronological.isEmpty()) return emptyList()

        val deduplicated = ArrayList<BatterySample>(chronological.size)
        deduplicated.add(chronological[0]) // TIME HEARTBEAT FIX

        for (i in 1 until chronological.size) {
            val current = chronological[i]
            val previous = deduplicated.last()

            val dropped = current.batteryLevel < previous.batteryLevel // TIME HEARTBEAT FIX
            val heartbeatPlateau = current.batteryLevel == previous.batteryLevel && (current.timestampEpochMillis - previous.timestampEpochMillis) > TIME_HEARTBEAT_MS // TIME HEARTBEAT FIX

            if (dropped || heartbeatPlateau) { // TIME HEARTBEAT FIX
                deduplicated.add(current) // TIME HEARTBEAT FIX
            }
        }

        return deduplicated
    }

    /**
     * Detect and cut at the latest session boundary.
     * Logic: Walk backward from newest sample and stop only when a strict reset condition is met.
     * No time-gap based reset is applied, so a discharge session survives long idle periods.
     */
    private fun keepMostRecentContinuousBlock(
        chronological: List<BatterySample>
    ): List<BatterySample> {
        if (chronological.isEmpty()) return emptyList()

        val result = ArrayList<BatterySample>(chronological.size)
        var current = chronological.last() // Start from the newest (current).
        result.add(current)

        // Walk backward (from newest to oldest) until strict session-reset conditions are met.
        for (i in chronological.lastIndex - 1 downTo 0) {
            val previous = chronological[i]

            // Set the reference level so shouldStartNewSession can detect a real charge jump.
            sessionBoundaryReferenceBattery = previous.batteryLevel

            // Break only on explicit charging or a >=2% upward jump.
            if (shouldStartNewSession(isCharging = current.isCharging, currentBattery = current.batteryLevel)) {
                break
            }

            // Otherwise, add this sample to our continuous block and move to the next older sample.
            result.add(previous)
            current = previous
        }

        // Reverse to get chronological order (oldest to newest).
        return result.asReversed()
    }

    /**
     * Continuous discharge session reset gate.
     * A new session starts only when currently charging or battery jumps up by >= 2%.
     */
    private fun shouldStartNewSession(isCharging: Boolean, currentBattery: Float): Boolean {
        if (isCharging) return true

        val lastRecordedBattery = sessionBoundaryReferenceBattery ?: return false
        return currentBattery >= (lastRecordedBattery + SESSION_RESET_RISE_THRESHOLD_PERCENT)
    }

    companion object {
        // Data quality thresholds.
        private const val SESSION_RESET_RISE_THRESHOLD_PERCENT = 2.0f // >=2% rise indicates a new charge session.
        private const val TIME_HEARTBEAT_MS = 30L * 60L * 1000L // TIME HEARTBEAT FIX

        // ML input constraints.
        private const val MIN_SAMPLES_FOR_PREDICTION = 5 // Need at least 5 samples to start ML.
        private const val WINDOW_SIZE = 25 // OLS window: last 25 samples = last 25% drain rate.
    }
}


