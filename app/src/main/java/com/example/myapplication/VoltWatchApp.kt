package com.example.myapplication

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.WorkManager

private const val APP_LOG_TAG = "VoltWatchApp"

class VoltWatchApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.d(APP_LOG_TAG, "🟢 Application onCreate() called")
        
        WorkManager.getInstance(this).cancelUniqueWork("battery_periodic_sampling")
        Log.d(APP_LOG_TAG, "✅ Cancelled any existing WorkManager jobs")
        
        // On Android 13+, only start service if notification permission is already granted.
        // The MainActivity will request the permission if needed.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val isGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            
            if (isGranted) {
                Log.d(APP_LOG_TAG, "✅ POST_NOTIFICATIONS already granted - Starting service")
                BatteryLoggingForegroundService.start(this)
            } else {
                Log.d(APP_LOG_TAG, "⏳ POST_NOTIFICATIONS not granted yet - MainActivity will request it")
            }
        } else {
            Log.d(APP_LOG_TAG, "✅ Android < 13 - Starting service (notification auto-granted)")
            BatteryLoggingForegroundService.start(this)
        }
    }
}

