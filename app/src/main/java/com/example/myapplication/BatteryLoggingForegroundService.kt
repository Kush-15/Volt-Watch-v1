package com.example.myapplication

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

private const val SERVICE_LOG_TAG = "BatteryFgService"

class BatteryLoggingForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null
    private lateinit var dao: BatterySampleDao
    private lateinit var repository: BatteryRepository
    private var lastBatteryLevel: Float = -1f

    override fun onCreate() {
        super.onCreate()
        dao = BatteryDatabase.getInstance(applicationContext).batterySampleDao()
        repository = BatteryRepository(dao)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (loopJob?.isActive != true) {
            loopJob = serviceScope.launch {
                while (isActive) {
                    logBatteryTickIfDropped()
                    delay(LOG_INTERVAL_MS)
                }
            }
        }
        // Self-heal: request automatic restart if process is killed for memory pressure.
        return START_STICKY
    }

    override fun onDestroy() {
        loopJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun logBatteryTickIfDropped() {
        val wakeLock = acquireTickWakeLock()
        try {
            val batteryIntent = registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )

            val batteryManager = getSystemService(BATTERY_SERVICE) as BatteryManager
            val batteryPercent = batteryManager
                .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                .toFloat()
                .coerceIn(0f, 100f)

            // Detect charging state — skip insertion if charging
            val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val plugged = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL ||
                plugged != 0

            if (isCharging) {
                Log.d(SERVICE_LOG_TAG, "Skipped sample while charging")
                return
            }

            // Event-driven: Only insert if battery has dropped (not flat or rising)
            if (lastBatteryLevel < 0f) {
                // First sample: initialize the baseline
                lastBatteryLevel = batteryPercent
                Log.d(SERVICE_LOG_TAG, "Initialized baseline battery level: $batteryPercent%")
                return
            }

            if (batteryPercent >= lastBatteryLevel) {
                // Battery is flat or rising — skip insertion
                Log.d(SERVICE_LOG_TAG, "Battery flat/rising ($lastBatteryLevel% -> $batteryPercent%) — skipped insertion")
                return
            }

            val voltageMv = batteryIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
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
                lastBatteryLevel = batteryPercent
                Log.d(SERVICE_LOG_TAG, "Logged background tick at $now (battery: $batteryPercent%, id: $id)")
            }

            dao.deleteOlderThan(now - TimeUnit.DAYS.toMillis(7))
        } catch (t: Throwable) {
            Log.e(SERVICE_LOG_TAG, "Battery logging tick failed", t)
        } finally {
            if (wakeLock?.isHeld == true) {
                wakeLock.release()
            }
        }
    }

    private fun acquireTickWakeLock(): PowerManager.WakeLock? {
        val pm = getSystemService(PowerManager::class.java) ?: return null
        val wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:BatteryTick")
        wakeLock.setReferenceCounted(false)
        wakeLock.acquire(WAKELOCK_TIMEOUT_MS)
        return wakeLock
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

        private const val LOG_INTERVAL_MS = 60_000L
        private const val WAKELOCK_TIMEOUT_MS = 20_000L

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


