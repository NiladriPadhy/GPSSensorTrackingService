package com.fieldsurvey.poc.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Fires when the user swipes away one of our ongoing notifications
 * (Android 14+ allows this even for FGS notifications). We re-post the
 * notification immediately, but only if the corresponding service is
 * still running — otherwise we'd resurrect a notification for a service
 * the user already stopped.
 */
class NotificationRedeliveryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getStringExtra(NotificationIds.EXTRA_WHICH)) {
            NotificationIds.WHICH_APP -> AppForegroundService.refreshNotification()
            NotificationIds.WHICH_TRACKING -> LocationTrackingService.refreshNotification()
        }
    }
}
