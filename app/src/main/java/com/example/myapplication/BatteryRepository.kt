package com.example.myapplication

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Storage Layer: Raw data read/write only.
 *
 * LAYER 1 (Storage-Only): BatteryRepository has single responsibility:
 * - Insert samples (with basic 1% drop gate to prevent duplicates at write time).
 * - Read raw samples from database.
 * - Prune data older than 30 days.
 * - Nothing else. No filtering. No session logic. No ML decisions.
 *
 * All filtering, idle-gap detection, and session reconstruction happens in SessionManager (Layer 2).
 * All ML math happens in PredictionEngine (Layer 3).
 */
class BatteryRepository(
    private val dao: BatterySampleDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private val logTag = "BatteryRepository"

    /**
     * Insert a battery sample. Only persists if battery level is strictly less than the latest sample.
     * This is the ONLY gatekeeper at write time; all other filtering happens in SessionManager.
     */
    suspend fun insertSample(sample: BatterySample): Long = withContext(ioDispatcher) {
        val latest = dao.getLatestSample()
        val sameLevelAsLatest = latest != null && sample.batteryLevel == latest.batteryLevel // TIME HEARTBEAT FIX
        val heartbeatElapsed = latest != null && (sample.timestampEpochMillis - latest.timestampEpochMillis) > TIME_HEARTBEAT_MS // TIME HEARTBEAT FIX
        // Storage-only rule: allow a plateau heartbeat after 30 minutes, but reject true duplicates and rises. // TIME HEARTBEAT FIX
        if (latest == null || sample.batteryLevel < latest.batteryLevel || (sameLevelAsLatest && heartbeatElapsed)) { // TIME HEARTBEAT FIX
            val insertedId = dao.insertSample(sample)
            Log.d(
                logTag,
                "insertSample INSERTED id=$insertedId level=${sample.batteryLevel.toInt()}% ts=${sample.timestampEpochMillis} isCharging=${sample.isCharging} latestBefore=${latest?.batteryLevel?.toInt() ?: "none"}%"
            )
            insertedId
        } else {
            // Insertion rejected; caller must handle -1 gracefully.
            Log.d(
                logTag,
                "insertSample SKIPPED level=${sample.batteryLevel.toInt()}% ts=${sample.timestampEpochMillis} isCharging=${sample.isCharging} latestLevel=${latest.batteryLevel.toInt()}% reason=not_lower_than_latest"
            )
            INSERT_SKIPPED_ID
        }
    }

    /**
     * Insert a sample without any gating (used for charging markers and state transitions).
     * Charging and state rows are raw data that SessionManager will filter out later.
     */
    suspend fun insertStateSample(sample: BatterySample): Long = withContext(ioDispatcher) {
        val insertedId = dao.insertSample(sample)
        Log.d(
            logTag,
            "insertStateSample INSERTED id=$insertedId level=${sample.batteryLevel.toInt()}% ts=${sample.timestampEpochMillis} isCharging=${sample.isCharging}"
        )
        insertedId
    }

    /**
     * Fetch raw session data: everything since the last charging timestamp, or fallback to recent window.
     * This is unfiltered data straight from the database.
     * SessionManager will apply all filtering logic on top of this.
     */
    suspend fun getRecentDischargingWindow(currentSystemBatteryLevel: Float): List<BatterySample> = withContext(ioDispatcher) {
        val latest = dao.getLatestSample()
        Log.d(
            logTag,
            "getRecentDischargingWindow start systemLevel=${currentSystemBatteryLevel.toInt()}% latestDbLevel=${latest?.batteryLevel?.toInt() ?: "none"}% latestDbCharging=${latest?.isCharging ?: "none"} latestTs=${latest?.timestampEpochMillis ?: "none"}"
        )

        // State guard: if real battery jumped up while DB says discharging, DB is stale; wipe it.
        if (
            latest != null &&
            !latest.isCharging &&
            (currentSystemBatteryLevel - latest.batteryLevel) > STALE_LEVEL_JUMP_THRESHOLD_PERCENT
        ) {
            dao.clearAllSamples()
            Log.w(
                logTag,
                "getRecentDischargingWindow stale DB cleared systemLevel=${currentSystemBatteryLevel.toInt()}% latestDbLevel=${latest.batteryLevel.toInt()}%"
            )
            return@withContext emptyList()
        }

        // Get the pivot timestamp (last time device was plugged in).
        val lastChargingTs = dao.getLatestChargingTimestamp()
        Log.d(logTag, "getRecentDischargingWindow latestChargingTimestamp=${lastChargingTs ?: "null"}")

        // Fetch raw samples: either since the last charge, or recent 25 if no charge found.
        val windowDesc = if (lastChargingTs == null) {
            // No charging timestamp found. Only trust recent rows if system level matches DB.
            val canUseFallback =
                latest != null &&
                    !latest.isCharging &&
                    kotlin.math.abs(currentSystemBatteryLevel - latest.batteryLevel) <= NO_PIVOT_LEVEL_MATCH_TOLERANCE

            if (!canUseFallback) {
                Log.w(
                    logTag,
                    "getRecentDischargingWindow no pivot and fallback rejected systemLevel=${currentSystemBatteryLevel.toInt()}% latestDbLevel=${latest?.batteryLevel?.toInt() ?: "none"}%"
                )
                emptyList()
            } else {
                val fallback = dao.getPredictionWindow(RAW_FETCH_WINDOW_SIZE)
                Log.d(logTag, "getRecentDischargingWindow using fallback rows=${fallback.size}")
                fallback
            }
        } else {
            // Pivot found; fetch everything since that timestamp (may include charging rows, duplicates, etc.).
            val sincePivot = dao.getPredictionWindowSince(sinceEpochMillis = lastChargingTs, windowSize = RAW_FETCH_WINDOW_SIZE)
            Log.d(logTag, "getRecentDischargingWindow sincePivot rows=${sincePivot.size} pivotTs=$lastChargingTs")
            sincePivot
        }

        // Return in chronological order (oldest to newest) for SessionManager to process.
        val chronological = windowDesc.asReversed()
        Log.d(
            logTag,
            "getRecentDischargingWindow finalRows=${chronological.size} firstTs=${chronological.firstOrNull()?.timestampEpochMillis ?: "none"} lastTs=${chronological.lastOrNull()?.timestampEpochMillis ?: "none"}"
        )
        chronological
    }

    /**
     * Cleanup: delete rows older than 30 days. Storage-layer concern only.
     */
    suspend fun cleanupHistoricalData(): Unit = withContext(ioDispatcher) {
        dao.deleteOlderThan(System.currentTimeMillis() - THIRTY_DAYS_MS)
    }

    /**
     * Prune samples older than a given epoch timestamp. Storage-layer concern only.
     */
    suspend fun pruneOlderThan(cutoffEpochMillis: Long): Int = withContext(ioDispatcher) {
        dao.deleteOlderThan(cutoffEpochMillis)
    }

    /**
     * Clear all samples. Used when DB is detected as stale or on hard reset.
     */
    suspend fun clearAllSamples(): Unit = withContext(ioDispatcher) {
        dao.clearAllSamples()
    }

    companion object {
        const val INSERT_SKIPPED_ID = -1L
        private const val TIME_HEARTBEAT_MS = 30L * 60L * 1000L // TIME HEARTBEAT FIX
        // Raw fetch window: size to request from DAO (SessionManager will trim to 25 for ML).
        private const val RAW_FETCH_WINDOW_SIZE = 25
        // Storage cleanup only.
        private const val THIRTY_DAYS_MS = 30L * 24L * 60L * 60L * 1000L
        // Stale data detection.
        private const val STALE_LEVEL_JUMP_THRESHOLD_PERCENT = 5.0f
        private const val NO_PIVOT_LEVEL_MATCH_TOLERANCE = 1.0f
    }
}
