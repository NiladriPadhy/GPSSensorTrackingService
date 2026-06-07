package com.fieldsurvey.poc.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.fieldsurvey.poc.MainActivity
import com.fieldsurvey.poc.R
import com.fieldsurvey.poc.logging.AppLog

/**
 * Lightweight, always-on foreground service that keeps the app process alive
 * across the day so that:
 *  - shift alarms are reliably delivered,
 *  - the user always sees an indicator that the app is installed and ready,
 *  - the OS does not aggressively cache-trim the process.
 *
 * This service does NOT track location; that responsibility belongs to
 * [LocationTrackingService] and is only active during shift hours.
 */
class AppForegroundService : Service() {

    companion object {
        const val ACTION_START = "com.fieldsurvey.poc.app_fgs.START"

        @Volatile private var instance: AppForegroundService? = null

        fun start(ctx: Context) {
            val i = Intent(ctx, AppForegroundService::class.java).setAction(ACTION_START)
            // Never let a background-start restriction (Android 12+) crash the
            // caller — boot/alarm/app-create all funnel through here.
            runCatching { ContextCompat.startForegroundService(ctx, i) }
        }

        /** Called by [NotificationRedeliveryReceiver] when the user swipes away the notif. */
        fun refreshNotification() {
            instance?.let { it.postNotification() }
        }
    }

    private val notificationManager by lazy {
        getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    }

    /**
     * Partial wake lock held for the whole lifetime of this always-on service.
     * A foreground service keeps the process alive but does NOT, by itself,
     * guarantee the CPU stays powered during deep Doze — a partial wake lock
     * does (screen and keyboard may still turn off). Held here, on the
     * always-on service, so the CPU is available across the entire day for
     * alarm delivery and for the tracking service when it runs.
     */
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        ensureChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        // Re-acquire defensively in case a previous lock was released (e.g.
        // after a process restart that reused this instance).
        acquireWakeLock()
        return START_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        if (instance === this) instance = null
        super.onDestroy()
    }

    @Suppress("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        // No timeout: this is a deliberately long-lived lock tied to the
        // service lifecycle and released in onDestroy(). The persistent
        // foreground notification keeps it user-visible and accountable.
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "FieldSurvey:AppForegroundService"
        ).apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
        AppLog.log("WAKELOCK", "Partial wake lock acquired")
    }

    private fun releaseWakeLock() {
        wakeLock?.let { wl ->
            if (wl.isHeld) runCatching { wl.release() }
            AppLog.log("WAKELOCK", "Partial wake lock released")
        }
        wakeLock = null
    }

    private fun startForegroundCompat() {
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NotificationIds.ID_APP_FOREGROUND,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // FGS type still required from Android 10; specialUse exists since 14, so
            // fall back to no-type for 10..13 (manifest declares it for newer).
            startForeground(NotificationIds.ID_APP_FOREGROUND, notif)
        } else {
            startForeground(NotificationIds.ID_APP_FOREGROUND, notif)
        }
    }

    private fun postNotification() {
        notificationManager.notify(NotificationIds.ID_APP_FOREGROUND, buildNotification())
    }

    private fun buildNotification(): Notification {
        val tap = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val deleteIntent = PendingIntent.getBroadcast(
            this, 100,
            Intent(this, NotificationRedeliveryReceiver::class.java)
                .putExtra(NotificationIds.EXTRA_WHICH, NotificationIds.WHICH_APP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, NotificationIds.CHANNEL_APP)
            .setSmallIcon(R.drawable.ic_location)
            .setContentTitle("Field Survey")
            .setContentText("Ready — shift tracking will start automatically.")
            .setContentIntent(tap)
            .setDeleteIntent(deleteIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (notificationManager.getNotificationChannel(NotificationIds.CHANNEL_APP) != null) return
        notificationManager.createNotificationChannel(
            NotificationChannel(
                NotificationIds.CHANNEL_APP,
                "App status",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Persistent indicator that Field Survey is running."
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
        )
    }
}
