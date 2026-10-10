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

    fun dismiss() {
        _sheetText.value = null
    }
}
