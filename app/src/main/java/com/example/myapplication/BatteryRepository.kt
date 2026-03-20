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
        if (latest == null || sample.batteryLevel < latest.batteryLevel) {
            dao.insertSample(sample)
        } else {
            INSERT_SKIPPED_ID
        }
    }

    suspend fun pruneOlderThan(cutoffEpochMillis: Long): Int = withContext(ioDispatcher) {
        dao.deleteOlderThan(cutoffEpochMillis)
    }

    suspend fun clearAllSamples(): Unit = withContext(ioDispatcher) {
        dao.clearAllSamples()
    }

    /**
     * Fetches the latest 50 discharging points from Room and returns them in chronological order.
     */
    suspend fun getRecentDischargingWindow(): List<BatterySample> = withContext(ioDispatcher) {
        val chronological = dao.getLast50NonChargingSamples().asReversed()
        keepMostRecentContinuousDischargeBlock(chronological)
    }

    /**
     * One-time historical cleanup for existing dirty logs.
     */
    suspend fun cleanupHistoricalData(): CleanupResult = withContext(ioDispatcher) {
            val removedCharging = dao.deleteChargingRows()
            val removedSpikes = dao.deleteOrphanUpwardSpikes()
            CleanupResult(
                deletedChargingRows = removedCharging,
                deletedOrphanSpikes = removedSpikes
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
    }
}

