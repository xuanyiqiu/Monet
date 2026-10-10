package dev.monet.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.monet.app.core.settings.ThemeMode
import dev.monet.app.feature.detail.DetailSheet
import dev.monet.app.feature.home.HomeScreen
import dev.monet.app.feature.home.MainViewModel
import dev.monet.app.feature.home.Screen
import dev.monet.app.feature.home.incomingText
import dev.monet.app.feature.lens.LensState
import dev.monet.app.feature.lens.canDrawOverlays
import dev.monet.app.feature.lens.needsNotificationPermission
import dev.monet.app.feature.lens.overlaySettingsIntent
import dev.monet.app.feature.lens.startLens
import dev.monet.app.feature.lens.stopLens
import dev.monet.app.feature.library.LibraryRoute
import dev.monet.app.feature.onboarding.OnboardingRoute
import dev.monet.app.feature.reader.ReaderRoute
import dev.monet.app.feature.settings.SettingsActions
import dev.monet.app.feature.settings.SettingsScreen
import dev.monet.app.feature.settings.SettingsViewModel
import dev.monet.app.ui.theme.Monet
import dev.monet.app.ui.theme.MonetTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    private val container get() = (application as MonetApp).container

    // The user must approve screen capture every time the lens starts (Android shows its own dialog).
    private val captureConsent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                startLens(this, result.resultCode, data)
            } else {
                Toast.makeText(this, "Allow screen capture so Monet can read other apps.", Toast.LENGTH_LONG).show()
            }
        }

    private fun requestCapture() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        captureConsent.launch(manager.createScreenCaptureIntent())
    }

    /** Explains Android's capture prompt once, right before the first time it appears. */
    private fun continueToCapture() {
        lifecycleScope.launch {
            if (container.settingsRepository.settings.first().captureExplained) {
                requestCapture()
            } else {
                viewModel.showCaptureNotice()
            }
        }
    }

    // Notifications are optional: continue to the capture consent whatever the answer is.
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { continueToCapture() }

    private fun toggleLens() {
        when {
            LensState.state.value.running -> stopLens(this)
            !canDrawOverlays(this) -> {
                Toast.makeText(this, "Allow \"Display over other apps\" for Monet, then come back.", Toast.LENGTH_LONG).show()
                startActivity(overlaySettingsIntent(this))
            }
            needsNotificationPermission(this) -> notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else -> continueToCapture()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = container
        // Only the first launch carries the intent; after rotation the ViewModel already has the text.
        if (savedInstanceState == null) viewModel.explain(intent.incomingText())
        setContent {
            val settingsViewModel: SettingsViewModel = viewModel(
                factory = viewModelFactory { initializer { SettingsViewModel(container.settingsRepository) } },
            )
            val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
            // null until DataStore has answered, so a returning user never sees the intro flash by.
            val stored by container.settingsRepository.settings.collectAsStateWithLifecycle(initialValue = null)
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
                val showCaptureNotice by viewModel.captureNotice.collectAsStateWithLifecycle()
                when (val s = stored) {
                    null -> Box(Modifier.fillMaxSize().background(Monet.colors.paper))
                    else -> if (!s.onboardingDone) {
                        OnboardingRoute(
                            targetLang = s.targetLang,
                            onTargetLang = { settingsViewModel.setTargetLang(it) },
                            onTry = viewModel::explain,
                            onFinish = { settingsViewModel.setOnboardingDone(true) },
                        )
                    } else {
                        AppNavigation(settingsViewModel)
                    }
                }
                sheetText?.let { DetailSheet(text = it, onDismiss = viewModel::dismiss) }
                if (showCaptureNotice) {
                    AlertDialog(
                        onDismissRequest = viewModel::dismissCaptureNotice,
                        containerColor = Monet.colors.surface,
                        title = { Text("Screen capture", style = Monet.type.title, color = Monet.colors.ink) },
                        text = {
                            Text(
                                "Next, Android will ask to let Monet capture your screen. Monet reads it on your device and only sends the text you choose to translate.",
                                style = Monet.type.body,
                                color = Monet.colors.inkMuted,
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                viewModel.dismissCaptureNotice()
                                settingsViewModel.setCaptureExplained(true)
                                requestCapture()
                            }) { Text("Continue", style = Monet.type.label, color = Monet.colors.seal) }
                        },
                        dismissButton = {
                            TextButton(onClick = viewModel::dismissCaptureNotice) {
                                Text("Not now", style = Monet.type.label, color = Monet.colors.inkMuted)
                            }
                        },
                    )
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun AppNavigation(settingsViewModel: SettingsViewModel) {
        val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
        val screen by viewModel.screen.collectAsStateWithLifecycle()
        BackHandler(enabled = screen != Screen.Home) { viewModel.back() }
        when (val current = screen) {
            Screen.Home -> {
                val lens by LensState.state.collectAsStateWithLifecycle()
                HomeScreen(
                    onExplain = viewModel::explain,
                    onOpenSettings = { viewModel.open(Screen.Settings) },
                    onOpenLibrary = { viewModel.open(Screen.Library) },
                    lensRunning = lens.running,
                    onToggleLens = ::toggleLens,
                )
            }
            Screen.Settings -> SettingsScreen(
                settings = settings,
                actions = SettingsActions(
                    onBack = { viewModel.back() },
                    onTargetLang = { settingsViewModel.setTargetLang(it) },
                    onPinyinStyle = { settingsViewModel.setPinyinStyle(it) },
                    onLinesPerStep = { settingsViewModel.setLinesPerStep(it) },
                    onThemeMode = { settingsViewModel.setThemeMode(it) },
                    onUseFakeApi = { settingsViewModel.setUseFakeApi(it) },
                    onApiBaseUrl = { settingsViewModel.setApiBaseUrl(it) },
                    onAuthToken = { settingsViewModel.setAuthToken(it) },
                    onReplayIntro = {
                        viewModel.open(Screen.Home)
                        settingsViewModel.setOnboardingDone(false)
                    },
                ),
            )
            Screen.Library -> LibraryRoute(
                onBack = { viewModel.back() },
                onOpen = { viewModel.open(Screen.Reader(it)) },
            )
            is Screen.Reader -> ReaderRoute(
                documentId = current.documentId,
                onBack = { viewModel.back() },
                onExplain = viewModel::explain,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.explain(intent.incomingText())
    }
}
