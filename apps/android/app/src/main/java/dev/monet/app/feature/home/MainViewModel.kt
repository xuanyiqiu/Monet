package dev.monet.app.feature.home

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Holds what the Detail sheet is explaining, so it survives rotation. */
class MainViewModel : ViewModel() {
    private val _sheetText = MutableStateFlow<String?>(null)
    val sheetText: StateFlow<String?> = _sheetText.asStateFlow()

    fun explain(raw: CharSequence?) {
        normalizeIncoming(raw)?.let { _sheetText.value = it }
    }

    private val _showSettings = MutableStateFlow(false)
    val showSettings: StateFlow<Boolean> = _showSettings.asStateFlow()

    fun openSettings() { _showSettings.value = true }
    fun closeSettings() { _showSettings.value = false }

    fun dismiss() {
        _sheetText.value = null
    }
}
