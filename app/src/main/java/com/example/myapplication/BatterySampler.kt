package com.example.myapplication

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Process
import android.provider.Settings
import android.util.Log
import java.util.concurrent.TimeUnit

private const val LOG_TAG = "BatterySampler"

class BatterySampler(
    private val context: Context,
    private val serviceClassName: String
) {
    var lastSampleIgnoredForCharging: Boolean = false
        private set

    private var lastSampleTimeMs: Long? = null

    fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun openUsageAccessSettings() {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun sample(): BatterySample? {
        lastSampleIgnoredForCharging = false

        val now = System.currentTimeMillis()
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryPercent = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).toDouble()

        val batteryIntent = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )

        val voltageMv = batteryIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        val plugged = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        if (plugged != 0) {
            lastSampleIgnoredForCharging = true
            Log.d(LOG_TAG, "Skipping sample: device is charging.")
            return null
        }

        val batteryLevel = batteryPercent.coerceIn(0.0, 100.0).toFloat()
        val servicesActive = isServiceRunning()
        val foreground = isForegroundActive()

        lastSampleTimeMs = now

        return BatterySample(
            timestampEpochMillis = now,
            batteryLevel = batteryLevel,
            voltage = voltageMv,
            servicesActive = servicesActive,
            foreground = foreground
        )
    }

    private fun isServiceRunning(): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val runningServices = activityManager.getRunningServices(Int.MAX_VALUE)
        for (service in runningServices) {
            if (service.service.className == serviceClassName) {
                return true
            }
        }

        return false
    }

    private fun isForegroundActive(): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val runningAppProcesses = activityManager.runningAppProcesses
        if (runningAppProcesses == null) return false

        val myPid = Process.myPid()
        for (process in runningAppProcesses) {
            if (process.pid == myPid && process.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                return true
            }
        }
        return false
    }
}
