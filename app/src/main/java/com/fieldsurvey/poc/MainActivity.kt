package com.fieldsurvey.poc

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.fieldsurvey.poc.scheduler.ShiftScheduler
import com.fieldsurvey.poc.service.AppForegroundService
import com.fieldsurvey.poc.service.LocationTrackingService
import com.fieldsurvey.poc.tracking.ShiftWindow
import com.fieldsurvey.poc.ui.nav.AppNav
import com.fieldsurvey.poc.ui.theme.FieldSurveyTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Permission flow runs sequentially so the user sees them one at a time:
 *   1. POST_NOTIFICATIONS (Android 13+) — required for FGS notifications
 *   2. ACCESS_FINE_LOCATION (+ coarse)
 *   3. ACCESS_BACKGROUND_LOCATION (Android 10+)
 *   4. Ignore battery optimizations ("Unrestricted") — keeps tracking alive in Doze
 *
 * Each step launches the next on completion. After the chain finishes
 * we re-apply the shift scheduler and start tracking if we're inside the
 * shift window right now (handles "app was killed, user re-opens").
 */
class MainActivity : ComponentActivity() {

    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // The app FGS was started in onCreate, but on Android 13+ its notification
            // would have been silently suppressed if POST_NOTIFICATIONS hadn't been
            // granted yet. Re-start it so onStartCommand fires again and the
            // notification is re-posted under the now-valid permission.
            AppForegroundService.start(this)
            requestFineLocationStep()
        }

    private val foregroundLocationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            val fineGranted = granted[Manifest.permission.ACCESS_FINE_LOCATION] == true
            if (fineGranted) requestBackgroundLocationStep()
            else finishSetupChain()
        }

    private val backgroundLocationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            requestUnrestrictedBatteryStep()
        }

    private val batteryOptimizationLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // Whatever the user chose, continue — tracking still runs via the
            // foreground service; the exemption only improves Doze reliability.
            finishSetupChain()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Ensure the always-on FGS is up even if the user came from a fresh install.
        AppForegroundService.start(this)
        setContent {
            FieldSurveyTheme { AppNav() }
        }
        startPermissionChain()
    }

    override fun onResume() {
        super.onResume()
        // Belt-and-suspenders: keep the always-on FGS running across resumes. This also
        // makes the notification appear immediately for users who granted
        // POST_NOTIFICATIONS through system Settings (outside our in-app prompt).
        AppForegroundService.start(this)
        // Re-evaluate on every resume: if we're inside a shift window and tracking is
        // not running (e.g. it got killed), start it. Cheap to call when already running.
        maybeStartTrackingForCurrentWindow()
    }

    // ---- Permission steps ---------------------------------------------------------------

    private fun startPermissionChain() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestFineLocationStep()
        }
    }

    private fun requestFineLocationStep() {
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            foregroundLocationLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        } else {
            requestBackgroundLocationStep()
        }
    }

    private fun requestBackgroundLocationStep() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            requestUnrestrictedBatteryStep()
        }
    }

    /**
     * Asks the OS to exempt the app from battery optimizations ("Unrestricted").
     * This is what keeps location tracking running normally during Doze /
     * app-standby. The foreground service already survives Doze, but an exempt
     * app additionally keeps full network + wake access and is far less likely
     * to be throttled or killed by aggressive OEM power managers.
     *
     * Shows the system allow/deny dialog directly (no trip to Settings) the
     * first time; once granted, isIgnoringBatteryOptimizations() short-circuits
     * so the user is never re-prompted.
     */
    @SuppressLint("BatteryLife")
    private fun requestUnrestrictedBatteryStep() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            finishSetupChain()
            return
        }
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:$packageName"))
        // Fall back to finishing the chain if a device doesn't support the
        // direct dialog (rare, mostly heavily-customized ROMs).
        runCatching { batteryOptimizationLauncher.launch(intent) }
            .onFailure { finishSetupChain() }
    }

    private fun finishSetupChain() {
        maybeStartTrackingForCurrentWindow()
    }

    private fun maybeStartTrackingForCurrentWindow() {
        val app = applicationContext as FieldSurveyApp
        lifecycleScope.launch {
            val settings = app.settings.flow.first()
            // Re-arm alarms in case they were cleared by the OS.
            ShiftScheduler.apply(this@MainActivity, settings)
            if (ShiftWindow.isNowInsideShift(settings)) {
                LocationTrackingService.start(this@MainActivity)
            }
        }
    }
}
