package com.fieldsurvey.poc.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.fieldsurvey.poc.FieldSurveyApp
import com.fieldsurvey.poc.data.RetentionManager
import com.fieldsurvey.poc.data.ShiftSettings
import com.fieldsurvey.poc.scheduler.ShiftScheduler
import com.fieldsurvey.poc.service.LocationTrackingService
import com.fieldsurvey.poc.tracking.AccuracyMode
import com.fieldsurvey.poc.tracking.ShiftWindow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class SettingsUiState(
    val enabled: Boolean = false,
    val startHour: Int = 9,
    val startMinute: Int = 0,
    val endHour: Int = 21,
    val endMinute: Int = 0,
    val accuracy: AccuracyMode = AccuracyMode.DEFAULT,
    val retentionDays: Int = ShiftSettings.DEFAULT_RETENTION_DAYS,
    val retentionOptions: List<Int> = ShiftSettings.RETENTION_OPTIONS
)

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val appCtx = app as FieldSurveyApp
    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state

    init {
        viewModelScope.launch {
            appCtx.settings.flow.collect { s ->
                _state.value = SettingsUiState(
                    enabled = s.enabled,
                    startHour = s.startHour, startMinute = s.startMinute,
                    endHour = s.endHour, endMinute = s.endMinute,
                    accuracy = s.accuracy,
                    retentionDays = s.retentionDays
                )
            }
        }
    }

    fun setEnabled(enabled: Boolean) = viewModelScope.launch {
        appCtx.settings.setEnabled(enabled)
        reapply()
    }

    fun setStart(h: Int, m: Int) = viewModelScope.launch {
        val cur = appCtx.settings.flow.first()
        appCtx.settings.setShiftWindow(h * 60 + m, cur.endMinuteOfDay)
        reapply()
    }

    fun setEnd(h: Int, m: Int) = viewModelScope.launch {
        val cur = appCtx.settings.flow.first()
        appCtx.settings.setShiftWindow(cur.startMinuteOfDay, h * 60 + m)
        reapply()
    }

    /**
     * Updates the accuracy profile. If we're currently inside the shift window
     * the tracking service is re-started so it picks up the new priority /
     * sampling interval / filter threshold immediately. No-op restart if the
     * mode hasn't actually changed.
     */
    fun setAccuracy(mode: AccuracyMode) = viewModelScope.launch {
        val cur = appCtx.settings.flow.first()
        if (cur.accuracy == mode) return@launch
        appCtx.settings.setAccuracy(mode)
        reapply()
    }

    /**
     * Updates the retention window and immediately purges anything that now
     * falls outside it, so reducing the window frees storage right away.
     */
    fun setRetentionDays(days: Int) = viewModelScope.launch {
        val cur = appCtx.settings.flow.first()
        if (cur.retentionDays == days) return@launch
        appCtx.settings.setRetentionDays(days)
        runCatching { RetentionManager.purge(appCtx) }
    }

    /**
     * After any settings change: re-arm the daily alarms and make sure the
     * tracking service is running iff we're currently inside the (possibly
     * new) shift window. [LocationTrackingService.start] is idempotent and
     * also re-reads the latest accuracy mode on every invocation, so calling
     * it again is the way we push a live config change into a running service.
     */
    private suspend fun reapply() {
        val s = appCtx.settings.flow.first()
        val ctx = getApplication<Application>()

        ShiftScheduler.apply(ctx, s)

        if (ShiftWindow.isNowInsideShift(s)) {
            LocationTrackingService.start(ctx)
        } else {
            LocationTrackingService.stop(ctx)
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
                SettingsViewModel(app)
            }
        }
    }
}
