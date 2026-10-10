package dev.monet.app.feature.home

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Top-level destinations. Kept as plain state: the app has four screens and one back stack of depth three. */
sealed interface Screen {
    data object Home : Screen
    data object Settings : Screen
    data object Library : Screen
    data class Reader(val documentId: String) : Screen
}

/** Where Back goes from here; null means the app should close. */
fun Screen.parent(): Screen? = when (this) {
    Screen.Home -> null
    Screen.Settings, Screen.Library -> Screen.Home
    is Screen.Reader -> Screen.Library
}

/** Holds the current screen and what the Detail sheet is explaining, so both survive rotation. */
class MainViewModel : ViewModel() {
    private val _sheetText = MutableStateFlow<String?>(null)
    val sheetText: StateFlow<String?> = _sheetText.asStateFlow()

    private val _screen = MutableStateFlow<Screen>(Screen.Home)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    fun explain(raw: CharSequence?) {
        normalizeIncoming(raw)?.let { _sheetText.value = it }
    }

    private val _captureNotice = MutableStateFlow(false)
    val captureNotice: StateFlow<Boolean> = _captureNotice.asStateFlow()

    fun showCaptureNotice() { _captureNotice.value = true }
    fun dismissCaptureNotice() { _captureNotice.value = false }

    fun dismiss() {
        _sheetText.value = null
    }

    fun open(screen: Screen) {
        _screen.value = screen
    }

    /** @return true if Back was handled inside the app. */
    fun back(): Boolean {
        val parent = _screen.value.parent() ?: return false
        _screen.value = parent
        return true
    }
}
