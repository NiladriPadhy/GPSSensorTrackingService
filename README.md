# Distance Tracking POC – Initial Analysis Report

A Proof of Concept (POC) Android application, developed in **Kotlin**, that calculates
the total distance travelled using only the device's **GPS sensor in offline mode**.
No external map service or Route/Directions API is used for distance calculation —
distance is computed on-device from filtered GPS fixes using the Haversine formula.

> A detailed technical analysis is available in
> [FieldSurveyApp_DistanceTracking_Report.md](FieldSurveyApp_DistanceTracking_Report.md).

## Support

> If this project helped you, consider sponsoring or buying me a coffee.
> Your support keeps it maintained, documented, and free.

[![GitHub Sponsors](https://img.shields.io/badge/Sponsor-GitHub-ea4aaa?style=for-the-badge&logo=GitHub-Sponsors&logoColor=white)](https://github.com/sponsors/NiladriPadhy)
[![Buy Me A Coffee](https://img.shields.io/badge/Buy%20Me%20a%20Coffee-ffdd00?style=for-the-badge&logo=buy-me-a-coffee&logoColor=black)](https://buymeacoffee.com/npadhy)

This work stays open source. A small contribution helps cover time for bug fixes, new features, and docs.

---

## Test Scenario

The app was tested against a vehicle's trip meter on a real road route that
deliberately included multiple direction changes.

### Road Test

| Metric | Value |
|---|---|
| **Vehicle Trip Meter Distance** | 2.00 km |
| **App Calculated Distance** | 1.94 km |
| **Route Points Captured** | 25 |

The test route included:

- Left turns
- Right turns
- U-turns

---

## Travel Distance Accuracy

$$
\text{Accuracy} = \frac{\text{App Distance}}{\text{Vehicle Distance}} \times 100
= \frac{1.94}{2.00} \times 100 = 97\%
$$

Or in plain terms:

```
Accuracy = (App Distance / Vehicle Distance) × 100
         = (1.94 / 2.00) × 100
         = 97%
```

### Result

- **Travel Distance Accuracy:** **97%**
- **Difference:** **0.06 km (60 meters)**

---

## Why the App Distance May Not Exactly Match the Vehicle Trip Meter

The application captures GPS locations periodically based on the speed-aware
tracking service. Since GPS positioning is subject to latency and measurement
limitations, an exact match with the vehicle's odometer should not be expected.

Distance accuracy can vary depending on several factors:

1. **GPS sensor accuracy** of the device
2. **Satellite signal strength and visibility**
3. **GPS latency** while acquiring updated positions
4. **Location sampling frequency** (time interval between captured points)
5. **Vehicle speed**
6. **Sharp turns, left/right turns, and U-turns**
7. **Road geometry** (curves vs. straight roads)
8. **Urban environments** with buildings causing signal reflections (urban canyon effect)
9. **Trees, tunnels, bridges, or other obstructions**
10. **Weather and atmospheric conditions**
11. **Device hardware quality** and GPS chipset performance
12. **Power optimization or battery saver modes** affecting location updates
13. **Operating system location policies**
14. **Temporary loss of GPS signal**
15. **Accuracy filtering algorithms** used by the application
16. **Number of route points captured** during the trip
17. **Interpolation between GPS coordinates** when calculating total distance
18. **Cold start vs. warm start** of the GPS receiver

---

## Observation

The application is designed to estimate the total distance travelled using
offline GPS tracking rather than to replicate a vehicle odometer. Therefore,
minor deviations are expected due to GPS sensor limitations and sampling intervals.

For this test case, the observed accuracy of approximately **97%** demonstrates
that the current approach provides a reasonable estimation of travelled distance
under real-world conditions.

The POC has been developed using **Kotlin**. A detailed analysis report is in
[FieldSurveyApp_DistanceTracking_Report.md](FieldSurveyApp_DistanceTracking_Report.md).

---

## How Distance Is Calculated (Offline)

1. The OS Fused Location Provider delivers GPS fixes (no batching — every fix is processed).
2. Each fix passes a **noise filter** (`GpsFilter`): poor-accuracy, jitter, GPS
   Doppler-speed (stationary), and teleport spikes are rejected so they can't
   inflate the total.
3. Distance between consecutive **accepted** fixes is summed using the
   **Haversine** great-circle formula, incrementally, and persisted so a crash
   never loses progress.
4. A stationary **anchor clamp** ensures a parked/untouched phone contributes
   **0 m** (no phantom distance).

No network, map tiles, or routing API are required for the distance figure.

---

## Key Features

- **Shift scheduling** — define a daily window (e.g. 09:00 → 21:00); tracking
  starts/stops automatically via `AlarmManager`, self-healing across reboots,
  app updates, and process kills.
- **Two foreground services** — an always-on app service (keeps the process
  alive, holds a wake lock) and an in-shift location service.
- **Configurable accuracy** — High / Medium / Low profiles (sampling rate +
  filter thresholds), changeable live.
- **Date-bucketed data** — view any day's distance, route, and points; default
  is today, with a date picker.
- **Map / List route view** — Google Maps polyline (split on gaps) or a list of
  captured points (time, lat/lon, accuracy, speed, bearing).
- **Live speed** — current km/h on the Home screen and in the tracking notification.
- **Activity logging** — per-date on-device log with an in-app viewer and share.
- **Data retention** — auto-delete data + logs older than a configurable window
  (1/3/7/10/15/30 days, default 7).
- **Reset today** — one-tap clear of the current day's data and log.
- **Battery / Doze resilience** — battery-optimisation exemption request +
  OEM-aware "keep alive" guidance screen.

---

## Project Layout

```
app/src/main/java/com/fieldsurvey/poc/
  MainActivity.kt            # permission chain, lifecycle re-arm
  FieldSurveyApp.kt          # startup: services, scheduler, retention purge
  data/                      # Room entities, DAOs, DB, DataStore settings, retention
  tracking/                  # Haversine, Bearing, GpsFilter, AccuracyMode, DateKeys
  service/                   # AppForegroundService, LocationTrackingService, notifications
  scheduler/                 # ShiftScheduler + alarm/boot receivers
  system/                    # OEM whitelist detection + intents
  logging/                   # AppLog (per-date file logger)
  ui/                        # home / map / log / settings / whitelist / nav / theme
```

---

## Prerequisites

- Android Studio (Koala / Ladybug or newer)
- JDK 17
- A real Android device (best for GPS; emulator works for basic testing)
- A **Google Maps API key** (only needed for the map view, not for distance)

## One-Time Setup

1. Create `local.properties` at the project root:
   ```
   sdk.dir=/Users/<you>/Library/Android/sdk
   MAPS_API_KEY=YOUR_GOOGLE_MAPS_API_KEY
   ```
   The build reads `MAPS_API_KEY` and injects it into the manifest and `BuildConfig`.

2. In Android Studio: **Open** → select the project folder → let Gradle sync.
   (If the Gradle wrapper jar is missing, run `gradle wrapper --gradle-version 8.9` once.)

## Run

- Connect a device (USB debugging on) or start an emulator with Google Play services.
- Run the **app** configuration and grant the prompted permissions:
  - Notifications (Android 13+)
  - Location (precise + background)
  - Allow unrestricted battery usage ("ignore battery optimizations")
- On Xiaomi/Oppo/Vivo/Samsung etc., use the in-app **"Keep tracking alive"**
  screen to allow auto-start / disable background restrictions.

## How to Test the Flow

1. Open **Shift settings**, set a window that includes the current time, toggle
   **Auto-track** on.
2. Tracking starts automatically when inside the window (and on app launch /
   boot if already inside it).
3. Walk or drive a route. The tracking notification stays visible and shows
   live speed + distance.
4. Open **View route map** (Map / List) to inspect the polyline and points, and
   **View logs** to see per-fix decisions.

## Generate a Signed APK

A self-contained build script is provided:

```bash
./output/build/build-signed-apk.sh
```

It produces `output/FieldSurveyPOC-release-signed.apk` using the keystore in
`output/build/`. (POC convenience: the keystore password/alias are hard-coded in
the script — replace with secure handling for any real release.)

---

## What's Intentionally Out of Scope (POC)

- Backend sync of data
- Map-matching / road-snapping of the recorded trace
- User authentication
- Polyline simplification (Douglas–Peucker) for very long shifts

## Notes on Android Specifics

- Location foreground service uses `FOREGROUND_SERVICE_TYPE_LOCATION`; the
  always-on service uses `specialUse` (Android 14+).
- Background location permission is requested **after** foreground location.
- `AlarmManager.setRepeating` drives daily start/stop. For exact-to-the-minute
  triggering, switch to `setExactAndAllowWhileIdle` (permissions already declared).
- `BootReceiver` re-arms alarms after reboot/app-update and resumes tracking if
  the device is inside an active shift window.
- Room uses destructive migration (POC) — a schema change clears local data.
