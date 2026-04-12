package com.example.myapplication

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

class BatteryNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
        BatteryAlertNotifier.handleBatteryChanged(context, intent)
    }
}

object BatteryAlertNotifier {
    private const val ALERTS_CHANNEL_ID = "volt_watch_alerts"
    private const val ALERTS_CHANNEL_NAME = "Battery Alerts"
    private const val ALERTS_CHANNEL_DESCRIPTION = "Volt Watch battery notifications"

    private const val ALERT_ID_CHARGING_ADVICE = 1001
    private const val ALERT_ID_FAST_DRAIN = 1002
    private const val ALERT_ID_CHARGE_REMINDER = 1003

    private const val PREFS_NAME = "volt_watch_alerts_prefs"
    private const val KEY_CHARGING_ADVICE_SENT = "charging_advice_sent"
    private const val KEY_LAST_LEVEL = "last_level"
    private const val KEY_LAST_LEVEL_TS = "last_level_ts"
    private const val KEY_LAST_FAST_DRAIN_ALERT_TS = "last_fast_drain_alert_ts"
    private const val KEY_LAST_CHARGE_REMINDER_ALERT_TS = "last_charge_reminder_alert_ts"
    private const val KEY_BEDTIME = "bedtime"
    const val KEY_LAST_ETA_MINUTES = "last_eta_minutes"

    private const val FAST_DRAIN_COOLDOWN_MS = 10L * 60L * 1000L
    private const val CHARGE_REMINDER_COOLDOWN_MS = 30L * 60L * 1000L

    fun createAlertsChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            ALERTS_CHANNEL_ID,
            ALERTS_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = ALERTS_CHANNEL_DESCRIPTION
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
    }

    fun handleBatteryChanged(context: Context, intent: Intent) {
        createAlertsChannel(context)

        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        if (level < 0) return

        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()

        maybeSendChargingAdvice(context, prefs, level, isCharging)
        maybeSendFastDrainAlert(context, prefs, level, isCharging, now)
        maybeSendChargeReminder(context, prefs, level, isCharging, now)

        prefs.edit()
            .putInt(KEY_LAST_LEVEL, level)
            .putLong(KEY_LAST_LEVEL_TS, now)
            .apply()
    }

    fun sendNotification(context: Context, id: Int, title: String, body: String, channelId: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val customIcon = context.resources.getIdentifier("ic_battery_alert", "drawable", context.packageName)
        val smallIcon = if (customIcon != 0) customIcon else android.R.drawable.ic_dialog_alert

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(smallIcon)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(id, notification)
    }

    private fun maybeSendChargingAdvice(
        context: Context,
        prefs: SharedPreferences,
        level: Int,
        isCharging: Boolean
    ) {
        if (!isCharging) {
            prefs.edit().putBoolean(KEY_CHARGING_ADVICE_SENT, false).apply()
            return
        }

        val alreadySent = prefs.getBoolean(KEY_CHARGING_ADVICE_SENT, false)
        if (level > 85 && !alreadySent) {
            sendNotification(
                context = context,
                id = ALERT_ID_CHARGING_ADVICE,
                title = "Unplug your charger",
                body = "Battery is at $level%. Charging above 85% reduces battery lifespan.",
                channelId = ALERTS_CHANNEL_ID
            )
            prefs.edit().putBoolean(KEY_CHARGING_ADVICE_SENT, true).apply()
        }
    }

    private fun maybeSendFastDrainAlert(
        context: Context,
        prefs: SharedPreferences,
        level: Int,
        isCharging: Boolean,
        now: Long
    ) {
        val lastLevel = prefs.getInt(KEY_LAST_LEVEL, -1)
        val lastTs = prefs.getLong(KEY_LAST_LEVEL_TS, 0L)
        if (lastLevel < 0 || lastTs <= 0L || isCharging) return

        val dropPercent = lastLevel - level
        val minutes = (now - lastTs).coerceAtLeast(0L) / 60_000.0
        val lastAlertTs = prefs.getLong(KEY_LAST_FAST_DRAIN_ALERT_TS, 0L)

        if (dropPercent > 5 && minutes < 5.0 && (now - lastAlertTs) >= FAST_DRAIN_COOLDOWN_MS) {
            sendNotification(
                context = context,
                id = ALERT_ID_FAST_DRAIN,
                title = "Fast battery drain detected",
                body = "Your battery dropped ${dropPercent}% in ${minutes.toInt()} minutes. A background app may be the cause.",
                channelId = ALERTS_CHANNEL_ID
            )
            prefs.edit().putLong(KEY_LAST_FAST_DRAIN_ALERT_TS, now).apply()
        }
    }

    private fun maybeSendChargeReminder(
        context: Context,
        prefs: SharedPreferences,
        level: Int,
        isCharging: Boolean,
        now: Long
    ) {
        if (isCharging || level >= 40) return

        val timeToEmptyMinutes = prefs.getInt(KEY_LAST_ETA_MINUTES, -1)
        if (timeToEmptyMinutes <= 0) return

        val minutesToBedtime = minutesUntilBedtime(prefs)
        if (minutesToBedtime <= 0) return

        val lastAlertTs = prefs.getLong(KEY_LAST_CHARGE_REMINDER_ALERT_TS, 0L)
        if (timeToEmptyMinutes < minutesToBedtime && (now - lastAlertTs) >= CHARGE_REMINDER_COOLDOWN_MS) {
            val bedtime = prefs.getString(KEY_BEDTIME, "23:00") ?: "23:00"
            sendNotification(
                context = context,
                id = ALERT_ID_CHARGE_REMINDER,
                title = "Charge your phone",
                body = "Battery won't last until $bedtime. Plug in soon.",
                channelId = ALERTS_CHANNEL_ID
            )
            prefs.edit().putLong(KEY_LAST_CHARGE_REMINDER_ALERT_TS, now).apply()
        }
    }

    private fun minutesUntilBedtime(prefs: SharedPreferences): Int {
        val bedtimeRaw = prefs.getString(KEY_BEDTIME, "23:00") ?: "23:00"
        val parts = bedtimeRaw.split(":")
        if (parts.size != 2) return 0

        val hour = parts[0].toIntOrNull()?.coerceIn(0, 23) ?: return 0
        val minute = parts[1].toIntOrNull()?.coerceIn(0, 59) ?: return 0

        val nowCal = java.util.Calendar.getInstance()
        val bedtimeCal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, hour)
            set(java.util.Calendar.MINUTE, minute)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }

        if (bedtimeCal.timeInMillis <= nowCal.timeInMillis) {
            bedtimeCal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        }

        return ((bedtimeCal.timeInMillis - nowCal.timeInMillis) / 60_000L).toInt().coerceAtLeast(0)
    }
}

