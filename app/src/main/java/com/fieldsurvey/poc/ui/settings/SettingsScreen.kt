package com.fieldsurvey.poc.ui.settings

import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fieldsurvey.poc.tracking.AccuracyMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory)
) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Shift settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        title = "Auto-track during shift hours",
                        right = {
                            Switch(
                                checked = state.enabled,
                                onCheckedChange = vm::setEnabled
                            )
                        }
                    )
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Shift window", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(12.dp))

                    Button(
                        onClick = {
                            TimePickerDialog(
                                context,
                                { _, h, m -> vm.setStart(h, m) },
                                state.startHour, state.startMinute, true
                            ).show()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Start: %02d:%02d".format(state.startHour, state.startMinute))
                    }

                    Spacer(Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = {
                            TimePickerDialog(
                                context,
                                { _, h, m -> vm.setEnd(h, m) },
                                state.endHour, state.endMinute, true
                            ).show()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("End: %02d:%02d".format(state.endHour, state.endMinute))
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Location accuracy", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    AccuracyDropdown(
                        selected = state.accuracy,
                        onSelected = vm::setAccuracy
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = accuracyDescription(state.accuracy)
                    )
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Data retention", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    RetentionDropdown(
                        selected = state.retentionDays,
                        options = state.retentionOptions,
                        onSelected = vm::setRetentionDays
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Route data and log files older than this are deleted automatically. " +
                            "Reducing the window purges old data immediately."
                    )
                }
            }

            Text(
                "Tracking will start daily at the start time and stop at the end time. " +
                    "If end time is earlier than start time, the window is treated as overnight.",
                fontWeight = FontWeight.Normal
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RetentionDropdown(
    selected: Int,
    options: List<Int>,
    onSelected: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = "$selected ${if (selected == 1) "day" else "days"}",
            onValueChange = {},
            readOnly = true,
            label = { Text("Keep data for") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { days ->
                DropdownMenuItem(
                    text = { Text("$days ${if (days == 1) "day" else "days"}") },
                    onClick = {
                        expanded = false
                        onSelected(days)
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccuracyDropdown(
    selected: AccuracyMode,
    onSelected: (AccuracyMode) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = selected.label,
            onValueChange = {},
            readOnly = true,
            label = { Text("Accuracy") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            AccuracyMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.label) },
                    onClick = {
                        expanded = false
                        onSelected(mode)
                    }
                )
            }
        }
    }
}

private fun accuracyDescription(mode: AccuracyMode): String = when (mode) {
    AccuracyMode.HIGH ->
        "Highest precision · GPS every 4 s while moving · best turn & curve detail (more battery)."
    AccuracyMode.MEDIUM ->
        "Balanced default · GPS every 6 s while moving · reliable turn capture for most field work."
    AccuracyMode.LOW ->
        "Power-saving · GPS every 15 s while moving · coarser detail, accepts fixes up to 55 m."
}

@Composable
private fun Row(title: String, right: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(title)
        right()
    }
}
