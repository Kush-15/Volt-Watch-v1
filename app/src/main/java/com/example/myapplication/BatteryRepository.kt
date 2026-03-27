package com.example.myapplication

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Data access layer for battery telemetry used by prediction.
 *
 * All Room calls are forced onto [Dispatchers.IO] to keep UI and compute threads responsive.
 */
class BatteryRepository(
    private val dao: BatterySampleDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    data class CleanupResult(
        val deletedChargingRows: Int,
        val deletedOrphanSpikes: Int
    )

    suspend fun insertSample(sample: BatterySample): Long = withContext(ioDispatcher) {
        val latest = dao.getLatestSample()
        when {
            latest == null -> dao.insertSample(sample)

            sample.batteryLevel < latest.batteryLevel -> dao.insertSample(sample)

            else -> INSERT_SKIPPED_ID
        }
    }

    suspend fun pruneOlderThan(cutoffEpochMillis: Long): Int = withContext(ioDispatcher) {
        dao.deleteOlderThan(cutoffEpochMillis)
    }

    suspend fun clearAllSamples(): Unit = withContext(ioDispatcher) {
        dao.clearAllSamples()
    }

    /**
     * Forces a state-marker write (power connected/disconnected) even when level did not drop.
     */
    suspend fun insertStateSample(sample: BatterySample): Long = withContext(ioDispatcher) {
        dao.insertSample(sample)
    }

    /**
     * Fetches current-session discharging points only (after the most recent charging pivot)
     * and returns them in chronological order.
     */
    suspend fun getRecentDischargingWindow(currentSystemBatteryLevel: Float): List<BatterySample> = withContext(ioDispatcher) {
        val latest = dao.getLatestSample()

        // State guard: DB is stale when real battery jumped up while latest row still says discharging.
        if (
            latest != null &&
            !latest.isCharging &&
            (currentSystemBatteryLevel - latest.batteryLevel) > STALE_LEVEL_JUMP_THRESHOLD_PERCENT
        ) {
            dao.clearAllSamples()
            return@withContext emptyList()
        }

        val lastChargingTs = dao.getLatestChargingTimestamp()
        val sessionWindowDesc = if (lastChargingTs == null) {
            // Fallback for first-run/no-pivot state: only trust recent rows when DB and system levels match.
            val canUseFallbackWindow =
                latest != null &&
                    !latest.isCharging &&
                    kotlin.math.abs(currentSystemBatteryLevel - latest.batteryLevel) <= NO_PIVOT_LEVEL_MATCH_TOLERANCE

            if (!canUseFallbackWindow) {
                return@withContext emptyList()
            }

            dao.getPredictionWindow(PREDICTION_WINDOW_SIZE)
        } else {
            dao.getPredictionWindowSince(
                sinceEpochMillis = lastChargingTs,
                windowSize = PREDICTION_WINDOW_SIZE
            )
        }

        val sessionChronological = sessionWindowDesc.asReversed()
        val cleanedSession = keepMostRecentContinuousDischargeBlock(sessionChronological)

        if (cleanedSession.size < MIN_SESSION_SAMPLES_FOR_PREDICTION) {
            emptyList()
        } else {
            cleanedSession
        }
    }

    /**
     * One-time soft cleanup for existing logs.
     */
    suspend fun cleanupHistoricalData(): CleanupResult = withContext(ioDispatcher) {
            val removedOldRows = dao.deleteOlderThan(System.currentTimeMillis() - THIRTY_DAYS_MS)
            CleanupResult(
                deletedChargingRows = 0,
                deletedOrphanSpikes = removedOldRows
            )
        }

    private fun keepMostRecentContinuousDischargeBlock(
        chronological: List<BatterySample>
    ): List<BatterySample> {
        if (chronological.isEmpty()) return emptyList()

        val newestBlockReversed = ArrayList<BatterySample>(chronological.size)
        var newer = chronological.last()
        newestBlockReversed.add(newer)

        for (i in chronological.lastIndex - 1 downTo 0) {
            val older = chronological[i]
            val hasUpwardBreak = newer.batteryLevel > older.batteryLevel + ML_UPWARD_TOLERANCE_PERCENT
            if (hasUpwardBreak) {
                break
            }

            newestBlockReversed.add(older)
            newer = older
        }

        return newestBlockReversed.asReversed()
    }

    companion object {
        const val INSERT_SKIPPED_ID = -1L
        private const val ML_UPWARD_TOLERANCE_PERCENT = 0.15f
        private const val PREDICTION_WINDOW_SIZE = 20
        private const val MIN_SESSION_SAMPLES_FOR_PREDICTION = 2
        private const val THIRTY_DAYS_MS = 30L * 24L * 60L * 60L * 1000L
        private const val STALE_LEVEL_JUMP_THRESHOLD_PERCENT = 5.0f
        private const val NO_PIVOT_LEVEL_MATCH_TOLERANCE = 1.0f
    }
}
