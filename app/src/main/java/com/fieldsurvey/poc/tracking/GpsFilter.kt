package com.fieldsurvey.poc.tracking

import android.location.Location

/**
 * Stateful filter that decides whether a fresh GPS [Location] is good enough
 * to count toward distance / be saved as a route point.
 *
 * Three layers of protection against phantom distance:
 *
 *  1. Per-fix gates — reject poor accuracy and physically impossible teleports.
 *
 *  2. GPS Doppler speed gate (primary stationary signal) — a parked device
 *     still wanders several metres in POSITION, but its GPS-reported SPEED stays
 *     near zero. Any fix reporting a speed below [movementSpeedMps] (with a
 *     trustworthy speed-accuracy) is treated as stationary no matter where the
 *     position drifted. This is what stops a phone left untouched on a desk /
 *     parked vehicle from accumulating distance while not moving.
 *
 *  3. Stationary "anchor" clamp (fallback when speed is unavailable) — while
 *     anchored, fixes within a release radius are rejected; a departure beyond
 *     the radius must be CONFIRMED over consecutive fixes before the anchor is
 *     released, so a single drift spike can't "walk" the anchor. Released
 *     distance is measured FROM THE ANCHOR so genuine travel is never lost.
 *
 * The noise floor and release radius scale with the fix's own accuracy.
 */
class GpsFilter(
    private val maxAccuracyMeters: Float = 30f,
    private val minMoveMeters: Double = 6.0,
    private val accuracyNoiseFactor: Double = 0.4,
    private val maxSpeedMps: Double = 60.0,             // ~216 km/h
    private val anchorReleaseMeters: Double = 20.0,
    private val anchorReleaseAccuracyFactor: Double = 1.5,
    private val stopConfirmFixes: Int = 2,
    // Speed at/above which the device is considered moving. A truly stationary
    // GPS reports Doppler speed ~0–0.3 m/s; 0.5 sits above that noise floor yet
    // below a slow walk (~0.9 m/s), so walking is not suppressed.
    private val movementSpeedMps: Float = 0.5f,
    // Only trust the reported speed for the stationary gate when its accuracy is
    // this good or better (when the device provides speed accuracy).
    private val maxTrustedSpeedAccuracyMps: Float = 2.0f,
    // Consecutive beyond-radius fixes required to release the anchor when no
    // trustworthy speed is available to corroborate real movement.
    private val departureConfirmFixes: Int = 2
) {
    private var last: Location? = null
    private var anchor: Location? = null
    private var subFloorCount: Int = 0
    private var departureCount: Int = 0

    data class Decision(
        val accepted: Boolean,
        val deltaMeters: Double,
        val reason: String? = null
    )

    fun evaluate(loc: Location): Decision {
        if (loc.accuracy > maxAccuracyMeters) {
            return Decision(false, 0.0, "low accuracy ${loc.accuracy.toInt()}m > ${maxAccuracyMeters.toInt()}m")
        }

        val prev = last
        if (prev == null) {
            // Assume stationary until movement is proven, so an initial GPS
            // wander burst can't be counted as travel.
            last = loc
            anchor = loc
            return Decision(true, 0.0, "first fix")
        }

        val dt = (loc.time - prev.time) / 1000.0
        if (dt <= 0.0) return Decision(false, 0.0, "non-monotonic time")

        // Most reliable motion cue: GPS Doppler speed. When trustworthy, it tells
        // us whether this fix is genuine movement or genuine stillness, no matter
        // how the position wandered.
        val speedTrustworthy = loc.hasSpeed() &&
            (!loc.hasSpeedAccuracy() || loc.speedAccuracyMetersPerSecond <= maxTrustedSpeedAccuracyMps)
        val reportedStationary = speedTrustworthy && loc.speed < movementSpeedMps

        val held = anchor
        if (held != null) {
            // ---- STATIONARY (anchored) ----
            val dFromAnchor = Haversine.distanceMeters(
                held.latitude, held.longitude, loc.latitude, loc.longitude
            )

            // (2) Speed says we're parked → reject regardless of position drift.
            if (reportedStationary) {
                departureCount = 0
                last = loc
                return Decision(
                    false, 0.0,
                    "stationary spd=%.1f (drift %dm)".format(loc.speed, dFromAnchor.toInt())
                )
            }

            // (3) Position-based clamp.
            val release = maxOf(anchorReleaseMeters, loc.accuracy * anchorReleaseAccuracyFactor)
            if (dFromAnchor < release) {
                departureCount = 0
                last = loc
                return Decision(false, 0.0, "stationary ${dFromAnchor.toInt()}m \u2264 ${release.toInt()}m anchor")
            }

            // Beyond the radius. Require confirmation unless speed corroborates
            // real movement, so one drift spike can't release / walk the anchor.
            val anchorSpeed = dFromAnchor / dt
            if (anchorSpeed > maxSpeedMps) {
                return Decision(false, 0.0, "teleport ${anchorSpeed.toInt()} m/s from anchor")
            }
            val speedConfirmsMove = speedTrustworthy && loc.speed >= movementSpeedMps
            departureCount++
            if (!speedConfirmsMove && departureCount < departureConfirmFixes) {
                // Hold the anchor; don't count yet. Wait for the next fix to
                // confirm this is sustained travel and not a momentary spike.
                last = loc
                return Decision(false, 0.0, "departure unconfirmed ${dFromAnchor.toInt()}m")
            }

            // Confirmed departure — release and count from the anchor.
            anchor = null
            subFloorCount = 0
            departureCount = 0
            last = loc
            return Decision(true, dFromAnchor, "departed anchor ${dFromAnchor.toInt()}m")
        }

        // ---- MOVING ----
        val dist = Haversine.distanceMeters(
            prev.latitude, prev.longitude,
            loc.latitude, loc.longitude
        )

        // (2) Speed says we've effectively stopped → start re-anchoring even if
        // the position is still drifting around.
        if (reportedStationary) {
            subFloorCount++
            last = loc
            if (subFloorCount >= stopConfirmFixes) {
                anchor = loc
                subFloorCount = 0
                return Decision(false, 0.0, "stopped (spd=%.1f) \u2014 anchored".format(loc.speed))
            }
            return Decision(false, 0.0, "slow spd=%.1f".format(loc.speed))
        }

        val noiseFloor = maxOf(minMoveMeters, loc.accuracy * accuracyNoiseFactor)
        if (dist < noiseFloor) {
            subFloorCount++
            last = loc
            if (subFloorCount >= stopConfirmFixes) {
                // Sustained lack of movement — drop a fresh anchor here so the
                // stationary clamp takes over.
                anchor = loc
                subFloorCount = 0
                return Decision(false, 0.0, "stopped \u2014 anchored")
            }
            return Decision(false, 0.0, "jitter ${dist.toInt()}m < ${noiseFloor.toInt()}m floor")
        }

        val speed = dist / dt
        if (speed > maxSpeedMps) {
            return Decision(false, 0.0, "teleport ${speed.toInt()} m/s")
        }

        subFloorCount = 0
        last = loc
        return Decision(true, dist)
    }

    fun reset() {
        last = null
        anchor = null
        subFloorCount = 0
        departureCount = 0
    }
}
