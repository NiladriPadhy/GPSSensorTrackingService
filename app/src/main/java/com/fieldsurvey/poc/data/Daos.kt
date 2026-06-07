package com.fieldsurvey.poc.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DayLogDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfMissing(log: DayLog): Long

    @Query("SELECT * FROM day_logs WHERE dateKey = :dateKey")
    suspend fun byDate(dateKey: String): DayLog?

    @Query("SELECT * FROM day_logs WHERE dateKey = :dateKey")
    fun byDateFlow(dateKey: String): Flow<DayLog?>

    @Query("SELECT dateKey FROM day_logs ORDER BY dateKey DESC")
    suspend fun allDates(): List<String>

    @Query(
        """
        UPDATE day_logs
           SET totalDistanceMeters = totalDistanceMeters + :deltaMeters,
               lastFixUtcMillis    = :timestampUtcMillis,
               firstFixUtcMillis   = COALESCE(firstFixUtcMillis, :timestampUtcMillis)
         WHERE dateKey = :dateKey
        """
    )
    suspend fun addDistance(dateKey: String, deltaMeters: Double, timestampUtcMillis: Long)

    @Query("DELETE FROM day_logs WHERE dateKey < :cutoffDateKey")
    suspend fun deleteOlderThan(cutoffDateKey: String): Int

    @Query("DELETE FROM day_logs WHERE dateKey = :dateKey")
    suspend fun deleteForDate(dateKey: String): Int
}

@Dao
interface LocationPointDao {

    @Insert
    suspend fun insert(point: LocationPoint): Long

    @Query("SELECT * FROM location_points WHERE dateKey = :dateKey ORDER BY timestampUtcMillis ASC")
    suspend fun pointsForDate(dateKey: String): List<LocationPoint>

    @Query("SELECT * FROM location_points WHERE dateKey = :dateKey ORDER BY timestampUtcMillis ASC")
    fun pointsForDateFlow(dateKey: String): Flow<List<LocationPoint>>

    @Query("SELECT COUNT(*) FROM location_points WHERE dateKey = :dateKey")
    suspend fun countForDate(dateKey: String): Int

    @Query("DELETE FROM location_points WHERE dateKey < :cutoffDateKey")
    suspend fun deleteOlderThan(cutoffDateKey: String): Int

    @Query("DELETE FROM location_points WHERE dateKey = :dateKey")
    suspend fun deleteForDate(dateKey: String): Int
}
