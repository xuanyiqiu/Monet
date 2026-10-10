package dev.monet.app.feature.lens

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.Dp
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.monet.app.MainActivity
import dev.monet.app.MonetApp
import dev.monet.app.R
import dev.monet.app.core.settings.Settings
import dev.monet.app.core.settings.ThemeMode
import dev.monet.app.ui.theme.MonetTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the floating bubble. Capture and OCR join it in F-07;
 * for now it only shows the bubble and the mode pill.
 */
class LensService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val owner = ServiceLifecycleOwner()
    private lateinit var windowManager: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private var view: ComposeView? = null

    private var expanded by mutableStateOf(false)
    private var rightSide by mutableStateOf(false)
    private var lastRawX = 0f
    private var lastRawY = 0f

    private val screenWidth get() = resources.displayMetrics.widthPixels
    private val screenHeight get() = resources.displayMetrics.heightPixels
    private fun px(dp: Dp) = (dp.value * resources.displayMetrics.density).toInt()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createChannel()
        // Must follow startForegroundService() within seconds, even if we then bail out.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        if (!canDrawOverlays(this)) {
            stopSelf()
            return
        }
        owner.start()
        addOverlay()
        LensState.update { it.copy(running = true, mode = LensMode.AUTO) }
        val repo = (application as MonetApp).container.settingsRepository
        scope.launch {
            repo.settings.collect { s -> LensState.update { it.copy(linesPerStep = s.linesPerStep) } }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        view?.let { runCatching { windowManager.removeView(it) } }
        view = null
        owner.stop()
        scope.cancel()
        LensState.update { it.copy(running = false) }
        super.onDestroy()
    }

    private fun addOverlay() {
        params = WindowManager.LayoutParams(
            px(BubbleSize),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth - px(BubbleSize)
            y = screenHeight / 3
        }
        rightSide = true

        val repo = (application as MonetApp).container.settingsRepository
        val actions = LensOverlayActions(
            onToggle = { applyExpanded(!expanded) },
            onTurnOff = { stopSelf() },
            onDragStart = { x, y ->
                lastRawX = params.x + x
                lastRawY = params.y + y
            },
            onDrag = { x, y -> dragTo(params.x + x, params.y + y) },
            onDragEnd = ::snap,
            onMode = { mode ->
                if (mode == LensMode.OFF) stopSelf() else LensState.update { it.copy(mode = mode) }
            },
            onLines = { n -> scope.launch { repo.setLinesPerStep(n) } },
        )
        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setContent {
                val settings by repo.settings.collectAsState(initial = Settings())
                val dark = when (settings.themeMode) {
                    ThemeMode.SYSTEM -> isSystemInDarkTheme()
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                }
                val state by LensState.state.collectAsState()
                MonetTheme(darkTheme = dark) {
                    LensOverlay(state, expanded, rightSide, actions)
                }
            }
        }
        view = composeView
        windowManager.addView(composeView, params)
    }

    private fun dragTo(rawX: Float, rawY: Float) {
        // Pointer positions are window-local and the window moves under the finger, so track raw deltas.
        params.x += (rawX - lastRawX).toInt()
        params.y = clampY(params.y + (rawY - lastRawY).toInt(), view?.height ?: 0, screenHeight)
        lastRawX = rawX
        lastRawY = rawY
        view?.let { windowManager.updateViewLayout(it, params) }
    }

    private fun snap() {
        rightSide = isOnRightHalf(params.x, params.width, screenWidth)
        params.x = snapToEdgeX(params.x, params.width, screenWidth)
        view?.let { windowManager.updateViewLayout(it, params) }
    }

    private fun applyExpanded(value: Boolean) {
        expanded = value
        params.width = px(if (value) OverlayPillWidth else BubbleSize)
        params.x = if (rightSide) screenWidth - params.width else 0
        view?.let { windowManager.updateViewLayout(it, params) }
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Lens", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, LensService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_lens)
            .setContentTitle("Monet is ready")
            .setContentText("Tap the seal bubble to pick a mode.")
            .setContentIntent(open)
            .addAction(0, "Turn off", stop)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "lens"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "dev.monet.app.lens.STOP"
    }
}
