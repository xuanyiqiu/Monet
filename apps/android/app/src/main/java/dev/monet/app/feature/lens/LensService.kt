package dev.monet.app.feature.lens

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.view.HapticFeedbackConstants
import android.graphics.PixelFormat
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.monet.app.MainActivity
import dev.monet.app.MonetApp
import dev.monet.app.R
import dev.monet.app.core.network.AnnotateLinesRequest
import dev.monet.app.core.network.AnnotateRequest
import dev.monet.app.core.network.AnnotationContext
import dev.monet.app.core.network.LineInput
import dev.monet.app.core.network.ApiException
import dev.monet.app.core.network.Depth
import dev.monet.app.core.settings.Settings
import dev.monet.app.core.settings.ThemeMode
import dev.monet.app.feature.lens.capture.ScreenCapturer
import dev.monet.app.feature.lens.capture.ScreenTextRecognizer
import dev.monet.app.feature.lens.capture.ReadingSession
import dev.monet.app.feature.lens.capture.SelRect
import dev.monet.app.feature.lens.capture.borderPixels
import dev.monet.app.feature.lens.capture.buildReadingUnits
import dev.monet.app.feature.lens.capture.medianArgb
import dev.monet.app.feature.lens.capture.pickTextColor
import dev.monet.app.feature.lens.capture.groupIntoBlocks
import dev.monet.app.feature.lens.capture.realDisplaySize
import dev.monet.app.feature.lens.capture.resolveSelection
import dev.monet.app.ui.theme.MonetTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the floating bubble and the screen capture session.
 * It must be started with the MediaProjection consent result (see [startLens]).
 */
class LensService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val owner = ServiceLifecycleOwner()
    private lateinit var windowManager: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private var view: ComposeView? = null

    private var capturer: ScreenCapturer? = null
    private val recognizer by lazy { ScreenTextRecognizer() }
    private var readJob: Job? = null
    private var started = false

    private var selectView: ComposeView? = null
    private var selectFrame: Bitmap? = null
    private var annotateJob: Job? = null
    private var selection by mutableStateOf<SelectionUiState?>(null)

    private var bandView: ComposeView? = null
    private var reading by mutableStateOf<ReadingUiState?>(null)
    private val translationCache = mutableMapOf<String, ChipText>()
    private val chipViews = mutableMapOf<String, ComposeView>()

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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (started) return START_NOT_STICKY
        started = true

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_RESULT_DATA, Intent::class.java) }

        // Must follow startForegroundService() within seconds, even if we then bail out.
        // The mediaProjection type requires the consent result to already be in hand (Android 14+).
        val type = if (resultData != null) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)

        if (!canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (resultData != null) {
            capturer = runCatching { ScreenCapturer(this, resultCode, resultData) }
                .onFailure { Log.e(TAG, "Screen capture could not start", it) }
                .getOrNull()
        }
        owner.start()
        addOverlay()
        LensState.update { it.copy(running = true, mode = LensMode.AUTO) }
        val repo = (application as MonetApp).container.settingsRepository
        scope.launch {
            repo.settings.collect { s ->
                LensState.update { it.copy(linesPerStep = s.linesPerStep) }
                reading?.let { r ->
                    if (r.session.linesPerStep != s.linesPerStep) {
                        reading = r.copy(session = r.session.withLinesPerStep(s.linesPerStep))
                        publishReading()
                        requestTranslations()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        readJob?.cancel()
        closeSelect()
        stopReading()
        view?.let { runCatching { windowManager.removeView(it) } }
        view = null
        capturer?.release()
        capturer = null
        runCatching { recognizer.close() }
        if (started) owner.stop()
        scope.cancel()
        LensState.update { it.copy(running = false) }
        super.onDestroy()
    }

    /**
     * Auto mode: captures the screen (without our own windows), groups the text into units and
     * starts a reading pass. Translations arrive per step and are cached by source text.
     */
    private fun startReading() {
        val cap = capturer
        if (cap == null) {
            toast("Screen capture is not available. Turn the lens off and on again.")
            return
        }
        if (readJob?.isActive == true || selectView != null) return
        applyExpanded(false)
        readJob = scope.launch {
            val overlay = view
            val band = bandView
            overlay?.visibility = View.INVISIBLE
            band?.visibility = View.INVISIBLE
            chipViews.values.forEach { it.visibility = View.INVISIBLE }
            // Hiding our windows forces a fresh frame, which also covers a static screen.
            delay(250)
            val frame = cap.grabFrame()
            overlay?.visibility = View.VISIBLE
            band?.visibility = View.VISIBLE
            chipViews.values.forEach { it.visibility = View.VISIBLE }
            if (frame == null) {
                toast("Could not capture the screen.")
                return@launch
            }
            try {
                val blocks = groupIntoBlocks(recognizer.recognize(frame))
                LensState.update { it.copy(lastRead = blocks) }
                // System bars are not page content; skip text recognized inside them.
                val units = buildReadingUnits(blocks.filter { it.top >= systemBarTopPx() && it.bottom <= frame.height - systemBarBottomPx() })
                if (units.isEmpty()) {
                    toast("No text found.")
                    return@launch
                }
                val styles = units.associate { u ->
                    val bg = medianArgb(borderPixels(frame.width, frame.height, u.bounds) { x, y -> frame.getPixel(x, y) })
                    u.id to ChipStyle(bg, pickTextColor(bg))
                }
                val settings = (application as MonetApp).container.settingsRepository.settings.first()
                clearChips()
                reading = ReadingUiState(ReadingSession(units, settings.linesPerStep), styles, emptyMap())
                showBand()
                publishReading()
                requestTranslations()
            } catch (e: Exception) {
                Log.e(TAG, "OCR failed", e)
                toast("Could not read the screen.")
            } finally {
                frame.recycle()
            }
        }
    }

    private fun stepReading(forward: Boolean) {
        val r = reading ?: return
        reading = r.copy(session = if (forward) r.session.next() else r.session.prev())
        view?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        publishReading()
        requestTranslations()
    }

    private fun stopReading() {
        clearChips()
        bandView?.let { runCatching { windowManager.removeView(it) } }
        bandView = null
        reading = null
        LensState.update { it.copy(reading = null) }
    }

    /** Keeps the pill (progress, enabled buttons) and the chips in sync with the session. */
    private fun publishReading() {
        val r = reading
        LensState.update {
            it.copy(
                reading = r?.session?.let { s -> ReadingProgress(s.label, s.hasPrev, s.hasNext) },
            )
        }
        if (r != null) {
            reading = r.copy(
                translations = r.session.active.associate { u -> u.id to (translationCache[u.text] ?: ChipText.Loading) },
            )
        }
        syncChips()
    }

    /**
     * One touchable window per active unit. Touchable windows are not subject to the 0.8 opacity
     * cap Android 12+ puts on non-touchable overlays, so the chip fully covers the source text.
     */
    private fun syncChips() {
        val r = reading
        val activeUnits = r?.session?.active.orEmpty()
        val ids = activeUnits.map { it.id }.toSet()
        chipViews.keys.filter { it !in ids }.forEach { id ->
            chipViews.remove(id)?.let { runCatching { windowManager.removeView(it) } }
        }
        if (r == null) return
        val displayW = realDisplaySize(this).first
        var added = false
        activeUnits.forEach { unit ->
            if (unit.id in chipViews) return@forEach
            val style = r.styles[unit.id] ?: return@forEach
            val chipParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = unit.bounds.left - CHIP_INFLATE_PX
                y = unit.bounds.top - CHIP_INFLATE_PX
                if (Build.VERSION.SDK_INT >= 30) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else if (Build.VERSION.SDK_INT >= 28) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            val chipView = ComposeView(this).apply {
                setViewTreeLifecycleOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
                setViewTreeViewModelStoreOwner(owner)
                setContent {
                    MonetTheme(darkTheme = false) {
                        ReadingChip(unit, style, reading?.translations?.get(unit.id) ?: ChipText.Loading, displayW)
                    }
                }
            }
            chipViews[unit.id] = chipView
            windowManager.addView(chipView, chipParams)
            added = true
        }
        // The bubble must stay above the chips so it remains reachable.
        if (added) view?.let {
            windowManager.removeView(it)
            windowManager.addView(it, params)
        }
    }

    private fun clearChips() {
        chipViews.values.forEach { runCatching { windowManager.removeView(it) } }
        chipViews.clear()
    }

    /** One request covers the active step plus the next one, so Next is usually instant. */
    private fun requestTranslations() {
        val r = reading ?: return
        val wanted = (r.session.active + r.session.upcoming()).filter {
            val cached = translationCache[it.text]
            cached == null || cached is ChipText.Unavailable
        }
        if (wanted.isEmpty()) return
        wanted.forEach { translationCache[it.text] = ChipText.Loading }
        publishReading()
        val container = (application as MonetApp).container
        scope.launch {
            try {
                val s = container.settingsRepository.settings.first()
                val context = r.session.units.joinToString(" ") { it.text }.take(1_800)
                val response = container.api.annotateLines(
                    AnnotateLinesRequest(
                        lines = wanted.map { LineInput(it.id, it.text.take(500)) },
                        targetLang = s.targetLang,
                        context = AnnotationContext(surrounding = context),
                    ),
                )
                val byId = wanted.associateBy { it.id }
                response.lines.forEach { line ->
                    val unit = byId[line.id] ?: return@forEach
                    translationCache[unit.text] =
                        if (line.translation.isEmpty()) ChipText.Unavailable(null) else ChipText.Text(line.translation)
                }
                // Anything the server skipped must not stay "loading" forever.
                wanted.forEach { if (translationCache[it.text] == ChipText.Loading) translationCache[it.text] = ChipText.Unavailable(null) }
            } catch (e: ApiException) {
                val note = if (e.isOffline) "Can't reach the server." else e.problem?.title
                wanted.forEach { translationCache[it.text] = ChipText.Unavailable(note) }
            }
            publishReading()
        }
    }

    private fun showBand() {
        bandView?.let { runCatching { windowManager.removeView(it) } }
        val (displayW, displayH) = realDisplaySize(this)
        val bandParams = WindowManager.LayoutParams(
            displayW,
            displayH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 30) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setContent {
                MonetTheme(darkTheme = false) {
                    reading?.let { ReadingBand(it) }
                }
            }
        }
        bandView = composeView
        windowManager.addView(composeView, bandParams)
        // The bubble must stay above the band so it remains reachable.
        view?.let {
            windowManager.removeView(it)
            windowManager.addView(it, params)
        }
    }

    /** Freezes the current screen, runs OCR and lets the user pick text on it. */
    private fun startSelect() {
        val cap = capturer
        if (cap == null) {
            toast("Screen capture is not available. Turn the lens off and on again.")
            return
        }
        if (readJob?.isActive == true || selectView != null) return
        stopReading()
        applyExpanded(false)
        readJob = scope.launch {
            val overlay = view
            overlay?.visibility = View.INVISIBLE
            delay(250)
            val frame = cap.grabFrame()
            if (frame == null) {
                overlay?.visibility = View.VISIBLE
                toast("Could not capture the screen.")
                return@launch
            }
            val lines = try {
                recognizer.recognize(frame)
            } catch (e: Exception) {
                Log.e(TAG, "OCR failed", e)
                overlay?.visibility = View.VISIBLE
                frame.recycle()
                toast("Could not read the screen.")
                return@launch
            }
            if (lines.isEmpty()) {
                overlay?.visibility = View.VISIBLE
                frame.recycle()
                toast("No text found.")
                return@launch
            }
            selectFrame = frame
            selection = SelectionUiState(frame.asImageBitmap(), lines)
            showSelectOverlay()
        }
    }

    private fun showSelectOverlay() {
        val actions = SelectActions(
            onGesture = ::onSelectGesture,
            onClose = ::closeSelect,
            onDetails = ::openDetails,
            onRetry = { selection?.result?.let { translateSelection(it.text) } },
        )
        // Exactly the display size (system bars included) so window pixels map 1:1 to frame pixels.
        val (displayW, displayH) = realDisplaySize(this)
        val selectParams = WindowManager.LayoutParams(
            displayW,
            displayH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 30) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        val repo = (application as MonetApp).container.settingsRepository
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
                MonetTheme(darkTheme = dark) {
                    selection?.let { SelectOverlay(it, actions) }
                }
            }
        }
        selectView = composeView
        windowManager.addView(composeView, selectParams)
    }

    private fun onSelectGesture(rect: SelRect) {
        val current = selection ?: return
        val result = resolveSelection(current.lines, rect, tapSizePx = px(48.dp)) ?: return
        selection = current.copy(result = result, card = CardState.Loading)
        translateSelection(result.text)
    }

    private fun translateSelection(text: String) {
        annotateJob?.cancel()
        selection = selection?.copy(card = CardState.Loading)
        val container = (application as MonetApp).container
        annotateJob = scope.launch {
            val card = try {
                val s = container.settingsRepository.settings.first()
                // `fast` keeps the card quick; the Detail sheet asks for `full`.
                CardState.Ready(container.api.annotate(AnnotateRequest(text = text, targetLang = s.targetLang, depth = Depth.FAST)))
            } catch (e: ApiException) {
                CardState.Failed(e.isOffline, e.problem?.detail ?: e.problem?.title ?: "Something went wrong")
            }
            selection = selection?.copy(card = card)
        }
    }

    private fun openDetails() {
        val text = selection?.result?.text ?: return
        val intent = Intent(this, MainActivity::class.java)
            .setAction(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(intent)
        closeSelect()
        stopReading()
    }

    private fun closeSelect() {
        annotateJob?.cancel()
        selectView?.let { runCatching { windowManager.removeView(it) } }
        selectView = null
        selection = null
        selectFrame?.recycle()
        selectFrame = null
        view?.visibility = View.VISIBLE
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

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
                if (mode == LensMode.OFF) {
                    stopSelf()
                } else {
                    if (mode == LensMode.SELECT) stopReading()
                    LensState.update { it.copy(mode = mode) }
                }
            },
            onLines = { n -> scope.launch { repo.setLinesPerStep(n) } },
            onRead = ::startReading,
            onSelect = ::startSelect,
            onPrev = { stepReading(forward = false) },
            onNext = { stepReading(forward = true) },
            onStopReading = ::stopReading,
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
        private const val TAG = "MonetLens"
        private const val CHANNEL_ID = "lens"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "dev.monet.app.lens.STOP"
        const val EXTRA_RESULT_CODE = "dev.monet.app.lens.RESULT_CODE"
        const val EXTRA_RESULT_DATA = "dev.monet.app.lens.RESULT_DATA"
    }
}

private fun Service.systemBarTopPx(): Int = systemDimenPx("status_bar_height")

private fun Service.systemBarBottomPx(): Int = systemDimenPx("navigation_bar_height")

private fun Service.systemDimenPx(name: String): Int {
    val id = resources.getIdentifier(name, "dimen", "android")
    return if (id > 0) resources.getDimensionPixelSize(id) else 0
}
