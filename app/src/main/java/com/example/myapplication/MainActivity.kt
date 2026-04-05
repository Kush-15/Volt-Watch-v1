package com.example.myapplication

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.BatteryManager
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val LOG_TAG = "MainActivity"

class MainActivity : AppCompatActivity() {

    private enum class RefreshSource {
        AUTO,
        MANUAL
    }

    // Cached colors — avoids Color.parseColor() on every UI refresh
    private companion object {
        val COLOR_GREEN  = android.graphics.Color.parseColor("#00C853")
        val COLOR_AMBER  = android.graphics.Color.parseColor("#FF9800")
        val COLOR_RED    = android.graphics.Color.parseColor("#FF1744")

        private const val PREFS_NAME = "battery_prediction_prefs"
        private const val KEY_OPT_PROMPTED = "battery_opt_prompted"
        private const val KEY_HISTORY_CLEANUP_DONE = "history_cleanup_done_v1"
        private const val KEY_DB_NUKE_DONE = "db_nuke_fresh_session_v1"
        private const val REQUIRED_CHARGING_TICKS_FOR_RESET = 2
        private const val SERVICE_RECOVERY_COOLDOWN_MS = 60_000L
        private const val MANUAL_REFRESH_THROTTLE_MS = 2_500L
        private const val MANUAL_REFRESH_VISUAL_DELAY_MS = 600L
    }

    private lateinit var sampler: BatterySampler
    private lateinit var database: BatteryDatabase
    private lateinit var dao: BatterySampleDao
    private lateinit var repository: BatteryRepository
    private val predictionEngine = PredictionEngine()

    // ── UI views ──
    private lateinit var batteryIcon: BatteryIconView
    private lateinit var batteryPercentText: TextView
    private lateinit var batteryStatusText: TextView
    private lateinit var timeRemainingText: TextView
    private lateinit var todText: TextView
    private lateinit var sampleCountText: TextView
    private lateinit var batteryGraph: BatteryGraphView

    private var samplingJob: Job? = null
    private var predictionJob: Job? = null
    private var promptedUsageAccess = false
    private var wasChargingInPreviousTick = false
    private var consecutiveChargingTicks = 0
    private var lastServiceRecoveryAttemptMs = 0L
    private var lastPredictionRunTimeMs: Long = 0L
    private var lastPredictionBatteryLevel: Float = 100f
    private var cachedSmoothedHours: Double? = null
    private var latestObservedBatteryLevel: Float? = null
    private var lastManualRefreshTapTimeMs: Long = 0L

    private val minSamplesToFit = BatteryPredictionUiFormatter.COLD_START_MIN_SAMPLES
    private val samplingIntervalMs = 30_000L
    private val thirtyDaysMillis = TimeUnit.DAYS.toMillis(30)

    // ── Permission Launchers ──
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Log.d(LOG_TAG, "✅ POST_NOTIFICATIONS permission granted - Starting Foreground Service")
            startBatteryService()
        } else {
            Log.w(LOG_TAG, "❌ POST_NOTIFICATIONS permission denied")
            Toast.makeText(
                this,
                "Permission denied: Background battery tracking requires notification permission",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        batteryIcon        = findViewById(R.id.batteryIcon)
        batteryPercentText = findViewById(R.id.batteryPercentText)
        batteryStatusText  = findViewById(R.id.batteryStatusText)
        timeRemainingText  = findViewById(R.id.timeRemainingText)
        todText            = findViewById(R.id.todText)
        sampleCountText    = findViewById(R.id.sampleCountText)
        batteryGraph       = findViewById(R.id.batteryGraph)

        database = BatteryDatabase.getInstance(applicationContext)
        dao = database.batterySampleDao()
        repository = BatteryRepository(dao)
        runOneTimeDbNukeIfNeeded()
        runOneTimeHistoricalCleanupIfNeeded()

        sampler = BatterySampler(this, BatteryLoggingForegroundService::class.java.name)
        promptIgnoreBatteryOptimizationsIfNeeded()

        timeRemainingText.setOnClickListener {
            requestManualPredictionRefresh()
        }
        
        // ── Request POST_NOTIFICATIONS permission (Android 13+) ──
        requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        startSampling()
    }

    override fun onPause() {
        super.onPause()
        stopSampling()
    }

    private fun startSampling() {
        if (samplingJob?.isActive == true) return

        samplingJob = lifecycleScope.launch {
            while (isActive) {
                // ── Single IPC call: read battery state + get sample in one shot ──
                val sample = sampler.sample()   // also sets sampler.isCharging

                if (sampler.isCharging) {
                    consecutiveChargingTicks += 1
                    if (consecutiveChargingTicks >= REQUIRED_CHARGING_TICKS_FOR_RESET) {
                        wasChargingInPreviousTick = true
                    }
                    predictionJob?.cancel()
                    withContext(Dispatchers.Main) {
                        batteryStatusText.text = "⚡ Charging..."
                        timeRemainingText.text  = "⚡ Charging..."
                        todText.text            = ""
                        sampleCountText.text    = "Sampling paused"
                    }
                    delay(samplingIntervalMs)
                    continue
                }

                consecutiveChargingTicks = 0

                // ── Charging → discharging transition ──
                if (wasChargingInPreviousTick) {
                    wasChargingInPreviousTick = false
                    predictionEngine.reset()
                    cachedSmoothedHours = null
                    lastPredictionRunTimeMs = 0L
                    withContext(Dispatchers.Main) {
                        sampleCountText.text   = "0/$minSamplesToFit samples"
                        timeRemainingText.text = "Learning your habits..."
                        todText.text           = ""
                    }
                    Log.d(LOG_TAG, "Device unplugged - reset prediction state")
                }

                if (!sampler.hasUsageAccess()) {
                    if (!promptedUsageAccess) {
                        promptedUsageAccess = true
                        sampler.openUsageAccessSettings()
                    }
                }

                if (sample != null) {
                    if (!sample.servicesActive) {
                        val now = System.currentTimeMillis()
                        if (now - lastServiceRecoveryAttemptMs >= SERVICE_RECOVERY_COOLDOWN_MS) {
                            lastServiceRecoveryAttemptMs = now
                            Log.w(LOG_TAG, "Foreground battery service not running; requesting restart")
                            requestNotificationPermissionIfNeeded()
                        }

                        // Fallback writer: keeps DB alive even if OEM kills the service.
                        withContext(Dispatchers.IO) {
                            val fallbackSample = sample.copy(
                                servicesActive = true,
                                foreground = false,
                                isCharging = false
                            )
                            val id = repository.insertSample(fallbackSample)
                            if (id > 0) {
                                Log.w(
                                    LOG_TAG,
                                    "Inserted fallback sample from UI loop (id=$id, level=${sample.batteryLevel.toInt()}%)"
                                )
                            }
                        }
                    }

                    withContext(Dispatchers.Main) {
                        val pct = sample.batteryLevel
                        batteryIcon.setLevel(pct)
                        batteryPercentText.text = "${pct.toInt()}%"
                        batteryPercentText.setTextColor(batteryColor(pct))
                        batteryStatusText.text = batteryStatusLabel(pct)
                    }

                    // Foreground service owns database writes to avoid duplicate inserts.
                    withContext(Dispatchers.IO) {
                        val cutoff = System.currentTimeMillis() - thirtyDaysMillis
                        val deleted = repository.pruneOlderThan(cutoff)
                        if (deleted > 0) Log.d(LOG_TAG, "Pruned $deleted old samples")
                    }

                    latestObservedBatteryLevel = sample.batteryLevel
                    refreshPrediction(
                        currentSystemBatteryLevel = sample.batteryLevel,
                        source = RefreshSource.AUTO,
                        forceRecompute = false
                    )
                } else {
                    withContext(Dispatchers.Main) {
                        batteryStatusText.text = "Calculating..."
                        timeRemainingText.text = "Calculating..."
                        todText.text           = ""
                    }
                }

                delay(samplingIntervalMs)
            }
        }
    }

    private fun stopSampling() {
        samplingJob?.cancel()
        samplingJob = null
        predictionJob?.cancel()
        predictionJob = null
    }

    private fun requestManualPredictionRefresh() {
        val batteryLevel = latestObservedBatteryLevel
        if (batteryLevel == null) {
            Toast.makeText(this, getString(R.string.prediction_refresh_no_data), Toast.LENGTH_SHORT).show()
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastManualRefreshTapTimeMs < MANUAL_REFRESH_THROTTLE_MS) {
            todText.text = getString(R.string.prediction_refresh_wait)
            return
        }

        if (predictionJob?.isActive == true) {
            todText.text = getString(R.string.prediction_refresh_busy)
            return
        }

        lastManualRefreshTapTimeMs = now
        todText.text = getString(R.string.prediction_refreshing)
        timeRemainingText.text = getString(R.string.prediction_refreshing)
        lifecycleScope.launch {
            // Give the user instant feedback before kicking off heavier prediction work.
            delay(MANUAL_REFRESH_VISUAL_DELAY_MS)
            refreshPrediction(
                currentSystemBatteryLevel = batteryLevel,
                source = RefreshSource.MANUAL,
                forceRecompute = true
            )
        }
    }

    private fun refreshPrediction(
        currentSystemBatteryLevel: Float,
        source: RefreshSource,
        forceRecompute: Boolean
    ) {
        if (source == RefreshSource.AUTO) {
            predictionJob?.cancel()
        }

        predictionJob = lifecycleScope.launch {
            try {
                val olsSamples = withContext(Dispatchers.IO) {
                    repository.getRecentDischargingWindow(currentSystemBatteryLevel)
                }

                val graphPoints = BatteryGraphSanitizer.buildDisplayPoints(olsSamples)

                // ── NEW: Changed threshold from minSamplesToFit (6) to 10 for better stability ──
                // With 10+ samples, the OLS fit is more numerically stable and less prone to noise
                if (olsSamples.size < 10) {
                    Log.d(LOG_TAG, "❌ Not enough samples: ${olsSamples.size}/10. Showing 'Learning...'")
                    withContext(Dispatchers.Main) {
                        sampleCountText.text   = "${olsSamples.size}/10 samples"
                        timeRemainingText.text = BatteryPredictionUiFormatter.remainingText(
                            sampleCount       = olsSamples.size,
                            rawPredictedHours = null
                        )
                        todText.text = "Need ${10 - olsSamples.size} more reading(s)"
                        batteryGraph.setData(graphPoints, null)
                    }
                    return@launch
                }

                val nowEpochMillis = System.currentTimeMillis()
                val currentBatteryLevel = olsSamples.last().batteryLevel

                val shouldRunMath = predictionEngine.shouldRunPrediction(
                    currentTimeMs = nowEpochMillis,
                    lastRunTimeMs = lastPredictionRunTimeMs,
                    currentBatteryLevel = currentBatteryLevel,
                    lastBatteryLevel = lastPredictionBatteryLevel
                )

                var manualMathInvalid = false

                Log.d(LOG_TAG, "📊 Prediction check: samples=${olsSamples.size}, shouldRun=$shouldRunMath, cached=${cachedSmoothedHours}")

                if (forceRecompute || shouldRunMath || cachedSmoothedHours == null) {
                    val result = predictionEngine.predictRemainingHours(olsSamples)
                    lastPredictionRunTimeMs = nowEpochMillis
                    lastPredictionBatteryLevel = currentBatteryLevel
                    
                    Log.d(LOG_TAG, "🔬 OLS Result: raw=${result.rawHours}h, smoothed=${result.smoothedHours}h, slope=${result.slope}")
                    
                    if (result.smoothedHours > 0.0) {
                        cachedSmoothedHours = result.smoothedHours
                    } else {
                        manualMathInvalid = source == RefreshSource.MANUAL
                        Log.w(LOG_TAG, "⚠️ OLS returned invalid result: rawHours=${result.rawHours}, smoothedHours=${result.smoothedHours}")
                    }
                } else {
                    Log.d(LOG_TAG, "✅ Using cached prediction: ${cachedSmoothedHours}h")
                }

                val predictedHoursForUi = cachedSmoothedHours
                val tDeathEpochMillis = predictedHoursForUi
                    ?.takeIf {
                        it > 0.0 &&
                            it <= BatteryPredictionUiFormatter.UNREALISTIC_HOURS_THRESHOLD
                    }
                    ?.let { nowEpochMillis + (it * 3_600_000.0).toLong() }

                Log.d(LOG_TAG, "📱 UI Update: predictedHours=$predictedHoursForUi, tDeath=$tDeathEpochMillis")

                withContext(Dispatchers.Main) {
                    sampleCountText.text = "${olsSamples.size} samples"
                    batteryGraph.setData(graphPoints, tDeathEpochMillis)

                    timeRemainingText.text = BatteryPredictionUiFormatter.remainingText(
                        sampleCount       = olsSamples.size,
                        rawPredictedHours = predictedHoursForUi
                    )

                    todText.text = when {
                        tDeathEpochMillis != null -> "Dies at ${formatAbsoluteTime(tDeathEpochMillis)}"
                        source == RefreshSource.MANUAL && manualMathInvalid -> getString(R.string.prediction_still_calculating)
                        source == RefreshSource.MANUAL -> getString(R.string.prediction_refreshed)
                        else -> ""
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Error updating prediction", e)
                withContext(Dispatchers.Main) {
                    timeRemainingText.text = BatteryPredictionUiFormatter.remainingText(
                        sampleCount = minSamplesToFit,
                        rawPredictedHours = cachedSmoothedHours
                    )
                    todText.text = getString(R.string.prediction_still_calculating)
                }
            }
        }
    }

    // ── Formatting helpers ──────────────────────────────────────────────────

    private fun formatAbsoluteTime(timestampMs: Long): String =
        SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(timestampMs))

    private fun batteryColor(level: Float) = when {
        level > 50f -> COLOR_GREEN
        level > 20f -> COLOR_AMBER
        else        -> COLOR_RED
    }

    private fun batteryStatusLabel(level: Float) = when {
        level > 80f -> "Good"
        level > 50f -> "Normal"
        level > 20f -> "Low battery"
        else        -> "Critical ⚠️"
    }


    private fun promptIgnoreBatteryOptimizationsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return

        val powerManager = getSystemService(PowerManager::class.java) ?: return
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) return

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_OPT_PROMPTED, false)) return

        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }

        try {
            startActivity(intent)
            prefs.edit().putBoolean(KEY_OPT_PROMPTED, true).apply()
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Unable to open battery optimization exemption screen", e)
        }
    }

    private fun runOneTimeHistoricalCleanupIfNeeded() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_HISTORY_CLEANUP_DONE, false)) return

        lifecycleScope.launch(Dispatchers.IO) {
            val result = repository.cleanupHistoricalData()
            Log.i(
                LOG_TAG,
                "Historical cleanup completed: removedCharging=${result.deletedChargingRows}, removedSpikes=${result.deletedOrphanSpikes}"
            )
            prefs.edit().putBoolean(KEY_HISTORY_CLEANUP_DONE, true).apply()
        }
    }

    /**
     * One-time wipe of all old contaminated rows before fresh session starts.
     * After this, charging/discharging will be captured with clean session boundary.
     */
    private fun runOneTimeDbNukeIfNeeded() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DB_NUKE_DONE, false)) return

        lifecycleScope.launch(Dispatchers.IO) {
            repository.clearAllSamples()
            Log.i(LOG_TAG, "🔥 Cleared all battery samples for fresh session start")
            prefs.edit().putBoolean(KEY_DB_NUKE_DONE, true).apply()
        }
    }

    /**
     * Request POST_NOTIFICATIONS permission on Android 13+ (API 33+).
     * 
     * The Foreground Service requires a notification to stay alive. Without this permission,
     * startForeground() will crash silently, preventing the BatteryLoggingForegroundService
     * from capturing battery data.
     */
    private fun requestNotificationPermissionIfNeeded() {
        // Only request on Android 13 (Tiramisu) and above
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Log.d(LOG_TAG, "Device running Android < 13: Notification permission auto-granted")
            startBatteryService()
            return
        }

        // Check if permission is already granted
        val isGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

        if (isGranted) {
            Log.d(LOG_TAG, "✅ POST_NOTIFICATIONS permission already granted")
            startBatteryService()
        } else {
            Log.d(LOG_TAG, "⏳ Requesting POST_NOTIFICATIONS permission from user...")
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /**
     * Start the BatteryLoggingForegroundService.
     * 
     * This is called after the POST_NOTIFICATIONS permission is confirmed to be granted.
     * The service will display a persistent notification and start collecting battery telemetry.
     */
    private fun startBatteryService() {
        Log.d(LOG_TAG, "🚀 Starting BatteryLoggingForegroundService...")
        BatteryLoggingForegroundService.start(this)
    }
}
