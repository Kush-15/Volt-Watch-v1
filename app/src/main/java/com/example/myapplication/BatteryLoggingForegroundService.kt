package com.example.myapplication

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

private const val SERVICE_LOG_TAG = "BatteryFgService"

class BatteryLoggingForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var dao: BatterySampleDao
    private lateinit var repository: BatteryRepository
    private var lastRecordedLevel: Int = LEVEL_UNINITIALIZED
    private lateinit var prefs: android.content.SharedPreferences

    private val batteryChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return

            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level < 0 || scale <= 0) return

            val normalizedLevel = ((level * 100f) / scale).toInt().coerceIn(0, 100)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL ||
                plugged != 0

            if (isCharging) {
                persistLastRecordedLevel(normalizedLevel)
                Log.d(SERVICE_LOG_TAG, "Charging event ignored (level=$normalizedLevel)")
                return
            }

            if (lastRecordedLevel == LEVEL_UNINITIALIZED) {
                persistLastRecordedLevel(normalizedLevel)
                Log.d(SERVICE_LOG_TAG, "Initialized drop gate baseline at $normalizedLevel%")
                return
            }

            // Strict gatekeeper: only 1% drops are persisted; flat/rising updates are ignored.
            if (normalizedLevel >= lastRecordedLevel) {
                Log.d(SERVICE_LOG_TAG, "Ignored non-drop update ($lastRecordedLevel% -> $normalizedLevel%)")
                return
            }

            serviceScope.launch {
                insertDischargeSample(intent, normalizedLevel)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        dao = BatteryDatabase.getInstance(applicationContext).batterySampleDao()
        repository = BatteryRepository(dao)
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        lastRecordedLevel = prefs.getInt(KEY_LAST_RECORDED_LEVEL, LEVEL_UNINITIALIZED)

        // If no persisted baseline exists, seed from the most recent row in Room.
        if (lastRecordedLevel == LEVEL_UNINITIALIZED) {
            serviceScope.launch {
                dao.getLatestSample()?.let { sample ->
                    persistLastRecordedLevel(sample.batteryLevel.toInt())
                }
            }
        }

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        registerBatteryReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Self-heal: request automatic restart if process is killed for memory pressure.
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterReceiverSafely()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun insertDischargeSample(batteryIntent: Intent, currentLevel: Int) {
        try {
            val batteryPercent = currentLevel.toFloat()
            val voltageMv = batteryIntent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
            val now = System.currentTimeMillis()

            val sample = BatterySample(
                timestampEpochMillis = now,
                batteryLevel = batteryPercent,
                voltage = voltageMv,
                servicesActive = true,
                foreground = false,
                isCharging = false
            )

            val id = repository.insertSample(sample)
            if (id > 0) {
                persistLastRecordedLevel(currentLevel)
                Log.d(SERVICE_LOG_TAG, "Logged battery drop sample at $now (level=$currentLevel%, id=$id)")
            } else {
                // Repository-level guard may skip if a concurrent write already inserted same level.
                persistLastRecordedLevel(currentLevel)
                Log.d(SERVICE_LOG_TAG, "Skipped duplicate drop sample at level=$currentLevel%")
            }

            dao.deleteOlderThan(now - TimeUnit.DAYS.toMillis(7))
        } catch (t: Throwable) {
            Log.e(SERVICE_LOG_TAG, "Battery logging insert failed", t)
        }
    }

    private fun registerBatteryReceiver() {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(batteryChangedReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            ContextCompat.registerReceiver(
                this,
                batteryChangedReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
    }

    private fun unregisterReceiverSafely() {
        try {
            unregisterReceiver(batteryChangedReceiver)
        } catch (_: IllegalArgumentException) {
            // Receiver was already unregistered.
        }
    }

    private fun persistLastRecordedLevel(level: Int) {
        lastRecordedLevel = level
        prefs.edit().putInt(KEY_LAST_RECORDED_LEVEL, level).apply()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Persistent monitoring for Volt Watch battery tracking"
            setShowBadge(false)
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.createNotificationChannel(channel)
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle("Volt Watch")
        .setContentText("Volt Watch is monitoring battery")
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .build()

    companion object {
        private const val CHANNEL_ID = "volt_watch_monitoring"
        private const val CHANNEL_NAME = "Volt Watch Monitoring"
        private const val NOTIFICATION_ID = 1001
        private const val PREFS_NAME = "battery_guard_monitoring"
        private const val KEY_LAST_RECORDED_LEVEL = "last_recorded_level"
        private const val LEVEL_UNINITIALIZED = -1

        fun start(context: Context) {
            val intent = Intent(context, BatteryLoggingForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}


