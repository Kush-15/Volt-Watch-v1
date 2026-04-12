package com.example.myapplication

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.app.usage.UsageStatsManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.BatteryManager
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.View
import android.graphics.drawable.GradientDrawable
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

private const val LOG_TAG = "MainActivity"

class MainActivity : AppCompatActivity() {

    private enum class RefreshSource {
        AUTO,
        MANUAL
    }

    private enum class Screen {
        HOME,
        HISTORY,
        REPORT,
        COMMUTE
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
    private lateinit var sessionManager: SessionManager
    private val predictionEngine = PredictionEngine()

    // ── UI views ──
    private lateinit var batteryIcon: BatteryIconView
    private lateinit var batteryPercentText: TextView
    private lateinit var batteryStatusText: TextView
    private lateinit var timeRemainingText: TextView
    private lateinit var sampleCountText: TextView
    private lateinit var batteryGraph: BatteryGraphView
    private lateinit var drainPerMinValue: TextView
    private lateinit var screenOnValue: TextView
    private lateinit var chargedValue: TextView
    private lateinit var homeConfidenceText: TextView
    private lateinit var homeConfidenceDot: View
    private lateinit var screenTitleText: TextView
    private lateinit var homeScreenContainer: View
    private lateinit var historyScreenContainer: View
    private lateinit var reportScreenContainer: View
    private lateinit var commuteScreenContainer: View
    private lateinit var homeTab: View
    private lateinit var historyTab: View
    private lateinit var reportTab: View
    private lateinit var homeTabIcon: ImageView
    private lateinit var historyTabIcon: ImageView
    private lateinit var reportTabIcon: ImageView
    private lateinit var homeTabLabel: TextView
    private lateinit var historyTabLabel: TextView
    private lateinit var reportTabLabel: TextView
    private lateinit var commuteBackText: TextView
    private lateinit var commuteSeekBar: SeekBar
    private lateinit var commuteDurationText: TextView
    private lateinit var commuteBatteryNowValueText: TextView
    private lateinit var commuteEtaValueText: TextView
    private lateinit var commuteResultCard: View
    private lateinit var commuteResultTitleText: TextView
    private lateinit var commuteResultSubtitleText: TextView
    private lateinit var totalDrainText: TextView
    private lateinit var sinceLastChargeText: TextView
    private lateinit var avgDrainRateText: TextView
    private lateinit var screenOnTimeText: TextView
    private lateinit var timesChargedText: TextView
    private lateinit var longestChargeText: TextView
    private lateinit var heaviestUsageText: TextView
    private lateinit var readingsCollectedText: TextView
    private lateinit var modelConfidenceText: TextView
    private lateinit var predictionAccuracyText: TextView
    private lateinit var historyHourlyChart: HistoryChartView
    private lateinit var historyDayRows: List<HistoryDayRowViews>

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
    private val idleGapForUsageBucketMs = TimeUnit.MINUTES.toMillis(30)
    private val dayMillis = TimeUnit.DAYS.toMillis(1)

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
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(R.layout.activity_main)

        screenTitleText    = findViewById(R.id.screenTitleText)
        val titleBasePaddingTop = screenTitleText.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(screenTitleText) { view, insets ->
            val topInset = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.setPadding(
                view.paddingLeft,
                titleBasePaddingTop + topInset,
                view.paddingRight,
                view.paddingBottom
            )
            insets
        }
        homeScreenContainer = findViewById(R.id.homeScreenContainer)
        historyScreenContainer = findViewById(R.id.historyScreenContainer)
        reportScreenContainer = findViewById(R.id.reportScreenContainer)
        commuteScreenContainer = findViewById(R.id.commuteScreenContainer)
        batteryIcon        = homeScreenContainer.findViewById(R.id.batteryIcon)
        batteryPercentText = homeScreenContainer.findViewById(R.id.batteryPercentText)
        batteryStatusText  = homeScreenContainer.findViewById(R.id.batteryStatusText)
        timeRemainingText  = homeScreenContainer.findViewById(R.id.timeRemainingText)
        sampleCountText    = homeScreenContainer.findViewById(R.id.sampleCountText)
        batteryGraph       = homeScreenContainer.findViewById(R.id.batteryGraph)
        drainPerMinValue = homeScreenContainer.findViewById(R.id.drainPerMinValue)
        screenOnValue = homeScreenContainer.findViewById(R.id.screenOnValue)
        chargedValue = homeScreenContainer.findViewById(R.id.chargedValue)
        homeConfidenceText = homeScreenContainer.findViewById(R.id.confidenceText)
        homeConfidenceDot = homeScreenContainer.findViewById(R.id.confidenceDot)
        homeTab = findViewById(R.id.homeTab)
        historyTab = findViewById(R.id.historyTab)
        reportTab = findViewById(R.id.reportTab)
        homeTabIcon = findViewById(R.id.homeTabIcon)
        historyTabIcon = findViewById(R.id.historyTabIcon)
        reportTabIcon = findViewById(R.id.reportTabIcon)
        homeTabLabel = findViewById(R.id.homeTabLabel)
        historyTabLabel = findViewById(R.id.historyTabLabel)
        reportTabLabel = findViewById(R.id.reportTabLabel)
        commuteBackText = commuteScreenContainer.findViewById(R.id.commuteBackText)
        commuteSeekBar = commuteScreenContainer.findViewById(R.id.commuteSeekBar)
        commuteDurationText = commuteScreenContainer.findViewById(R.id.commuteDurationText)
        commuteBatteryNowValueText = commuteScreenContainer.findViewById(R.id.commuteBatteryNowValueText)
        commuteEtaValueText = commuteScreenContainer.findViewById(R.id.commuteEtaValueText)
        commuteResultCard = commuteScreenContainer.findViewById(R.id.commuteResultCard)
        commuteResultTitleText = commuteScreenContainer.findViewById(R.id.commuteResultTitleText)
        commuteResultSubtitleText = commuteScreenContainer.findViewById(R.id.commuteResultSubtitleText)
        totalDrainText = reportScreenContainer.findViewById(R.id.totalDrainText)
        sinceLastChargeText = reportScreenContainer.findViewById(R.id.sinceLastChargeText)
        avgDrainRateText = reportScreenContainer.findViewById(R.id.avgDrainRateText)
        screenOnTimeText = reportScreenContainer.findViewById(R.id.screenOnTimeText)
        timesChargedText = reportScreenContainer.findViewById(R.id.timesChargedText)
        longestChargeText = reportScreenContainer.findViewById(R.id.longestChargeText)
        heaviestUsageText = reportScreenContainer.findViewById(R.id.heaviestUsageText)
        readingsCollectedText = reportScreenContainer.findViewById(R.id.readingsCollectedText)
        modelConfidenceText = reportScreenContainer.findViewById(R.id.confidenceText)
        predictionAccuracyText = reportScreenContainer.findViewById(R.id.predictionAccuracyText)
        historyHourlyChart = historyScreenContainer.findViewById(R.id.historyHourlyChart)
        historyDayRows = listOf(
            historyScreenContainer.findViewById<View>(R.id.historyDayRow0),
            historyScreenContainer.findViewById<View>(R.id.historyDayRow1),
            historyScreenContainer.findViewById<View>(R.id.historyDayRow2),
            historyScreenContainer.findViewById<View>(R.id.historyDayRow3),
            historyScreenContainer.findViewById<View>(R.id.historyDayRow4),
            historyScreenContainer.findViewById<View>(R.id.historyDayRow5),
            historyScreenContainer.findViewById<View>(R.id.historyDayRow6)
        ).map { row ->
            HistoryDayRowViews(
                row,
                row.findViewById(R.id.historyDayLabelText),
                row.findViewById(R.id.historyDayBarTrack),
                row.findViewById(R.id.historyDayBarFill),
                row.findViewById(R.id.historyDayDrainText)
            )
        }

        database = BatteryDatabase.getInstance(applicationContext)
        dao = database.batterySampleDao()
        repository = BatteryRepository(dao)
        sessionManager = SessionManager(repository) // Layer 2: Data preparation (fetches from Layer 1)
        runOneTimeDbNukeIfNeeded()
        runOneTimeHistoricalCleanupIfNeeded()

        sampler = BatterySampler(this, BatteryLoggingForegroundService::class.java.name)
        promptIgnoreBatteryOptimizationsIfNeeded()

        timeRemainingText.setOnClickListener {
            requestManualPredictionRefresh()
        }

        homeScreenContainer.findViewById<View>(R.id.commuteCheckButton).setOnClickListener {
            showScreen(Screen.COMMUTE)
        }

        commuteBackText.setOnClickListener {
            showScreen(Screen.HOME)
        }

        commuteSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val snapped = ((progress.coerceAtLeast(5) + 2) / 5) * 5
                if (snapped != progress) {
                    seekBar?.progress = snapped
                    return
                }
                commuteDurationText.text = "$snapped min"
                updateCommuteCard()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        homeTab.setOnClickListener { showScreen(Screen.HOME) }
        historyTab.setOnClickListener { showScreen(Screen.HISTORY) }
        reportTab.setOnClickListener { showScreen(Screen.REPORT) }
        showScreen(Screen.HOME)
        updateCommuteCard()
        refreshHomeTodayTiles()
        refreshHistoryCards()
        refreshReportCards()
        
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
                        val isDeviceIdle = getOptimizedIdleStatus(this@MainActivity) // Read the persisted screen state for the UI label.
                        batteryIcon.setLevel(pct)
                        batteryPercentText.text = "${pct.toInt()}%"
                        batteryPercentText.setTextColor(batteryColor(pct))
                        batteryStatusText.text = if (isDeviceIdle) "Idle Mode" else batteryStatusLabel(pct) // Show the idle label while the grace period or long idle is active.
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

    private fun showScreen(screen: Screen) {
        homeScreenContainer.visibility = if (screen == Screen.HOME) View.VISIBLE else View.GONE
        historyScreenContainer.visibility = if (screen == Screen.HISTORY) View.VISIBLE else View.GONE
        reportScreenContainer.visibility = if (screen == Screen.REPORT) View.VISIBLE else View.GONE
        commuteScreenContainer.visibility = if (screen == Screen.COMMUTE) View.VISIBLE else View.GONE
        screenTitleText.text = when (screen) {
            Screen.HOME -> getString(R.string.app_title)
            Screen.HISTORY -> getString(R.string.nav_history)
            Screen.REPORT -> getString(R.string.nav_report)
            Screen.COMMUTE -> getString(R.string.commute_title)
        }

        if (screen == Screen.COMMUTE) {
            updateCommuteCard()
        }
        if (screen == Screen.HISTORY) {
            refreshHistoryCards()
        }
        if (screen == Screen.REPORT) {
            refreshReportCards()
        }
        if (screen == Screen.HOME) {
            refreshHomeTodayTiles()
        }

        val activeColor = COLOR_AMBER
        val inactiveColor = android.graphics.Color.parseColor("#555555")
        fun setTabState(tab: View, icon: ImageView, label: TextView, active: Boolean) {
            val color = if (active) activeColor else inactiveColor
            icon.setColorFilter(color)
            label.setTextColor(color)
        }
        setTabState(homeTab, homeTabIcon, homeTabLabel, screen == Screen.HOME)
        setTabState(historyTab, historyTabIcon, historyTabLabel, screen == Screen.HISTORY)
        setTabState(reportTab, reportTabIcon, reportTabLabel, screen == Screen.REPORT)
    }

    private fun requestManualPredictionRefresh() {
        val batteryLevel = latestObservedBatteryLevel
        if (batteryLevel == null) {
            Toast.makeText(this, getString(R.string.prediction_refresh_no_data), Toast.LENGTH_SHORT).show()
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastManualRefreshTapTimeMs < MANUAL_REFRESH_THROTTLE_MS) {
                        batteryStatusText.text = getString(R.string.prediction_refresh_wait)
            return
        }

        if (predictionJob?.isActive == true) {
            batteryStatusText.text = getString(R.string.prediction_refresh_busy)
            return
        }

        lastManualRefreshTapTimeMs = now
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
                // Layer 1 (Storage) → Layer 2 (Data Prep) → Layer 3 (ML)
                // SessionManager handles all filtering, deduplication, idle-gap detection, and validation.
                val cleanSamples = withContext(Dispatchers.IO) {
                    sessionManager.prepareCleanSamplesForPrediction(currentSystemBatteryLevel)
                }

                val graphPoints = BatteryGraphSanitizer.buildDisplayPoints(cleanSamples)
                val totalRoomReadings = withContext(Dispatchers.IO) { dao.getSampleCount() }
                val cappedCount = cappedModelReadingsCount(totalRoomReadings)

                // ── NEW: Changed threshold from minSamplesToFit (6) to 10 for better stability ──
                // With 10+ samples, the OLS fit is more numerically stable and less prone to noise
                if (cleanSamples.size < 10) {
                    Log.d(LOG_TAG, "❌ Not enough samples: ${cleanSamples.size}/10. Showing 'Learning...'")
                    withContext(Dispatchers.Main) {
                        sampleCountText.text   = "${cleanSamples.size}/10 samples"
                        applyHomeConfidenceUi(cappedCount)
                        timeRemainingText.text = BatteryPredictionUiFormatter.remainingText(
                            sampleCount       = cleanSamples.size,
                            rawPredictedHours = null
                        )
                        batteryStatusText.text = "Need ${10 - cleanSamples.size} more reading(s)"
                        batteryGraph.setData(graphPoints, null)
                    }
                    return@launch
                }

                val nowEpochMillis = System.currentTimeMillis()
                val currentBatteryLevel = cleanSamples.last().batteryLevel
                val isDeviceIdle = getOptimizedIdleStatus(this@MainActivity) // Decide idle vs active from persisted timestamps, not from PowerManager.

                val shouldRunMath = predictionEngine.shouldRunPrediction(
                    currentTimeMs = nowEpochMillis,
                    lastRunTimeMs = lastPredictionRunTimeMs,
                    currentBatteryLevel = currentBatteryLevel,
                    lastBatteryLevel = lastPredictionBatteryLevel
                )

                var manualMathInvalid = false

                Log.d(LOG_TAG, "📊 Prediction check: samples=${cleanSamples.size}, shouldRun=$shouldRunMath, cached=${cachedSmoothedHours}")

                if (forceRecompute || shouldRunMath || cachedSmoothedHours == null) {
                    val result = predictionEngine.predictRemainingHours(
                        cleanSamples,
                        isDeviceIdle = isDeviceIdle
                    ) // Pass the persisted idle state into the cap selector.
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
                    sampleCountText.text = "${cleanSamples.size} samples"
                    applyHomeConfidenceUi(cappedCount)
                    batteryGraph.setData(graphPoints, tDeathEpochMillis)

                    timeRemainingText.text = BatteryPredictionUiFormatter.remainingText(
                        sampleCount       = cleanSamples.size,
                        rawPredictedHours = predictedHoursForUi
                    )

                    batteryStatusText.text = when {
                        tDeathEpochMillis != null -> "Dies at ${formatAbsoluteTime(tDeathEpochMillis)}"
                        source == RefreshSource.MANUAL && manualMathInvalid -> getString(R.string.prediction_still_calculating)
                        source == RefreshSource.MANUAL -> getString(R.string.prediction_refreshed)
                        else -> ""
                    }
                    updateCommuteCard()
                }
                refreshHomeTodayTiles()
                refreshHistoryCards()
                refreshReportCards()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Error updating prediction", e)
                withContext(Dispatchers.Main) {
                    timeRemainingText.text = BatteryPredictionUiFormatter.remainingText(
                        sampleCount = minSamplesToFit,
                        rawPredictedHours = cachedSmoothedHours
                    )
                    batteryStatusText.text = getString(R.string.prediction_still_calculating)
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

    private fun updateCommuteCard() {
        val snapshot = readBatterySnapshot(this)
        val batteryPercent = latestObservedBatteryLevel?.toInt() ?: snapshot.level
        val etaMinutes = cachedSmoothedHours
            ?.takeIf { it > 0.0 && it.isFinite() }
            ?.let { (it * 60.0).toInt().coerceAtLeast(1) }

        commuteBatteryNowValueText.text = if (batteryPercent in 0..100) "$batteryPercent%" else getString(R.string.value_unavailable)
        commuteEtaValueText.text = formatMinutesForCommute(etaMinutes)

        val tripMinutes = commuteSeekBar.progress.coerceAtLeast(5)
        commuteDurationText.text = "$tripMinutes min"
        updateCommuteResultCard(etaMinutes, tripMinutes)
    }

    private fun formatMinutesForCommute(minutes: Int?): String {
        if (minutes == null || minutes <= 0) return getString(R.string.value_unavailable)
        return if (minutes < 60) "$minutes min" else "${minutes / 60}h ${minutes % 60}m"
    }

    private fun updateCommuteResultCard(etaMinutes: Int?, tripMinutes: Int) {
        if (etaMinutes == null) {
            commuteResultCard.setBackgroundColor(android.graphics.Color.parseColor("#2e2e2e"))
            commuteResultTitleText.setTextColor(android.graphics.Color.parseColor("#888888"))
            commuteResultSubtitleText.setTextColor(android.graphics.Color.parseColor("#666666"))
            commuteResultTitleText.text = getString(R.string.prediction_still_calculating)
            commuteResultSubtitleText.text = getString(R.string.value_unavailable)
            return
        }

        val diff = etaMinutes - tripMinutes
        when {
            diff >= 10 -> {
                commuteResultCard.setBackgroundColor(android.graphics.Color.parseColor("#1a2e1a"))
                commuteResultTitleText.setTextColor(android.graphics.Color.parseColor("#7bc850"))
                commuteResultSubtitleText.setTextColor(android.graphics.Color.parseColor("#4a7a30"))
                commuteResultTitleText.text = "You'll make it"
                commuteResultSubtitleText.text = "$diff min to spare"
            }
            diff >= 0 -> {
                commuteResultCard.setBackgroundColor(android.graphics.Color.parseColor("#2e2a1a"))
                commuteResultTitleText.setTextColor(android.graphics.Color.parseColor("#f5a623"))
                commuteResultSubtitleText.setTextColor(android.graphics.Color.parseColor("#8a6a20"))
                commuteResultTitleText.text = "Cutting it close"
                commuteResultSubtitleText.text = "Only $diff min buffer"
            }
            else -> {
                commuteResultCard.setBackgroundColor(android.graphics.Color.parseColor("#2e1a1a"))
                commuteResultTitleText.setTextColor(android.graphics.Color.parseColor("#e05555"))
                commuteResultSubtitleText.setTextColor(android.graphics.Color.parseColor("#8a3535"))
                commuteResultTitleText.text = "Won't make it"
                commuteResultSubtitleText.text = "Charge for ~${-diff} min first"
            }
        }
    }

    private fun refreshHomeTodayTiles() {
        lifecycleScope.launch {
            val homeStats = withContext(Dispatchers.IO) {
                val todaySamples = dao.getSamplesSince(startOfDayMillis())
                buildHomeStats(todaySamples)
            }
            drainPerMinValue.text = homeStats.drainPerMinuteText
            screenOnValue.text = homeStats.screenOnText
            chargedValue.text = homeStats.chargedText
        }
    }

    private fun refreshHistoryCards() {
        historyHourlyChart.setLoading()
        lifecycleScope.launch {
            val historyData = withContext(Dispatchers.IO) {
                buildHistoryData()
            }

            historyDayRows.zip(historyData.dayRows).forEach { (views, row) ->
                views.dayLabel.text = row.dayLabel
                views.drainText.text = row.drainLabel
                val percentWidth = row.barFraction.coerceIn(0f, 1f)
                views.barTrack.post {
                    val target = (views.barTrack.width * percentWidth).roundToInt().coerceAtLeast(0)
                    views.barFill.layoutParams = views.barFill.layoutParams.apply {
                        width = target
                    }
                    views.barFill.requestLayout()
                }
            }

            historyHourlyChart.setIntensities(historyData.hourlyIntensities)
        }
    }

    private fun refreshReportCards() {
        lifecycleScope.launch {
            val reportData = withContext(Dispatchers.IO) {
                val allCount = dao.getSampleCount()
                val todaySamples = dao.getSamplesSince(startOfDayMillis())
                buildReportData(todaySamples = todaySamples, totalCount = allCount)
            }
            applyReportData(reportData)
        }
    }

    private fun startOfDayMillis(): Long {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    private fun buildHomeStats(todaySamples: List<BatterySample>): HomeStats {
        val drainRate = calculateAverageDrainPerMinute(todaySamples)
        val chargeCount = countChargingSessions(todaySamples)
        return HomeStats(
            drainPerMinuteText = drainRate?.let { String.format(Locale.getDefault(), "%.1f%%", it) }
                ?: getString(R.string.value_unavailable),
            screenOnText = getTodayScreenOnHoursText(),
            chargedText = "${chargeCount}x"
        )
    }

    private suspend fun buildHistoryData(): HistoryData {
        val now = System.currentTimeMillis()
        val sevenDaysStart = startOfDayMillis() - (6 * dayMillis)
        val samples = dao.getSamplesSince(sevenDaysStart)

        val calendar = Calendar.getInstance()
        val dayKeys = (0..6).map { offset ->
            calendar.timeInMillis = startOfDayMillis() - (offset * dayMillis)
            dayKey(calendar)
        }

        val groupedByDay = samples.groupBy {
            calendar.timeInMillis = it.timestampEpochMillis
            dayKey(calendar)
        }

        val dayRows = dayKeys.map { key ->
            val daySamples = groupedByDay[key].orEmpty()
            val maxLevel = daySamples.maxOfOrNull { it.batteryLevel }
            val minLevel = daySamples.minOfOrNull { it.batteryLevel }
            val drain = if (maxLevel != null && minLevel != null) (maxLevel - minLevel).coerceAtLeast(0f) else null
            HistoryDayRowData(
                dayLabel = key.dayLabel,
                drainPercent = drain
            )
        }

        val maxDrain = dayRows.maxOfOrNull { it.drainPercent ?: 0f }?.takeIf { it > 0f } ?: 0f
        val normalizedRows = dayRows.map { row ->
            val fraction = if (row.drainPercent != null && maxDrain > 0f) row.drainPercent / maxDrain else 0f
            row.copy(
                drainLabel = row.drainPercent?.let { "${it.roundToInt()}%" } ?: getString(R.string.value_unavailable),
                barFraction = fraction
            )
        }

        val hourlyRates = DoubleArray(24)
        val hourlyCounts = IntArray(24)
        val sorted = samples.sortedBy { it.timestampEpochMillis }
        for (i in 1 until sorted.size) {
            val prev = sorted[i - 1]
            val cur = sorted[i]
            val deltaMs = (cur.timestampEpochMillis - prev.timestampEpochMillis).coerceAtLeast(0L)
            if (deltaMs == 0L || cur.isCharging || prev.isCharging || cur.timestampEpochMillis > now) continue
            val drop = (prev.batteryLevel - cur.batteryLevel).coerceAtLeast(0f)
            if (drop <= 0f) continue
            val deltaMinutes = deltaMs / 60_000.0
            if (deltaMinutes <= 0.0) continue
            calendar.timeInMillis = cur.timestampEpochMillis
            val hour = calendar.get(Calendar.HOUR_OF_DAY)
            hourlyRates[hour] += drop / deltaMinutes
            hourlyCounts[hour] += 1
        }

        val hourlyAverages = List(24) { hour ->
            if (hourlyCounts[hour] > 0) (hourlyRates[hour] / hourlyCounts[hour]).toFloat() else 0f
        }
        val maxHourly = hourlyAverages.maxOrNull()?.takeIf { it > 0f } ?: 0f
        val hourlyIntensities = if (maxHourly > 0f) {
            hourlyAverages.map { (it / maxHourly).coerceIn(0f, 1f) }
        } else {
            List(24) { 0f }
        }

        return HistoryData(
            dayRows = normalizedRows,
            hourlyIntensities = hourlyIntensities
        )
    }

    private fun buildReportData(todaySamples: List<BatterySample>, totalCount: Int): ReportData {
        val trainingCount = cappedModelReadingsCount(totalCount)
        if (todaySamples.isEmpty()) {
            return ReportData(
                totalDrain = getString(R.string.value_unavailable),
                sinceLastCharge = getString(R.string.value_unavailable),
                avgDrainRate = getString(R.string.value_unavailable),
                screenOnTime = getString(R.string.value_unavailable),
                timesCharged = "0x",
                longestCharge = getString(R.string.value_unavailable),
                heaviestUsage = getString(R.string.value_unavailable),
                readingsCollected = getString(R.string.report_readings_collected_value, trainingCount),
                confidence = confidenceLabel(trainingCount),
                confidenceColor = confidenceColorForCount(trainingCount),
                predictionAccuracy = "± ~5 min"
            )
        }

        val first = todaySamples.first()
        val last = todaySamples.last()
        val elapsedMinutes = ((last.timestampEpochMillis - first.timestampEpochMillis) / 60_000.0).coerceAtLeast(0.0)
        val totalDrainPct = (first.batteryLevel - last.batteryLevel).coerceAtLeast(0f)

        val avgDrainRate = calculateAverageDrainPerMinute(todaySamples)
            ?.let { getString(R.string.report_avg_drain_rate_value, it) }
            ?: getString(R.string.value_unavailable)

        var timesCharged = 0
        var longestChargeMinutes = 0L
        var currentChargeStart: Long? = null
        val hourlyDrop = DoubleArray(24)

        for (i in 1 until todaySamples.size) {
            val prev = todaySamples[i - 1]
            val cur = todaySamples[i]
            val deltaMs = (cur.timestampEpochMillis - prev.timestampEpochMillis).coerceAtLeast(0L)
            if (deltaMs <= idleGapForUsageBucketMs) {
                if (!prev.isCharging && !cur.isCharging && cur.batteryLevel < prev.batteryLevel) {
                    val hour = Calendar.getInstance().apply { timeInMillis = cur.timestampEpochMillis }.get(Calendar.HOUR_OF_DAY)
                    hourlyDrop[hour] += (prev.batteryLevel - cur.batteryLevel).toDouble()
                }
            }

            if (!prev.isCharging && cur.isCharging) {
                timesCharged += 1
                currentChargeStart = cur.timestampEpochMillis
            } else if (prev.isCharging && !cur.isCharging) {
                val start = currentChargeStart
                if (start != null) {
                    val durationMinutes = ((cur.timestampEpochMillis - start) / 60_000L).coerceAtLeast(0L)
                    longestChargeMinutes = maxOf(longestChargeMinutes, durationMinutes)
                }
                currentChargeStart = null
            }
        }

        if (todaySamples.last().isCharging && currentChargeStart != null) {
            val durationMinutes = ((System.currentTimeMillis() - currentChargeStart) / 60_000L).coerceAtLeast(0L)
            longestChargeMinutes = maxOf(longestChargeMinutes, durationMinutes)
        }

        val topHour = hourlyDrop.indices.maxByOrNull { hourlyDrop[it] }
        val heaviestUsage = if (topHour == null || hourlyDrop[topHour] <= 0.0) {
            getString(R.string.value_unavailable)
        } else {
            formatHourRange(topHour)
        }

        return ReportData(
            totalDrain = "${totalDrainPct.toInt()}%",
            sinceLastCharge = getString(
                R.string.report_subtitle_format,
                (elapsedMinutes / 60.0).toFloat()
            ),
            avgDrainRate = avgDrainRate,
            screenOnTime = getTodayScreenOnHoursText(),
            timesCharged = getString(R.string.report_times_charged_value, timesCharged),
            longestCharge = if (longestChargeMinutes > 0L) getString(R.string.report_longest_charge_value, longestChargeMinutes.toInt()) else getString(R.string.value_unavailable),
            heaviestUsage = heaviestUsage,
            readingsCollected = getString(R.string.report_readings_collected_value, trainingCount),
            confidence = confidenceLabel(trainingCount),
            confidenceColor = confidenceColorForCount(trainingCount),
            predictionAccuracy = "± ~5 min"
        )
    }

    private fun applyReportData(data: ReportData) {
        totalDrainText.text = data.totalDrain
        sinceLastChargeText.text = data.sinceLastCharge
        avgDrainRateText.text = data.avgDrainRate
        screenOnTimeText.text = data.screenOnTime
        timesChargedText.text = data.timesCharged
        longestChargeText.text = data.longestCharge
        heaviestUsageText.text = data.heaviestUsage
        readingsCollectedText.text = data.readingsCollected
        modelConfidenceText.text = data.confidence
        modelConfidenceText.setTextColor(data.confidenceColor)
        predictionAccuracyText.text = data.predictionAccuracy
    }

    private fun calculateAverageDrainPerMinute(samples: List<BatterySample>): Double? {
        var totalDrop = 0.0
        var totalMinutes = 0.0
        for (i in 1 until samples.size) {
            val prev = samples[i - 1]
            val cur = samples[i]
            if (prev.isCharging || cur.isCharging) continue
            val deltaMs = (cur.timestampEpochMillis - prev.timestampEpochMillis).coerceAtLeast(0L)
            if (deltaMs <= 0L || deltaMs > idleGapForUsageBucketMs) continue
            val drop = (prev.batteryLevel - cur.batteryLevel).coerceAtLeast(0f)
            if (drop <= 0f) continue
            totalDrop += drop
            totalMinutes += deltaMs / 60_000.0
        }
        if (totalDrop <= 0.0 || totalMinutes <= 0.0) return null
        return totalDrop / totalMinutes
    }

    private fun countChargingSessions(samples: List<BatterySample>): Int {
        var count = 0
        for (i in 1 until samples.size) {
            if (!samples[i - 1].isCharging && samples[i].isCharging) {
                count += 1
            }
        }
        return count
    }

    private fun getTodayScreenOnHoursText(): String {
        if (!sampler.hasUsageAccess()) return getString(R.string.value_unavailable)
        val usageManager = getSystemService(USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return getString(R.string.value_unavailable)
        val stats = usageManager.queryAndAggregateUsageStats(startOfDayMillis(), System.currentTimeMillis())
        val totalMs = stats.values.sumOf { it.totalTimeInForeground }
        if (totalMs <= 0L) return getString(R.string.value_unavailable)
        val hours = totalMs / 3_600_000.0
        return getString(R.string.report_screen_on_hours_value, hours)
    }

    private fun dayKey(calendar: Calendar): DayKey {
        val year = calendar.get(Calendar.YEAR)
        val dayOfYear = calendar.get(Calendar.DAY_OF_YEAR)
        val dayLabel = SimpleDateFormat("EEE", Locale.getDefault()).format(calendar.time)
        return DayKey(year, dayOfYear, dayLabel)
    }

    private fun formatHourRange(startHour24: Int): String {
        val endHour24 = (startHour24 + 1) % 24
        return "${formatHour(startHour24)}-${formatHour(endHour24)}"
    }

    private fun formatHour(hour24: Int): String {
        val amPm = if (hour24 < 12) "AM" else "PM"
        val display = when (val h = hour24 % 12) {
            0 -> 12
            else -> h
        }
        return "$display $amPm"
    }

    private fun confidenceLabel(sampleCount: Int): String = when {
        sampleCount < 4 -> "Low"
        sampleCount < 7 -> "Medium"
        else -> "High"
    }

    private fun confidenceColorForCount(sampleCount: Int): Int = when {
        sampleCount < 4 -> android.graphics.Color.parseColor("#e05555")
        sampleCount < 7 -> android.graphics.Color.parseColor("#f5a623")
        else -> android.graphics.Color.parseColor("#7bc850")
    }

    private fun cappedModelReadingsCount(totalRoomReadings: Int): Int = minOf(totalRoomReadings, 10)

    private fun applyHomeConfidenceUi(cappedCount: Int) {
        homeConfidenceText.text = "Model confidence: $cappedCount of 10 readings"
        val color = confidenceColorForCount(cappedCount)
        val background = homeConfidenceDot.background
        if (background is GradientDrawable) {
            background.setColor(color)
        } else {
            homeConfidenceDot.setBackgroundColor(color)
        }
    }

    private data class HomeStats(
        val drainPerMinuteText: String,
        val screenOnText: String,
        val chargedText: String
    )

    private data class DayKey(
        val year: Int,
        val dayOfYear: Int,
        val dayLabel: String
    )

    private data class HistoryDayRowData(
        val dayLabel: String,
        val drainPercent: Float?,
        val drainLabel: String = "—",
        val barFraction: Float = 0f
    )

    private data class HistoryData(
        val dayRows: List<HistoryDayRowData>,
        val hourlyIntensities: List<Float>
    )

    private data class HistoryDayRowViews(
        val root: View,
        val dayLabel: TextView,
        val barTrack: View,
        val barFill: View,
        val drainText: TextView
    )

    private data class ReportData(
        val totalDrain: String,
        val sinceLastCharge: String,
        val avgDrainRate: String,
        val screenOnTime: String,
        val timesCharged: String,
        val longestCharge: String,
        val heaviestUsage: String,
        val readingsCollected: String,
        val confidence: String,
        val confidenceColor: Int,
        val predictionAccuracy: String
    )


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
            repository.cleanupHistoricalData()
            Log.i(
                LOG_TAG,
                "Historical cleanup completed: deleted rows older than 30 days"
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
