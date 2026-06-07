package com.fieldsurvey.poc.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.fieldsurvey.poc.tracking.AccuracyMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/**
 * Shift hour configuration: minutes-of-day (0..1439) for start and end.
 * `enabled` flips automatic tracking on/off.
 */
data class ShiftSettings(
    val enabled: Boolean,
    val startMinuteOfDay: Int,   // e.g. 9*60 = 540 for 09:00
    val endMinuteOfDay: Int,     // e.g. 21*60 = 1260 for 21:00
    val accuracy: AccuracyMode = AccuracyMode.DEFAULT,
    val retentionDays: Int = DEFAULT_RETENTION_DAYS
) {
    val startHour: Int get() = startMinuteOfDay / 60
    val startMinute: Int get() = startMinuteOfDay % 60
    val endHour: Int get() = endMinuteOfDay / 60
    val endMinute: Int get() = endMinuteOfDay % 60

    /** Treats end < start as overnight (e.g. 22:00 -> 06:00). */
    fun isWithinShift(hour: Int, minute: Int): Boolean {
        val nowMin = hour * 60 + minute
        return if (startMinuteOfDay <= endMinuteOfDay) {
            nowMin in startMinuteOfDay until endMinuteOfDay
        } else {
            nowMin >= startMinuteOfDay || nowMin < endMinuteOfDay
        }
    }

    companion object {
        const val DEFAULT_RETENTION_DAYS = 7
        val RETENTION_OPTIONS = listOf(1, 3, 7, 10, 15, 30)
    }
}

class SettingsRepository(private val context: Context) {

    private object Keys {
        val ENABLED = booleanPreferencesKey("enabled")
        val START = intPreferencesKey("start_minute")
        val END = intPreferencesKey("end_minute")
        val ACCURACY = stringPreferencesKey("accuracy_mode")
        val RETENTION = intPreferencesKey("retention_days")
    }

    val flow: Flow<ShiftSettings> = context.dataStore.data.map { prefs ->
        ShiftSettings(
            enabled = prefs[Keys.ENABLED] ?: false,
            startMinuteOfDay = prefs[Keys.START] ?: (9 * 60),
            endMinuteOfDay = prefs[Keys.END] ?: (21 * 60),
            accuracy = AccuracyMode.fromName(prefs[Keys.ACCURACY]),
            retentionDays = prefs[Keys.RETENTION] ?: ShiftSettings.DEFAULT_RETENTION_DAYS
        )
    }

    suspend fun setEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.ENABLED] = enabled }
    }

    suspend fun setShiftWindow(startMinute: Int, endMinute: Int) {
        context.dataStore.edit {
            it[Keys.START] = startMinute
            it[Keys.END] = endMinute
        }
    }

    suspend fun setAccuracy(mode: AccuracyMode) {
        context.dataStore.edit { it[Keys.ACCURACY] = mode.name }
    }

    suspend fun setRetentionDays(days: Int) {
        context.dataStore.edit { it[Keys.RETENTION] = days }
    }
}
