package com.fieldsurvey.poc.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.fieldsurvey.poc.MainActivity
import com.fieldsurvey.poc.R
import com.fieldsurvey.poc.FieldSurveyApp
import com.fieldsurvey.poc.data.AppDatabase
import com.fieldsurvey.poc.data.DayLog
import com.fieldsurvey.poc.data.LocationPoint
import com.fieldsurvey.poc.logging.AppLog
import com.fieldsurvey.poc.tracking.AccuracyMode
import com.fieldsurvey.poc.tracking.Bearing
import com.fieldsurvey.poc.tracking.DateKeys
import com.fieldsurvey.poc.tracking.GpsFilter
import com.fieldsurvey.poc.tracking.Haversine
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foreground service that captures location during a configured shift window.
 * Independent of [AppForegroundService]: this one starts/stops when the
 * shift alarm fires (or when the user toggles settings inside the window),
 * while the app FGS runs all day.
 *
 * All data is bucketed by `dateKey` (`YYYY-MM-DD` local time). When midnight
 * crosses, new fixes silently flow into the next day's [DayLog].
 */
class LocationTrackingService : LifecycleService() {

    companion object {
        private const val ACTION_START = "com.fieldsurvey.poc.tracking.START"

        private const val MOVING_SPEED_MPS = 1.0f

        // ---- Vertex (route-point) save policy ----
        private const val SAVE_ON_TIME_MS = 60_000L          // bound straight segments by time
        private const val SAVE_ON_DISTANCE_M = 120.0         // ...and by distance
        private const val TURN_BEARING_DEG = 20f             // heading change that counts as a turn

        // ---- Bearing reliability gates ----
        // GPS-reported bearing is only trustworthy above this speed; below it we
        // derive heading from positions, and only when the leg is long enough to
        // be meaningful (avoids spinning the heading on stationary jitter).
        private const val BEARING_TRUST_SPEED_MPS = 1.5f
        private const val BEARING_MIN_LEG_M = 6.0

        // ---- Adaptive sampling ----
        // Keep sampling at the fast "moving" rate for this long after the last
        // detected motion, so a stop-and-go (waiting at a signal, then turning)
        // is never missed by an early back-off to the slow stationary rate.
        private const val MOTION_GRACE_MS = 90_000L

        @Volatile private var instance: LocationTrackingService? = null

        /**
         * Live ground speed (metres/second) of the most recent fix while the
         * tracking service is running, or null when not tracking. Exposed so
         * the UI can show a live speedometer. Set on every fix and cleared in
         * onDestroy().
         */
        private val _currentSpeedMps = MutableStateFlow<Float?>(null)
        val currentSpeedMps: StateFlow<Float?> = _currentSpeedMps

        fun start(ctx: Context) {
            val i = Intent(ctx, LocationTrackingService::class.java).setAction(ACTION_START)
            // Guard against ForegroundServiceStartNotAllowedException: the shift
            // alarm / boot receiver may invoke this from the background on
            // Android 12+. The always-on app FGS normally keeps us eligible, but
            // never let a denied start crash the caller — log and move on; the
            // next onResume / alarm / boot will retry.
            runCatching { ContextCompat.startForegroundService(ctx, i) }
                .onFailure {
                    AppLog.log("SERVICE", "start() blocked: ${it.javaClass.simpleName} ${it.message}")
                }
        }

        /**
         * Stops the tracking service cleanly. Uses [Context.stopService] directly
         * rather than delivering an ACTION_STOP intent so we never create the
         * service just to tear it down (which on Android 12+ risks an
         * IllegalStateException / FGS deadline crash when the app is
         * transitioning between foreground/background).
         */
        fun stop(ctx: Context) {
            val i = Intent(ctx, LocationTrackingService::class.java)
            runCatching { ctx.stopService(i) }
        }

        /** Called by [NotificationRedeliveryReceiver] when user swipes away the notif. */
        fun refreshNotification() {
            instance?.postNotification()
        }

        /**
         * Clears today's in-memory tracking state on the running service so a
         * user-initiated "reset today's data" doesn't keep accumulating onto
         * stale counters / anchor. No-op when the service isn't running (then
         * only the DB + log files are cleared by the caller).
         */
        fun resetTodayData() {
            instance?.resetTodayDataInternal()
        }
    }

    private val client by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val notificationManager by lazy {
        getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    }
    private val timeFormat by lazy { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    /** Rebuilt whenever [currentAccuracy] changes so filter thresholds stay in sync. */
    private var filter: GpsFilter = GpsFilter(maxAccuracyMeters = AccuracyMode.DEFAULT.maxAccuracyMeters)
    private var currentAccuracy: AccuracyMode = AccuracyMode.DEFAULT
    private var currentIntervalMs: Long = AccuracyMode.DEFAULT.stationaryIntervalMs

    private var lastAcceptedLat: Double? = null
    private var lastAcceptedLon: Double? = null
    private var lastAcceptedTimeMs: Long = 0
    private var lastAcceptedSaved: Boolean = false

    private var lastSavedLat: Double? = null
    private var lastSavedLon: Double? = null
    private var lastSavedBearingDeg: Float? = null
    private var lastSavedTimeMs: Long = 0

    // Motion state for adaptive sampling and stop/start vertex capture.
    private var lastMotionTimeMs: Long = 0
    private var wasMoving: Boolean = false
    private var seeded: Boolean = false

    // Live notification counters — scoped to currentDayKey, reset on rollover.
    private var currentDayKey: String = DateKeys.today()
    @Volatile private var totalDistanceMetersToday: Double = 0.0
    @Volatile private var acceptedFixesToday: Int = 0
    @Volatile private var savedPointsToday: Int = 0
    @Volatile private var lastFixUtcMillis: Long? = null
    @Volatile private var lastTurnUtcMillis: Long? = null
    @Volatile private var lastSpeedMps: Float? = null

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            // IMPORTANT: process EVERY fix in the batch, not just lastLocation.
            // The OS may deliver several fixes at once; taking only the last one
            // discards the intermediate points that define the shape of a turn,
            // collapsing the corner into a straight chord and under-counting
            // distance. Iterating result.locations preserves turn geometry.
            val locs = result.locations
            if (locs.isEmpty()) {
                result.lastLocation?.let { handleLocation(it) }
                return
            }
            if (locs.size > 1) {
                AppLog.log("BATCH", "Received batch of ${locs.size} fixes")
            }
            locs.sortedBy { it.time }.forEach { handleLocation(it) }
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // We always go straight to foreground tracking. Stops are delivered
        // via Context.stopService(), not via an intent action, so we never
        // create the service just to tear it down.
        startTracking()
        return START_STICKY
    }

    private fun startTracking() {
        startForegroundCompat()
        if (!hasFineLocationPermission()) {
            AppLog.log("SERVICE", "Start aborted — FINE location permission missing")
            stopSelf()
            return
        }
        AppLog.log("SERVICE", "Tracking service starting")
        seedFromTodayLog()
        // Show the live-speed card as soon as tracking is active (0 km/h until
        // the first speed-bearing fix), so it appears immediately and stays put
        // instead of popping in only after a fix that happens to report speed.
        _currentSpeedMps.value = lastSpeedMps ?: 0f
        // Arm the motion-grace window so the very first request samples at the
        // fast rate and we get a prompt initial fix (otherwise a cold start
        // would wait a full stationary interval for the first location).
        lastMotionTimeMs = System.currentTimeMillis()
        // Pull the latest accuracy mode from settings; this also runs on every
        // restart so that a user-facing change in SettingsScreen takes effect
        // immediately (SettingsViewModel just calls start() again).
        applyLatestAccuracyMode()
    }

    private fun applyLatestAccuracyMode() {
        val app = applicationContext as? FieldSurveyApp ?: return
        lifecycleScope.launch {
            val mode = app.settings.flow.first().accuracy
            if (mode != currentAccuracy) {
                AppLog.log("ACCURACY", "Mode changed ${currentAccuracy.label} -> ${mode.label}")
                currentAccuracy = mode
                filter = GpsFilter(maxAccuracyMeters = mode.maxAccuracyMeters)
            }
            // Start fast (within the armed grace window) for a prompt first fix;
            // handleLocation's adaptive logic backs off to stationary when idle.
            requestLocation(currentAccuracy.movingIntervalMs)
        }
    }

    /**
     * Zeroes all per-day counters and motion/anchor state for the current day,
     * then refreshes the notification. The caller separately clears the DB rows
     * and log file; this keeps the live service from continuing to accumulate
     * onto stale in-memory values.
     */
    private fun resetTodayDataInternal() {
        currentDayKey = DateKeys.today()
        totalDistanceMetersToday = 0.0
        acceptedFixesToday = 0
        savedPointsToday = 0
        lastFixUtcMillis = null
        lastTurnUtcMillis = null
        lastSavedLat = null
        lastSavedLon = null
        lastSavedBearingDeg = null
        lastSavedTimeMs = 0
        lastAcceptedLat = null
        lastAcceptedLon = null
        lastAcceptedTimeMs = 0
        lastAcceptedSaved = false
        wasMoving = false
        filter.reset()
        postNotification()
    }

    private fun seedFromTodayLog() {
        // Seed in-memory notification counters from the DB exactly once per
        // service instance. Re-running on every start() (e.g. each settings
        // change) would race with concurrent handleLocation() increments and
        // could momentarily regress the live counters. The DB itself stays
        // authoritative via SQL increments regardless.
        if (seeded) return
        seeded = true
        val today = DateKeys.today()
        currentDayKey = today
        val db = AppDatabase.get(this)
        lifecycleScope.launch {
            db.dayLogDao().insertIfMissing(DayLog(dateKey = today))
            val log = db.dayLogDao().byDate(today)
            totalDistanceMetersToday = log?.totalDistanceMeters ?: 0.0
            savedPointsToday = db.locationPointDao().countForDate(today)
            acceptedFixesToday = savedPointsToday
            postNotification()
        }
    }

    private fun handleLocation(loc: Location) {
        AppLog.log(
            "FIX",
            "lat=%.6f lon=%.6f acc=%.0fm spd=%s brg=%s".format(
                Locale.US, loc.latitude, loc.longitude, loc.accuracy,
                if (loc.hasSpeed()) "%.1f".format(Locale.US, loc.speed) else "-",
                if (loc.hasBearing()) "%.0f".format(Locale.US, loc.bearing) else "-"
            )
        )
        // Live speed for the UI / notification. Only update when this fix
        // actually reports a speed — many fused/network fixes omit it, and
        // nulling on those caused the Home speed card to flicker on/off. When a
        // fix lacks speed we keep the last known value; the flow is cleared to
        // null only when the service stops (onDestroy).
        if (loc.hasSpeed()) {
            lastSpeedMps = loc.speed
            _currentSpeedMps.value = loc.speed
        }
        val decision = filter.evaluate(loc)
        if (!decision.accepted) {
            AppLog.log("REJECT", decision.reason ?: "filtered")
            return
        }

        // Reset per-day counters at midnight rollover.
        val dayKey = DateKeys.forMillis(loc.time)
        if (dayKey != currentDayKey) {
            AppLog.log("ROLLOVER", "Day changed $currentDayKey -> $dayKey, counters reset")
            currentDayKey = dayKey
            totalDistanceMetersToday = 0.0
            acceptedFixesToday = 0
            savedPointsToday = 0
            lastSavedLat = null
            lastSavedLon = null
            lastSavedBearingDeg = null
            lastAcceptedSaved = false
            wasMoving = false
            purgeExpiredData()
        }

        totalDistanceMetersToday += decision.deltaMeters
        acceptedFixesToday += 1
        lastFixUtcMillis = loc.time
        AppLog.log(
            "ACCEPT",
            "+%.1fm total=%.0fm fixes=%d".format(
                Locale.US, decision.deltaMeters, totalDistanceMetersToday, acceptedFixesToday
            )
        )

        val db = AppDatabase.get(this)
        lifecycleScope.launch {
            db.dayLogDao().insertIfMissing(DayLog(dateKey = dayKey))
            db.dayLogDao().addDistance(dayKey, decision.deltaMeters, loc.time)
        }

        // ---- Motion state (reported speed, falling back to displacement) ----
        val now = loc.time
        val prevLat = lastAcceptedLat
        val prevLon = lastAcceptedLon
        val prevTimeMs = lastAcceptedTimeMs

        val reportedSpeed = if (loc.hasSpeed()) loc.speed.toDouble() else 0.0
        val derivedSpeed = if (prevTimeMs > 0 && now > prevTimeMs)
            decision.deltaMeters / ((now - prevTimeMs) / 1000.0) else 0.0
        val effectiveSpeed = maxOf(reportedSpeed, derivedSpeed)
        val moving = effectiveSpeed >= MOVING_SPEED_MPS
        if (moving) lastMotionTimeMs = now
        val stopEvent = wasMoving && !moving      // just came to rest
        val startEvent = !wasMoving && moving     // just started moving

        // ---- Heading of the current leg (previous accepted fix -> this fix) ----
        val legBearing = computeLegBearing(loc, prevLat, prevLon, decision.deltaMeters)

        // ---- Decide whether to persist this fix as a route vertex ----
        var saveCurrent = false
        var alsoSavePrevious = false
        var isTurn = false
        var reason = ""

        if (lastSavedLat == null || lastSavedLon == null) {
            saveCurrent = true
            reason = "first"
        } else {
            // Establish the heading reference as soon as we have one, so turns
            // are detected from the very first leg (previously the reference
            // stayed null until the first time-based save, blinding turn
            // detection for up to a minute after start).
            if (lastSavedBearingDeg == null && legBearing != null) {
                lastSavedBearingDeg = legBearing
            }

            val ref = lastSavedBearingDeg
            val turnDelta = if (ref != null && legBearing != null) Bearing.delta(legBearing, ref) else 0f
            val timeSinceSave = now - lastSavedTimeMs
            val distSinceSave = Haversine.distanceMeters(
                lastSavedLat!!, lastSavedLon!!, loc.latitude, loc.longitude
            )

            when {
                turnDelta >= TURN_BEARING_DEG -> {
                    saveCurrent = true
                    isTurn = true
                    // The previous accepted fix is the corner apex; persist it
                    // too (if not already saved) so the polyline bends sharply
                    // at the corner instead of cutting across it.
                    alsoSavePrevious = !lastAcceptedSaved
                    reason = "turn ${turnDelta.toInt()}°"
                }
                stopEvent -> { saveCurrent = true; reason = "stop" }
                startEvent -> { saveCurrent = true; reason = "start" }
                timeSinceSave >= SAVE_ON_TIME_MS -> { saveCurrent = true; reason = "time" }
                distSinceSave >= SAVE_ON_DISTANCE_M -> { saveCurrent = true; reason = "dist" }
            }
        }

        if (saveCurrent) {
            if (alsoSavePrevious && prevLat != null && prevLon != null) {
                persistPoint(dayKey, prevLat, prevLon, 0f, null, null, null, null, prevTimeMs)
            }
            persistPoint(
                dateKey = dayKey,
                lat = loc.latitude,
                lon = loc.longitude,
                accuracy = loc.accuracy,
                speedMps = if (loc.hasSpeed()) loc.speed else null,
                speedAccuracyMps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    loc.hasSpeedAccuracy()) loc.speedAccuracyMetersPerSecond else null,
                bearingDeg = if (loc.hasBearing()) loc.bearing else null,
                bearingAccuracyDeg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    loc.hasBearingAccuracy()) loc.bearingAccuracyDegrees else null,
                timeMs = now
            )
            lastSavedLat = loc.latitude
            lastSavedLon = loc.longitude
            lastSavedTimeMs = now
            if (legBearing != null) lastSavedBearingDeg = legBearing
            lastAcceptedSaved = true
            if (isTurn) {
                lastTurnUtcMillis = now
                AppLog.log("TURN", "Heading change — vertex + apex saved ($reason)")
            } else {
                AppLog.log("SAVE", "Vertex saved ($reason, pts=$savedPointsToday)")
            }
        } else {
            lastAcceptedSaved = false
        }

        lastAcceptedLat = loc.latitude
        lastAcceptedLon = loc.longitude
        lastAcceptedTimeMs = now
        wasMoving = moving

        postNotification()

        // ---- Adaptive sampling: stay fast for a grace window after motion ----
        val withinGrace = (now - lastMotionTimeMs) <= MOTION_GRACE_MS
        val targetInterval = if (withinGrace) currentAccuracy.movingIntervalMs
        else currentAccuracy.stationaryIntervalMs
        if (targetInterval != currentIntervalMs && hasFineLocationPermission()) {
            AppLog.log("INTERVAL", "${if (withinGrace) "active" else "idle"} → ${targetInterval / 1000}s")
            requestLocation(targetInterval)
        }
    }

    /**
     * Heading (degrees) of the leg from (prevLat, prevLon) to [loc], or null
     * when it can't be trusted: no previous fix, or moving too slowly over too
     * short a leg for the bearing to be meaningful. Prefers the GPS-reported
     * course at speed, otherwise derives it from the two positions.
     */
    private fun computeLegBearing(
        loc: Location,
        prevLat: Double?,
        prevLon: Double?,
        legMeters: Double
    ): Float? {
        if (prevLat == null || prevLon == null) return null
        if (loc.hasSpeed() && loc.speed >= BEARING_TRUST_SPEED_MPS && loc.hasBearing()) {
            return loc.bearing
        }
        if (legMeters >= BEARING_MIN_LEG_M) {
            return Bearing.between(prevLat, prevLon, loc.latitude, loc.longitude)
        }
        return null
    }

    /** Fire-and-forget retention purge triggered at midnight rollover. */
    private fun purgeExpiredData() {
        val app = applicationContext as? FieldSurveyApp ?: return
        lifecycleScope.launch {
            com.fieldsurvey.poc.data.RetentionManager.purge(app)
        }
    }

    private fun persistPoint(
        dateKey: String,
        lat: Double,
        lon: Double,
        accuracy: Float,
        speedMps: Float?,
        speedAccuracyMps: Float?,
        bearingDeg: Float?,
        bearingAccuracyDeg: Float?,
        timeMs: Long
    ) {
        savedPointsToday += 1
        val db = AppDatabase.get(this)
        lifecycleScope.launch {
            db.locationPointDao().insert(
                LocationPoint(
                    dateKey = dateKey,
                    latitude = lat,
                    longitude = lon,
                    accuracyMeters = accuracy,
                    speedMps = speedMps,
                    speedAccuracyMps = speedAccuracyMps,
                    bearingDeg = bearingDeg,
                    bearingAccuracyDeg = bearingAccuracyDeg,
                    timestampUtcMillis = timeMs
                )
            )
        }
    }

    private fun requestLocation(intervalMs: Long) {
        currentIntervalMs = intervalMs
        if (!hasFineLocationPermission()) return
        val request = LocationRequest.Builder(currentAccuracy.priority, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            // No batching: deliver each fix as soon as it's available so we never
            // coalesce away the intermediate points that define a turn.
            .setMaxUpdateDelayMillis(0)
            .setWaitForAccurateLocation(false)
            .build()
        runCatching { client.removeLocationUpdates(callback) }
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        AppLog.log(
            "REQUEST",
            "${currentAccuracy.label} priority=${currentAccuracy.priority} interval=${intervalMs / 1000}s"
        )
        postNotification()
    }

    private fun hasFineLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    // ---- Notification --------------------------------------------------------------------

    private fun startForegroundCompat() {
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NotificationIds.ID_TRACKING,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NotificationIds.ID_TRACKING, notif)
        }
    }

    private fun postNotification() {
        notificationManager.notify(NotificationIds.ID_TRACKING, buildNotification())
    }

    private fun buildNotification(): Notification {
        val tap = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val deleteIntent = PendingIntent.getBroadcast(
            this, 200,
            Intent(this, NotificationRedeliveryReceiver::class.java)
                .putExtra(NotificationIds.EXTRA_WHICH, NotificationIds.WHICH_TRACKING),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val km = totalDistanceMetersToday / 1000.0
        val moving = currentIntervalMs == currentAccuracy.movingIntervalMs
        val mode = if (moving) "moving" else "stationary"
        val lastFix = lastFixUtcMillis?.let { timeFormat.format(Date(it)) } ?: "—"
        val lastTurn = lastTurnUtcMillis?.let { timeFormat.format(Date(it)) } ?: "—"
        val speedKmh = lastSpeedMps?.let { it * 3.6f }
        val speedStr = speedKmh?.let { "%.0f km/h".format(Locale.US, it) } ?: "— km/h"

        val title = "Tracking · $speedStr"
        val short = "%.2f km today · %d pts · %s".format(Locale.US, km, savedPointsToday, mode)
        val long = """
            Speed: %s
            Distance: %.2f km
            Saved route points: %d
            Accepted GPS fixes: %d
            Mode: %s (every %d s)
            Last fix: %s
            Last turn: %s
        """.trimIndent().format(
            Locale.US,
            speedStr,
            km,
            savedPointsToday,
            acceptedFixesToday,
            mode,
            currentIntervalMs / 1000,
            lastFix,
            lastTurn
        )

        return NotificationCompat.Builder(this, NotificationIds.CHANNEL_TRACKING)
            .setSmallIcon(R.drawable.ic_location)
            .setContentTitle(title)
            .setContentText(short)
            .setStyle(NotificationCompat.BigTextStyle().bigText(long))
            .setContentIntent(tap)
            .setDeleteIntent(deleteIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (notificationManager.getNotificationChannel(NotificationIds.CHANNEL_TRACKING) != null) return
        notificationManager.createNotificationChannel(
            NotificationChannel(
                NotificationIds.CHANNEL_TRACKING,
                getString(R.string.tracking_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.tracking_channel_desc)
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
        )
    }

    override fun onDestroy() {
        AppLog.log("SERVICE", "Tracking service stopped")
        runCatching { client.removeLocationUpdates(callback) }
        _currentSpeedMps.value = null
        if (instance === this) instance = null
        super.onDestroy()
    }
}
