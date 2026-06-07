package com.fieldsurvey.poc.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.fieldsurvey.poc.data.ShiftSettings
import java.util.Calendar

/**
 * Schedules daily start/stop alarms based on a [ShiftSettings] window.
 *
 * Uses inexact repeating alarms (setRepeating) for POC simplicity. For exact
 * to-the-minute scheduling on Android 12+, the SCHEDULE_EXACT_ALARM permission
 * would also be required.
 */
object ShiftScheduler {

    const val EXTRA_PHASE = "phase"
    const val PHASE_START = "start"
    const val PHASE_STOP = "stop"

    private const val REQ_START = 1001
    private const val REQ_STOP = 1002

    fun apply(context: Context, settings: ShiftSettings) {
        cancel(context)
        if (!settings.enabled) return

        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val startAt = nextOccurrence(settings.startHour, settings.startMinute)
        val stopAt = nextOccurrence(settings.endHour, settings.endMinute)

        am.setRepeating(
            AlarmManager.RTC_WAKEUP,
            startAt,
            AlarmManager.INTERVAL_DAY,
            pendingIntent(context, REQ_START, PHASE_START)
        )
        am.setRepeating(
            AlarmManager.RTC_WAKEUP,
            stopAt,
            AlarmManager.INTERVAL_DAY,
            pendingIntent(context, REQ_STOP, PHASE_STOP)
        )
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent(context, REQ_START, PHASE_START))
        am.cancel(pendingIntent(context, REQ_STOP, PHASE_STOP))
    }

    private fun pendingIntent(context: Context, requestCode: Int, phase: String): PendingIntent {
        val intent = Intent(context, ShiftAlarmReceiver::class.java).apply {
            putExtra(EXTRA_PHASE, phase)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(context, requestCode, intent, flags)
    }

    private fun nextOccurrence(hour: Int, minute: Int): Long {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        return cal.timeInMillis
    }
}
