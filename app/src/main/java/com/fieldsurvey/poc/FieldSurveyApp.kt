package com.fieldsurvey.poc

import android.app.Application
import com.fieldsurvey.poc.data.AppDatabase
import com.fieldsurvey.poc.data.RetentionManager
import com.fieldsurvey.poc.data.SettingsRepository
import com.fieldsurvey.poc.logging.AppLog
import com.fieldsurvey.poc.scheduler.ShiftScheduler
import com.fieldsurvey.poc.service.AppForegroundService
import com.fieldsurvey.poc.service.LocationTrackingService
import com.fieldsurvey.poc.tracking.ShiftWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class FieldSurveyApp : Application() {

    val database by lazy { AppDatabase.get(this) }
    val settings by lazy { SettingsRepository(this) }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()

        // Activity logger must be ready before any component logs.
        AppLog.init(this)
        AppLog.log("APP", "Process started")

        // Always-on FGS — runs across the entire day.
        AppForegroundService.start(this)

        appScope.launch {
            // Enforce data retention on every launch.
            runCatching { RetentionManager.purge(this@FieldSurveyApp) }

            // Apply schedule, then start tracking immediately if we're inside the shift right now.
            val s = settings.flow.first()
            ShiftScheduler.apply(this@FieldSurveyApp, s)
            if (ShiftWindow.isNowInsideShift(s)) {
                AppLog.log("APP", "Inside shift window on launch — starting tracking")
                LocationTrackingService.start(this@FieldSurveyApp)
            }
        }
    }
}
