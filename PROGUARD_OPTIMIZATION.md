# ProGuard / R8 Optimization

This project already uses `app/proguard-rules.pro` with the optimized Android default profile:

- `getDefaultProguardFile("proguard-android-optimize.txt")`

To actually apply optimization, release minification must be enabled.

## What was changed

In `app/build.gradle.kts` under `buildTypes.release`:

- `isMinifyEnabled = true`
- `isShrinkResources = true`

This enables:

- bytecode shrinking and optimization (R8)
- obfuscation for smaller and harder-to-reverse release builds
- unused resource removal

## Verify

Run a release build:

```powershell
.\gradlew.bat :app:assembleRelease
```

If you hit missing-class runtime issues after minification, add targeted keep rules in `app/proguard-rules.pro` for reflection-based APIs only.

