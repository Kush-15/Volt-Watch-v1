# Volt Watch Project Guide

## What this app is

**Volt Watch** is an Android app in Kotlin that tracks battery behavior and predicts how long the battery will last.
It stores battery samples in a local **Room database**, then runs a battery-time prediction engine on recent discharge data.

The app is designed to:
- collect battery samples in the background,
- ignore charging noise,
- keep only useful discharge data,
- run a regression-based prediction model,
- and show the result on the UI and graph.

---

## Best reading order for a beginner

If you are new to Android app development, read the project in this order:

1. **`MainActivity.kt`** — this is the UI and orchestration layer.
2. **`BatteryLoggingForegroundService.kt`** — this is the main background data collector.
3. **`BatteryDatabase.kt`** — this defines the Room entity, DAO, and database.
4. **`BatteryRepository.kt`** — this filters and prepares database data for prediction.
5. **`PredictionEngine.kt`** — this performs the real battery-life prediction math.
6. **`BatteryGraphView.kt`** and **`BatteryGraphSanitizer.kt`** — these draw the graph.
7. **`BatteryPredictionUiFormatter.kt`** — this converts raw model output into text shown on screen.
8. **`BatteryWorkScheduler.kt`**, **`BatterySamplingWorker.kt`**, and **`BatteryBootReceiver.kt`** — these support background tracking and recovery.
9. **`OlsRegression.kt`** — this is a more general regression helper used by the project.
10. **`VoltWatchApp.kt`** — this is the application entry point.

---

## High-level architecture

Volt Watch has four main layers:

### 1. Data collection
Battery information is read from Android system broadcasts and battery APIs.
The main collector is the foreground service.

### 2. Data storage
Collected samples are saved into a Room SQLite table called **`batterysample`**.

### 3. Prediction logic
Recent valid battery drops are filtered, then fed into the prediction engine.
The engine uses **weighted OLS regression** plus smoothing.

### 4. UI rendering
The app shows:
- battery percentage,
- charging state,
- estimated time remaining,
- time-to-zero,
- sample count,
- and a line graph.

---

## Data flow: how one battery sample moves through the app

Here is the full flow in simple terms:

1. Android reports a battery change.
2. The service or sampler reads the current battery level.
3. If the phone is charging, the app pauses prediction and avoids logging bad training data.
4. If the phone is discharging, a `BatterySample` is created.
5. The sample is inserted into Room through the repository.
6. The repository fetches only the correct discharge session data.
7. The prediction engine fits a weighted regression line.
8. The result is converted into a user-friendly label.
9. The UI updates the graph and prediction text.

---

## File-by-file explanation

## `MainActivity.kt`

This is the app’s main screen controller.

### What it does
- Sets up the UI views.
- Creates the database and repository.
- Starts and stops sampling when the activity resumes or pauses.
- Requests notification permission for Android 13+.
- Prompts for battery optimization exemptions.
- Shows battery percentage and prediction text.
- Calls the prediction engine when enough data is available.

### Important ideas in this file
- It uses a **foreground service** for continuous background tracking.
- It also has a fallback sampling path if the service is killed.
- It resets the prediction state when the phone is unplugged.
- It uses `BatteryPredictionUiFormatter` to decide whether to show:
  - `Learning your habits...`
  - `Calculating...`
  - `24h+ remaining`
  - or a normal ETA like `5h 18m remaining`.

### Beginner note
Think of `MainActivity` as the coordinator. It does not do the ML math itself. It connects the UI, the database, and the prediction engine.

---

## `BatteryLoggingForegroundService.kt`

This is the most important background collector.

### What it does
- Runs as a foreground service.
- Shows a persistent notification.
- Listens for battery broadcasts.
- Detects charging and discharging state.
- Saves battery samples to the database when appropriate.

### Key behavior
- If the phone is charging, it does not train on that data.
- If the phone is discharging, it may insert a new sample.
- It uses a **gatekeeper rule** so duplicates and charging noise do not flood the database.
- It can restart itself using sticky service behavior and boot receiver support.

### Why this file matters
This is the app’s telemetry engine. Without it, the Room database would not stay updated reliably in the background.

---

## `BatteryDatabase.kt`

This file contains the Room database structure.

### Main parts

#### `BatterySample` entity
A single row in the database.
It stores:
- `id`
- `timestampEpochMillis`
- `batteryLevel`
- `voltage`
- `servicesActive`
- `foreground`
- `isCharging`

#### `BatterySampleDao`
This is where all SQL queries live.
Examples include:
- insert a sample,
- get the latest sample,
- fetch prediction windows,
- get the last charging timestamp,
- delete old rows,
- clear all rows.

#### `BatteryDatabase`
This is the Room database wrapper that connects the entity and DAO.

### Beginner note
Room is just SQLite with Kotlin-friendly annotations.
The entity defines the table, and the DAO defines the SQL queries.

---

## `BatteryRepository.kt`

This file is the business-logic layer between the database and the ML engine.

### What it does
- Inserts samples only when they are useful.
- Clears stale data when the database no longer matches the real battery state.
- Fetches discharge-session samples only.
- Removes old or contaminated samples.
- Returns a clean list for prediction.

### Important logic in this file

#### 1. Drop-only insert logic
The repository only inserts some samples if the battery level dropped compared to the latest stored row.
This reduces flat lines and duplicate rows.

#### 2. Stale data guard
If the real battery level is much higher than the last stored value and the database still says the phone is discharging, the repository treats the database as stale and clears it.

#### 3. Session isolation
The repository looks for the last charging timestamp and only uses data from the current discharge session.
This prevents old overnight data from polluting the prediction.

#### 4. Continuous downward block filtering
If there is an upward spike inside the data, the repository keeps only the most recent clean downward section.

#### 5. Background threading
All Room calls run on `Dispatchers.IO`.
This keeps the UI smooth.

### Beginner note
The repository is the app’s filter layer.
It decides which battery rows are trustworthy enough for the prediction engine.

---

## `BatterySampler.kt`

This helper reads the current battery state from Android.

### What it does
- Calls `registerReceiver(null, Intent.ACTION_BATTERY_CHANGED)` to read the sticky battery broadcast.
- Detects whether the phone is charging.
- Reads the battery percentage.
- Reads voltage.
- Captures whether the service and foreground state are active.
- Returns a `BatterySample` only when the device is discharging.

### Why it exists
This class avoids repeating battery-reading code in multiple places.
It keeps the current battery snapshot in one place.

---

## `BatterySamplingWorker.kt`

This is a WorkManager worker for periodic background sampling.

### What it does
- Reads battery state in the background.
- Skips logging while charging.
- Creates a sample.
- Inserts the sample into Room.
- Deletes very old samples after insertion.

### Why it matters
WorkManager can help the app keep collecting data even when the service is interrupted.

### Important limitation
WorkManager on Android is not exact to the minute every time, especially under Doze mode or OEM restrictions.

---

## `BatteryWorkScheduler.kt`

This file schedules the periodic worker.

### What it does
- Builds a `PeriodicWorkRequest`.
- Schedules it every 15 minutes.
- Uses a unique work name so only one periodic job exists.

### Beginner note
This is the app’s background scheduler.
It tells Android to run the worker on a repeating schedule.

---

## `BatteryBootReceiver.kt`

This receiver listens for device reboot and app replacement events.

### What it does
- Starts the foreground service after boot.
- Restarts logging when the app is updated.

### Why it matters
If the phone restarts, your tracking should not silently stop.
This receiver helps resume tracking.

---

## `PredictionEngine.kt`

This is the heart of the ML pipeline.

### What it does
- Receives cleaned battery samples.
- Keeps only the last usable window.
- Builds 1% drop anchor points.
- Computes weighted OLS regression.
- Applies sanity checks.
- Applies bounding and EMA smoothing.
- Returns a final prediction in hours.

### Current prediction logic in plain English
1. Ignore too-small sample sets.
2. Keep only recent 1% battery-drop anchors.
3. Precompute time gaps between points.
4. Reduce the weight of stale idle samples in memory.
5. Fit a weighted regression line.
6. If the slope is flat or positive, return an invalid prediction.
7. Convert slope into remaining battery hours.
8. Clamp unrealistic jumps.
9. Smooth the result with EMA.
10. Return the final prediction for the UI.

### Why weighted OLS is used
Old idle data and recent heavy-usage data should not count equally.
Weighted OLS gives more influence to recent samples.

### Why the velocity penalty exists
If the phone was idle for a long time and then started draining faster, old samples should lose influence.
The in-memory velocity penalty helps the model react faster to the new usage pattern.

---

## `OlsRegression.kt`

This file contains a more general regression implementation.

### What it does
- Standardizes features.
- Fits a regression model using matrix math.
- Supports multiple features.
- Uses ridge regularization to avoid matrix instability.
- Provides methods to predict values and inspect feature slopes.

### Why it is in the project
It is a reusable regression utility.
Depending on the current app flow, the project may use it as a helper model or for experiments.

### Beginner note
This file is more mathematical and general-purpose than `PredictionEngine.kt`.
`PredictionEngine.kt` is the main battery ETA engine.

---

## `BatteryGraphSanitizer.kt`

This file cleans graph points before drawing them.

### What it does
- Detects sudden upward spikes.
- Replaces weird points with smoother values.
- Helps the graph look like a realistic battery drain curve.

### Why it matters
Raw battery telemetry can look jagged.
The sanitizer makes the graph easier to read.

---

## `BatteryGraphView.kt`

This is the custom graph view.

### What it draws
- Historical battery percentage curve.
- Gradient fill under the curve.
- Prediction line toward 0%.
- Latest battery dot.
- Axis labels and grid lines.

### Beginner note
This is just the drawing layer.
It does not calculate predictions.
It visualizes the data that the repository and prediction engine provide.

---

## `BatteryPredictionUiFormatter.kt`

This file turns model output into text for the screen.

### What it does
- Shows `Learning your habits...` when there are too few samples.
- Shows `Calculating...` for invalid math.
- Shows `24h+ remaining` when the prediction is too large.
- Converts numeric hours into a human-readable string like `3h 42m remaining`.

### Why it matters
The ML engine returns numbers, but humans need readable text.
This class is the translation layer.

---

## `BatteryIconView.kt`

This is the custom battery icon view.

### What it does
- Draws a battery icon.
- Fills it according to the current battery level.
- Changes appearance based on battery state.

### Why it matters
This gives the app a polished UI instead of just plain text.

---

## `VoltWatchApp.kt`

This is the application class.

### What it usually does
- Initializes app-wide objects.
- Provides shared singletons like the database.
- Helps the app set up dependencies early.

### Beginner note
This is the first app-level object Android creates when the process starts.

---

## Background system summary

Volt Watch uses **multiple background mechanisms** together:

### Foreground Service
Best for continuous monitoring.
It is harder for Android to kill because it shows a notification.

### WorkManager
Used for periodic fallback sampling.
Useful if the service is interrupted.

### Boot Receiver
Restarts the service after reboot or app replacement.

### Battery broadcast listener
Lets the app react immediately when battery state changes.

---

## Database schema summary

The main table is **`batterysample`**.

Typical fields:
- `id` → row identifier
- `timestampEpochMillis` → when the sample was taken
- `batteryLevel` → battery percentage
- `voltage` → battery voltage
- `servicesActive` → whether background collection was active
- `foreground` → whether the app was in foreground
- `isCharging` → whether the phone was charging

This table is the app’s history store.
The ML model reads from it.

---

## Prediction model summary

Volt Watch’s prediction pipeline is not a neural network.
It is a **regression-based time-to-zero estimator**.

### Main idea
- Battery drops create points over time.
- The app fits a line to recent discharge data.
- The slope tells how fast the battery is draining.
- The slope is converted into remaining time.

### Why the model can fail
The model can become inaccurate if:
- idle data dominates,
- charging data leaks in,
- duplicate samples appear,
- the slope becomes too flat,
- or the battery pattern changes suddenly.

### Why the current design helps
The app uses:
- charge-session isolation,
- drop-only logging,
- stale data cleanup,
- weighted OLS,
- velocity penalty,
- EMA smoothing,
- and UI sanity checks.

Together, these make the prediction more stable.

---

## Common troubleshooting ideas

### If the app shows `Calculating...`
Possible reasons:
- not enough valid samples,
- session just reset after charging,
- slope is flat or rising,
- or the data is too noisy.

### If the app shows a huge ETA like `24h+ remaining`
Possible reasons:
- battery is draining very slowly,
- the model still sees old idle data,
- or the slope is too close to zero.

### If the graph looks jagged
Possible reasons:
- charging spikes,
- duplicate rows,
- or sudden usage changes.

The sanitizer helps with this.

### If the database is not populating
Possible reasons:
- service permission issues,
- notification permission issues on Android 13+,
- aggressive battery optimizations,
- or the service was not restarted after boot.

---

## Short beginner summary

If you remember only one thing, remember this:

- **`MainActivity.kt`** controls the screen.
- **`BatteryLoggingForegroundService.kt`** collects battery data.
- **`BatteryDatabase.kt`** stores it.
- **`BatteryRepository.kt`** cleans it.
- **`PredictionEngine.kt`** predicts battery life.
- **`BatteryGraphView.kt`** shows the result.

That is the full backend flow of Volt Watch.

---

## Final note

This project is built around a **battery telemetry pipeline**, not just a simple UI.
The quality of the prediction depends heavily on data quality.
So the most important parts are:
- correct logging,
- clean discharge-only data,
- and stable regression logic.

If you want, I can also make a **second markdown file** later that is a simpler **line-by-line beginner explanation** of each file.

