package com.fieldsurvey.poc.ui.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fieldsurvey.poc.data.LocationPoint
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class ViewMode { Map, List }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    dateKey: String,
    onBack: () -> Unit,
    vm: MapViewModel = viewModel(factory = MapViewModel.factory(dateKey))
) {
    val state by vm.state.collectAsState()
    var mode by rememberSaveable { mutableStateOf(ViewMode.Map) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "%s · %.2f km".format(Locale.US, state.dateKey, state.distanceKm)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                SegmentedButton(
                    selected = mode == ViewMode.Map,
                    onClick = { mode = ViewMode.Map },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    icon = {
                        Icon(Icons.Default.Place, contentDescription = null)
                    }
                ) { Text("Map") }
                SegmentedButton(
                    selected = mode == ViewMode.List,
                    onClick = { mode = ViewMode.List },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    icon = {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = null)
                    }
                ) { Text("List") }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    state.points.isEmpty() -> {
                        Surface(modifier = Modifier.fillMaxSize()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("No route data for ${state.dateKey}.")
                            }
                        }
                    }
                    mode == ViewMode.Map -> {
                        RouteMap(state.points, state.segments)
                    }
                    else -> {
                        PointList(state.rawPoints)
                    }
                }
            }
        }
    }
}

@Composable
private fun RouteMap(points: List<LatLng>, segments: List<List<LatLng>>) {
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(points.first(), 14f)
    }

    val bounds = remember(points) {
        if (points.size < 2) null
        else LatLngBounds.builder().apply { points.forEach { include(it) } }.build()
    }

    GoogleMap(
        modifier = Modifier.fillMaxSize(),
        cameraPositionState = cameraPositionState,
        onMapLoaded = {
            bounds?.let {
                cameraPositionState.move(CameraUpdateFactory.newLatLngBounds(it, 80))
            }
        }
    ) {
        segments.forEach { seg ->
            if (seg.size >= 2) Polyline(points = seg, width = 10f)
        }
        Marker(state = MarkerState(points.first()), title = "Start")
        if (points.size > 1) {
            Marker(state = MarkerState(points.last()), title = "End")
        }
    }
}

@Composable
private fun PointList(points: List<LocationPoint>) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(points, key = { it.id }) { p ->
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(
                    text = timeFormat.format(Date(p.timestampUtcMillis)),
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "%.6f, %.6f".format(Locale.US, p.latitude, p.longitude)
                )

                // Accuracy + speed line.
                val speedText = p.speedMps?.let { s ->
                    val accSuffix = p.speedAccuracyMps?.let { " ±%.1f".format(Locale.US, it) }.orEmpty()
                    "  ·  %.1f m/s%s".format(Locale.US, s, accSuffix)
                } ?: ""
                Text(text = "± %.0f m%s".format(Locale.US, p.accuracyMeters, speedText))

                // Bearing line (only if reported).
                p.bearingDeg?.let { b ->
                    val bearingAcc = p.bearingAccuracyDeg?.let { " ±%.0f°".format(Locale.US, it) }.orEmpty()
                    Text(text = "Bearing %.0f°%s".format(Locale.US, b, bearingAcc))
                }
            }
            HorizontalDivider()
        }
    }
}
