# Bug Fix: "Learning Your Habits" with 0 Samples After Charging

## Problem Statement
After charging and unplugging the device, the app showed **"Learning your habits..."** with **0 samples**, even though:
- The database WAS populating with new battery samples
- New discharging data was being recorded to Room DB
- The UI should have shown the incoming samples gradually

## Root Cause Analysis

### The Issue Chain:
1. **User charges phone** → BatteryService logs `is_charging = 1` marker
2. **User unplugs phone** → MainActivity detects transition and resets UI to "0/$minSamplesToFit samples"
3. **BatteryRepository.getRecentDischargingWindow()** was called to fetch fresh samples
4. **PROBLEM**: The old code enforced **strict session isolation**:
   ```kotlin
   val lastChargingTs = dao.getLatestChargingTimestamp()  // Gets the "plugged in" timestamp
   val sessionWindowDesc = if (lastChargingTs == null) {
       dao.getLatestNonChargingPredictionWindow(PREDICTION_WINDOW_SIZE)
   } else {
       // Only fetch samples AFTER the charging timestamp
       dao.getPredictionWindowSince(sinceEpochMillis = lastChargingTs, windowSize = PREDICTION_WINDOW_SIZE)
   }
   
   if (cleanedSession.size < MIN_SESSION_SAMPLES_FOR_PREDICTION) {
       return emptyList()  // ← Returns EMPTY if not enough new samples!
   }
   ```

5. **Result**: On first call after unplugging, the new discharge cycle has < 5 samples → returns empty list → UI shows "Learning..."

### Why This Happened:
The code was trying to be **too smart** with session isolation. It wanted to ensure that the OLS model only learned from the current discharge cycle, preventing old "idle overnight" data from skewing predictions. But it went too far:
- Forcing the repository to return empty lists until 5+ fresh samples exist
- This caused the UI to display "0 samples" immediately after charging
- Users perceived this as "data collection stopped"

## The Fix

**File Changed**: `BatteryRepository.kt`

**Changes Made**:
1. **Removed strict session isolation enforcement** at the repository level
2. **Changed the query strategy**: Instead of filtering by `timestampEpochMillis > lastChargingTimestamp`, now we simply fetch the latest 20 non-charging samples
3. **Let the UI layer decide**: Moved the "is this enough samples?" check to MainActivity (line ~310), not the repository
4. **Removed unnecessary constant**: Deleted `MIN_SESSION_SAMPLES_FOR_PREDICTION = 5`

**New Code** (lines 42-62 in BatteryRepository.kt):
```kotlin
suspend fun getRecentDischargingWindow(currentSystemBatteryLevel: Float): List<BatterySample> = withContext(ioDispatcher) {
     val latest = dao.getLatestSample()

    // State guard: DB is stale when real battery jumped up while latest row still says discharging.
    if (
        latest != null &&
        !latest.isCharging &&
        (currentSystemBatteryLevel - latest.batteryLevel) > STALE_LEVEL_JUMP_THRESHOLD_PERCENT
    ) {
        dao.clearAllSamples()
        return@withContext emptyList()
    }

    // FIX: After charging, NEVER enforce strict session isolation that returns empty list.
    // Instead, fetch the most recent window of discharging data and let it build naturally.
    // This prevents the "Learning your habits with 0 samples" issue after each charge.
    val sessionWindowDesc = dao.getLatestNonChargingPredictionWindow(PREDICTION_WINDOW_SIZE)

    val sessionChronological = sessionWindowDesc.asReversed()
    val cleanedSession = keepMostRecentContinuousDischargeBlock(sessionChronological)

    // Return whatever we have; don't enforce a minimum here. Let the UI layer decide if it's enough.
    return@withContext cleanedSession
}
```

## Expected Behavior After Fix

### Scenario: User Charges Phone Overnight, Then Unplugs

**Time: 9:00 AM - Unplugging**
- Device was idle all night (old samples from battery 100% → 95%)
- New discharging cycle starts
- App shows: `Learning your habits... (0/10 samples)` ← This is CORRECT

**Time: 9:05 AM**
- User uses phone normally
- Battery drops 95% → 94% → 93% (3 new samples)
- App shows: `Learning your habits... (3/10 samples)` ← Database IS populating

**Time: 9:10 AM**
- Battery at 89%
- App shows: `Learning your habits... (6/10 samples)` ← Getting closer

**Time: 9:15 AM**
- Battery at 85%
- App shows: `Learning your habits... (10/10 samples)` ← **NOW prediction starts!**
- App begins showing actual ETA (e.g., "5 hours 23 minutes remaining")

### Key Points:
✅ Database continuously populates after unplugging  
✅ Sample count increments in the UI  
✅ Prediction starts once 10 samples are reached  
✅ No "0 samples" confusion  

## What This Fix Does NOT Change

- ❌ **Does NOT** clear the database on charge
- ❌ **Does NOT** prevent old pre-charge data from being retained (30-day retention still applies)
- ❌ **Does NOT** change the OLS math or prediction logic
- ✅ **MAINTAINS** the stale database guard (if real battery jumps up while DB shows discharging, clear it)
- ✅ **MAINTAINS** the "continuous discharge block" filter to prevent charging spikes from entering predictions
- ✅ **MAINTAINS** EMA smoothing and prediction bounds

## Testing Checklist

- [ ] **Install the app fresh (no previous DB)**
  1. Charge to 100%
  2. Unplug → app should show "Learning your habits... (0/10)"
  3. Use phone normally for 5 minutes
  4. Check: Sample count should increase (1, 2, 3... up to 10)
  5. Once 10+ samples → prediction shows (e.g., "5h 30m remaining")

- [ ] **Overnight idle test**
  1. Leave phone idle overnight with ~30% battery
  2. Next morning at 95%: charge to 100%, unplug
  3. Use phone → check sample count increments
  4. Verify: Database has old idle samples + new discharge samples

- [ ] **Rapid charge/discharge cycle**
  1. Charge to 95%
  2. Immediately unplug
  3. Use phone → confirm learning starts fresh

## Implementation Details

### Why This Approach?

The old approach tried to isolate sessions at the **data access layer** (repository), which is wrong because:
- Repository doesn't know what "enough samples" means for good predictions
- UI layer is better positioned to show feedback ("Learning...", sample count, etc.)
- Returning empty lists from repository caused false "no data" signals

The new approach:
- Repository always returns available data (no empty list from validation)
- Data is cleaned (old charging rows filtered, discharge blocks validated)
- UI layer decides what to show based on sample count

This follows the **separation of concerns** principle:
- **Repository**: Fetch + filter data, don't validate thresholds
- **UI Layer**: Validate thresholds and show appropriate feedback

---

**Deployed**: `BatteryRepository.kt` refactor  
**Backward Compatible**: ✅ Yes (only changes query strategy, not data model)  
**Performance Impact**: ✅ Minimal (same number of queries, slightly simpler logic)

