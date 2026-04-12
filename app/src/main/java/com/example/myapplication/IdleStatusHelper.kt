package com.example.myapplication

import android.content.Context

private const val IDLE_PREFS_NAME = "battery_guard_monitoring"
private const val KEY_LAST_SCREEN_OFF = "last_screen_off_time"
private const val KEY_LAST_SCREEN_ON = "last_screen_on_time"
private const val KEY_LAST_ACTIVE_TIMESTAMP = "last_active_timestamp"
private const val LONG_SLEEP_THRESHOLD_MS = 30L * 60L * 1000L
private const val UI_GRACE_PERIOD_MS = 3L * 60L * 1000L

/**
 * Returns whether Volt Watch should currently treat the device as idle.
 *
 * This helper is read-only: it only consults SharedPreferences and never writes state.
 * It keeps the standby cap visible during the 3-minute UI viewing grace period,
 * while still falling back to idle after a long screen-off interval even across restarts.
 */
fun getOptimizedIdleStatus(context: Context): Boolean {
    val prefs = context.getSharedPreferences(IDLE_PREFS_NAME, Context.MODE_PRIVATE)
    val now = System.currentTimeMillis()

    val lastScreenOff = prefs.getLong(KEY_LAST_SCREEN_OFF, 0L)
    val lastScreenOn = prefs.getLong(KEY_LAST_SCREEN_ON, 0L)
    val lastActiveTimestamp = prefs.getLong(KEY_LAST_ACTIVE_TIMESTAMP, 0L)

    if (lastScreenOff <= 0L && lastScreenOn <= 0L && lastActiveTimestamp <= 0L) {
        return false
    }

    // Idle if the device has stayed screen-off for at least 30 minutes and has not since woken.
    val asleepLongEnough =
        lastScreenOff > 0L &&
            lastScreenOn <= lastScreenOff &&
            now - lastScreenOff >= LONG_SLEEP_THRESHOLD_MS

    // Idle grace if the device just woke after a long sleep and the 3-minute viewing window is still active.
    val justWokeUp =
        lastScreenOff > 0L &&
            lastScreenOn > lastScreenOff &&
            now - lastScreenOff >= LONG_SLEEP_THRESHOLD_MS &&
            now - lastScreenOn <= UI_GRACE_PERIOD_MS

    // Long-duration fallback when a restart lost the live screen state but the last persisted off time is old.
    val longDurationIdle =
        lastScreenOff > 0L &&
            lastScreenOn < lastScreenOff &&
            now - lastScreenOff >= LONG_SLEEP_THRESHOLD_MS

    // Preserve the 3-minute view state even if the UI only has the last-active timestamp after a restart.
    val activeGrace =
        lastActiveTimestamp > 0L &&
            lastActiveTimestamp >= lastScreenOn &&
            lastActiveTimestamp > lastScreenOff &&
            now - lastActiveTimestamp <= UI_GRACE_PERIOD_MS &&
            now - lastScreenOff >= LONG_SLEEP_THRESHOLD_MS

    return asleepLongEnough || justWokeUp || longDurationIdle || activeGrace
}
