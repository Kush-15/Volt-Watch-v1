# Battery Prediction - Quick Reference Card

## Key Files

| File | Purpose | Key Classes |
|------|---------|------------|
| BatteryDatabase.kt | Room setup | BatterySample (entity), BatterySampleDao, BatteryDatabase |
| BatterySampler.kt | Hardware sampling | BatterySampler |
| MainActivity.kt | App logic | MainActivity |
| OlsRegression.kt | ML model | OlsRegression |
| BatteryPredictionTest.kt | Unit tests | BatteryPredictionTest |

## Entity: BatterySample

```kotlin
@Entity(tableName = "batterysample", indices = [Index("timestampEpochMillis")])
data class BatterySample(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val timestampEpochMillis: Long,    // UTC epoch (indexed)
    val batteryLevel: Float,            // 0.0–100.0 %
    val voltage: Int,                   // millivolts
    val servicesActive: Boolean,        // bg services
    val foreground: Boolean = false     // app in foreground
)
```

## DAO: BatterySampleDao

```kotlin
@Dao
interface BatterySampleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSample(sample: BatterySample): Long

    @Query("SELECT * FROM batterysample WHERE timestampEpochMillis >= :sinceEpochMillis ORDER BY timestampEpochMillis ASC")
    suspend fun getSamplesSince(sinceEpochMillis: Long): List<BatterySample>

    @Query("DELETE FROM batterysample WHERE timestampEpochMillis < :cutoffEpochMillis")
    suspend fun deleteOlderThan(cutoffEpochMillis: Long): Int

    @Query("SELECT * FROM batterysample WHERE timestampEpochMillis >= :sinceEpochMillis ORDER BY timestampEpochMillis ASC")
    fun samplesSinceFlow(sinceEpochMillis: Long): Flow<List<BatterySample>>
}
```

## Database: BatteryDatabase

```kotlin
@Database(entities = [BatterySample::class], version = 1, exportSchema = false)
abstract class BatteryDatabase : RoomDatabase() {
    abstract fun batterySampleDao(): BatterySampleDao
    
    companion object {
        fun getInstance(context: Context): BatteryDatabase = 
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext, BatteryDatabase::class.java,
                    "battery_database"
                ).build().also { instance = it }
            }
    }
}
```

## Feature Mask

```
Bit 0: Time (required, always enabled)
Bit 1: Voltage (millivolts)
Bit 2: Services Active (boolean)
Bit 3: Foreground (boolean)

Default: 0b111 = time + voltage + services
```

## TOD Calculation

```
Input: 
  - Current battery (%)
  - Slope (pp/min) from OLS
  - Current time (epoch ms)

Checks:
  ✓ Slope < 0 (battery draining)
  ✓ Battery > 0% (has charge)
  ✓ TOD > now (future)
  ✓ TOD ≤ now + 30 days (reasonable)

Formula:
  t_death_minutes = current_battery / (-slope)
  t_death_epoch_ms = now_ms + (t_death_minutes * 60_000)
```

## Dispatcher Usage

```
DB Operations (insert, query, delete)
  ↓
withContext(Dispatchers.IO) {
    dao.insertSample(sample)
    dao.deleteOlderThan(cutoff)
    dao.getSamplesSince(sevenDaysAgoMs)
}

OLS Training (CPU-intensive)
  ↓
withContext(Dispatchers.Default) {
    regression.fit(xRows, yValues)
    regression.slopeForFeature(0)
}

UI Updates
  ↓
withContext(Dispatchers.Main) {
    predictionText.text = "..."
}
```

## Common Operations

### Insert & Prune (7-day window)

```kotlin
withContext(Dispatchers.IO) {
    dao.insertSample(sample)
    val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)
    dao.deleteOlderThan(cutoff)
}
```

### Fetch 7-Day History

```kotlin
val sevenDaysAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)
val samples = withContext(Dispatchers.IO) {
    dao.getSamplesSince(sevenDaysAgo)
}
```

### Train Model

```kotlin
withContext(Dispatchers.Default) {
    val xRows = samples.map { buildFeatureVector(it, anchorTime) }.toTypedArray()
    val yValues = samples.map { it.batteryLevel.toDouble() }.toDoubleArray()
    regression.fit(xRows, yValues)
}
```

### Calculate TOD

```kotlin
val slope = regression.slopeForFeature(0)

if (slope != null && slope < 0.0 && currentBattery > 0.0) {
    val minutesToEmpty = currentBattery / -slope
    val millisToEmpty = (minutesToEmpty * 60_000).toLong()
    val tDeathEpochMs = nowEpochMs + millisToEmpty
    
    if (tDeathEpochMs > nowEpochMs && tDeathEpochMs <= nowEpochMs + 30_days_ms) {
        // Valid TOD
    }
}
```

## Edge Cases

| Condition | Handling |
|-----------|----------|
| < 6 samples | Display "Collecting..." |
| slope == 0 | Battery not draining; no prediction |
| slope > 0 | Battery improving (anomaly); no prediction |
| battery ≤ 0% | Already dead; invalid TOD |
| TOD in past | Data error; invalid |
| TOD > 30 days | Model unreliable; invalid |

## Testing

Run: `./gradlew test`

Tests cover:
- Empty/mismatched data
- Single and multi-feature fitting
- Zero/positive slope handling
- Feature scaling
- TOD validity checks
- Edge cases (14+ test cases)

## Performance Notes

- Index on `timestampEpochMillis` enables O(log N) range queries
- OLS fitting is O(N²) in sample count
- For >2000 samples, consider downsampling by hour
- Typical 7-day history: ~20,000 samples at 30-sec intervals ≈ 500 KB DB

## Logging

Key log tags:
- `MainActivity` — Main app logic
- `BatterySampler` — Hardware sampling
- `BatteryPredictionTest` — Unit tests

Example logs:
```
D/MainActivity: Sample inserted with id=123
D/MainActivity: Pruned 150 old samples
D/MainActivity: Slope (pp/min): -0.5
D/MainActivity: TOD computed: 14:30
W/MainActivity: Slope is null or non-negative
W/MainActivity: Computed TOD > 30 days in future
```

## Dependencies

```toml
room = "2.6.1"

implementation(libs.androidx.room.runtime)
implementation(libs.androidx.room.ktx)
ksp(libs.androidx.room.compiler)
```

## Links

- **Architecture Guide:** `ROOM_REFACTORING_GUIDE.md`
- **Summary:** `REFACTORING_SUMMARY.md`
- **Examples:** `IMPLEMENTATION_EXAMPLES.md`
- **Checklist:** `VERIFICATION_CHECKLIST.md`

---

**Last Updated:** 2026-02-24
**Status:** ✅ Complete
