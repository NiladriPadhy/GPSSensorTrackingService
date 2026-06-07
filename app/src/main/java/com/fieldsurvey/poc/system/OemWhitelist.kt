package com.fieldsurvey.poc.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Runtime helper for guiding the user through "whitelisting" the app so the OS
 * (and aggressive OEM power managers) don't throttle or kill the background
 * tracking service.
 *
 * Two layers:
 *  1. Standard Android battery-optimization exemption (works on all devices).
 *  2. OEM-specific auto-start / background-allow screens — detected from
 *     [Build.MANUFACTURER]/[Build.BRAND] and deep-linked where possible.
 */
object OemWhitelist {

    data class Step(val text: String)

    data class Guide(
        /** Friendly label, e.g. "Xiaomi (MIUI)". */
        val label: String,
        /** Whether this is a known aggressive OEM that needs the extra steps. */
        val needsExtraSteps: Boolean,
        val steps: List<Step>,
        /** Candidate component intents to open the OEM auto-start screen, tried in order. */
        val autoStartIntents: List<Intent>
    )

    // ---- Battery optimization (standard Android) ----------------------------------------

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** System dialog: "Allow [app] to run in the background / ignore battery optimizations". */
    fun requestIgnoreBatteryOptimizationsIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))

    /** The full list of apps and their battery-optimization state. */
    fun batteryOptimizationListIntent(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    /** App's own details page — universal fallback that always exists. */
    fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))

    // ---- OEM detection ------------------------------------------------------------------

    /**
     * Tries each intent in order and starts the first one the system can
     * resolve. Returns true if something launched. Adds NEW_TASK so it also
     * works if called from a non-activity context.
     */
    fun launchFirstResolvable(context: Context, intents: List<Intent>): Boolean {
        for (intent in intents) {
            val resolvable = context.packageManager.resolveActivity(intent, 0) != null
            if (!resolvable) continue
            runCatching {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            }
        }
        return false
    }

    fun guideForCurrentDevice(): Guide {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()
        val who = "$manufacturer $brand"

        return when {
            who.containsAny("xiaomi", "redmi", "poco") -> Guide(
                label = "Xiaomi / Redmi / POCO (MIUI / HyperOS)",
                needsExtraSteps = true,
                steps = listOf(
                    Step("Open Settings → Apps → Manage apps → Field Survey."),
                    Step("Enable Autostart."),
                    Step("Open Battery saver (or App battery saver) and set it to No restrictions."),
                    Step("In Recents, swipe down on Field Survey and tap the lock icon to keep it running."),
                ),
                autoStartIntents = listOf(
                    component("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                    component("com.miui.securitycenter", "com.miui.powercenter.PowerSettings"),
                )
            )

            who.containsAny("oppo", "realme", "oneplus", "coloros", "realmeui") -> Guide(
                label = "OPPO / Realme / OnePlus (ColorOS / OxygenOS)",
                needsExtraSteps = true,
                steps = listOf(
                    Step("Open Settings → Battery → App battery management (or Background usage)."),
                    Step("Find Field Survey and Allow background activity."),
                    Step("Open Startup manager / Auto-launch and enable Field Survey."),
                    Step("Disable any 'Sleep' / 'Deep optimization' for the app."),
                ),
                autoStartIntents = listOf(
                    component("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                    component("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                    component("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
                    component("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
                )
            )

            who.containsAny("vivo", "iqoo", "funtouch") -> Guide(
                label = "vivo / iQOO (Funtouch OS / OriginOS)",
                needsExtraSteps = true,
                steps = listOf(
                    Step("Open Settings → Battery → Background power consumption → allow Field Survey."),
                    Step("Open Settings → More settings → Permission manager → Auto-start and enable Field Survey."),
                    Step("Add Field Survey to the high-background-power-consumption whitelist."),
                ),
                autoStartIntents = listOf(
                    component("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                    component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
                    component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
                )
            )

            who.containsAny("huawei", "honor", "emui", "magicos") -> Guide(
                label = "Huawei / Honor (EMUI / MagicOS)",
                needsExtraSteps = true,
                steps = listOf(
                    Step("Open Settings → Apps → Field Survey → Battery."),
                    Step("Turn OFF 'Power-intensive prompt' and turn ON 'Keep running after screen off'."),
                    Step("Open App launch, switch Field Survey to Manage manually, and enable Auto-launch, Secondary launch, and Run in background."),
                ),
                autoStartIntents = listOf(
                    component("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                    component("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
                    component("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
                )
            )

            who.containsAny("samsung") -> Guide(
                label = "Samsung (One UI)",
                needsExtraSteps = true,
                steps = listOf(
                    Step("Open Settings → Apps → Field Survey → Battery and set it to Unrestricted."),
                    Step("Open Settings → Battery → Background usage limits."),
                    Step("Make sure Field Survey is NOT in 'Sleeping apps' or 'Deep sleeping apps'; remove it if present."),
                    Step("Turn off 'Put unused apps to sleep' or add Field Survey as an exception."),
                ),
                autoStartIntents = listOf(
                    component("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
                    component("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity"),
                )
            )

            who.containsAny("asus", "zenui") -> Guide(
                label = "ASUS (ZenUI)",
                needsExtraSteps = true,
                steps = listOf(
                    Step("Open the Mobile Manager → Auto-start manager and allow Field Survey."),
                    Step("Open Settings → Apps → Field Survey → Battery and set it to Unrestricted."),
                ),
                autoStartIntents = listOf(
                    component("com.asus.mobilemanager", "com.asus.mobilemanager.MainActivity"),
                    component("com.asus.mobilemanager", "com.asus.mobilemanager.autostart.AutoStartActivity"),
                )
            )

            else -> Guide(
                label = deviceLabelFallback(),
                needsExtraSteps = false,
                steps = listOf(
                    Step("Open Settings → Apps → Field Survey → Battery."),
                    Step("Set battery usage to Unrestricted (or Don't optimize)."),
                    Step("If your phone has an Auto-start / Startup manager, enable Field Survey there too."),
                ),
                autoStartIntents = emptyList()
            )
        }
    }

    private fun deviceLabelFallback(): String {
        val m = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        return m.ifBlank { "Your device" }
    }

    private fun component(pkg: String, cls: String): Intent =
        Intent().apply { component = ComponentName(pkg, cls) }

    private fun String.containsAny(vararg needles: String): Boolean =
        needles.any { this.contains(it) }
}
