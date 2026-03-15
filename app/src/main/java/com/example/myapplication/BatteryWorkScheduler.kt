package com.example.myapplication

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object BatteryWorkScheduler {
    private const val UNIQUE_WORK_NAME = "battery_periodic_sampling"

    /**
     * Schedules one unique periodic worker at 15-minute cadence.
     *
     * Note: Android may defer exact execution in Doze/app standby.
     */
    fun schedulePeriodicSampling(context: Context) {
        val request = PeriodicWorkRequestBuilder<BatterySamplingWorker>(15, TimeUnit.MINUTES)
            .addTag(UNIQUE_WORK_NAME)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }
}

