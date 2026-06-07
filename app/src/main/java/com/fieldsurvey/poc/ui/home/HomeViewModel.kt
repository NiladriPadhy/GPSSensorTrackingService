package com.fieldsurvey.poc.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.fieldsurvey.poc.FieldSurveyApp
import com.fieldsurvey.poc.data.ShiftSettings
import com.fieldsurvey.poc.logging.AppLog
import com.fieldsurvey.poc.service.LocationTrackingService
import com.fieldsurvey.poc.tracking.DateKeys
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.util.Calendar

data class HomeUiState(
    val today: String = DateKeys.today(),
    val selectedDate: String = DateKeys.today(),
    val settings: ShiftSettings = ShiftSettings(false, 9 * 60, 21 * 60),
    val inShiftWindow: Boolean = false,
    val distanceKm: Double = 0.0,
    val pointCount: Int = 0,
    /** Live ground speed in km/h while tracking, or null when not tracking. */
    val currentSpeedKmh: Float? = null
)

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val appCtx = app as FieldSurveyApp
    private val _selectedDate = MutableStateFlow(DateKeys.today())
    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state

    init {
        observe()
    }

    fun selectDate(dateKey: String) {
        _selectedDate.value = dateKey
    }

    fun selectToday() {
        _selectedDate.value = DateKeys.today()
    }

    /**
     * Clears all of today's tracking data and its log file. Order matters:
     *  1. reset the running service's in-memory counters/anchor so it doesn't
     *     keep adding onto stale values,
     *  2. delete today's DB rows (DayLog + LocationPoints),
     *  3. delete today's log file.
     * Guarded by the UI to today only.
     */
    fun resetToday() = viewModelScope.launch {
        val today = DateKeys.today()
        LocationTrackingService.resetTodayData()
        appCtx.database.locationPointDao().deleteForDate(today)
        appCtx.database.dayLogDao().deleteForDate(today)
        AppLog.deleteForDate(today)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observe() {
        // Re-emit "today" every minute so the home screen advances at midnight.
        val todayTicker = flow {
            while (true) {
                emit(DateKeys.today())
                kotlinx.coroutines.delay(60_000)
            }
        }

        val perDate = _selectedDate.flatMapLatest { date ->
            combine(
                appCtx.database.dayLogDao().byDateFlow(date),
                appCtx.database.locationPointDao().pointsForDateFlow(date)
            ) { log, pts -> Triple(date, log?.totalDistanceMeters ?: 0.0, pts.size) }
        }

        viewModelScope.launch {
            combine(
                appCtx.settings.flow,
                perDate,
                todayTicker,
                LocationTrackingService.currentSpeedMps
            ) { settings, (selected, meters, count), today, speedMps ->
                val cal = Calendar.getInstance()
                val inWindow = settings.enabled && settings.isWithinShift(
                    cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)
                )
                HomeUiState(
                    today = today,
                    selectedDate = selected,
                    settings = settings,
                    inShiftWindow = inWindow,
                    distanceKm = meters / 1000.0,
                    pointCount = count,
                    currentSpeedKmh = speedMps?.let { it * 3.6f }
                )
            }.collect { _state.value = it }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
                HomeViewModel(app)
            }
        }
    }
}
