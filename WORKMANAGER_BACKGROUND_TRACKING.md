# Foreground Service Battery Tracking (Volt Watch)

## What was added

- `app/src/main/java/com/example/myapplication/BatteryLoggingForegroundService.kt`
  - Persistent foreground service for battery telemetry.
  - Creates notification channel: `volt_watch_monitoring`.
  - Displays ongoing notification text: `Volt Watch is monitoring battery`.
  - Logs battery level + voltage every 60 seconds.
  - Inserts Room rows with:
    - `servicesActive = true`
    - `foreground = false`
  - Uses `PowerManager.PARTIAL_WAKE_LOCK` per data-collection tick.
  - Returns `START_STICKY` for self-healing restart behavior.

- `app/src/main/java/com/example/myapplication/VoltWatchApp.kt`
  - Starts foreground service at app process start.

- `app/src/main/java/com/example/myapplication/BatteryBootReceiver.kt`
  - Restarts foreground service after:
    - `BOOT_COMPLETED`
    - `LOCKED_BOOT_COMPLETED`
    - `MY_PACKAGE_REPLACED`

- `app/src/main/AndroidManifest.xml`
  - Registers `BatteryLoggingForegroundService` with `foregroundServiceType="dataSync"`.
  - Adds permissions:
    - `RECEIVE_BOOT_COMPLETED`
    - `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
    - `FOREGROUND_SERVICE`
    - `FOREGROUND_SERVICE_DATA_SYNC`
    - `WAKE_LOCK`
    - `POST_NOTIFICATIONS`

- `app/src/main/java/com/example/myapplication/MainActivity.kt`
  - Uses the new foreground service class name for runtime service-state sampling.
  - Keeps battery optimization exemption prompt for OnePlus/OxygenOS.

## OnePlus battery-optimization exemption intent

```kotlin
val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
    data = Uri.parse("package:$packageName")
}
startActivity(intent)
```

## Notes

- Foreground service keeps monitoring active during lock screen and heavy app switching better than activity-only sampling.
- `POST_NOTIFICATIONS` runtime approval is required on Android 13+ to show the persistent notification reliably.
- WorkManager files may still exist in the project, but foreground service is now the primary collection path.

