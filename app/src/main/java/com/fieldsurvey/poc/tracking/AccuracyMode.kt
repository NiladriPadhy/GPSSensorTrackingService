package com.fieldsurvey.poc.tracking

import com.google.android.gms.location.Priority

/**
 * User-selectable accuracy / power tradeoff for the location tracking service.
 *
 * Each mode bundles three knobs:
 *  - [priority]              — FusedLocationProvider request priority
 *  - [movingIntervalMs]      — sample period while the device is moving (≥1 m/s)
 *  - [stationaryIntervalMs]  — sample period while the device is stationary
 *  - [maxAccuracyMeters]     — fixes worse than this are rejected by [GpsFilter]
 *
 * [MEDIUM] is the default.
 */
enum class AccuracyMode(
    val label: String,
    val priority: Int,
    val movingIntervalMs: Long,
    val stationaryIntervalMs: Long,
    val maxAccuracyMeters: Float
) {
    HIGH(
        label = "High",
        priority = Priority.PRIORITY_HIGH_ACCURACY,
        movingIntervalMs = 4_000L,              // 4 s
        stationaryIntervalMs = 20_000L,         // 20 s
        maxAccuracyMeters = 20f
    ),
    MEDIUM(
        label = "Medium",
        priority = Priority.PRIORITY_HIGH_ACCURACY,
        movingIntervalMs = 6_000L,              // 6 s
        stationaryIntervalMs = 30_000L,         // 30 s
        maxAccuracyMeters = 30f
    ),
    LOW(
        label = "Low",
        priority = Priority.PRIORITY_BALANCED_POWER_ACCURACY,
        movingIntervalMs = 15_000L,             // 15 s
        stationaryIntervalMs = 60_000L,         // 60 s
        maxAccuracyMeters = 55f
    );

    companion object {
        val DEFAULT: AccuracyMode = MEDIUM

        fun fromName(name: String?): AccuracyMode =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
