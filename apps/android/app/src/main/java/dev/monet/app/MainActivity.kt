package dev.monet.app

import android.Manifest
import android.content.Intent
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import dev.monet.app.feature.lens.LensState
import dev.monet.app.feature.lens.canDrawOverlays
import dev.monet.app.feature.lens.needsNotificationPermission
import dev.monet.app.feature.lens.overlaySettingsIntent
import dev.monet.app.feature.lens.startLens
import dev.monet.app.feature.lens.stopLens
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import android.graphics.Color
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.monet.app.core.settings.ThemeMode
import dev.monet.app.feature.detail.DetailSheet
import dev.monet.app.feature.home.HomeScreen
import dev.monet.app.feature.home.MainViewModel
import dev.monet.app.feature.home.incomingText
import dev.monet.app.feature.settings.SettingsActions
import dev.monet.app.feature.settings.SettingsScreen
import dev.monet.app.feature.settings.SettingsViewModel
import dev.monet.app.ui.theme.MonetTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    // Notifications are optional: start the lens whatever the answer is.
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { startLens(this) }

    private fun toggleLens() {
        when {
            LensState.state.value.running -> stopLens(this)
            !canDrawOverlays(this) -> {
                Toast.makeText(this, "Allow \"Display over other apps\" for Monet, then come back.", Toast.LENGTH_LONG).show()
                startActivity(overlaySettingsIntent(this))
            }
            needsNotificationPermission(this) -> notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else -> startLens(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as MonetApp).container
        // Only the first launch carries the intent; after rotation the ViewModel already has the text.
        if (savedInstanceState == null) viewModel.explain(intent.incomingText())
        setContent {
            val settingsViewModel: SettingsViewModel = viewModel(
                factory = viewModelFactory { initializer { SettingsViewModel(container.settingsRepository) } },
            )
            val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
            val dark = when (settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            DisposableEffect(dark) {
                val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            MonetTheme(darkTheme = dark) {
                val sheetText by viewModel.sheetText.collectAsStateWithLifecycle()
                val showSettings by viewModel.showSettings.collectAsStateWithLifecycle()
                if (showSettings) {
                    BackHandler(onBack = viewModel::closeSettings)
                    SettingsScreen(
                        settings = settings,
                        actions = SettingsActions(
                            onBack = viewModel::closeSettings,
                            onTargetLang = { settingsViewModel.setTargetLang(it) },
                            onPinyinStyle = { settingsViewModel.setPinyinStyle(it) },
                            onLinesPerStep = { settingsViewModel.setLinesPerStep(it) },
                            onThemeMode = { settingsViewModel.setThemeMode(it) },
                            onUseFakeApi = { settingsViewModel.setUseFakeApi(it) },
                            onApiBaseUrl = { settingsViewModel.setApiBaseUrl(it) },
                            onAuthToken = { settingsViewModel.setAuthToken(it) },
                        ),
                    )
                } else {
                    val lens by LensState.state.collectAsStateWithLifecycle()
                    HomeScreen(
                        onExplain = viewModel::explain,
                        onOpenSettings = viewModel::openSettings,
                        lensRunning = lens.running,
                        onToggleLens = ::toggleLens,
                    )
                }
                sheetText?.let { DetailSheet(text = it, onDismiss = viewModel::dismiss) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.explain(intent.incomingText())
    }
}
