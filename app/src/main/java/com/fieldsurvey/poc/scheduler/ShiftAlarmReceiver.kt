package com.fieldsurvey.poc.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.fieldsurvey.poc.service.LocationTrackingService

/**
 * Triggered by [ShiftScheduler]'s daily alarms. Starts or stops the
 * tracking foreground service based on the phase extra.
 */
class ShiftAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getStringExtra(ShiftScheduler.EXTRA_PHASE)) {
            ShiftScheduler.PHASE_START -> LocationTrackingService.start(context)
            ShiftScheduler.PHASE_STOP -> LocationTrackingService.stop(context)
        }
    }
}
