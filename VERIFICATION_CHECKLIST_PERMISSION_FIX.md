# Quick Verification Checklist: POST_NOTIFICATIONS Permission Fix

## Before Running the App

### ✅ Code Changes Verified
- [x] `MainActivity.kt` imports added
- [x] `ActivityResultLauncher` property defined
- [x] `requestNotificationPermissionIfNeeded()` function added
- [x] `startBatteryService()` function added
- [x] `onCreate()` calls permission request at end
- [x] `VoltWatchApp.kt` updated with permission check
- [x] `AndroidManifest.xml` has `android:name=".VoltWatchApp"`
- [x] `AndroidManifest.xml` has `POST_NOTIFICATIONS` permission
- [x] `AndroidManifest.xml` has service with `foregroundServiceType="dataSync"`

---

## Test Scenario #1: First Launch (Fresh Install)

### Setup
1. Uninstall app completely (if previously installed)
2. Connect Android 13+ device or emulator
3. Build and install fresh: `./gradlew installDebug`

### Expected Behavior
1. App opens → MainActivity onCreate() called
2. **Permission dialog appears:** "Volt Watch wants to send notifications"
3. Tap **"Allow"**
4. Notification **"Volt Watch is monitoring battery"** appears in status bar
5. No crashes
6. No error toasts

### Verification
```bash
# In terminal:
adb logcat | grep -E "VoltWatchApp|MainActivity|BatteryFgService"

# Expected logs:
# VoltWatchApp: 🟢 Application onCreate() called
# VoltWatchApp: ⏳ POST_NOTIFICATIONS not granted yet - MainActivity will request it
# MainActivity: ⏳ Requesting POST_NOTIFICATIONS permission from user...
# MainActivity: ✅ POST_NOTIFICATIONS permission granted - Starting Foreground Service
# BatteryFgService: ✅ onCreate() called - Service starting...
# BatteryFgService: ✅ Foreground service started with notification
```

### Database Check
```bash
adb shell sqlite3 /data/data/com.example.myapplication/databases/volt_watch_battery.db \
  "SELECT COUNT(*) as sample_count FROM batterysample;"

# Expected: Should increase over 5-10 minutes
# First check: 0 (starting fresh)
# After 5 min: Should see 1+ samples
# After 10 min: Should see 2+ samples
```

---

## Test Scenario #2: Permission Already Granted

### Setup
1. App already installed with permission granted
2. Kill the app: `adb shell am force-stop com.example.myapplication`
3. Reopen app

### Expected Behavior
1. No permission dialog
2. Service starts immediately
3. Notification appears instantly (less than 1 second)
4. No toasts

### Verification
```bash
# Logs should not contain "Requesting POST_NOTIFICATIONS"
adb logcat | grep "Requesting POST_NOTIFICATIONS"  # Should return empty

# Should see immediate service startup:
adb logcat | grep -E "BatteryFgService.*✅.*Foreground service started"
```

---

## Test Scenario #3: User Denies Permission

### Setup
1. Uninstall app: `adb uninstall com.example.myapplication`
2. Install fresh: `./gradlew installDebug`
3. Open app

### Expected Behavior
1. Permission dialog appears
2. Tap **"Deny"** or **"Don't allow"**
3. **Toast appears:** "Permission denied: Background battery tracking requires notification permission"
4. No notification in status bar
5. Service does NOT start
6. App should show "Learning your habits..." or similar

### Verification
```bash
adb logcat | grep "POST_NOTIFICATIONS permission denied"

# Expected:
# MainActivity: ❌ POST_NOTIFICATIONS permission denied
# MainActivity (Toast): "Permission denied: Background battery tracking requires notification permission"
```

---

## Test Scenario #4: Android 12 and Below

### Setup
1. Deploy to Android 12 device/emulator
2. Uninstall and reinstall app: `./gradlew installDebug`
3. Open app

### Expected Behavior
1. **NO permission dialog** (permission auto-granted on older Android)
2. Notification appears in status bar immediately
3. Service starts without user interaction
4. App fully functional

### Verification
```bash
adb logcat | grep "Android < 13"

# Expected:
# VoltWatchApp: ✅ Android < 13 - Starting service (notification auto-granted)
# BatteryFgService: ✅ Foreground service started with notification
```

---

## Test Scenario #5: Permission Revoked at Runtime

### Setup
1. App installed and running (permission granted)
2. Go to device Settings > Apps > Volt Watch > Permissions
3. Turn **OFF** the "Notifications" permission
4. Come back to app

### Expected Behavior
1. When service next tries to update (or on app restart):
   - Service may crash (this is expected system behavior)
   - Could show error state or "Calculating..."
2. To recover: User must go back to Settings and grant permission again

### Note
This is a rare edge case (user explicitly revoking during runtime). The app gracefully handles it through the launcher's retry logic.

---

## Logcat Pattern Reference

### ✅ Success Pattern
```
VoltWatchApp: ⏳ POST_NOTIFICATIONS not granted yet
MainActivity: ⏳ Requesting POST_NOTIFICATIONS permission from user...
MainActivity: ✅ POST_NOTIFICATIONS permission granted - Starting Foreground Service
BatteryFgService: ✅ Foreground service started with notification
BatteryFgService: ✅ Battery BroadcastReceiver registered
```

### ❌ Failure Pattern (Should NOT See)
```
BatteryFgService: SecurityException: startForeground()
BatteryFgService: Permission not granted
BatteryLoggingForegroundService not found
```

---

## Manual Testing Commands

### Grant Permission Manually
```bash
adb shell pm grant com.example.myapplication android.permission.POST_NOTIFICATIONS
adb shell am force-stop com.example.myapplication
adb shell am start -n com.example.myapplication/.MainActivity
```

### Revoke Permission
```bash
adb shell pm revoke com.example.myapplication android.permission.POST_NOTIFICATIONS
```

### Check Permission Status
```bash
adb shell dumpsys package com.example.myapplication | grep POST_NOTIFICATIONS
```

### View Service Process
```bash
adb shell ps | grep myapplication
# Expected: com.example.myapplication and BatteryLoggingForegroundService should appear
```

### View Active Notifications
```bash
adb shell dumpsys notification | grep -A10 "Volt Watch"
```

---

## Common Issues & Fixes

| Issue | Symptom | Fix |
|---|---|---|
| Permission not granted | Service crashes silently, database empty | Check Settings > Apps > Volt Watch > Permissions |
| Toast keeps showing | App keeps asking for permission | User may have tapped "Don't ask again". Clear app data in Settings |
| No notification visible | Service started but notification not showing | Go to Settings > Apps > Volt Watch > Notifications and toggle ON |
| Service crashes on restart | App works, then crashes after reboot | Boot receiver not working. Check if BatteryBootReceiver is registered in Manifest |
| Database still empty | All seems to work but no data | Wait 1-2 minutes, battery must actually drop for data to be inserted |

---

## Sign-Off

After completing all test scenarios above, you should see:

✅ **First launch:** Permission dialog → Grant → Service starts → Notification appears  
✅ **Subsequent launches:** Service starts immediately (no dialog)  
✅ **Permission denied:** Toast warning, service does NOT start  
✅ **Android 12-:** Service starts without any permission request  
✅ **Database:** Populated with samples (check after 5+ minutes of device not charging)

**If all above pass:** The runtime permission fix is working correctly! 🎉

