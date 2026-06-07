package com.fieldsurvey.poc.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.fieldsurvey.poc.data.SettingsRepository
import com.fieldsurvey.poc.service.AppForegroundService
import com.fieldsurvey.poc.service.LocationTrackingService
import com.fieldsurvey.poc.tracking.ShiftWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Re-arms shift alarms after device reboot or app update, and restarts the always-on FGS. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        // Always restart the always-on app FGS first.
        AppForegroundService.start(context)

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = SettingsRepository(context).flow.first()
                ShiftScheduler.apply(context, settings)
                if (ShiftWindow.isNowInsideShift(settings)) {
                    LocationTrackingService.start(context)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
