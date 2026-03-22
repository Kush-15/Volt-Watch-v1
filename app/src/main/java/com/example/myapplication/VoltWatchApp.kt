package com.example.myapplication

import android.app.Application
import androidx.work.WorkManager

class VoltWatchApp : Application() {
    override fun onCreate() {
        super.onCreate()
        WorkManager.getInstance(this).cancelUniqueWork("battery_periodic_sampling")
        BatteryLoggingForegroundService.start(this)
    }
}

