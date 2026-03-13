# Battery Prediction - Implementation Examples

This document provides practical code examples for common scenarios and integration points.

## 1. Basic Sample Insertion and Pruning

### Scenario: Insert a battery sample and maintain 7-day history

```kotlin
// In MainActivity
private val sevenDaysMillis = TimeUnit.DAYS.toMillis(7)

private suspend fun insertAndPruneSample(sample: BatterySample) {
    withContext(Dispatchers.IO) {
        // Insert sample with auto-generated ID
        val id = dao.insertSample(sample)
        Log.d(LOG_TAG, "Sample inserted with id=$id")
        
        // Prune samples older than 7 days
        val cutoffEpochMillis = System.currentTimeMillis() - sevenDaysMillis
        val deletedCount = dao.deleteOlderThan(cutoffEpochMillis)
        
        if (deletedCount > 0) {
            Log.d(LOG_TAG, "Pruned $deletedCount old samples")
        }
    }
}

// Usage from lifecycleScope
lifecycleScope.launch {
    val sample = sampler.sample()
    if (sample != null) {
        insertAndPruneSample(sample)
    }
}
```

## 2. Fetching 7-Day History for Training

### Scenario: Load all samples from the past 7 days

```kotlin
private suspend fun fetch7DayHistory(): List<BatterySample> {
    return withContext(Dispatchers.IO) {
        val sevenDaysAgoMs = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)
        dao.getSamplesSince(sevenDaysAgoMs)
    }
}

// Usage
lifecycleScope.launch {
    val history = fetch7DayHistory()
    Log.d(LOG_TAG, "Loaded ${history.size} samples from last 7 days")
    
    if (history.size >= 6) {
        trainModel(history)
    } else {
        Log.d(LOG_TAG, "Not enough samples yet: ${history.size}/6")
    }
}
```

## 3. OLS Model Training with Feature Selection

### Scenario: Train model with configurable features

```kotlin
private suspend fun trainModelOnHistory(
    samples: List<BatterySample>,
    featureMask: Int = 0b111  // time + voltage + services
): Boolean {
    return withContext(Dispatchers.Default) {
        if (samples.size < 6) {
            Log.w(LOG_TAG, "Insufficient samples for training: ${samples.size}")
            return@withContext false
        }
        
        val anchorTimeMs = samples.first().timestampEpochMillis
        val xRows = mutableListOf<DoubleArray>()
        val yValues = mutableListOf<Double>()
        
        for (sample in samples) {
            val features = buildFeatureVector(sample, anchorTimeMs, featureMask)
            xRows.add(features)
            yValues.add(sample.batteryLevel.toDouble())
        }
        
        val fitted = regression.fit(xRows.toTypedArray(), yValues.toDoubleArray())
        
        if (fitted) {
            val slope = regression.slopeForFeature(0)
            Log.d(LOG_TAG, "Model fitted. Slope (pp/min): $slope")
        }
        
        fitted
    }
}

private fun buildFeatureVector(
    sample: BatterySample,
    anchorTimeMs: Long,
    featureMask: Int
): DoubleArray {
    val features = mutableListOf<Double>()
    
    // Feature 0: Time (always enabled)
    val minutesSinceStart = (sample.timestampEpochMillis - anchorTimeMs) / 60000.0
    features.add(minutesSinceStart)
    
    // Feature 1: Voltage (if enabled)
    if ((featureMask and 0b010) != 0) {
        features.add(sample.voltage.toDouble())
    }
    
    // Feature 2: Services active (if enabled)
    if ((featureMask and 0b100) != 0) {
        features.add(if (sample.servicesActive) 1.0 else 0.0)
    }
    
    return features.toDoubleArray()
}
```

## 4. TOD Calculation with Full Validation

### Scenario: Compute Time of Death safely

```kotlin
private suspend fun calculateTOD(
    samples: List<BatterySample>
): Pair<String?, Long?> {
    return withContext(Dispatchers.Default) {
        if (samples.size < 6) {
            return@withContext Pair(null, null)
        }
        
        val nowEpochMillis = System.currentTimeMillis()
        val anchorTimeMs = samples.first().timestampEpochMillis
        
        // Prepare data
        val xRows = samples.map { sample ->
            val minutesSinceStart = (sample.timestampEpochMillis - anchorTimeMs) / 60000.0
            doubleArrayOf(minutesSinceStart)
        }.toTypedArray()
        
        val yValues = samples.map { it.batteryLevel.toDouble() }.toDoubleArray()
        
        // Train model
        if (!regression.fit(xRows, yValues)) {
            Log.w(LOG_TAG, "Failed to fit model")
            return@withContext Pair(null, null)
        }
        
        // Get slope
        val slope = regression.slopeForFeature(0) ?: run {
            Log.w(LOG_TAG, "Could not compute slope")
            return@withContext Pair(null, null)
        }
        
        Log.d(LOG_TAG, "Slope: $slope pp/min")
        
        // Check if battery is draining
        if (slope >= 0.0) {
            Log.d(LOG_TAG, "Slope non-negative; battery not draining (or sensor noise)")
            return@withContext Pair(null, null)
        }
        
        // Get current battery
        val currentBatteryPercent = samples.last().batteryLevel.toDouble()
        Log.d(LOG_TAG, "Current battery: $currentBatteryPercent%")
        
        // Validate current battery
        if (currentBatteryPercent <= 0.0) {
            Log.w(LOG_TAG, "Current battery <= 0%; device likely dead")
            return@withContext Pair(null, null)
        }
        
        // Compute TOD
        val minutesToEmpty = currentBatteryPercent / -slope
        val millisToEmpty = (minutesToEmpty * 60000.0).toLong()
        val tDeathEpochMillis = nowEpochMillis + millisToEmpty
        
        Log.d(LOG_TAG, "Minutes to empty: $minutesToEmpty")
        
        // Validate TOD is in future
        if (tDeathEpochMillis <= nowEpochMillis) {
            Log.w(LOG_TAG, "TOD is in past (data anomaly)")
            return@withContext Pair(null, null)
        }
        
        // Validate TOD is reasonable (within 30 days)
        val maxReasonableFutureMs = nowEpochMillis + TimeUnit.DAYS.toMillis(30)
        if (tDeathEpochMillis > maxReasonableFutureMs) {
            Log.w(LOG_TAG, "TOD > 30 days in future (unreliable)")
            return@withContext Pair(null, null)
        }
        
        // Format time
        val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
        val formattedTime = formatter.format(Date(tDeathEpochMillis))
        
        Log.d(LOG_TAG, "TOD: $formattedTime")
        
        return@withContext Pair(formattedTime, tDeathEpochMillis)
    }
}

// Usage
lifecycleScope.launch {
    val history = fetch7DayHistory()
    val (timeStr, epochMs) = calculateTOD(history)
    
    withContext(Dispatchers.Main) {
        if (timeStr != null) {
            predictionText.text = "Your phone will die at: $timeStr"
        } else {
            predictionText.text = "Not enough data for prediction"
        }
    }
}
```

## 5. Monitoring Database Size

### Scenario: Check and log database statistics

```kotlin
private suspend fun logDatabaseStats() {
    withContext(Dispatchers.IO) {
        val count = dao.getSampleCount()
        Log.d(LOG_TAG, "Database contains $count samples")
        
        // Estimate size (each sample ≈ 40-50 bytes)
        val estimatedSizeKb = count * 50 / 1024
        Log.d(LOG_TAG, "Estimated database size: ~${estimatedSizeKb} KB")
        
        // Fetch oldest and newest samples
        val allSamples = dao.getSamplesSince(0L)
        if (allSamples.isNotEmpty()) {
            val oldest = allSamples.first()
            val newest = allSamples.last()
            
            val ageHours = (System.currentTimeMillis() - oldest.timestampEpochMillis) / 3600000.0
            Log.d(LOG_TAG, "Oldest sample: ${ageHours.toInt()} hours ago")
            Log.d(LOG_TAG, "Battery level range: ${oldest.batteryLevel}% to ${newest.batteryLevel}%")
        }
    }
}

// Call periodically or on demand
lifecycleScope.launch {
    logDatabaseStats()
}
```

## 6. Feature Scaling and Normalization Example

### Scenario: Understand how OLS handles features

```kotlin
// Example: 5 samples over 5 minutes with voltage and service data

val samples = listOf(
    BatterySample(
        timestampEpochMillis = 1000L,
        batteryLevel = 100.0f,
        voltage = 4200,           // 4.2V in mV
        servicesActive = false,
        foreground = true
    ),
    BatterySample(
        timestampEpochMillis = 61000L,  // 1 minute later
        batteryLevel = 99.0f,
        voltage = 4100,
        servicesActive = true,
        foreground = true
    ),
    // ... more samples
)

val anchorTime = samples.first().timestampEpochMillis

// Build features: [time_minutes, voltage_mv, services_bool]
val xRows = samples.map { sample ->
    doubleArrayOf(
        (sample.timestampEpochMillis - anchorTime) / 60000.0,  // Time in minutes
        sample.voltage.toDouble(),                              // Voltage in mV
        if (sample.servicesActive) 1.0 else 0.0               // Binary service flag
    )
}.toTypedArray()

val yValues = samples.map { it.batteryLevel.toDouble() }.toDoubleArray()

// OLS internally:
// 1. Computes means: mean_time ≈ 2.0, mean_voltage ≈ 4150, mean_services ≈ 0.6
// 2. Computes standard deviations (scales)
// 3. Normalizes each feature to zero mean, unit variance
// 4. Fits linear model: y_norm = intercept + coef_1 * time_norm + coef_2 * voltage_norm + ...
// 5. Returns slopes accounting for original scales

// Example slopes:
val slopeTime = regression.slopeForFeature(0)      // -1.0 pp/min (battery drops 1% per minute)
val slopeVoltage = regression.slopeForFeature(1)   // -0.05 pp/mV (battery drops 0.05% per mV)
val slopeServices = regression.slopeForFeature(2)  // -2.0 pp (services active drops battery 2%)

// Usage: predict battery at time 10 minutes with voltage 4050mV, services active
val prediction = regression.predict(doubleArrayOf(10.0, 4050.0, 1.0))
// Output: ≈ 95.5% (based on coefficients from training)
```

## 7. Handling Data Gaps (e.g., Device Off)

### Scenario: Handle interruptions in sampling

```kotlin
private suspend fun getSamplesSinceLastSampling(): List<BatterySample> {
    return withContext(Dispatchers.IO) {
        // Get samples since device was last sampled
        val lastSampleTime = lastSampleEpochMs ?: System.currentTimeMillis()
        val samples = dao.getSamplesSince(lastSampleTime - TimeUnit.HOURS.toMillis(1))
        
        if (samples.isEmpty()) {
            Log.d(LOG_TAG, "No new samples since last check")
        } else {
            Log.d(LOG_TAG, "Found ${samples.size} new samples")
        }
        
        return@withContext samples
    }
}

// Detect sampling gaps
private suspend fun detectSamplingGaps(): Boolean {
    val history = fetch7DayHistory()
    if (history.size < 2) return false
    
    var maxGapMs = 0L
    for (i in 1 until history.size) {
        val gapMs = history[i].timestampEpochMillis - history[i-1].timestampEpochMillis
        if (gapMs > maxGapMs) maxGapMs = gapMs
    }
    
    val maxGapMinutes = maxGapMs / 60000
    Log.d(LOG_TAG, "Max sampling gap: ${maxGapMinutes} minutes")
    
    // Flag if gap > expected interval (e.g., 5 minutes expected, gap > 30 minutes)
    return maxGapMs > TimeUnit.MINUTES.toMillis(30)
}
```

## 8. Testing TOD Calculation Manually

### Scenario: Unit test without database

```kotlin
// In BatteryPredictionTest.kt
@Test
fun testTodWithRealWorldData() {
    // Simulate 7 hours of battery discharge: 100% → 30%
    val xRows = arrayOf(
        doubleArrayOf(0.0),
        doubleArrayOf(1.0),
        doubleArrayOf(2.0),
        doubleArrayOf(4.0),
        doubleArrayOf(6.0),
        doubleArrayOf(7.0)
    )
    val yValues = doubleArrayOf(100.0, 85.0, 70.0, 50.0, 30.0, 15.0)
    
    val regression = OlsRegression()
    assertTrue(regression.fit(xRows, yValues))
    
    // Slope should be roughly -12 pp/hour = -0.2 pp/min
    val slope = regression.slopeForFeature(0)
    assertNotNull(slope)
    assertTrue(slope!! < 0.0)
    
    // At 15% battery with slope -0.2 pp/min:
    // t = 15 / 0.2 = 75 minutes
    val currentBattery = 15.0
    val minutesToEmpty = currentBattery / -slope
    assertTrue(minutesToEmpty > 70 && minutesToEmpty < 80)
}
```

## 9. Downsampling for High-Frequency Data

### Scenario: Reduce sample count before fitting

```kotlin
private fun downsampleByHour(
    samples: List<BatterySample>
): List<BatterySample> {
    val hourGrouped = samples.groupBy { sample ->
        TimeUnit.MILLISECONDS.toHours(sample.timestampEpochMillis) / 60  // Group by hour
    }
    
    return hourGrouped.map { (_, groupedSamples) ->
        // Average battery and voltage within each hour
        val avgBattery = groupedSamples.map { it.batteryLevel }.average().toFloat()
        val avgVoltage = groupedSamples.map { it.voltage }.average().toInt()
        val servicesActive = groupedSamples.count { it.servicesActive } > groupedSamples.size / 2
        
        // Use midpoint timestamp
        val midpointIndex = groupedSamples.size / 2
        val midpointTime = groupedSamples[midpointIndex].timestampEpochMillis
        
        BatterySample(
            timestampEpochMillis = midpointTime,
            batteryLevel = avgBattery,
            voltage = avgVoltage,
            servicesActive = servicesActive,
            foreground = false  // Or majority vote
        )
    }.sortedBy { it.timestampEpochMillis }
}

// Usage before training
val history = fetch7DayHistory()
val downsampled = if (history.size > 2000) {
    Log.d(LOG_TAG, "Downsampling ${history.size} samples to hourly")
    downsampleByHour(history)
} else {
    history
}

trainModelOnHistory(downsampled)
```

## 10. Real-Time UI Updates with Flow

### Scenario: Observe database changes and update UI

```kotlin
// In MainActivity
private fun observeLatestSamples() {
    val sevenDaysAgoMs = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)
    
    dao.samplesSinceFlow(sevenDaysAgoMs)
        .flowOn(Dispatchers.IO)  // Observe on IO thread
        .map { samples ->
            // Compute TOD on Default dispatcher
            withContext(Dispatchers.Default) {
                calculateTOD(samples)
            }
        }
        .flowOn(Dispatchers.Default)
        .collect { (timeStr, epochMs) ->
            // Update UI on Main thread
            withContext(Dispatchers.Main) {
                if (timeStr != null) {
                    predictionText.text = "TOD: $timeStr"
                }
            }
        }
    
    // Tie to lifecycle
    lifecycleScope.launch {
        repeatOnLifecycle(Lifecycle.State.STARTED) {
            observeLatestSamples()
        }
    }
}
```

---

These examples demonstrate the key patterns for integrating Room database, training OLS models, calculating TOD safely, and handling edge cases in a real-world battery prediction app.
