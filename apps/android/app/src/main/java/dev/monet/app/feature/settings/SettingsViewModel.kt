package dev.monet.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.monet.app.core.network.Lang
import dev.monet.app.core.network.SourceLang
import dev.monet.app.core.settings.ChipSize
import dev.monet.app.core.settings.TtsSpeed
import dev.monet.app.core.settings.PinyinStyle
import dev.monet.app.core.settings.Settings
import dev.monet.app.core.settings.SettingsRepository
import dev.monet.app.core.settings.ThemeMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val repo: SettingsRepository) : ViewModel() {
    val settings: StateFlow<Settings> =
        repo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Settings())

    fun setTargetLang(v: Lang) = viewModelScope.launch { repo.setTargetLang(v) }
    fun setPinyinStyle(v: PinyinStyle) = viewModelScope.launch { repo.setPinyinStyle(v) }
    fun setLinesPerStep(v: Int) = viewModelScope.launch { repo.setLinesPerStep(v) }
    fun setSourceLang(v: SourceLang) = viewModelScope.launch { repo.setSourceLang(v) }
    fun setOverlayOpacity(v: Int) = viewModelScope.launch { repo.setOverlayOpacity(v) }
    fun setChipSize(v: ChipSize) = viewModelScope.launch { repo.setChipSize(v) }
    fun setTtsSpeed(v: TtsSpeed) = viewModelScope.launch { repo.setTtsSpeed(v) }
    fun setThemeMode(v: ThemeMode) = viewModelScope.launch { repo.setThemeMode(v) }
    fun setUseFakeApi(v: Boolean) = viewModelScope.launch { repo.setUseFakeApi(v) }
    fun setApiBaseUrl(v: String) = viewModelScope.launch { repo.setApiBaseUrl(v.trim()) }
    fun setAuthToken(v: String) = viewModelScope.launch { repo.setAuthToken(v.trim()) }
    fun setOnboardingDone(v: Boolean) = viewModelScope.launch { repo.setOnboardingDone(v) }
    fun setCaptureExplained(v: Boolean) = viewModelScope.launch { repo.setCaptureExplained(v) }
}
