package com.example.myapplication

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.BatteryManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

private const val UI_BATTERY_CHANGED_ACTION = Intent.ACTION_BATTERY_CHANGED

data class BatterySnapshot(
    val level: Int,
    val isCharging: Boolean,
    val currentMa: Int,
    val voltageMv: Int,
    val status: Int
)

fun readBatterySnapshot(context: Context): BatterySnapshot {
    val intent = context.registerReceiver(null, IntentFilter(UI_BATTERY_CHANGED_ACTION))
    val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
    val normalizedLevel = if (level >= 0 && scale > 0) ((level * 100f) / scale).toInt() else -1
    val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        ?: BatteryManager.BATTERY_STATUS_UNKNOWN
    val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
    val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    val currentNow = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
    val voltageMv = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
    val isCharging = plugged != 0 ||
        status == BatteryManager.BATTERY_STATUS_CHARGING ||
        status == BatteryManager.BATTERY_STATUS_FULL

    return BatterySnapshot(
        level = normalizedLevel.coerceIn(0, 100),
        isCharging = isCharging,
        currentMa = abs(currentNow / 1000),
        voltageMv = voltageMv,
        status = status
    )
}

fun batteryColor(level: Float): Int = when {
    level > 50f -> Color.parseColor("#7BC850")
    level > 20f -> Color.parseColor("#F5A623")
    else -> Color.parseColor("#E05555")
}

fun batteryStatusLabel(level: Float): String = when {
    level > 50f -> "Good"
    level > 20f -> "Low battery"
    else -> "Critical battery"
}

fun chargingLabel(currentMa: Int): String = when {
    currentMa >= 1800 -> "Fast charging"
    currentMa >= 800 -> "Normal charging"
    else -> "Trickle charging"
}

fun chargingColor(currentMa: Int): Int = when {
    currentMa >= 1800 -> Color.parseColor("#7BC850")
    currentMa >= 800 -> Color.parseColor("#F5A623")
    else -> Color.parseColor("#E05555")
}

fun confidenceColor(sampleCount: Int): Int = when {
    sampleCount < 4 -> Color.parseColor("#E05555")
    sampleCount < 7 -> Color.parseColor("#F5A623")
    else -> Color.parseColor("#7BC850")
}

fun confidenceText(sampleCount: Int): String = "Model confidence: $sampleCount of 10 readings"

fun formatSamplesText(sampleCount: Int): String = "$sampleCount/10 samples"

fun formatRemainingHours(hours: Double?): String {
    if (hours == null || hours.isNaN() || hours.isInfinite() || hours <= 0.0) return "Calculating..."
    return if (hours < 1.0) {
        "~${floor(hours * 60.0).toInt().coerceAtLeast(0)} min left"
    } else {
        val totalMinutes = floor(hours * 60.0).toInt().coerceAtLeast(0)
        val h = totalMinutes / 60
        val m = totalMinutes % 60
        "${h}h ${m}m left"
    }
}

fun formatRemainingMinutes(minutes: Int?): String {
    if (minutes == null || minutes <= 0) return "Calculating..."
    return if (minutes < 60) "~$minutes min left" else "${minutes / 60}h ${minutes % 60}m left"
}

fun formatPercent(value: Float): String = "${value.toInt()}%"

fun formatOneDecimalPercent(value: Double): String = String.format(Locale.getDefault(), "%.1f%%", value)

fun formatAbsoluteTime(timestampMs: Long): String =
    SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(timestampMs))


