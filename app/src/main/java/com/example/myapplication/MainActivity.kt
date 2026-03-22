package com.example.myapplication

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.BatteryManager
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
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

    // Cached colors — avoids Color.parseColor() on every UI refresh
    private companion object {
        val COLOR_GREEN  = android.graphics.Color.parseColor("#00C853")
        val COLOR_AMBER  = android.graphics.Color.parseColor("#FF9800")
        val COLOR_RED    = android.graphics.Color.parseColor("#FF1744")

        private const val PREFS_NAME = "battery_prediction_prefs"
        private const val KEY_OPT_PROMPTED = "battery_opt_prompted"
        private const val KEY_HISTORY_CLEANUP_DONE = "history_cleanup_done_v1"
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
    private var lastPredictionRunTimeMs: Long = 0L
    private var lastPredictionBatteryLevel: Float = 100f
    private var cachedSmoothedHours: Double? = null

    private val minSamplesToFit = BatteryPredictionUiFormatter.COLD_START_MIN_SAMPLES
    private val samplingIntervalMs = 30_000L
    private val sevenDaysMillis = TimeUnit.DAYS.toMillis(7)

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
        runOneTimeHistoricalCleanupIfNeeded()

        sampler = BatterySampler(this, BatteryLoggingForegroundService::class.java.name)
        promptIgnoreBatteryOptimizationsIfNeeded()
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
                    wasChargingInPreviousTick = true
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

                // ── Charging → discharging transition ──
                if (wasChargingInPreviousTick) {
                    wasChargingInPreviousTick = false
                    predictionEngine.reset()
                    cachedSmoothedHours = null
                    lastPredictionRunTimeMs = 0L
                    withContext(Dispatchers.IO) {
                        repository.clearAllSamples()
                    }
                    withContext(Dispatchers.Main) {
                        sampleCountText.text   = "0/$minSamplesToFit samples"
                        timeRemainingText.text = "Learning your habits..."
                        todText.text           = ""
                    }
                    Log.d(LOG_TAG, "Device unplugged - cleared old samples and reset prediction state")
                }

                if (!sampler.hasUsageAccess()) {
                    if (!promptedUsageAccess) {
                        promptedUsageAccess = true
                        sampler.openUsageAccessSettings()
                    }
                }

                if (sample != null) {
                    withContext(Dispatchers.Main) {
                        val pct = sample.batteryLevel
                        batteryIcon.setLevel(pct)
                        batteryPercentText.text = "${pct.toInt()}%"
                        batteryPercentText.setTextColor(batteryColor(pct))
                        batteryStatusText.text = batteryStatusLabel(pct)
                    }

                    // Foreground service owns database writes to avoid duplicate inserts.
                    withContext(Dispatchers.IO) {
                        val cutoff = System.currentTimeMillis() - sevenDaysMillis
                        val deleted = repository.pruneOlderThan(cutoff)
                        if (deleted > 0) Log.d(LOG_TAG, "Pruned $deleted old samples")
                    }

                    updatePrediction()
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

    private fun updatePrediction() {
        predictionJob?.cancel()
        predictionJob = lifecycleScope.launch {
            try {
                val olsSamples = withContext(Dispatchers.IO) {
                    repository.getRecentDischargingWindow()
                }

                val graphPoints = BatteryGraphSanitizer.buildDisplayPoints(olsSamples)

                if (olsSamples.size < minSamplesToFit) {
                    withContext(Dispatchers.Main) {
                        sampleCountText.text   = "${olsSamples.size}/$minSamplesToFit samples"
                        timeRemainingText.text = BatteryPredictionUiFormatter.remainingText(
                            sampleCount       = olsSamples.size,
                            rawPredictedHours = null
                        )
                        todText.text = "Need ${minSamplesToFit - olsSamples.size} more reading(s)"
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

                if (shouldRunMath || cachedSmoothedHours == null) {
                    val result = predictionEngine.predictRemainingHours(olsSamples)
                    lastPredictionRunTimeMs = nowEpochMillis
                    lastPredictionBatteryLevel = currentBatteryLevel
                    cachedSmoothedHours = if (result.smoothedHours > 0.0) result.smoothedHours else null
                }

                val predictedHoursForUi = cachedSmoothedHours
                val tDeathEpochMillis = predictedHoursForUi
                    ?.takeIf {
                        it > 0.0 &&
                            it <= BatteryPredictionUiFormatter.UNREALISTIC_HOURS_THRESHOLD
                    }
                    ?.let { nowEpochMillis + (it * 3_600_000.0).toLong() }

                withContext(Dispatchers.Main) {
                    sampleCountText.text = "${olsSamples.size} samples"
                    batteryGraph.setData(graphPoints, tDeathEpochMillis)

                    timeRemainingText.text = BatteryPredictionUiFormatter.remainingText(
                        sampleCount       = olsSamples.size,
                        rawPredictedHours = predictedHoursForUi
                    )

                    todText.text = if (tDeathEpochMillis != null)
                        "Dies at ${formatAbsoluteTime(tDeathEpochMillis)}"
                    else
                        ""
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Error updating prediction", e)
                withContext(Dispatchers.Main) {
                    timeRemainingText.text = "Error"
                    todText.text           = e.message ?: "Unknown error"
                }
            }
        }
    }

    // ── Formatting helpers ──────────────────────────────────────────────────

    private fun formatAbsoluteTime(timestampMs: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestampMs))

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
}
