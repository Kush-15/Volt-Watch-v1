package com.example.myapplication

import android.app.Application

class VoltWatchApp : Application() {
    override fun onCreate() {
        super.onCreate()
        BatteryLoggingForegroundService.start(this)
    }
}

