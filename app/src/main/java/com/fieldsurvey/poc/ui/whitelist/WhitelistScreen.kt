package com.fieldsurvey.poc.ui.whitelist

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.DisposableEffect
import com.fieldsurvey.poc.system.OemWhitelist

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhitelistScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val guide = remember { OemWhitelist.guideForCurrentDevice() }
    var isExempt by remember { mutableStateOf(OemWhitelist.isIgnoringBatteryOptimizations(context)) }

    // Re-check the exemption whenever we come back to this screen (e.g. after
    // the user returns from the system battery dialog).
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isExempt = OemWhitelist.isIgnoringBatteryOptimizations(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun launch(intents: List<android.content.Intent>, failMsg: String) {
        if (!OemWhitelist.launchFirstResolvable(context, intents)) {
            Toast.makeText(context, failMsg, Toast.LENGTH_LONG).show()
            // Universal fallback: app details page.
            OemWhitelist.launchFirstResolvable(context, listOf(OemWhitelist.appDetailsIntent(context)))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Keep tracking alive") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "To track your full route reliably, Android and your phone's manufacturer " +
                    "must be allowed to keep Field Survey running in the background.",
            )

            // ---- Step 1: Standard battery optimization exemption ----
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (isExempt) Icons.Default.CheckCircle else Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (isExempt) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.size(8.dp))
                        Text("Step 1 · Battery optimization", fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (isExempt)
                            "Granted — the app is exempt from battery optimization. ✓"
                        else
                            "Not granted. Tap below and choose Allow so Android won't pause tracking in Doze."
                    )
                    Spacer(Modifier.height(12.dp))
                    if (!isExempt) {
                        Button(
                            onClick = {
                                launch(
                                    listOf(OemWhitelist.requestIgnoreBatteryOptimizationsIntent(context)),
                                    "Open Settings → Battery and allow unrestricted usage."
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Allow unrestricted (recommended)") }
                        Spacer(Modifier.height(8.dp))
                    }
                    OutlinedButton(
                        onClick = {
                            launch(
                                listOf(OemWhitelist.batteryOptimizationListIntent()),
                                "Open Settings → Apps → Field Survey → Battery."
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Open battery optimization list") }
                }
            }

            // ---- Step 2: OEM-specific auto-start / background ----
            Card(
                Modifier.fillMaxWidth(),
                colors = if (guide.needsExtraSteps)
                    CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                else CardDefaults.cardColors()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Step 2 · Manufacturer settings", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Detected device: ${guide.label}",
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(12.dp))

                    if (guide.needsExtraSteps) {
                        Text(
                            "This brand aggressively closes background apps. Follow these steps:",
                        )
                    } else {
                        Text("Your device should work with Step 1. If tracking still stops, follow these steps:")
                    }
                    Spacer(Modifier.height(8.dp))

                    guide.steps.forEachIndexed { i, step ->
                        Row(Modifier.padding(vertical = 4.dp)) {
                            Text("${i + 1}.", fontWeight = FontWeight.SemiBold, modifier = Modifier.size(width = 24.dp, height = 20.dp))
                            Text(step.text)
                        }
                    }

                    if (guide.autoStartIntents.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = {
                                launch(
                                    guide.autoStartIntents,
                                    "Couldn't open it directly — opening app settings instead."
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Open auto-start settings") }
                    }
                }
            }

            // ---- Always-available fallback ----
            OutlinedButton(
                onClick = {
                    launch(
                        listOf(OemWhitelist.appDetailsIntent(context)),
                        "Open Settings manually."
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Open this app's settings") }

            Text(
                "Tip: also lock Field Survey in the Recent apps screen so it isn't swiped away.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
