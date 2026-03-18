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
        dao.insertSample(sample)
    }

    suspend fun pruneOlderThan(cutoffEpochMillis: Long): Int = withContext(ioDispatcher) {
        dao.deleteOlderThan(cutoffEpochMillis)
    }

    suspend fun clearAllSamples(): Unit = withContext(ioDispatcher) {
        dao.clearAllSamples()
    }

    /**
     * Fetches the latest 100 discharging points from Room and returns them in chronological order.
     */
    suspend fun getRecentDischargingWindow(): List<BatterySample> = withContext(ioDispatcher) {
        val chronological = dao.getLast100NonChargingSamples().asReversed()
        keepMostRecentContinuousDischargeBlock(chronological)
    }

    /**
     * One-time historical cleanup for existing dirty logs.
     */
    suspend fun cleanupHistoricalData(spikeThresholdPercent: Float = ORPHAN_SPIKE_THRESHOLD_PERCENT): CleanupResult =
        withContext(ioDispatcher) {
            val removedCharging = dao.deleteChargingRows()
            val removedSpikes = dao.deleteOrphanUpwardSpikes(spikeThresholdPercent)
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
        private const val ML_UPWARD_TOLERANCE_PERCENT = 0.15f
        private const val ORPHAN_SPIKE_THRESHOLD_PERCENT = 1.0f
    }
}

