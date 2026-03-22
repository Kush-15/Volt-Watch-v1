# Code Changes Summary: Runtime Permission Fix

## File: MainActivity.kt

### Change #1: Import Statements

**BEFORE:**
```kotlin
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
// ...
```

**AFTER:**
```kotlin
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
// ... (rest unchanged)
```

**New Imports:**
- `android.Manifest` - Access to permission constants
- `android.content.pm.PackageManager` - Permission checking
- `android.widget.Toast` - User feedback on denial
- `androidx.activity.result.contract.ActivityResultContracts` - Modern permission request
- `androidx.core.content.ContextCompat` - Compat wrapper for permission checks

---

### Change #2: Add ActivityResultLauncher Property

**Location:** Inside `MainActivity` class, after the companion object, before `override fun onCreate()`

**Code:**
```kotlin
class MainActivity : AppCompatActivity() {
    // ... existing properties ...
    private val minSamplesToFit = BatteryPredictionUiFormatter.COLD_START_MIN_SAMPLES
    private val samplingIntervalMs = 30_000L
    private val sevenDaysMillis = TimeUnit.DAYS.toMillis(7)

    // ✅ NEW: Add this launcher property
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
        // ... existing code ...
    }
}
```

---

### Change #3: Update onCreate() Method

**BEFORE:**
```kotlin
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
```

**AFTER:**
```kotlin
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
    
    // ✅ NEW: Request POST_NOTIFICATIONS permission (Android 13+)
    requestNotificationPermissionIfNeeded()
}
```

**Added Line:**
```kotlin
requestNotificationPermissionIfNeeded()
```

---

### Change #4: Add New Helper Functions

**Location:** Inside `MainActivity` class, at the END before the closing brace `}`

**Code:**
```kotlin
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
```

---

## File: VoltWatchApp.kt

### Complete File Replacement

**BEFORE:**
```kotlin
package com.example.myapplication

import android.app.Application
import androidx.work.WorkManager

class VoltWatchApp : Application() {
    override fun onCreate() {
        super.onCreate()
        WorkManager.getInstance(this).cancelUniqueWork("battery_periodic_sampling")
        BatteryLoggingForegroundService.start(this)
    }
}
```

**AFTER:**
```kotlin
package com.example.myapplication

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.WorkManager

private const val APP_LOG_TAG = "VoltWatchApp"

class VoltWatchApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.d(APP_LOG_TAG, "🟢 Application onCreate() called")
        
        WorkManager.getInstance(this).cancelUniqueWork("battery_periodic_sampling")
        Log.d(APP_LOG_TAG, "✅ Cancelled any existing WorkManager jobs")
        
        // On Android 13+, only start service if notification permission is already granted.
        // The MainActivity will request the permission if needed.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val isGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            
            if (isGranted) {
                Log.d(APP_LOG_TAG, "✅ POST_NOTIFICATIONS already granted - Starting service")
                BatteryLoggingForegroundService.start(this)
            } else {
                Log.d(APP_LOG_TAG, "⏳ POST_NOTIFICATIONS not granted yet - MainActivity will request it")
            }
        } else {
            Log.d(APP_LOG_TAG, "✅ Android < 13 - Starting service (notification auto-granted)")
            BatteryLoggingForegroundService.start(this)
        }
    }
}
```

**Changes:**
1. Added new imports for permission checking
2. Added `APP_LOG_TAG` constant
3. Added detailed logging
4. Added Android version and permission checks
5. Conditional service startup based on permission status

---

## File: AndroidManifest.xml

**NO CHANGES NEEDED** - Already correctly configured:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <!-- ✅ Permission already declared -->
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    
    <!-- ✅ Other required permissions ... -->

    <application
        <!-- ✅ Application class correctly set -->
        android:name=".VoltWatchApp"
        ...>
        
        <!-- ✅ Service correctly configured -->
        <service
            android:name=".BatteryLoggingForegroundService"
            android:enabled="true"
            android:exported="false"
            android:foregroundServiceType="dataSync"
            android:stopWithTask="false" />
        
        <!-- ... rest of manifest ... -->
    </application>
</manifest>
```

---

## Diff Summary

### Files Modified: 2
1. `MainActivity.kt` - Added permission request launcher and helper functions
2. `VoltWatchApp.kt` - Added conditional permission check before service start

### Files Created: 2
1. `RUNTIME_PERMISSION_FIX.md` - Comprehensive documentation
2. `VERIFICATION_CHECKLIST_PERMISSION_FIX.md` - Testing guide

### Files Unchanged: 1
1. `AndroidManifest.xml` - Already correctly configured

### Lines Added: ~100
- MainActivity: ~60 lines (imports + launcher + 2 functions)
- VoltWatchApp: ~40 lines (imports + detailed permission check with logging)

### Lines Removed: 1
- VoltWatchApp: Old direct service call replaced with conditional logic

---

## Quick Integration Guide

### Step 1: Update MainActivity.kt
1. Add new imports (Manifest, PackageManager, Toast, ActivityResultContracts, ContextCompat)
2. Add `notificationPermissionLauncher` property with callback
3. Call `requestNotificationPermissionIfNeeded()` at end of `onCreate()`
4. Add two new functions: `requestNotificationPermissionIfNeeded()` and `startBatteryService()`

### Step 2: Update VoltWatchApp.kt
1. Add new imports (Manifest, PackageManager, Build, Log, ContextCompat)
2. Add `APP_LOG_TAG` constant
3. Replace the entire `onCreate()` with new permission-aware version

### Step 3: Rebuild and Test
```bash
./gradlew clean build
./gradlew installDebug
```

### Step 4: Verify
- ✅ No crashes on first launch
- ✅ Permission dialog appears on Android 13+
- ✅ Service starts after permission grant
- ✅ Toast appears if permission denied
- ✅ Service starts immediately on Android 12-

---

## Backward Compatibility

✅ **Fully backward compatible:**
- Devices with Android < 13: Permission automatically granted, service starts directly
- Devices with Android 13+: Permission check before startup, proper request flow
- Apps already installed: No breaking changes, existing data preserved

---

## Testing the Changes

### Compile Check
```bash
./gradlew compileDebugKotlin
# Should complete without errors
```

### Build Check
```bash
./gradlew assembleDebug
# Should produce APK without errors
```

### Runtime Test (First Launch)
```bash
adb uninstall com.example.myapplication
./gradlew installDebug
adb logcat | grep -E "VoltWatchApp|MainActivity|POST_NOTIFICATIONS"
# Observe permission request flow
```

