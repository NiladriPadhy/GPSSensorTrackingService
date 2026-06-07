package com.fieldsurvey.poc.data

import com.fieldsurvey.poc.FieldSurveyApp
import com.fieldsurvey.poc.logging.AppLog
import com.fieldsurvey.poc.tracking.DateKeys
import kotlinx.coroutines.flow.first

/**
 * Enforces the user-configured data-retention window. Deletes day logs,
 * location points and activity-log files whose date is older than the
 * retention horizon (today minus retentionDays-1, so a 7-day setting keeps
 * today plus the previous six days).
 */
object RetentionManager {

    suspend fun purge(app: FieldSurveyApp) {
        val days = app.settings.flow.first().retentionDays
        val cutoff = DateKeys.daysAgo(days - 1) // keep [cutoff .. today]

        val removedLogs = app.database.dayLogDao().deleteOlderThan(cutoff)
        val removedPoints = app.database.locationPointDao().deleteOlderThan(cutoff)
        AppLog.deleteOlderThan(cutoff)

        AppLog.log(
            "RETENTION",
            "Purged before $cutoff (keep ${days}d): $removedLogs day logs, $removedPoints points, old log files removed"
        )
    }
}
