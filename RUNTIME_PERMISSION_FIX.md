# Runtime Permission Fix: POST_NOTIFICATIONS for Android 13+

## Problem Statement
On Android 13 (API 33) and above, a Foreground Service requires the `POST_NOTIFICATIONS` permission to be **granted at runtime** before calling `startForeground()`. Without this permission, the service crashes **silently**, preventing the `BatteryLoggingForegroundService` from:
- Registering the `BroadcastReceiver` for battery events
- Collecting battery telemetry
- Storing data in the Room database

This caused the database to remain empty despite the service being started.

---

## Solution Overview

### Architecture
The fix implements a **two-layer permission verification**:

1. **Application Layer** (`VoltWatchApp.kt`): On startup, check if the permission is already granted. If yes, start the service immediately. If no, wait for MainActivity.
2. **Activity Layer** (`MainActivity.kt`): Use `ActivityResultLauncher` to request the permission from the user. Once granted, immediately start the service.

### Flow Diagram
```
App Startup (VoltWatchApp)
    ↓
Check Android version
    ↓
[Android < 13?] → Start service (permission auto-granted)
    ↓ No
[Permission already granted?] → Start service
    ↓ No
Wait for MainActivity to request permission
    ↓
MainActivity onCreate()
    ↓
Call requestNotificationPermissionIfNeeded()
    ↓
Check Android version again
    ↓
[Android < 13?] → Start service (permission auto-granted)
    ↓ No
[Permission already granted?] → Start service
    ↓ No
Launch ActivityResultLauncher → Request permission from user
    ↓
User grants/denies permission
    ↓
[Granted?] → Start service + Success log
    ↓ Denied
Show Toast warning + Log error
```

---

## Files Modified

### 1. `MainActivity.kt`

**Changes:**
- Added imports for permission handling:
  - `android.Manifest`
  - `android.content.pm.PackageManager`
  - `android.widget.Toast`
  - `androidx.activity.result.contract.ActivityResultContracts`
  - `androidx.core.content.ContextCompat`

- Added `ActivityResultLauncher` property:
  ```kotlin
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
  ```

- Updated `onCreate()` to call `requestNotificationPermissionIfNeeded()`:
  ```kotlin
  override fun onCreate(savedInstanceState: Bundle?) {
      super.onCreate(savedInstanceState)
      // ... existing setup code ...
      
      sampler = BatterySampler(this, BatteryLoggingForegroundService::class.java.name)
      promptIgnoreBatteryOptimizationsIfNeeded()
      
      // ── Request POST_NOTIFICATIONS permission (Android 13+) ──
      requestNotificationPermissionIfNeeded()
  }
  ```

- Added two new helper functions:

  **`requestNotificationPermissionIfNeeded()`** (~40 lines):
  - Checks if device is running Android 13+ (Tiramisu/API 33+)
  - If Android < 13: Permission is auto-granted, so start service immediately
  - If Android 13+:
    - Check if permission is already granted using `ContextCompat.checkSelfPermission()`
    - If granted: Start service immediately
    - If not granted: Launch the `notificationPermissionLauncher` to request from user

  **`startBatteryService()`** (~5 lines):
  - Wrapper function that calls `BatteryLoggingForegroundService.start(this)`
  - Logs the action for debugging

### 2. `VoltWatchApp.kt`

**Changes:**
- Added imports:
  - `android.Manifest`
  - `android.content.pm.PackageManager`
  - `android.os.Build`
  - `android.util.Log`
  - `androidx.core.content.ContextCompat`

- Added constant for logging:
  ```kotlin
  private const val APP_LOG_TAG = "VoltWatchApp"
  ```

- Updated `onCreate()` with graceful permission checking:
  ```kotlin
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
  ```

### 3. `AndroidManifest.xml`

**No Changes Required** - Already correctly configured:
```xml
<!-- Permission declaration (manifests the requirement) -->
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<!-- Application class -->
<application android:name=".VoltWatchApp" ...>
    <!-- MainActivity triggers permission request -->
    <activity android:name=".MainActivity" ...>
        <intent-filter>
            <action android:name="android.intent.action.MAIN" />
            <category android:name="android.intent.category.LAUNCHER" />
        </intent-filter>
    </activity>
    
    <!-- Foreground Service with correct type -->
    <service
        android:name=".BatteryLoggingForegroundService"
        android:foregroundServiceType="dataSync"
        ... />
</application>
```

---

## Technical Details

### Android Version Handling

**Android < 13 (API < 33):**
- `POST_NOTIFICATIONS` permission does not exist
- Automatically granted at install time
- `startForeground()` will not throw exception
- Both `VoltWatchApp` and `MainActivity` will start service without issues

**Android 13+ (API 33+):**
- `POST_NOTIFICATIONS` permission must be requested at runtime
- Default is "not granted" until user explicitly grants it
- `startForeground()` throws `java.lang.SecurityException` if permission not granted
- Two-layer verification prevents the crash:
  1. `VoltWatchApp` checks during app startup
  2. `MainActivity` requests permission if needed

### ActivityResultLauncher

The `ActivityResultLauncher` is the modern replacement for `requestPermissions()` + `onRequestPermissionsResult()`:

```kotlin
private val notificationPermissionLauncher = registerForActivityResult(
    ActivityResultContracts.RequestPermission()  // Built-in contract for single permission
) { isGranted: Boolean ->
    // This callback runs on Main thread after user responds
    if (isGranted) {
        startBatteryService()
    } else {
        Toast.makeText(..., "Permission denied: ...", Toast.LENGTH_LONG).show()
    }
}

// To request permission:
notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
```

**Advantages:**
- Cleaner code (no `onRequestPermissionsResult()` override)
- Type-safe permission names
- Lifecycle-aware (handles config changes correctly)
- Works with modern architecture

### ServiceIntegration

The `startBatteryService()` function is now the central entry point:

```kotlin
private fun startBatteryService() {
    Log.d(LOG_TAG, "🚀 Starting BatteryLoggingForegroundService...")
    BatteryLoggingForegroundService.start(this)
}
```

This ensures that service startup is only attempted after permission is confirmed.

---

## Expected User Experience

### Scenario 1: First Launch (Android 13+)
```
1. User opens Volt Watch app
2. MainActivity onCreate() → requestNotificationPermissionIfNeeded()
3. Permission dialog appears: "Volt Watch wants to send notifications"
4. User taps "Allow"
5. BatteryLoggingForegroundService starts
6. Notification "Volt Watch is monitoring battery" appears in status bar
7. Battery data collection begins ✅
```

### Scenario 2: Permission Already Granted (Android 13+)
```
1. User opens Volt Watch app (already granted permission in past)
2. MainActivity onCreate() → requestNotificationPermissionIfNeeded()
3. Check: ContextCompat.checkSelfPermission() returns PERMISSION_GRANTED
4. startBatteryService() called immediately
5. Notification appears instantly
6. No dialog shown to user
7. Battery data collection begins ✅
```

### Scenario 3: Permission Denied (Android 13+)
```
1. User opens Volt Watch app
2. Permission dialog appears
3. User taps "Deny"
4. Toast shown: "Permission denied: Background battery tracking requires notification permission"
5. Log warning: ❌ POST_NOTIFICATIONS permission denied
6. Service NOT started
7. Battery data collection does NOT begin ❌
8. User can grant permission later in Settings > Apps > Volt Watch > Permissions
```

### Scenario 4: Android 12 and Below
```
1. User opens Volt Watch app
2. No permission dialog shown
3. VoltWatchApp.onCreate() → "Android < 13" branch
4. BatteryLoggingForegroundService starts immediately
5. Battery data collection begins ✅
```

---

## Debugging & Verification

### Check if Permission is Granted
```bash
# Query the permission status
adb shell pm list permissions -g | grep POST_NOTIFICATIONS

# Or check package-specific:
adb shell pm dump com.example.myapplication | grep POST_NOTIFICATIONS
```

**Expected output (if granted):**
```
    android.permission.POST_NOTIFICATIONS: granted=true
```

### Monitor Service Startup
```bash
# Watch logs in real-time
adb logcat | grep -E "VoltWatchApp|MainActivity|BatteryFgService"

# Expected log sequence on first launch (Android 13+):
# VoltWatchApp: 🟢 Application onCreate() called
# VoltWatchApp: ✅ Cancelled any existing WorkManager jobs
# VoltWatchApp: ⏳ POST_NOTIFICATIONS not granted yet - MainActivity will request it
# MainActivity: onCreate(savedInstanceState)
# MainActivity: ⏳ Requesting POST_NOTIFICATIONS permission from user...
# [User grants permission]
# MainActivity: ✅ POST_NOTIFICATIONS permission granted - Starting Foreground Service
# BatteryFgService: ✅ onCreate() called - Service starting...
# BatteryFgService: ✅ Notification channel created
# BatteryFgService: ✅ Foreground service started with notification
# BatteryFgService: ✅ Battery BroadcastReceiver registered
```

### Verify Service Notification Appears
```bash
# List all notifications
adb shell dumpsys notification | grep -A5 "Volt Watch"

# Or simply check in the notification panel of the emulator/device
# Look for: "Volt Watch is monitoring battery"
```

### Test Database Insertion
```bash
# Pull the database file
adb pull /data/data/com.example.myapplication/databases/volt_watch_battery.db

# Use sqlite3 to inspect
sqlite3 volt_watch_battery.db
> SELECT COUNT(*) FROM batterysample;

# Should return a number > 0 if data collection is working
```

### Simulate Permission Grant/Deny
```bash
# Grant permission
adb shell pm grant com.example.myapplication android.permission.POST_NOTIFICATIONS

# Deny permission (requires SDK 31+)
adb shell pm revoke com.example.myapplication android.permission.POST_NOTIFICATIONS

# Verify status
adb shell dumpsys package com.example.myapplication | grep POST_NOTIFICATIONS
```

---

## Potential Issues & Troubleshooting

### Issue 1: Permission Still Denied After User Grants
**Cause:** Launcher not registered, or exception swallowed somewhere.

**Solution:** Check logs for:
```
ActivityResultLauncher not registered before calling launch()
```

Ensure `registerForActivityResult()` is called **during Activity initialization** (before `onCreate()`), not during `onResume()` or other lifecycle events.

### Issue 2: Service Crashes on startForeground()
**Cause:** Permission check bypassed, or permission revoked after check.

**Solution:** Add try-catch in `BatteryLoggingForegroundService.onCreate()`:
```kotlin
override fun onCreate() {
    // ... setup ...
    try {
        startForeground(NOTIFICATION_ID, buildNotification())
        Log.d(SERVICE_LOG_TAG, "✅ Foreground service started")
    } catch (e: SecurityException) {
        Log.e(SERVICE_LOG_TAG, "❌ POST_NOTIFICATIONS permission not granted", e)
        // Gracefully stop or retry later
        stopSelf()
    }
}
```

### Issue 3: Notification Not Showing
**Cause:** Permission granted but notification channel not created.

**Solution:** Verify `createNotificationChannel()` is called before `startForeground()`:
```kotlin
override fun onCreate() {
    // ...
    createNotificationChannel()  // ← Must be before startForeground()
    startForeground(NOTIFICATION_ID, buildNotification())
    // ...
}
```

### Issue 4: Different Behavior on Different Devices
**Cause:** OEM modifications to Android (e.g., OnePlus OxygenOS).

**Solution:** Ensure battery optimization exemption is also granted:
```bash
adb shell pm dump com.example.myapplication | grep REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
```

---

## Security Considerations

### Why requestNotificationPermissionIfNeeded()?
1. **User Consent:** Android enforces that sensitive permissions require explicit user approval
2. **Privacy:** Users have full control over what apps can notify them
3. **Accessibility:** Prevents notification spam and hijacking

### Safe Practices Implemented
✅ Permission checked before service start  
✅ User feedback (Toast) on denial  
✅ No crash or hang if permission denied  
✅ Graceful fallback on older Android versions  
✅ Logging for diagnostics  

---

## Summary

| Component | Change | Purpose |
|---|---|---|
| **MainActivity.kt** | Added `ActivityResultLauncher` + permission helper functions | Request runtime permission from user |
| **VoltWatchApp.kt** | Added permission check before starting service | Prevent early service crash |
| **AndroidManifest.xml** | No changes (already correct) | Manifest declaration + service config |

**Result:** ✅ Foreground Service now starts reliably on Android 13+, battery data collection begins immediately after permission grant, database is populated correctly.

---

## References

- [Android Runtime Permissions](https://developer.android.com/training/permissions/requesting)
- [Foreground Services](https://developer.android.com/develop/background-work/services/foreground-services)
- [ActivityResultContracts.RequestPermission](https://developer.android.com/reference/androidx/activity/result/contract/ActivityResultContracts.RequestPermission)
- [Build Version Codes](https://developer.android.com/reference/android/os/Build.VERSION_CODES)

