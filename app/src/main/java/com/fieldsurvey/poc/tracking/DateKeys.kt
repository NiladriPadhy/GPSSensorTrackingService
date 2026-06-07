package com.fieldsurvey.poc.tracking

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Local-calendar day keys (`YYYY-MM-DD`) used to bucket tracking data.
 * A day starts at 00:00:00 and ends at 23:59:59 in the device's local time zone.
 */
object DateKeys {

    private val fmt: SimpleDateFormat
        get() = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun forMillis(utcMillis: Long): String = fmt.format(Date(utcMillis))

    fun today(): String = forMillis(System.currentTimeMillis())

    /** Date key for [n] days before today (local time). `daysAgo(0)` == today. */
    fun daysAgo(n: Int): String {
        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -n) }
        return forMillis(cal.timeInMillis)
    }

    /** Returns epoch millis for the start of [dateKey] in local time. */
    fun startOfDayMillis(dateKey: String): Long {
        val parsed = fmt.parse(dateKey) ?: return 0L
        val cal = Calendar.getInstance().apply {
            time = parsed
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }
}
