package com.example.myapplication

import android.app.ActivityManager
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Process
import android.provider.Settings
import android.util.Log

private const val LOG_TAG = "BatterySampler"

class BatterySampler(
    private val context: Context,
    private val serviceClassName: String
) {
    companion object {
        // Reuse the filter object — avoids repeated allocation in the hot sampling path
        private val BATTERY_CHANGED_FILTER = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
    }

    /**
     * True after each call to [sample] when the device was found to be charging or full.
     * MainActivity reads this flag instead of making a second registerReceiver() call.
     */
    var isCharging: Boolean = false
        private set

    var lastSampleIgnoredForCharging: Boolean = false
        private set

    private var lastSampleTimeMs: Long? = null

    fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
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

    /**
     * Reads the sticky battery broadcast exactly ONCE per call, sets [isCharging],
     * and returns a [BatterySample] only while the device is discharging.
     *
     * This eliminates the duplicate registerReceiver() call that previously existed
     * in MainActivity's isDeviceCharging() method.
     */
    fun sample(): BatterySample? {
        lastSampleIgnoredForCharging = false

        // Single IPC call — result is reused for all fields below
        val batteryIntent = context.registerReceiver(null, BATTERY_CHANGED_FILTER)

        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                     status == BatteryManager.BATTERY_STATUS_FULL

        val plugged = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        if (plugged != 0) {
            lastSampleIgnoredForCharging = true
            Log.d(LOG_TAG, "Skipping sample: device is charging (plugged=$plugged).")
            return null
        }

        val now = System.currentTimeMillis()
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryPercent = batteryManager
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            .toDouble()
            .coerceIn(0.0, 100.0)

        val voltageMv = batteryIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        val servicesActive = isServiceRunning()
        val foreground = isForegroundActive()

        lastSampleTimeMs = now

        return BatterySample(
            timestampEpochMillis = now,
            batteryLevel = batteryPercent.toFloat(),
            voltage = voltageMv,
            servicesActive = servicesActive,
            foreground = foreground,
            isCharging = false
        )
    }

    /**
     * On API 26+ getRunningServices() only returns the caller's own services, so
     * capping at 50 is safe and avoids scanning a huge list on older devices.
     */
    private fun isServiceRunning(): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        return activityManager.getRunningServices(50)
            .any { it.service.className == serviceClassName }
    }

    /**
     * Uses getMyMemoryState() to check only this process — much cheaper than
     * iterating all running app processes via runningAppProcesses.
     */
    private fun isForegroundActive(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }
}
