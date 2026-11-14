package dev.monet.app.feature.lens

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import dev.monet.app.MainActivity
import dev.monet.app.R
import dev.monet.app.feature.lens.capture.ScreenCapturer
import dev.monet.app.feature.lens.capture.ScreenTextRecognizer
import dev.monet.app.feature.lens.capture.SelRect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service behind the Lens. It owns the floating bubble with its mode pill and the screen
 * capture session; reading ([ReadingController]), selecting ([SelectController]) and the details
 * sheet ([DetailOverlay]) are separate parts that draw their own windows through [LensHost].
 *
 * Start it with the MediaProjection consent result (see [startLens]). If Android later ends the
 * capture, the bubble stays and offers to resume it ([CaptureConsentActivity]).
 */
class LensService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val owner = ServiceLifecycleOwner()
    private val recognizer by lazy { ScreenTextRecognizer() }

    private lateinit var host: LensHost
    private lateinit var reading: ReadingController
    private lateinit var select: SelectController
    private lateinit var detail: DetailOverlay

    private var capturer: ScreenCapturer? = null
    private var started = false
    private var displaySize = 0 to 0

    /** What the user asked for when capture turned out to be missing; runs once it is back. */
    private var afterCapture: (() -> Unit)? = null

    private var bubbleView: ComposeView? = null
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private var expanded by mutableStateOf(false)
    private var rightSide by mutableStateOf(true)
    private var collapsedY = 0
    private var lastRawX = 0f
    private var lastRawY = 0f

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL_ID, "Lens", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_RESULT_DATA, Intent::class.java) }

        // Must follow startForegroundService() within seconds, even if we then bail out. On
        // Android 14+ the mediaProjection type is only allowed once the consent result is in hand.
        val withProjection = resultData != null || capturer != null
        val type = if (withProjection) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)

        if (!canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!started) {
            started = true
            startLensUi()
        }
        if (resultData != null) attachCapturer(resultCode, resultData)
        return START_NOT_STICKY
    }

    private fun startLensUi() {
        owner.start()
        host = LensHost(this, scope, owner)
        displaySize = host.displaySize()
        detail = DetailOverlay(host, onOpenChanged = ::onCoverChanged)
        reading = ReadingController(
            host = host,
            recognizer = recognizer,
            capturer = { capturer },
            ownRegions = ::controlRegions,
            prepareCapture = ::collapseForCapture,
            raiseBubble = ::raiseBubble,
            onOpenDetail = { text -> detail.open(text) },
            onNeedCapture = { requestCapture(then = { reading.start() }) },
        )
        select = SelectController(
            host = host,
            recognizer = recognizer,
            capturer = { capturer },
            setBubbleVisible = { visible -> bubbleView?.visibility = if (visible) View.VISIBLE else View.INVISIBLE },
            prepareCapture = ::collapseForCapture,
            onOpenDetail = { text -> detail.open(text) },
            onNeedCapture = { requestCapture(then = { select.start() }) },
            onOpenChanged = ::onCoverChanged,
        )
        addBubble()
        LensState.update { it.copy(running = true, mode = LensMode.AUTO, captureLost = false) }
        scope.launch {
            host.container.settingsRepository.settings.collect { settings ->
                LensState.update { it.copy(linesPerStep = settings.linesPerStep) }
                reading.setLinesPerStep(settings.linesPerStep)
            }
        }
    }

    override fun onDestroy() {
        if (started) {
            select.close()
            detail.close()
            reading.stop()
            host.remove(bubbleView)
            bubbleView = null
            owner.stop()
        }
        capturer?.release()
        capturer = null
        runCatching { recognizer.close() }
        scope.cancel()
        LensState.update { it.copy(running = false, reading = null, captureLost = false) }
        super.onDestroy()
    }

    // ---- Screen capture --------------------------------------------------------------------

    private fun attachCapturer(resultCode: Int, resultData: Intent) {
        capturer?.release()
        capturer = runCatching { ScreenCapturer(this, resultCode, resultData, onStopped = ::onCaptureEnded) }
            .onFailure { Log.e(TAG, "Screen capture could not start", it) }
            .getOrNull()
        LensState.update { it.copy(captureLost = capturer == null) }
        val pending = afterCapture
        afterCapture = null
        if (capturer != null) pending?.invoke()
    }

    /** Android ended the projection: the device was locked or the user stopped sharing. */
    private fun onCaptureEnded() {
        capturer?.release()
        capturer = null
        if (!started) return
        select.close()
        reading.stop()
        LensState.update { it.copy(captureLost = true) }
        host.toast("Screen capture ended. Tap the Monet bubble to resume.")
    }

    private fun requestCapture(then: (() -> Unit)? = null) {
        afterCapture = then
        applyExpanded(false)
        startActivity(Intent(this, CaptureConsentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!started) return
        val size = host.displaySize()
        if (size == displaySize) return
        displaySize = size
        capturer?.resizeToDisplay()
        select.close()
        reading.onDisplayChanged()
        // Keep the bubble on its edge of the new screen, collapsed.
        expanded = false
        bubbleParams.width = host.px(BubbleSize)
        bubbleParams.x = if (rightSide) size.first - bubbleParams.width else 0
        bubbleParams.y = clampY(collapsedY, bubbleView?.height ?: 0, size.second)
        collapsedY = bubbleParams.y
        bubbleView?.let { host.update(it, bubbleParams) }
    }

    // ---- Bubble and pill -------------------------------------------------------------------

    private fun addBubble() {
        bubbleParams = WindowManager.LayoutParams(
            host.px(BubbleSize),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = displaySize.first - host.px(BubbleSize)
            y = displaySize.second / 3
        }
        collapsedY = bubbleParams.y
        rightSide = true

        val settingsRepository = host.container.settingsRepository
        val actions = LensOverlayActions(
            onToggle = { applyExpanded(!expanded) },
            onTurnOff = { stopSelf() },
            onDragStart = { x, y ->
                lastRawX = bubbleParams.x + x
                lastRawY = bubbleParams.y + y
            },
            onDrag = { x, y -> dragTo(bubbleParams.x + x, bubbleParams.y + y) },
            onDragEnd = ::snap,
            onMode = { mode ->
                if (mode == LensMode.OFF) {
                    stopSelf()
                } else {
                    if (mode == LensMode.SELECT) reading.stop()
                    LensState.update { it.copy(mode = mode) }
                }
            },
            onLines = { lines -> scope.launch { settingsRepository.setLinesPerStep(lines) } },
            onRead = { reading.start() },
            onSelect = {
                reading.stop()
                select.start()
            },
            onPrev = { reading.step(forward = false) },
            onNext = { reading.step(forward = true) },
            onStopReading = { reading.stop() },
            onResumeCapture = { requestCapture() },
        )
        val bubble = host.composeView {
            val state by LensState.state.collectAsState()
            LensOverlay(state, expanded, rightSide, actions)
        }
        bubbleView = bubble
        host.add(bubble, bubbleParams)
    }

    /** Windows stack in the order they were added; adding the bubble again puts it back on top. */
    private fun raiseBubble() {
        val bubble = bubbleView ?: return
        host.remove(bubble)
        host.add(bubble, bubbleParams)
    }

    /** Where the bubble (and the pill, when open) is on screen: text under it is not page content. */
    private fun controlRegions(): List<SelRect> {
        val bubble = bubbleView ?: return emptyList()
        if (bubble.visibility != View.VISIBLE || bubble.width == 0) return emptyList()
        val location = IntArray(2)
        bubble.getLocationOnScreen(location)
        return listOf(SelRect(location[0], location[1], location[0] + bubble.width, location[1] + bubble.height))
    }

    private fun collapseForCapture(): Boolean {
        if (!expanded) return false
        applyExpanded(false)
        return true
    }

    /** The details sheet or the selection screen covers the page, so reading must not follow what it sees. */
    private fun onCoverChanged(open: Boolean) {
        reading.paused = open || detail.isOpen || select.isOpen
    }

    private fun dragTo(rawX: Float, rawY: Float) {
        // Pointer positions are window-local and the window moves under the finger, so track raw deltas.
        bubbleParams.x += (rawX - lastRawX).toInt()
        bubbleParams.y = clampY(bubbleParams.y + (rawY - lastRawY).toInt(), bubbleView?.height ?: 0, displaySize.second)
        lastRawX = rawX
        lastRawY = rawY
        collapsedY = bubbleParams.y
        bubbleView?.let { host.update(it, bubbleParams) }
        reading.notifyOwnChange()
    }

    private fun snap() {
        rightSide = isOnRightHalf(bubbleParams.x, bubbleParams.width, displaySize.first)
        bubbleParams.x = snapToEdgeX(bubbleParams.x, bubbleParams.width, displaySize.first)
        bubbleView?.let { host.update(it, bubbleParams) }
        reading.notifyOwnChange()
    }

    private fun applyExpanded(value: Boolean) {
        if (value == expanded && bubbleView != null && bubbleParams.width == host.px(if (value) OverlayPillWidth else BubbleSize)) return
        if (value) collapsedY = bubbleParams.y
        expanded = value
        bubbleParams.width = host.px(if (value) OverlayPillWidth else BubbleSize)
        bubbleParams.x = if (rightSide) displaySize.first - bubbleParams.width else 0
        // An open pill near the bottom edge would hang off the screen: lift it, and put it back after.
        bubbleParams.y = if (value) {
            pillTop(collapsedY, host.px(PILL_MAX_HEIGHT), displaySize.second - host.navigationBarPx())
        } else {
            collapsedY
        }
        bubbleView?.let { host.update(it, bubbleParams) }
        if (started) reading.notifyOwnChange()
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
        private const val TAG = "MonetLens"
        private const val CHANNEL_ID = "lens"
        private const val NOTIFICATION_ID = 1
        private val PILL_MAX_HEIGHT = 340.dp
        const val ACTION_STOP = "dev.monet.app.lens.STOP"
        const val EXTRA_RESULT_CODE = "dev.monet.app.lens.RESULT_CODE"
        const val EXTRA_RESULT_DATA = "dev.monet.app.lens.RESULT_DATA"
    }
}
