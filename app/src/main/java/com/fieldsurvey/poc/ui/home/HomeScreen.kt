package com.fieldsurvey.poc.ui.home

import android.app.DatePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fieldsurvey.poc.R
import com.fieldsurvey.poc.tracking.DateKeys
import java.util.Calendar
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onOpenMap: (String) -> Unit,
    onOpenLogs: (String) -> Unit,
    onOpenWhitelist: () -> Unit,
    vm: HomeViewModel = viewModel(factory = HomeViewModel.Factory)
) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    var showResetConfirm by remember { mutableStateOf(false) }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("Reset today's data?") },
            text = {
                Text(
                    "This permanently deletes today's tracking distance, route points " +
                        "and activity log. This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.resetToday()
                        showResetConfirm = false
                    }
                ) { Text("Reset", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Shift window status
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Shift window", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (state.settings.enabled)
                            "%02d:%02d → %02d:%02d  (auto)".format(
                                state.settings.startHour, state.settings.startMinute,
                                state.settings.endHour, state.settings.endMinute
                            )
                        else "Disabled"
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (state.inShiftWindow) "Currently in shift window — tracking"
                        else "Outside shift window — idle"
                    )
                }
            }

            // Live speed — shown only while the tracking service is reporting fixes.
            state.currentSpeedKmh?.let { kmh ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Current speed", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "%.0f km/h".format(Locale.US, kmh),
                            fontSize = 40.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text("%.1f m/s".format(Locale.US, kmh / 3.6f))
                    }
                }
            }

            // Date selector
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Showing", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = state.selectedDate +
                                if (state.selectedDate == state.today) "  (today)" else "",
                            modifier = Modifier.weight(1f)
                        )
                        if (state.selectedDate != state.today) {
                            OutlinedButton(onClick = { vm.selectToday() }) { Text("Today") }
                            Spacer(Modifier.height(0.dp))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { showDatePicker(context, state.selectedDate, vm::selectDate) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Change date") }
                }
            }

            // Distance + points for selected date
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(state.selectedDate, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "%.2f km".format(Locale.US, state.distanceKm),
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text("${state.pointCount} route points saved")
                }
            }

            Button(
                onClick = { onOpenMap(state.selectedDate) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("View route map") }

            OutlinedButton(
                onClick = { onOpenLogs(state.selectedDate) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("View logs") }

            OutlinedButton(
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Shift settings") }

            OutlinedButton(
                onClick = onOpenWhitelist,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Keep tracking alive (battery setup)") }

            // Reset is offered only while viewing the current day, and clears
            // today's tracking data + log.
            if (state.selectedDate == state.today) {
                Button(
                    onClick = { showResetConfirm = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) { Text("Reset today's data") }
            }
        }
    }
}

private fun showDatePicker(
    context: android.content.Context,
    currentDateKey: String,
    onPicked: (String) -> Unit
) {
    val cal = Calendar.getInstance().apply {
        timeInMillis = DateKeys.startOfDayMillis(currentDateKey)
    }
    DatePickerDialog(
        context,
        { _, year, month, day ->
            val picked = Calendar.getInstance().apply {
                set(Calendar.YEAR, year)
                set(Calendar.MONTH, month)
                set(Calendar.DAY_OF_MONTH, day)
                set(Calendar.HOUR_OF_DAY, 12) // noon to dodge DST edge cases
            }
            onPicked(DateKeys.forMillis(picked.timeInMillis))
        },
        cal.get(Calendar.YEAR),
        cal.get(Calendar.MONTH),
        cal.get(Calendar.DAY_OF_MONTH)
    ).apply {
        // Don't allow picking future dates.
        datePicker.maxDate = System.currentTimeMillis()
    }.show()
}
