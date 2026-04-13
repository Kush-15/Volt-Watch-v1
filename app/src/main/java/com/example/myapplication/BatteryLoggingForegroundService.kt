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
    private var lastScreenOffTimestampMs: Long = SCREEN_OFF_UNSET // Persisted screen-off timestamp for the idle helper.
    private var lastScreenOnTimestampMs: Long = SCREEN_ON_UNSET // Persisted screen-on timestamp for the 3-minute UI grace period.
    private lateinit var prefs: android.content.SharedPreferences

    private val batteryChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return

            BatteryAlertNotifier.handleBatteryChanged(this@BatteryLoggingForegroundService, intent)

            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level < 0 || scale <= 0) return

            val normalizedLevel = ((level * 100f) / scale).toInt().coerceIn(0, 100)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
            val isCharging = plugged != 0 ||
                status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

            Log.d(
                SERVICE_LOG_TAG,
                "batteryEvent level=$normalizedLevel% status=$status plugged=$plugged isCharging=$isCharging lastRecordedLevel=$lastRecordedLevel"
            )

            if (isCharging) {
                serviceScope.launch {
                    insertChargingMarker(normalizedLevel)
                }
                return
            }

            if (lastRecordedLevel == LEVEL_UNINITIALIZED) {
                persistLastRecordedLevel(normalizedLevel)
                Log.d(SERVICE_LOG_TAG, "Initialized drop gate baseline at $normalizedLevel%")
                return
            }

            // If level jumped up while we were not tracking (restart/kill), re-baseline.
            if (normalizedLevel > lastRecordedLevel) {
                val previousLevel = lastRecordedLevel
                serviceScope.launch {
                    maybeResetStaleDatabase(normalizedLevel)
                }
                persistLastRecordedLevel(normalizedLevel)
                Log.d(
                    SERVICE_LOG_TAG,
                    "Re-baselined after level increase ($previousLevel% -> $normalizedLevel%)"
                )
                return
            }

            // Strict gatekeeper: only drops are persisted; flat updates are ignored.
            if (normalizedLevel == lastRecordedLevel) {
                Log.d(SERVICE_LOG_TAG, "Ignored flat update at $normalizedLevel%")
                return
            }

            serviceScope.launch {
                insertDischargeSample(intent, normalizedLevel)
            }
        }
    }

    private val powerStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_POWER_CONNECTED -> {
                    serviceScope.launch {
                        insertPowerTransitionSample(forceCharging = true)
                    }
                }

                Intent.ACTION_POWER_DISCONNECTED -> {
                    serviceScope.launch {
                        insertPowerTransitionSample(forceCharging = false)
                    }
                }
            }
        }
    }

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    val now = System.currentTimeMillis()
                    persistScreenOffTimestamp(now) // Store the exact screen-off time immediately so idle mode survives restarts.
                    persistLastActiveTimestamp(now) // Keep the latest active timestamp in SharedPreferences for helper-based recovery.
                    Log.d(SERVICE_LOG_TAG, "Screen turned off; idle timer started")
                }

                Intent.ACTION_SCREEN_ON -> {
                    val now = System.currentTimeMillis()
                    persistScreenOnTimestamp(now) // Store the exact screen-on time immediately so the 3-minute grace window is measurable.
                    persistLastActiveTimestamp(now) // Keep a durable "last active" timestamp for the helper.
                    Log.d(SERVICE_LOG_TAG, "Screen turned on; grace-period timer started")
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        dao = BatteryDatabase.getInstance(applicationContext).batterySampleDao()
        repository = BatteryRepository(dao)
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        lastRecordedLevel = prefs.getInt(KEY_LAST_RECORDED_LEVEL, LEVEL_UNINITIALIZED)
        lastScreenOffTimestampMs = prefs.getLong(KEY_LAST_SCREEN_OFF_TIME, prefs.getLong(LEGACY_KEY_LAST_SCREEN_OFF_TIMESTAMP, SCREEN_OFF_UNSET)) // Restore the persisted screen-off time across process restarts.
        lastScreenOnTimestampMs = prefs.getLong(KEY_LAST_SCREEN_ON_TIME, SCREEN_ON_UNSET) // Restore the persisted screen-on time across process restarts.
        Log.d(SERVICE_LOG_TAG, "onCreate persistedBaseline=$lastRecordedLevel")

        // If no persisted baseline exists, seed from the most recent row in Room.
        if (lastRecordedLevel == LEVEL_UNINITIALIZED) {
            serviceScope.launch {
                dao.getLatestSample()?.let { sample ->
                    persistLastRecordedLevel(sample.batteryLevel.toInt())
                }
            }
        }

        serviceScope.launch {
            recoverChargingStateOnRestart()
        }

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        Log.d(SERVICE_LOG_TAG, "startForeground completed notificationId=$NOTIFICATION_ID")
        registerBatteryReceiver()
        registerScreenStateReceiver() // New: keep the screen-off timestamp current for idle-mode ETA capping.
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Self-heal: request automatic restart if process is killed for memory pressure.
        Log.d(SERVICE_LOG_TAG, "onStartCommand flags=$flags startId=$startId returning=START_STICKY")
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterReceiverSafely()
        unregisterPowerReceiverSafely()
        unregisterScreenReceiverSafely() // New: avoid leaking the screen-state receiver when the service stops.
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
                Log.d(
                    SERVICE_LOG_TAG,
                    "insertDischargeSample INSERTED id=$id level=$currentLevel% ts=$now isCharging=${sample.isCharging}"
                )
            } else {
                // Keep service baseline aligned with DB truth when repository rejects the row.
                val latestDbLevel = dao.getLatestSample()?.batteryLevel?.toInt()
                if (latestDbLevel != null) {
                    persistLastRecordedLevel(latestDbLevel)
                }
                Log.d(
                    SERVICE_LOG_TAG,
                    "insertDischargeSample SKIPPED level=$currentLevel% ts=$now isCharging=${sample.isCharging} dbLatest=${latestDbLevel ?: "none"}"
                )
            }

            dao.deleteOlderThan(now - TimeUnit.DAYS.toMillis(7))
        } catch (t: Throwable) {
            Log.e(SERVICE_LOG_TAG, "Battery logging insert failed", t)
        }
    }

    private suspend fun insertPowerTransitionSample(forceCharging: Boolean) {
        try {
            val sticky = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level < 0 || scale <= 0) return

            val normalizedLevel = ((level * 100f) / scale).toInt().coerceIn(0, 100)
            val voltageMv = sticky?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
            val now = System.currentTimeMillis()

            val transitionSample = BatterySample(
                timestampEpochMillis = now,
                batteryLevel = normalizedLevel.toFloat(),
                voltage = voltageMv,
                servicesActive = true,
                foreground = false,
                isCharging = forceCharging
            )

            repository.insertStateSample(transitionSample)
            if (!forceCharging) {
                persistChargeEndAnchor(normalizedLevel, now)
            }
            persistLastRecordedLevel(normalizedLevel)
            Log.d(
                SERVICE_LOG_TAG,
                "insertPowerTransitionSample INSERTED level=$normalizedLevel% ts=$now isCharging=$forceCharging"
            )
        } catch (t: Throwable) {
            Log.e(SERVICE_LOG_TAG, "Failed to insert power transition sample", t)
        }
    }

    private suspend fun insertChargingMarker(level: Int) {
        try {
            val sticky = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
            val voltageMv = sticky.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
            val now = System.currentTimeMillis()

            val chargingMarker = BatterySample(
                timestampEpochMillis = now,
                batteryLevel = level.toFloat(),
                voltage = voltageMv,
                servicesActive = true,
                foreground = false,
                isCharging = true
            )

            repository.insertStateSample(chargingMarker)
            persistLastRecordedLevel(level)
            Log.d(
                SERVICE_LOG_TAG,
                "insertChargingMarker INSERTED level=$level% ts=$now isCharging=${chargingMarker.isCharging}"
            )
        } catch (t: Throwable) {
            Log.e(SERVICE_LOG_TAG, "Failed to insert charging marker", t)
        }
    }

    private suspend fun recoverChargingStateOnRestart() {
        try {
            val sticky = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
            val level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level < 0 || scale <= 0) return

            val normalizedLevel = ((level * 100f) / scale).toInt().coerceIn(0, 100)
            val status = sticky.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            val plugged = sticky.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
            val isChargingNow = plugged != 0 ||
                status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

            val latest = dao.getLatestSample()
            val shouldInsertRecoveryMarker = latest == null || latest.isCharging != isChargingNow
            Log.d(
                SERVICE_LOG_TAG,
                "recoverChargingStateOnRestart latestCharging=${latest?.isCharging ?: "none"} nowCharging=$isChargingNow latestLevel=${latest?.batteryLevel?.toInt() ?: "none"}% currentLevel=$normalizedLevel% insertMarker=$shouldInsertRecoveryMarker"
            )
            if (shouldInsertRecoveryMarker) {
                val voltageMv = sticky.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
                val marker = BatterySample(
                    timestampEpochMillis = System.currentTimeMillis(),
                    batteryLevel = normalizedLevel.toFloat(),
                    voltage = voltageMv,
                    servicesActive = true,
                    foreground = false,
                    isCharging = isChargingNow
                )
                repository.insertStateSample(marker)
                Log.d(
                    SERVICE_LOG_TAG,
                    "Recovered charging state on restart (charging=$isChargingNow, level=$normalizedLevel%)"
                )
            }

            persistLastRecordedLevel(normalizedLevel)
        } catch (t: Throwable) {
            Log.e(SERVICE_LOG_TAG, "Failed to recover charging state on restart", t)
        }
    }

    private suspend fun maybeResetStaleDatabase(currentLevel: Int) {
        val latest = dao.getLatestSample() ?: return
        if (!latest.isCharging && (currentLevel - latest.batteryLevel) > STALE_LEVEL_JUMP_THRESHOLD_PERCENT) {
            dao.clearAllSamples()
            Log.w(
                SERVICE_LOG_TAG,
                "Cleared stale DB after level jump (${latest.batteryLevel.toInt()}% -> $currentLevel%)"
            )
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
        Log.d(SERVICE_LOG_TAG, "Registered ACTION_BATTERY_CHANGED receiver")

        val powerFilter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(powerStateReceiver, powerFilter, RECEIVER_NOT_EXPORTED)
        } else {
            ContextCompat.registerReceiver(
                this,
                powerStateReceiver,
                powerFilter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
        Log.d(SERVICE_LOG_TAG, "Registered power transition receivers")
    }

    private fun unregisterReceiverSafely() {
        try {
            unregisterReceiver(batteryChangedReceiver)
        } catch (_: IllegalArgumentException) {
            // Receiver was already unregistered.
        }
    }

    private fun unregisterPowerReceiverSafely() {
        try {
            unregisterReceiver(powerStateReceiver)
        } catch (_: IllegalArgumentException) {
            // Receiver was already unregistered.
        }
    }

    private fun unregisterScreenReceiverSafely() {
        try {
            unregisterReceiver(screenStateReceiver)
        } catch (_: IllegalArgumentException) {
            // Receiver was already unregistered.
        }
    }

    private fun registerScreenStateReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenStateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            ContextCompat.registerReceiver(
                this,
                screenStateReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
        Log.d(SERVICE_LOG_TAG, "Registered screen state receiver")
    }

    private fun persistLastRecordedLevel(level: Int) {
        lastRecordedLevel = level
        prefs.edit().putInt(KEY_LAST_RECORDED_LEVEL, level).apply()
    }

    private fun persistScreenOffTimestamp(timestampMs: Long) {
        lastScreenOffTimestampMs = timestampMs
        prefs.edit()
            .putLong(KEY_LAST_SCREEN_OFF_TIME, timestampMs)
            .putLong(LEGACY_KEY_LAST_SCREEN_OFF_TIMESTAMP, timestampMs)
            .apply() // Persist the exact screen-off timestamp immediately for the idle helper.
    }

    private fun persistScreenOnTimestamp(timestampMs: Long) {
        lastScreenOnTimestampMs = timestampMs
        prefs.edit()
            .putLong(KEY_LAST_SCREEN_ON_TIME, timestampMs)
            .apply() // Persist the exact screen-on timestamp immediately for the 3-minute grace period.
    }

    private fun persistLastActiveTimestamp(timestampMs: Long) {
        prefs.edit()
            .putLong(KEY_LAST_ACTIVE_TIMESTAMP, timestampMs)
            .apply() // Persist the latest active timestamp so helper logic survives service restarts.
    }

    private fun persistChargeEndAnchor(level: Int, timestampMs: Long) {
        val alertPrefs = getSharedPreferences(BatteryAlertNotifier.PREFS_NAME, MODE_PRIVATE)
        alertPrefs.edit()
            .putInt(BatteryAlertNotifier.KEY_LAST_CHARGE_END_LEVEL, level)
            .putLong(BatteryAlertNotifier.KEY_LAST_CHARGE_END_TIME, timestampMs)
            .apply()
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
        private const val KEY_LAST_SCREEN_OFF_TIME = "last_screen_off_time" // Exact key required by the idle helper.
        private const val KEY_LAST_SCREEN_ON_TIME = "last_screen_on_time" // Exact key required by the idle helper.
        private const val KEY_LAST_ACTIVE_TIMESTAMP = "last_active_timestamp" // Exact key required by the idle helper.
        private const val LEGACY_KEY_LAST_SCREEN_OFF_TIMESTAMP = "last_screen_off_timestamp" // Backward-compatible fallback for older app data.
        private const val LEVEL_UNINITIALIZED = -1
        private const val SCREEN_OFF_UNSET = -1L // Sentinel used when no screen-off timestamp has been recorded yet.
        private const val SCREEN_ON_UNSET = -1L // Sentinel used when no screen-on timestamp has been recorded yet.
        private const val STALE_LEVEL_JUMP_THRESHOLD_PERCENT = 5.0f

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


