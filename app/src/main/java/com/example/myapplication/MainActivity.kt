package com.example.myapplication

import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
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

/**
 * MainActivity for battery prediction.
 *
 * Responsibilities:
 * 1. Sample battery telemetry via BatterySampler
 * 2. Persist samples to Room database (with 7-day retention)
 * 3. Train OLS model on historical samples using Dispatchers.Default
 * 4. Calculate TOD (Time of Death) with safe slope handling
 * 5. Update UI on Dispatchers.Main
 *
 * Feature mask: bit0=time (required), bit1=voltage, bit2=services
 */
class MainActivity : AppCompatActivity() {
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
    private var promptedUsageAccess = false

    private val minSamplesToFit = 6
    private val samplingIntervalMs = 30_000L // Short interval for quick testing; set to 300_000L for 5 minutes.
    private val sevenDaysMillis = TimeUnit.DAYS.toMillis(7)

    // Feature mask: bit0=time (required), bit1=voltage, bit2=services
    private val featureMask = 0b111

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Bind views
        batteryIcon        = findViewById(R.id.batteryIcon)
        batteryPercentText = findViewById(R.id.batteryPercentText)
        batteryStatusText  = findViewById(R.id.batteryStatusText)
        timeRemainingText  = findViewById(R.id.timeRemainingText)
        todText            = findViewById(R.id.todText)
        sampleCountText    = findViewById(R.id.sampleCountText)
        batteryGraph       = findViewById(R.id.batteryGraph)

        // Initialize database
        database = BatteryDatabase.getInstance(applicationContext)
        dao = database.batterySampleDao()

        sampler = BatterySampler(this, "com.example.myapplication.SampleForegroundService")
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
                if (isFeatureEnabled(2) && !sampler.hasUsageAccess()) {
                    if (!promptedUsageAccess) {
                        promptedUsageAccess = true
                        sampler.openUsageAccessSettings()
                    }
                }

                val sample = sampler.sample()
                if (sample != null) {
                    // Update battery icon and percentage immediately
                    withContext(Dispatchers.Main) {
                        val pct = sample.batteryLevel
                        batteryIcon.setLevel(pct)
                        batteryPercentText.text = "${pct.toInt()}%"
                        batteryPercentText.setTextColor(batteryColor(pct))
                        batteryStatusText.text = batteryStatusLabel(pct)
                    }

                    // Persist sample and prune old data
                    withContext(Dispatchers.IO) {
                        val id = dao.insertSample(sample)
                        Log.d(LOG_TAG, "Sample inserted with id=$id at ${sample.timestampEpochMillis}")

                        val cutoffEpochMillis = System.currentTimeMillis() - sevenDaysMillis
                        val deletedCount = dao.deleteOlderThan(cutoffEpochMillis)
                        if (deletedCount > 0) {
                            Log.d(LOG_TAG, "Pruned $deletedCount old samples")
                        }
                    }

                    updatePrediction()
                } else {
                    // Device is charging
                    withContext(Dispatchers.Main) {
                        batteryStatusText.text = "Charging ⚡"
                        timeRemainingText.text = "Charging"
                        todText.text = ""
                    }
                }

                delay(samplingIntervalMs)
            }
        }
    }

    private fun stopSampling() {
        samplingJob?.cancel()
        samplingJob = null
    }

    private fun updatePrediction() {
        lifecycleScope.launch {
            try {
                val sevenDaysAgoMillis = System.currentTimeMillis() - sevenDaysMillis
                val samples = withContext(Dispatchers.IO) {
                    dao.getSamplesSince(sevenDaysAgoMillis)
                }

                // Update graph data (regardless of whether we have enough for OLS)
                val graphPoints = samples.map { Pair(it.timestampEpochMillis, it.batteryLevel) }

                if (samples.size < minSamplesToFit) {
                    withContext(Dispatchers.Main) {
                        sampleCountText.text = "${samples.size}/$minSamplesToFit samples"
                        timeRemainingText.text = BatteryPredictionUiFormatter.remainingText(
                            sampleCount = samples.size,
                            rawPredictedHours = null
                        )
                        todText.text = "Need ${minSamplesToFit - samples.size} more reading(s)"
                        batteryGraph.setData(graphPoints, null)
                    }
                    return@launch
                }

                val nowEpochMillis = System.currentTimeMillis()

                val (xRows, yValues) = withContext(Dispatchers.IO) {
                    val features = mutableListOf<DoubleArray>()
                    val labels = mutableListOf<Double>()
                    val anchorTime = samples.first().timestampEpochMillis
                    for (sample in samples) {
                        features.add(buildFeatureVector(sample, anchorTime))
                        labels.add(sample.batteryLevel.toDouble())
                    }
                    Pair(features.toTypedArray(), labels.toDoubleArray())
                }

                val (_, tDeathEpochMillis) = withContext(Dispatchers.Default) {
                    fitAndPredict(xRows, yValues, nowEpochMillis, samples.first().timestampEpochMillis)
                }

                withContext(Dispatchers.Main) {
                    sampleCountText.text = "${samples.size} samples"
                    batteryGraph.setData(graphPoints, tDeathEpochMillis)

                    val rawPredictedHours = tDeathEpochMillis?.let {
                        (it - nowEpochMillis) / 3_600_000.0
                    }
                    val remainingText = BatteryPredictionUiFormatter.remainingText(
                        sampleCount = samples.size,
                        rawPredictedHours = rawPredictedHours
                    )
                    timeRemainingText.text = remainingText

                    val isNormalRangePrediction = samples.size >= BatteryPredictionUiFormatter.COLD_START_MIN_SAMPLES &&
                        rawPredictedHours != null &&
                        rawPredictedHours > 0.0 &&
                        rawPredictedHours <= BatteryPredictionUiFormatter.UNREALISTIC_HOURS_THRESHOLD

                    if (isNormalRangePrediction && tDeathEpochMillis != null) {
                        todText.text = "Dies at ${formatAbsoluteTime(tDeathEpochMillis)}"
                    } else {
                        todText.text = ""
                    }
                }
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Error updating prediction", e)
                withContext(Dispatchers.Main) {
                    timeRemainingText.text = "Error"
                    todText.text = e.message ?: "Unknown error"
                }
            }
        }
    }

    /**
     * Fit model and compute TOD prediction.
     * Runs on Dispatchers.Default for CPU-intensive OLS fitting.
     */
    private suspend fun fitAndPredict(
        xRows: Array<DoubleArray>,
        yValues: DoubleArray,
        nowEpochMillis: Long,
        anchorTimeMs: Long
    ): Pair<Double?, Long?> = withContext(Dispatchers.Default) {
        val fitted = regression.fit(xRows, yValues)
        if (!fitted) return@withContext Pair(null, null)
        if (xRows.isEmpty()) return@withContext Pair(null, null)

        val currentBatteryPercent = yValues.last()

        val slope = regression.slopeForFeature(0)
        Log.d(LOG_TAG, "Slope (pp/min): $slope, Current battery: $currentBatteryPercent%")

        if (slope == null || slope >= 0.0) {
            Log.d(LOG_TAG, "Slope is null or non-negative (battery not draining or sensor noise)")
            return@withContext Pair(currentBatteryPercent, null)
        }

        if (currentBatteryPercent <= 0.0) {
            Log.w(LOG_TAG, "Current battery <= 0%, device likely already dead")
            return@withContext Pair(currentBatteryPercent, null)
        }

        val minutesToEmpty = currentBatteryPercent / -slope
        val millisToEmpty = (minutesToEmpty * 60000.0).toLong()
        val tDeathEpochMillis = nowEpochMillis + millisToEmpty

        if (tDeathEpochMillis <= nowEpochMillis) {
            Log.w(LOG_TAG, "Computed TOD is in the past (data anomaly)")
            return@withContext Pair(currentBatteryPercent, null)
        }

        val maxReasonableFutureMs = nowEpochMillis + TimeUnit.DAYS.toMillis(30)
        if (tDeathEpochMillis > maxReasonableFutureMs) {
            Log.w(LOG_TAG, "Computed TOD > 30 days in future (model unreliable)")
            return@withContext Pair(currentBatteryPercent, null)
        }

        Log.d(LOG_TAG, "TOD computed: ${formatAbsoluteTime(tDeathEpochMillis)}")
        return@withContext Pair(currentBatteryPercent, tDeathEpochMillis)
    }

    private fun buildFeatureVector(sample: BatterySample, anchorTimeMs: Long): DoubleArray {
        val values = ArrayList<Double>(4)
        // Feature 0: Time in minutes (always enabled)
        val minutesSinceStart = (sample.timestampEpochMillis - anchorTimeMs) / 60000.0
        values.add(minutesSinceStart)
        // Feature 1: Voltage
        if (isFeatureEnabled(1)) values.add(sample.voltage.toDouble())
        // Feature 2: Services active
        if (isFeatureEnabled(2)) values.add(if (sample.servicesActive) 1.0 else 0.0)
        // Feature 3: Foreground
        if (isFeatureEnabled(3)) values.add(if (sample.foreground) 1.0 else 0.0)
        return values.toDoubleArray()
    }

    private fun isFeatureEnabled(bit: Int): Boolean {
        if (bit == 0) return true
        return featureMask and (1 shl bit) != 0
    }

    // ── Formatting helpers ──────────────────────────────────────────────────


    private fun formatAbsoluteTime(timestampMs: Long): String {
        val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
        return formatter.format(Date(timestampMs))
    }

    private fun batteryColor(level: Float): Int {
        return when {
            level > 50f -> android.graphics.Color.parseColor("#00C853")
            level > 20f -> android.graphics.Color.parseColor("#FF9800")
            else        -> android.graphics.Color.parseColor("#FF1744")
        }
    }

    private fun batteryStatusLabel(level: Float): String = when {
        level > 80f -> "Good"
        level > 50f -> "Normal"
        level > 20f -> "Low battery"
        else        -> "Critical ⚠️"
    }
}
