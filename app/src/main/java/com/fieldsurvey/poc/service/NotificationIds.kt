package com.fieldsurvey.poc.service

/**
 * Centralised notification identifiers and channel constants so the two
 * services + the redelivery receiver agree on IDs.
 */
object NotificationIds {
    const val CHANNEL_TRACKING = "tracking"
    const val CHANNEL_APP = "app_status"

    const val ID_APP_FOREGROUND = 4241
    const val ID_TRACKING = 4242

    /** Used by [NotificationRedeliveryReceiver]. */
    const val EXTRA_WHICH = "which"
    const val WHICH_APP = "app"
    const val WHICH_TRACKING = "tracking"
}
