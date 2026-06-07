package com.fieldsurvey.poc.tracking

import com.fieldsurvey.poc.data.ShiftSettings
import java.util.Calendar

/**
 * Pure helper: is the current local clock inside the configured shift window?
 */
object ShiftWindow {

    fun isNowInsideShift(settings: ShiftSettings, now: Calendar = Calendar.getInstance()): Boolean {
        if (!settings.enabled) return false
        return settings.isWithinShift(
            now.get(Calendar.HOUR_OF_DAY),
            now.get(Calendar.MINUTE)
        )
    }
}
