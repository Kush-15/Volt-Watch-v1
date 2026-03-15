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
        private const val KEY_LAST_GOOD_HOURS = "last_good_hours"
        private const val KEY_LAST_GOOD_AT_MS = "last_good_at_ms"
        private const val KEY_OPT_PROMPTED = "battery_opt_prompted"
        private val LAST_GOOD_MAX_AGE_MS = TimeUnit.HOURS.toMillis(12)
        private const val CHARGE_SPIKE_THRESHOLD_PCT = 1.0f
        private const val UI_CAP_HOURS_WHEN_FLAT = 25.0
    }

    private val regression = OlsRegression()
    private lateinit var sampler: BatterySampler
    private lateinit var database: BatteryDatabase
    private lateinit var dao: BatterySampleDao

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

    /**
     * Epoch ms when the current discharge session started (set when the phone is unplugged).
     * 0 means no charging transition observed this session — use full 7-day history for OLS.
     */
    private var sessionStartTimeMs: Long = 0L

    private val minSamplesToFit = 6
    private val samplingIntervalMs = 30_000L
    private val sevenDaysMillis = TimeUnit.DAYS.toMillis(7)

    // Feature mask: bit0=time (required), bit1=voltage, bit2=services, bit3=foreground
    // Keep services/foreground disabled for now because sampling currently runs only while
    // MainActivity is foreground, which makes those columns mostly constant/noisy.
    private val featureMask = 0b011

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
                    // Start a new session window so the OLS model only sees fresh
                    // post-charge data, WITHOUT deleting historical graph data.
                    sessionStartTimeMs = System.currentTimeMillis()
                    withContext(Dispatchers.Main) {
                        sampleCountText.text   = "0/$minSamplesToFit samples"
                        timeRemainingText.text = "Learning your habits..."
                        todText.text           = ""
                    }
                    Log.d(LOG_TAG, "Device unplugged — new session started at $sessionStartTimeMs")
                }

                if (isFeatureEnabled(2) && !sampler.hasUsageAccess()) {
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

                    withContext(Dispatchers.IO) {
                        val id = dao.insertSample(sample)
                        Log.d(LOG_TAG, "Sample inserted id=$id at ${sample.timestampEpochMillis}")
                        val cutoff = System.currentTimeMillis() - sevenDaysMillis
                        val deleted = dao.deleteOlderThan(cutoff)
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
                val sevenDaysAgoMillis = System.currentTimeMillis() - sevenDaysMillis

                // Load full 7-day history from DB in one query
                val allSamples = withContext(Dispatchers.IO) {
                    dao.getSamplesSince(sevenDaysAgoMillis)
                }

                // Train only on the latest discharge window (after the most recent upward spike).
                // This prevents mixed charge/discharge history from forcing a non-negative slope.
                val spikeStartMs = findLastChargeSpikeTimestamp(allSamples)
                val effectiveStartMs = maxOf(sessionStartTimeMs, spikeStartMs)
                val olsSamples = if (effectiveStartMs > 0L)
                    allSamples.filter { it.timestampEpochMillis >= effectiveStartMs }
                else
                    allSamples

                // Graph always shows full history for context
                val graphPoints = allSamples.map { Pair(it.timestampEpochMillis, it.batteryLevel) }

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

                val (xRows, yValues) = withContext(Dispatchers.IO) {
                    val features   = ArrayList<DoubleArray>(olsSamples.size)
                    val labels     = DoubleArray(olsSamples.size)
                    val anchorTime = olsSamples.first().timestampEpochMillis
                    olsSamples.forEachIndexed { i, s ->
                        features.add(buildFeatureVector(s, anchorTime))
                        labels[i] = s.batteryLevel.toDouble()
                    }
                    Pair(features.toTypedArray(), labels)
                }

                val (_, tDeathEpochMillis) = withContext(Dispatchers.Default) {
                    fitAndPredict(xRows, yValues, nowEpochMillis)
                }

                withContext(Dispatchers.Main) {
                    sampleCountText.text = "${olsSamples.size} samples"
                    batteryGraph.setData(graphPoints, tDeathEpochMillis)

                    val rawPredictedHours = tDeathEpochMillis?.let {
                        (it - nowEpochMillis) / 3_600_000.0
                    }

                    val effectiveHoursForUi = if (
                        rawPredictedHours != null &&
                        rawPredictedHours > 0.0 &&
                        rawPredictedHours <= BatteryPredictionUiFormatter.UNREALISTIC_HOURS_THRESHOLD
                    ) {
                        saveLastGoodPrediction(rawPredictedHours)
                        rawPredictedHours
                    } else {
                        // Warm start: if current session is too flat/noisy, show the most
                        // recent valid prediction instead of staying in Calculating for long.
                        readRecentLastGoodPrediction()
                    }

                    timeRemainingText.text = BatteryPredictionUiFormatter.remainingText(
                        sampleCount       = olsSamples.size,
                        rawPredictedHours = effectiveHoursForUi
                    )

                    val isNormalRange =
                        olsSamples.size >= BatteryPredictionUiFormatter.COLD_START_MIN_SAMPLES &&
                        rawPredictedHours != null &&
                        rawPredictedHours > 0.0 &&
                        rawPredictedHours <= BatteryPredictionUiFormatter.UNREALISTIC_HOURS_THRESHOLD

                    todText.text = if (isNormalRange)
                        "Dies at ${formatAbsoluteTime(tDeathEpochMillis!!)}"
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

    private suspend fun fitAndPredict(
        xRows: Array<DoubleArray>,
        yValues: DoubleArray,
        nowEpochMillis: Long
    ): Pair<Double?, Long?> = withContext(Dispatchers.Default) {
        if (!regression.fit(xRows, yValues) || xRows.isEmpty())
            return@withContext Pair(null, null)

        val currentBatteryPercent = yValues.last()
        val slope = regression.slopeForFeature(0)
        Log.d(LOG_TAG, "Slope (pp/min): $slope, Battery: $currentBatteryPercent%")

        if (slope == null || slope >= 0.0) {
            Log.d(LOG_TAG, "Non-negative slope — showing capped estimate instead of perpetual Calculating")
            val cappedTDeath = nowEpochMillis + (UI_CAP_HOURS_WHEN_FLAT * 3_600_000.0).toLong()
            return@withContext Pair(currentBatteryPercent, cappedTDeath)
        }
        if (currentBatteryPercent <= 0.0) {
            Log.w(LOG_TAG, "Battery ≤ 0% — device likely dead")
            return@withContext Pair(currentBatteryPercent, null)
        }

        val millisToEmpty  = ((currentBatteryPercent / -slope) * 60_000.0).toLong()
        val tDeathEpochMs  = nowEpochMillis + millisToEmpty

        if (tDeathEpochMs <= nowEpochMillis) {
            Log.w(LOG_TAG, "TOD in the past — data anomaly")
            return@withContext Pair(currentBatteryPercent, null)
        }
        if (tDeathEpochMs > nowEpochMillis + TimeUnit.DAYS.toMillis(30)) {
            Log.w(LOG_TAG, "TOD > 30 days — model unreliable")
            return@withContext Pair(currentBatteryPercent, null)
        }

        Log.d(LOG_TAG, "TOD: ${formatAbsoluteTime(tDeathEpochMs)}")
        Pair(currentBatteryPercent, tDeathEpochMs)
    }

    private fun buildFeatureVector(sample: BatterySample, anchorTimeMs: Long): DoubleArray {
        val values = ArrayList<Double>(4)
        values.add((sample.timestampEpochMillis - anchorTimeMs) / 60_000.0)
        if (isFeatureEnabled(1)) values.add(sample.voltage.toDouble())
        if (isFeatureEnabled(2)) values.add(if (sample.servicesActive) 1.0 else 0.0)
        if (isFeatureEnabled(3)) values.add(if (sample.foreground) 1.0 else 0.0)
        return values.toDoubleArray()
    }

    private fun isFeatureEnabled(bit: Int): Boolean {
        if (bit == 0) return true
        return featureMask and (1 shl bit) != 0
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

    private fun findLastChargeSpikeTimestamp(samples: List<BatterySample>): Long {
        if (samples.size < 2) return 0L
        var spikeStart = 0L
        for (i in 1 until samples.size) {
            val delta = samples[i].batteryLevel - samples[i - 1].batteryLevel
            if (delta >= CHARGE_SPIKE_THRESHOLD_PCT) {
                spikeStart = samples[i].timestampEpochMillis
            }
        }
        return spikeStart
    }

    private fun saveLastGoodPrediction(hours: Double) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putFloat(KEY_LAST_GOOD_HOURS, hours.toFloat())
            .putLong(KEY_LAST_GOOD_AT_MS, System.currentTimeMillis())
            .apply()
    }

    private fun readRecentLastGoodPrediction(): Double? {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val savedAt = prefs.getLong(KEY_LAST_GOOD_AT_MS, 0L)
        if (savedAt <= 0L || (System.currentTimeMillis() - savedAt) > LAST_GOOD_MAX_AGE_MS) {
            return null
        }

        val value = prefs.getFloat(KEY_LAST_GOOD_HOURS, -1f).toDouble()
        return if (value > 0.0 && value <= BatteryPredictionUiFormatter.UNREALISTIC_HOURS_THRESHOLD) {
            value
        } else {
            null
        }
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
}
