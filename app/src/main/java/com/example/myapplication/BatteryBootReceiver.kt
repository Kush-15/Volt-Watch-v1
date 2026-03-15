package com.example.myapplication

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts persistent battery logging service after reboot/update.
 */
class BatteryBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (
            action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            BatteryLoggingForegroundService.start(context)
        }
    }
}

