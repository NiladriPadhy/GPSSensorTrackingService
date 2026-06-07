package com.fieldsurvey.poc.ui.map

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.fieldsurvey.poc.FieldSurveyApp
import com.fieldsurvey.poc.data.LocationPoint
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class MapUiState(
    val dateKey: String,
    val points: List<LatLng> = emptyList(),
    val rawPoints: List<LocationPoint> = emptyList(),
    val segments: List<List<LatLng>> = emptyList(),
    val distanceKm: Double = 0.0
)

class MapViewModel(
    app: Application,
    private val dateKey: String
) : AndroidViewModel(app) {

    private val appCtx = app as FieldSurveyApp
    private val _state = MutableStateFlow(MapUiState(dateKey = dateKey))
    val state: StateFlow<MapUiState> = _state

    /** Break the polyline wherever there is a gap larger than this. */
    private val segmentGapMillis: Long = 2 * 60_000L

    init {
        observe()
    }

    private fun observe() {
        viewModelScope.launch {
            combine(
                appCtx.database.dayLogDao().byDateFlow(dateKey),
                appCtx.database.locationPointDao().pointsForDateFlow(dateKey)
            ) { log, pts ->
                val latLngs = pts.map { LatLng(it.latitude, it.longitude) }
                MapUiState(
                    dateKey = dateKey,
                    points = latLngs,
                    rawPoints = pts,
                    segments = splitOnGaps(pts),
                    distanceKm = (log?.totalDistanceMeters ?: 0.0) / 1000.0
                )
            }.collect { _state.value = it }
        }
    }

    private fun splitOnGaps(points: List<LocationPoint>): List<List<LatLng>> {
        if (points.isEmpty()) return emptyList()
        val out = mutableListOf<MutableList<LatLng>>()
        var cur = mutableListOf<LatLng>()
        out += cur
        var lastTs = points.first().timestampUtcMillis
        for (p in points) {
            if (p.timestampUtcMillis - lastTs > segmentGapMillis && cur.isNotEmpty()) {
                cur = mutableListOf()
                out += cur
            }
            cur += LatLng(p.latitude, p.longitude)
            lastTs = p.timestampUtcMillis
        }
        return out.filter { it.isNotEmpty() }
    }

    companion object {
        fun factory(dateKey: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
                MapViewModel(app, dateKey)
            }
        }
    }
}
