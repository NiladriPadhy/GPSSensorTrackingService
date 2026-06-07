package com.fieldsurvey.poc.ui.log

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.fieldsurvey.poc.logging.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LogUiState(
    val dateKey: String,
    val content: String = "",
    val hasFile: Boolean = false
)

class LogViewModel(
    app: Application,
    private val dateKey: String
) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(LogUiState(dateKey = dateKey))
    val state: StateFlow<LogUiState> = _state

    init {
        // Re-read every 2 s so logs stream live while tracking is active.
        viewModelScope.launch {
            while (isActive) {
                val text = withContext(Dispatchers.IO) { AppLog.read(dateKey) }
                _state.value = LogUiState(
                    dateKey = dateKey,
                    content = text,
                    hasFile = text.isNotEmpty()
                )
                delay(2_000)
            }
        }
    }

    companion object {
        fun factory(dateKey: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
                LogViewModel(app, dateKey)
            }
        }
    }
}
