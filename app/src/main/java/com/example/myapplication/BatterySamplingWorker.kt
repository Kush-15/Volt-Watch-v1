package com.example.myapplication

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

private const val WORKER_LOG_TAG = "BatterySamplingWorker"

/**
 * Periodic background worker for battery telemetry.
 *
 * Logs a discharging sample into Room with fixed state markers:
 * - servicesActive = true (background collector is running)
 * - foreground = false (sample captured outside UI session)
 */
class BatterySamplingWorker(
    appContext: android.content.Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val batteryIntent = applicationContext.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )

            val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val plugged = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL ||
                plugged != 0

            // Charging guard: skip training samples while charging.
            if (isCharging) {
                Log.d(WORKER_LOG_TAG, "Skipped sample while charging")
                return Result.success()
            }

            val batteryManager =
                applicationContext.getSystemService(android.content.Context.BATTERY_SERVICE) as BatteryManager
            val batteryPercent = batteryManager
                .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                .toDouble()
                .coerceIn(0.0, 100.0)

            val voltageMv = batteryIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
            val now = System.currentTimeMillis()

            val sample = BatterySample(
                timestampEpochMillis = now,
                batteryLevel = batteryPercent.toFloat(),
                voltage = voltageMv,
                servicesActive = true,
                foreground = false,
                isCharging = false
            )

            val dao = BatteryDatabase.getInstance(applicationContext).batterySampleDao()
            dao.insertSample(sample)
            dao.deleteOlderThan(now - TimeUnit.DAYS.toMillis(7))

            Log.d(WORKER_LOG_TAG, "Background sample inserted at $now")
            Result.success()
        } catch (t: Throwable) {
            Log.e(WORKER_LOG_TAG, "Background sampling failed", t)
            Result.retry()
        }
    }
}

