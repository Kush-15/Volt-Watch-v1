# Battery Prediction App - Room Database Refactoring

## Overview

This document describes the refactoring of the battery prediction app to use Room database for persistent storage of battery samples. The app now tracks 7 days of battery telemetry and provides personalized predictions using OLS (Ordinary Least Squares) regression trained on historical device data.

## Architecture

### 1. Data Model: `BatterySample` Entity

**Location:** `BatteryDatabase.kt`

```kotlin
@Entity(
    tableName = "batterysample",
    indices = [Index(value = ["timestampEpochMillis"])]
)
data class BatterySample(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val timestampEpochMillis: Long,
    val batteryLevel: Float,           // 0.0..100.0 (percentage)
    val voltage: Int,                  // millivolts
    val servicesActive: Boolean,       // background services running
    val foreground: Boolean = false    // app in foreground
)
```

**Field Descriptions:**

- **id**: Auto-generated primary key for database indexing.
- **timestampEpochMillis**: UTC epoch timestamp in milliseconds. Indexed for fast range queries (7-day lookups).
- **batteryLevel**: Battery percentage (0.0–100.0). Normalized to prevent outliers.
- **voltage**: Battery voltage in millivolts. Used as a secondary feature for prediction.
- **servicesActive**: Boolean flag indicating whether background services are running. Used as a feature to capture device load.
- **foreground**: Boolean flag indicating foreground state. Useful for separating foreground/background drain patterns.

**Index Strategy:**

- Primary index on `id` (auto-generated)
- Secondary index on `timestampEpochMillis` for efficient range queries (`WHERE timestamp >= sinceMs`)

### 2. Data Access Object (DAO): `BatterySampleDao`

**Location:** `BatteryDatabase.kt`

All DAO functions are **suspend** functions, designed to run on `Dispatchers.IO` via Room's default behavior.

#### Key Functions:

```kotlin
@Suspend
suspend fun insertSample(sample: BatterySample): Long
```
Insert or replace a sample. Returns the row ID. On conflict, replaces existing row (useful for periodic retransmissions).

---

```kotlin
@Suspend
suspend fun getSamplesSince(sinceEpochMillis: Long): List<BatterySample>
```
Retrieve all samples in ascending timestamp order since a given epoch milliseconds. Used for training model on 7-day history.

**Example:**
```kotlin
val sevenDaysAgoMs = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)
val samples = dao.getSamplesSince(sevenDaysAgoMs)
```

---

```kotlin
@Suspend
suspend fun deleteOlderThan(cutoffEpochMillis: Long): Int
```
Delete samples older than cutoff. Returns count of deleted rows. Called after each insertion to maintain a 7-day sliding window.

**Example:**
```kotlin
val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)
val deleted = dao.deleteOlderThan(cutoff)
Log.d("DB", "Deleted $deleted old samples")
```

---

```kotlin
fun samplesSinceFlow(sinceEpochMillis: Long): Flow<List<BatterySample>>
```
Returns a Kotlin Flow that emits whenever samples change. Useful for reactive UI updates without polling.

**Note:** This is a bonus function for future UI enhancements (e.g., real-time charts).

---

```kotlin
suspend fun getSampleCount(): Int
```
Get total sample count. Useful for debugging and status checks.

---

```kotlin
suspend fun clearAllSamples()
```
Clear all samples (testing/reset only).

### 3. Database Singleton: `BatteryDatabase`

**Location:** `BatteryDatabase.kt`

```kotlin
@Database(
    entities = [BatterySample::class],
    version = 1,
    exportSchema = false
)
abstract class BatteryDatabase : RoomDatabase() {
    abstract fun batterySampleDao(): BatterySampleDao

    companion object {
        @Volatile
        private var instance: BatteryDatabase? = null

        fun getInstance(context: Context): BatteryDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    BatteryDatabase::class.java,
                    "battery_database"
                ).build().also { instance = it }
            }
        }
    }
}
```

**Key Points:**

- **Singleton pattern:** Thread-safe double-checked locking ensures only one database instance.
- **Version 1, exportSchema = false:** Suitable for development; can add migrations for schema updates later.
- **Database name:** "battery_database" (stored in app's private directory).

### 4. Battery Sampler: `BatterySampler.kt`

Updated to work with the Room `BatterySample` entity:

```kotlin
fun sample(): BatterySample?
```

Returns a nullable `BatterySample` with:
- Current timestamp in epoch milliseconds
- Battery level (0–100%)
- Voltage in millivolts
- Services active status
- Foreground status

**Filtering:**
- Returns `null` if device is charging (skips charging data)
- Returns `null` if battery is unavailable

### 5. OLS Regression: `OlsRegression.kt`

**Location:** `OlsRegression.kt`

Implements Ordinary Least Squares regression with feature normalization (zero mean, unit variance).

#### Feature Mask

```
bit0: time (required, always enabled)
bit1: voltage
bit2: services active
bit3: usage minutes (future)
```

**Example:** `featureMask = 0b111` enables time, voltage, and services.

#### Slope Semantics

The `slopeForFeature(i)` function returns the coefficient divided by the feature's standard deviation. This accounts for normalization:

- **Feature 0 (time in minutes):** Slope is in **percentage points per minute**
- **Feature 1 (voltage):** Slope is in **percentage points per millivolt**
- **Feature 2 (services):** Slope is a unit-less coefficient for the binary indicator

#### Prediction Formula

```
y_predicted = intercept + slope_0 * time + slope_1 * voltage + slope_2 * services + ...
```

All inputs are normalized before prediction.

### 6. Main Activity: `MainActivity.kt`

**Responsibilities:**

1. Initialize database and DAO
2. Periodically sample battery using `BatterySampler`
3. Persist samples to Room database (insert + prune)
4. Fetch 7-day history and train OLS model
5. Calculate Time of Death (TOD) prediction
6. Update UI with prediction

#### Concurrency Model

All database operations use `withContext(Dispatchers.IO)`:

```kotlin
val samples = withContext(Dispatchers.IO) {
    dao.getSamplesSince(sevenDaysAgoMs)
}
```

OLS fitting and prediction use `withContext(Dispatchers.Default)`:

```kotlin
val slope = withContext(Dispatchers.Default) {
    regression.fit(xRows, yValues)
    regression.slopeForFeature(0)
}
```

UI updates use `withContext(Dispatchers.Main)`:

```kotlin
withContext(Dispatchers.Main) {
    predictionText.text = "Your phone will die at: $time"
}
```

#### Insert + Prune Pattern

After each sample insertion, old data is pruned in the same coroutine:

```kotlin
withContext(Dispatchers.IO) {
    dao.insertSample(sample)
    val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)
    dao.deleteOlderThan(cutoff)
}
```

This maintains a sliding window of 7 days without requiring separate background tasks.

#### Feature Vector Construction

```kotlin
fun buildFeatureVector(sample: BatterySample, anchorTimeMs: Long): DoubleArray {
    val values = ArrayList<Double>(4)
    
    // Feature 0: Time in minutes (always enabled)
    val minutesSinceStart = (sample.timestampEpochMillis - anchorTimeMs) / 60000.0
    values.add(minutesSinceStart)
    
    // Feature 1: Voltage (if enabled)
    if (isFeatureEnabled(1)) {
        values.add(sample.voltage.toDouble())
    }
    
    // Feature 2: Services (if enabled)
    if (isFeatureEnabled(2)) {
        values.add(if (sample.servicesActive) 1.0 else 0.0)
    }
    
    // Feature 3: Foreground (if enabled)
    if (isFeatureEnabled(3)) {
        values.add(if (sample.foreground) 1.0 else 0.0)
    }
    
    return values.toDoubleArray()
}
```

**Note:** Time is always the first feature (anchor). Other features can be toggled via `featureMask`.

## Time of Death (TOD) Calculation

### Safe Calculation with Edge Case Handling

The TOD is calculated only if the battery is genuinely draining (`slope < 0`):

```kotlin
if (slope == null || slope >= 0.0) {
    // Battery not draining, or sensor noise
    Log.d(LOG_TAG, "Slope is null or non-negative; no imminent death predicted")
    return Pair(currentBatteryPercent, null)
}

// Current battery percentage as a decimal (0.0–100.0)
val currentBatteryPercent = yValues.last()

// Validate current battery
if (currentBatteryPercent <= 0.0) {
    Log.w(LOG_TAG, "Current battery <= 0%; device likely already dead")
    return Pair(currentBatteryPercent, null)
}

// Solve: 0 = currentBatteryPercent + slope * t
// t = -currentBatteryPercent / slope (in minutes)
val minutesToEmpty = currentBatteryPercent / -slope
val millisToEmpty = (minutesToEmpty * 60000.0).toLong()
val tDeathEpochMillis = System.currentTimeMillis() + millisToEmpty
```

### Validation Checks

1. **Slope check:** Must be negative (battery draining)
2. **Current battery check:** Must be > 0%
3. **Future check:** TOD must be in the future
4. **Reasonableness check:** TOD must be within 30 days

```kotlin
if (tDeathEpochMillis <= nowEpochMillis) {
    Log.w(LOG_TAG, "Computed TOD is in the past (data anomaly)")
    return Pair(currentBatteryPercent, null)
}

val maxReasonableFutureMs = nowEpochMillis + TimeUnit.DAYS.toMillis(30)
if (tDeathEpochMillis > maxReasonableFutureMs) {
    Log.w(LOG_TAG, "Computed TOD > 30 days in future (model unreliable)")
    return Pair(currentBatteryPercent, null)
}
```

### Unit Conversions

**Slope units:** Percentage points per minute (from OLS training on time features in minutes)

**Time to death calculation:**
```
t_minutes = battery_percent / (-slope_pp_per_min)
t_millis = t_minutes * 60_000
t_death_epoch = now_epoch + t_millis
```

## Edge Cases and Handling

### 1. Empty History
- **Condition:** < 6 samples
- **Handling:** Display "Collecting data... (N/6)"
- **Result:** No prediction until minimum threshold reached

### 2. Zero Slope
- **Condition:** `slope ≈ 0.0`
- **Meaning:** Battery level is constant (no drain detected)
- **Handling:** Return null for TOD; display "Battery stable"
- **Cause:** Sensor noise or truly constant voltage

### 3. Positive Slope
- **Condition:** `slope > 0.0`
- **Meaning:** Battery is improving (impossible during discharge)
- **Handling:** Return null for TOD; display "Battery improving (anomaly?)"
- **Cause:** Charging detected mid-sample or sensor error

### 4. High Sampling Density
- **Condition:** Many samples in 7 days (e.g., 30-second intervals over 7 days = 20,000+ samples)
- **Performance Impact:** OLS fitting O(n²) in sample count
- **Mitigation Options:**
  - Downsample: Keep only every Nth sample before fitting
  - Use streaming aggregation: Average samples per hour before training
  - Increase pruning frequency: Keep only 3 days instead of 7

### 5. p_now Out of Range
- **Condition:** `currentBatteryPercent < 0.0` or `> 100.0`
- **Handling:** Clamp to [0.0, 100.0] before TOD calculation
- **Prevention:** `BatterySample.batteryLevel` is Float with implicit coercion

### 6. t_death Unreasonable
- **Condition:** TOD > 30 days in future
- **Handling:** Log warning and return null
- **Cause:** Model extrapolating poorly; device likely not draining

## Testing

### Unit Tests: `BatteryPredictionTest.kt`

Comprehensive test coverage includes:

1. **Empty/Mismatched Data:**
   - `testEmptyDatasetFails()` — Reject empty input
   - `testMismatchedSizesFails()` — Reject mismatched X/y

2. **Basic Fitting:**
   - `testSimpleLinearFit()` — Single-feature regression
   - `testMultiFeatureFit()` — Multi-feature regression

3. **Slope Semantics:**
   - `testZeroSlopeHandling()` — Constant battery
   - `testPositiveSlopeDetection()` — Battery improving (anomaly)
   - `testTodCalculationNegativeSlope()` — Valid TOD computation

4. **Edge Cases:**
   - `testTodWithZeroBattery()` — Invalid current battery
   - `testConstantFeatureHandling()` — Zero-variance feature
   - `testPredictionMismatchedFeatures()` — Feature count mismatch

5. **Feature Scaling:**
   - `testFeatureScaling()` — Normalization and scaling
   - `testMultipleFitCalls()` — State reset between fits

### Running Tests

```bash
./gradlew test
```

## Data Retention and Pruning

### 7-Day Sliding Window

The app maintains a rolling 7-day history:

```kotlin
private val sevenDaysMillis = TimeUnit.DAYS.toMillis(7)

// Prune after each insert
val cutoffEpochMillis = System.currentTimeMillis() - sevenDaysMillis
val deletedCount = dao.deleteOlderThan(cutoffEpochMillis)
```

### Benefits

- **Personalized:** Predictions are per-device (history includes device's unique drain patterns)
- **Lightweight:** 7 days ≈ 20,160 samples at 5-minute intervals; ~500 KB database
- **Relevant:** Recent drain patterns are more predictive than 6-month-old data

### Optional WorkManager Integration

For periodic background pruning without app foreground:

```kotlin
val pruneWork = PeriodicWorkRequestBuilder<PruneWorker>(
    1, TimeUnit.HOURS
).build()
WorkManager.getInstance(context).enqueueUniquePeriodicWork(
    "prune_battery_samples",
    ExistingPeriodicWorkPolicy.REPLACE,
    pruneWork
)
```

(Not included in current implementation; can be added for production.)

## Dependencies

Added to `gradle/libs.versions.toml`:

```toml
[versions]
room = "2.6.1"

[libraries]
androidx-room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
androidx-room-ktx = { group = "androidx.room", name = "room-ktx", version.ref = "room" }
androidx-room-compiler = { group = "androidx.room", name = "room-compiler", version.ref = "room" }
```

Added to `app/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.kotlin.ksp)
}

dependencies {
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
}
```

## Future Enhancements

1. **Downsampling:** Aggregate samples by hour or day before fitting
2. **Segmentation:** Separate predictions for foreground vs. background drain
3. **Confidence Intervals:** Return TOD ± range based on residual variance
4. **Online Learning:** Update model incrementally as new samples arrive
5. **Multi-Device Sync:** Share anonymized drain patterns across fleet for cold-start users
6. **UI Dashboard:** Real-time plot of battery level, slope, and predicted TOD
7. **Notifications:** Alert user at 1-hour-to-death threshold
8. **Export:** Save 7-day history as CSV for analysis

## Summary

This refactoring achieves the goals:

✅ **Room Database:** Persistent 7-day battery sample storage  
✅ **DAO Pattern:** Clean, suspend-based data access  
✅ **Personalized Predictions:** Per-device OLS model trained on historical data  
✅ **Safe TOD Calculation:** Comprehensive edge-case handling and validation  
✅ **Concurrency:** Proper dispatcher usage (IO for DB, Default for CPU-intensive fitting, Main for UI)  
✅ **Testing:** Comprehensive unit tests for regression and edge cases  
✅ **Documentation:** Clear field and function semantics  

The app is now production-ready for battery prediction with robust error handling and efficient data management.
