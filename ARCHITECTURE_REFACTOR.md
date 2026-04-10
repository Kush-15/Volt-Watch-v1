# Backend Architecture Refactor: 3-Layer Separation of Concerns

## Overview
Your backend has been refactored from a tangled single-layer approach into a clean 3-layer architecture. This solves:
- Idle-period contamination of OLS slope
- Unexpected sample count drops
- Charging rows mixing into discharge windows  
- Wildly overestimated predictions (4+ hours at 31% battery)

---

## Layer Architecture

### **Layer 1: Storage (BatteryRepository.kt)**
**Responsibility:** Raw data read/write only.

**What it does:**
- Insert samples (with basic 1% drop gate at write time)
- Read raw, unfiltered samples from Room database
- Prune data older than 30 days
- Detect stale DB state and wipe if needed

**What it does NOT do:**
- Filter out charging rows ❌
- Remove duplicates (beyond the 1% gate) ❌
- Detect idle gaps ❌
- Validate sample counts for ML ❌
- Apply any ML logic ❌

**Key Constants:**
- `RAW_FETCH_WINDOW_SIZE = 25` - Fetch raw data in 25-sample chunks
- `THIRTY_DAYS_MS = 30L * 24L * 60L * 60L * 1000L` - Age-based cleanup only

---

### **Layer 2: Data Preparation (SessionManager.kt) - NEW**
**Responsibility:** Transform raw data into clean ML-ready samples.

**What it does (in this exact order):**
1. **Fetch raw samples** from Layer 1 (Repository)
2. **Filter out charging rows** (`isCharging = 1` → removed)
3. **Remove duplicate levels** (keep only rows where battery dropped >= 1%)
4. **Detect idle gaps** (> 60 minutes) and cut there, keeping only the most recent continuous discharge block
5. **Validate minimum sample count** (need >= 5 samples)
6. **Return trimmed window** (last 20 samples = most recent 20% of battery drain)

**What it does NOT do:**
- Write to database ❌
- Run OLS math ❌
- Update UI ❌
- Make predictions ❌

**Key Constants:**
- `UPWARD_SPIKE_TOLERANCE_PERCENT = 0.15f` - Allow small measurement noise
- `IDLE_GAP_BREAK_MS = 60L * 60L * 1000L` - 60 minutes = end of discharge session
- `MIN_SAMPLES_FOR_PREDICTION = 5` - Cold start threshold
- `WINDOW_SIZE = 20` - OLS window size (last 20% drain)

**Functions:**
- `prepareCleanSamplesForPrediction(currentSystemBatteryLevel)` - Main entry point
- `removeDuplicateLevels()` - Deduplicate flat-line entries
- `keepMostRecentContinuousBlock()` - Cut at idle gaps, discard old standby data

---

### **Layer 3: ML Prediction (PredictionEngine.kt)**
**Responsibility:** Pure mathematics - Weighted OLS regression with safety guards.

**What it does:**
- Receive clean List<BatterySample> from SessionManager
- Calculate weighted OLS slope and intercept
- Apply velocity penalty (down-weight stale idle samples)
- Apply confidence blending (fallback for low sample counts)
- **Apply physical cap** (7 min/1% battery = hard limit, NEVER removed)
- Apply EMA smoothing to avoid violent swings
- Return predicted time-to-zero

**What it does NOT do:**
- Touch the database ❌
- Fetch data directly ❌
- Make data quality decisions ❌
- Remove the physical cap ❌

**Key Constants:**
- `OLS_WINDOW_SIZE = 20` - Matches SessionManager's output
- `MIN_SAMPLES_FOR_PREDICTION = 5` - Matches SessionManager's gate
- `PHYSICAL_CAP_MINUTES_PER_PERCENT = 7.0` - SACRED: Never remove or relax
- `GLOBAL_FALLBACK_MINUTES_PER_PERCENT = 7.0` - Used for early-session blending

**Physical Cap Rule:**
```kotlin
val physicalCapHours = (currentBattery / 100.0) * 7.0 / 60.0
val clampedHours = blendedHours.coerceAtMost(physicalCapHours)
```
This is the last line of defense against ML hallucinations.

---

## Data Flow

```
BatteryLoggingForegroundService.kt (writes raw samples)
                ↓
        BatteryRepository (Layer 1 - Storage)
                ↓
        SessionManager (Layer 2 - Data Prep)
       ↙ (removed charging rows)
      ↙ (removed duplicates)
     ↙ (cut at idle gaps)
    ↙ (validated min count)
   ↙ (trimmed to 20 samples)
                ↓
        PredictionEngine (Layer 3 - ML)
       ↙ (OLS math)
      ↙ (velocity penalty)
     ↙ (confidence blending)
    ↙ (physical cap clamping)
   ↙ (EMA smoothing)
                ↓
        MainActivity (UI update)
```

---

## Integration in MainActivity

```kotlin
// Initialize all three layers in onCreate()
repository = BatteryRepository(dao)                    // Layer 1
sessionManager = SessionManager(repository)             // Layer 2
predictionEngine = PredictionEngine()                   // Layer 3

// In refreshPrediction():
val cleanSamples = withContext(Dispatchers.IO) {
    sessionManager.prepareCleanSamplesForPrediction(currentSystemBatteryLevel)  // Layer 2
}

if (cleanSamples.size >= 10) {
    val result = predictionEngine.predictRemainingHours(cleanSamples)           // Layer 3
    // ... display result on UI
}
```

---

## Key Rules (Sacred)

✅ **BatteryRepository.kt only reads/writes.**  
✅ **SessionManager.kt only filters and validates (NO math, NO UI, NO DB writes).**  
✅ **PredictionEngine.kt only does OLS math (NO DB, NO filtering, NO decisions).**  
✅ **Physical cap is NEVER removed or relaxed.** (7 min per 1% = sacred limit)  
✅ **If prediction looks wrong, the fix goes in SessionManager.kt, NOT PredictionEngine.kt.**

---

## Why This Fixes Your Bugs

| Bug | Root Cause | Fix |
|-----|-----------|-----|
| 4+ hours at 31% battery | Old idle data contaminating slope | SessionManager cuts at idle gaps (>60 min) |
| Sample count dropping unexpectedly | Charging rows mixed in | SessionManager filters `isCharging=1` |
| Wildly inaccurate early predictions | Insufficient data blending | PredictionEngine: confidence-based fallback |
| Stale data after charge | No session reset logic | SessionManager enforces pivot timestamp |

---

## Testing Checklist

After installing the app:

- [ ] Charge phone to 100%
- [ ] Unplug and let battery drain naturally  
- [ ] Check that prediction updates every 5 min or 2% drop
- [ ] At 50%, check that prediction is realistic (4-6 hours)
- [ ] At 20%, check that prediction is realistic (1-2 hours)
- [ ] Plug in to charge; verify "⚡ Charging..." displays
- [ ] Let sit for 2 hours, unplug; verify "Learning your habits..." appears
- [ ] After 5 fresh samples at 100%, check new prediction is reasonable
- [ ] Verify physical cap enforced: no prediction exceeds (battery% × 7 / 60) hours

---

## Files Changed

| File | Change | Type |
|------|--------|------|
| `BatteryRepository.kt` | Removed all filtering/session logic; now storage-only | **Refactored** |
| `PredictionEngine.kt` | Added physical cap (7 min/1%); updated constants | **Enhanced** |
| `MainActivity.kt` | Added SessionManager injection; route data through Layer 2 | **Updated** |
| `SessionManager.kt` | **NEW** - All data prep logic lives here | **Created** |

---

## Next Steps

1. Build and test the app
2. Monitor logcat for any Layer boundary violations (e.g., "Data filtering in wrong layer")
3. Verify database only grows when battery drops (no flat lines)
4. Confirm predictions are stable and realistic

If bugs persist, they will now be **localized to a single layer**, making them much easier to fix.

