package com.fieldsurvey.poc.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per calendar date that has any tracking activity.
 * `dateKey` is `YYYY-MM-DD` in the device's local time zone.
 */
@Entity(tableName = "day_logs")
data class DayLog(
    @PrimaryKey val dateKey: String,
    val totalDistanceMeters: Double = 0.0,
    val firstFixUtcMillis: Long? = null,
    val lastFixUtcMillis: Long? = null
)

@Entity(
    tableName = "location_points",
    indices = [
        Index("dateKey"),
        Index("timestampUtcMillis")
    ]
)
data class LocationPoint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dateKey: String,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val speedMps: Float?,
    val speedAccuracyMps: Float? = null,
    val bearingDeg: Float? = null,
    val bearingAccuracyDeg: Float? = null,
    val timestampUtcMillis: Long
)
