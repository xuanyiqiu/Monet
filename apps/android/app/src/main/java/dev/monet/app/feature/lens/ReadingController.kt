package dev.monet.app.feature.lens

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import dev.monet.app.core.network.AnnotateLinesRequest
import dev.monet.app.core.network.AnnotationContext
import dev.monet.app.core.network.ApiException
import dev.monet.app.core.network.LineInput
import dev.monet.app.feature.detail.underlineStyle
import dev.monet.app.feature.lens.capture.FrameGate
import dev.monet.app.feature.lens.capture.ReadHistory
import dev.monet.app.feature.lens.capture.ReadingSession
import dev.monet.app.feature.lens.capture.ReadingUnit
import dev.monet.app.feature.lens.capture.ScreenCapturer
import dev.monet.app.feature.lens.capture.ScreenTextRecognizer
import dev.monet.app.feature.lens.capture.SelRect
import dev.monet.app.feature.lens.capture.borderPixels
import dev.monet.app.feature.lens.capture.buildReadingUnits
import dev.monet.app.feature.lens.capture.chipTextSizePx
import dev.monet.app.feature.lens.capture.chipWidthPx
import dev.monet.app.feature.lens.capture.excludeRegions
import dev.monet.app.feature.lens.capture.groupIntoBlocks
import dev.monet.app.feature.lens.capture.isReadable
import dev.monet.app.feature.lens.capture.medianArgb
import dev.monet.app.feature.lens.capture.pickTextColor
import dev.monet.app.feature.lens.capture.reanchorIndex
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Auto mode. Reads the screen into units, walks them a few at a time under the reading band, draws
 * a translation chip over each active unit, and reads again when the page scrolls ([FrameGate]),
 * re-anchoring on the first unit the user has not read yet.
 */
internal class ReadingController(
    private val host: LensHost,
    private val recognizer: ScreenTextRecognizer,
    private val capturer: () -> ScreenCapturer?,
    /** Screen areas covered by the Lens's own controls; text under them is not page content. */
    private val ownRegions: () -> List<SelRect>,
    /** Gets the Lens's controls out of the way of a capture; true when that changed the screen. */
    private val prepareCapture: () -> Boolean,
    /** Re-stacks the bubble above windows added after it, so it stays reachable. */
    private val raiseBubble: () -> Unit,
    private val onOpenDetail: (String) -> Unit,
    private val onNeedCapture: () -> Unit,
) {
    private val gate = FrameGate()
    private val history = ReadHistory()
    private val translations = mutableMapOf<String, ChipText>()
    private val chipViews = mutableMapOf<String, View>()
    private val chipParams = mutableMapOf<String, WindowManager.LayoutParams>()
    private val measurePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.SERIF }
    private var bandView: View? = null
    private var handleView: View? = null
    private var handleParams: WindowManager.LayoutParams? = null

    private var ui by mutableStateOf<ReadingUiState?>(null)
    private var chipTextScale by mutableStateOf(1f)
    private var hidden = false
    private var following = true
    private var cacheLang: String? = null
    private var readJob: Job? = null
    private var tickJob: Job? = null

    val isReading: Boolean get() = ui != null

    /** While another Lens surface covers the screen (details, selection), page changes are not ours to follow. */
    var paused: Boolean = false
        set(value) {
            field = value
            if (!value) gate.ownChange(host.now())
        }

    /** Starts a fresh pass over what is on screen now. */
    fun start() {
        history.clear()
        following = true
        read(fresh = true)
    }

    fun stop() {
        readJob?.cancel()
        tickJob?.cancel()
        tickJob = null
        capturer()?.onFrame = null
        clearChips()
        host.remove(bandView)
        bandView = null
        host.remove(handleView)
        handleView = null
        ui = null
        hidden = false
        LensState.update { it.copy(reading = null) }
    }

    fun step(forward: Boolean) {
        val state = ui ?: return
        ui = state.copy(session = if (forward) state.session.next() else state.session.prev())
        (handleView ?: bandView)?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        if (hidden) setHidden(false)
        publish()
        requestTranslations()
    }

    fun setLinesPerStep(lines: Int) {
        val state = ui ?: return
        if (state.session.linesPerStep == lines) return
        ui = state.copy(session = state.session.withLinesPerStep(lines))
        publish()
        requestTranslations()
    }

    fun applyChipTextScale(scale: Float) {
        chipTextScale = scale
    }

    /** The Lens moved or resized one of its own controls; that is not the page changing. */
    fun notifyOwnChange() = gate.ownChange(host.now())

    /** Rotation or a display size change: every position we hold is stale. */
    fun onDisplayChanged() {
        if (!isReading) return
        readJob?.cancel()
        setHidden(true)
        host.remove(bandView)
        bandView = null
        host.scope.launch {
            delay(DISPLAY_CHANGE_SETTLE_MS)
            if (isReading) read(fresh = false)
        }
    }

    private fun read(fresh: Boolean) {
        val cap = capturer()
        if (cap == null) {
            onNeedCapture()
            return
        }
        cap.onFrame = ::onFrame
        readJob?.cancel()
        readJob = host.scope.launch {
            val controlsMoved = prepareCapture()
            val overlaysWereVisible = isReading && !hidden
            setHidden(true)
            // Our own windows are part of what gets captured: wait for a frame without them.
            if (controlsMoved || overlaysWereVisible) delay(CLEAR_FRAME_MS)
            val frame = awaitFrame(cap)
            if (frame == null) {
                if (fresh) host.toast("Could not capture the screen.")
                setHidden(false)
                return@launch
            }
            try {
                val lines = excludeRegions(recognizer.recognize(frame), ownRegions())
                if (Log.isLoggable(TAG, Log.DEBUG)) lines.forEach { Log.d(TAG, "line [${it.left},${it.top} ${it.right},${it.bottom}] ${it.text}") }
                val contentTop = host.statusBarPx()
                val contentBottom = frame.height - host.navigationBarPx()
                // System bars are not page content, and neither are address bars or bare counters.
                val units = buildReadingUnits(
                    groupIntoBlocks(lines, pageWidth = frame.width)
                        .filter { it.top >= contentTop && it.bottom <= contentBottom && isReadable(it.text) },
                )
                if (fresh && units.isEmpty()) {
                    host.toast("No text found.")
                    if (isReading) stop()
                    return@launch
                }
                val previous = ui
                if (!fresh && previous != null) history.markFrom(previous.session)
                val styles = units.associate { unit ->
                    val background = medianArgb(borderPixels(frame.width, frame.height, unit.bounds) { x, y -> frame.getPixel(x, y) })
                    unit.id to ChipStyle(background, pickTextColor(background))
                }
                val linesPerStep = host.container.settingsRepository.settings.first().linesPerStep
                val index = if (fresh) 0 else reanchorIndex(units, history)
                clearChips()
                ui = ReadingUiState(ReadingSession(units, linesPerStep, index), styles, emptyMap())
                ensureBand()
                setHidden(false)
                publish()
                requestTranslations()
                startTicker()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Reading the screen failed", e)
                if (fresh) host.toast("Could not read the screen.")
                setHidden(false)
            } finally {
                frame.recycle()
            }
        }
    }

    private suspend fun awaitFrame(cap: ScreenCapturer): Bitmap? {
        repeat(FRAME_ATTEMPTS) {
            cap.grabFrame()?.let { return it }
            delay(FRAME_RETRY_MS)
        }
        return null
    }

    // ---- Following the page ----------------------------------------------------------------

    /** Runs on the capture thread for every frame. */
    private fun onFrame(signature: ByteArray) {
        val event = gate.onFrame(signature, host.now())
        if (event == FrameGate.Event.MOVING) host.scope.launch { onMoving() }
    }

    private fun onMoving() {
        if (paused || !following || !isReading) return
        // The chips sit where the text used to be; take them away until the page is still again.
        readJob?.cancel()
        setHidden(true)
    }

    private fun startTicker() {
        if (tickJob?.isActive == true) return
        tickJob = host.scope.launch {
            while (isActive) {
                delay(TICK_MS)
                val event = gate.onTick(host.now())
                if (paused || !following || !isReading) continue
                when (event) {
                    FrameGate.Event.SETTLED -> read(fresh = false)
                    FrameGate.Event.RESTLESS -> {
                        following = false
                        host.toast("This screen keeps moving, so Monet stopped following it. Tap Read again for an update.")
                        read(fresh = false)
                    }
                    else -> Unit
                }
            }
        }
    }

    // ---- Windows ---------------------------------------------------------------------------

    private fun setHidden(value: Boolean) {
        hidden = value
        val visibility = if (value) View.GONE else View.VISIBLE
        bandView?.visibility = visibility
        chipViews.values.forEach { it.visibility = visibility }
        handleView?.visibility = if (value || ui?.session?.active.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    /** Keeps the pill (progress, enabled buttons), the chips and the handle in sync with the session. */
    private fun publish() {
        val state = ui
        LensState.update {
            it.copy(reading = state?.session?.let { s -> ReadingProgress(s.label, s.hasPrev, s.hasNext) })
        }
        if (state != null) {
            ui = state.copy(
                translations = state.session.active.associate { unit -> unit.id to (translations[unit.text] ?: ChipText.Loading) },
            )
        }
        syncChips()
        sizeChips()
        syncHandle()
        gate.ownChange(host.now())
    }

    private fun chipTextPx(unit: ReadingUnit): Float {
        val metrics = host.service.resources.displayMetrics
        val lineHeight = unit.bounds.height.toFloat() / unit.lineCount.coerceAtLeast(1)
        return chipTextSizePx(lineHeight, metrics.density, host.service.resources.configuration.fontScale, chipTextScale)
    }

    /**
     * Sets each chip window's width from its text. A window left to size itself is capped at
     * 320dp by Android, which would cut the chip short of a full-width line.
     */
    private fun sizeChips() {
        val state = ui ?: return
        val displayWidth = host.displaySize().first
        val padding = host.px(CHIP_PADDING) + 2 * CHIP_INFLATE_PX
        state.session.active.forEach { unit ->
            val view = chipViews[unit.id] ?: return@forEach
            val params = chipParams[unit.id] ?: return@forEach
            val shown = (state.translations[unit.id] as? ChipText.Text)?.value.orEmpty()
            measurePaint.textSize = chipTextPx(unit)
            val width = chipWidthPx(
                sourceWidthPx = unit.bounds.width + 2 * CHIP_INFLATE_PX,
                textWidthPx = measurePaint.measureText(shown),
                lineCount = unit.lineCount,
                paddingPx = padding,
                maxWidthPx = displayWidth - params.x,
            )
            if (params.width != width) {
                params.width = width
                host.update(view, params)
            }
        }
    }

    private fun ensureBand() {
        if (bandView != null) return
        val (width, height) = host.displaySize()
        val band = host.composeView(followAppTheme = false) { ui?.let { ReadingBand(it) } }
        bandView = band
        host.add(band, host.params(width, height, touchable = false))
        raiseBubble()
    }

    /**
     * One touchable window per active unit. Touchable windows are not subject to the 0.8 opacity
     * cap Android 12+ puts on non-touchable overlays, so the chip fully covers the source text.
     */
    private fun syncChips() {
        val state = ui
        val active = state?.session?.active.orEmpty().take(MAX_CHIPS)
        val ids = active.map { it.id }.toSet()
        chipViews.keys.filter { it !in ids }.forEach { id ->
            host.remove(chipViews.remove(id))
            chipParams.remove(id)
        }
        if (state == null) return
        val scaledDensity = host.service.resources.displayMetrics.density * host.service.resources.configuration.fontScale
        var added = false
        active.forEach { unit ->
            if (unit.id in chipViews) return@forEach
            val style = state.styles[unit.id] ?: return@forEach
            val chip = host.composeView(followAppTheme = false) {
                ReadingChip(
                    unit = unit,
                    style = style,
                    text = ui?.translations?.get(unit.id) ?: ChipText.Loading,
                    textSizeSp = chipTextPx(unit) / scaledDensity,
                    onClick = { onOpenDetail(unit.text) },
                )
            }
            if (hidden) chip.visibility = View.GONE
            val params = host.params(
                unit.bounds.width + 2 * CHIP_INFLATE_PX,
                WindowManager.LayoutParams.WRAP_CONTENT,
                x = (unit.bounds.left - CHIP_INFLATE_PX).coerceAtLeast(0),
                y = (unit.bounds.top - CHIP_INFLATE_PX).coerceAtLeast(0),
            )
            chipViews[unit.id] = chip
            chipParams[unit.id] = params
            host.add(chip, params)
            added = true
        }
        if (added) raiseBubble()
    }

    /** The handle sits at the right edge, just under the band. */
    private fun syncHandle() {
        val session = ui?.session
        val active = session?.active.orEmpty()
        if (session == null || active.isEmpty()) {
            handleView?.visibility = View.GONE
            return
        }
        val (displayWidth, displayHeight) = host.displaySize()
        val size = host.px(ReadingHandleSize)
        val margin = host.px(8.dp)
        val x = displayWidth - size - margin
        val y = (active.maxOf { it.bounds.bottom } + margin).coerceAtMost(displayHeight - host.navigationBarPx() - size - margin)
        val existing = handleView
        if (existing == null) {
            val handle = host.composeView {
                ReadingHandle(
                    hasNext = ui?.session?.hasNext == true,
                    onNext = { step(forward = true) },
                    onPrevious = { step(forward = false) },
                )
            }
            val params = host.params(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, x = x, y = y)
            handleView = handle
            handleParams = params
            if (hidden) handle.visibility = View.GONE
            host.add(handle, params)
            raiseBubble()
        } else {
            handleParams?.let { params ->
                params.x = x
                params.y = y
                host.update(existing, params)
            }
            existing.visibility = if (hidden) View.GONE else View.VISIBLE
        }
    }

    private fun clearChips() {
        chipViews.values.forEach { host.remove(it) }
        chipParams.clear()
        chipViews.clear()
    }

    // ---- Translations ----------------------------------------------------------------------

    /** One request covers the active step plus the next one, so Next is usually instant. */
    private fun requestTranslations() {
        val state = ui ?: return
        host.scope.launch {
            val settings = host.container.settingsRepository.settings.first()
            // Cached translations belong to one target language.
            if (cacheLang != settings.targetLang.name) {
                translations.clear()
                cacheLang = settings.targetLang.name
            }
            val wanted = (state.session.active + state.session.upcoming()).filter { unit ->
                val cached = translations[unit.text]
                cached == null || cached is ChipText.Unavailable
            }.distinctBy { it.text }
            if (wanted.isEmpty()) {
                publish()
                return@launch
            }
            wanted.forEach { translations[it.text] = ChipText.Loading }
            publish()
            try {
                val response = host.container.api.annotateLines(
                    AnnotateLinesRequest(
                        lines = wanted.map { LineInput(it.id, it.text.take(MAX_LINE_CHARS)) },
                        targetLang = settings.targetLang,
                        context = AnnotationContext(
                            surrounding = state.session.units.joinToString(" ") { it.text }.take(MAX_CONTEXT_CHARS),
                            app = SCREEN_OCR_HINT,
                        ),
                    ),
                )
                val byId = wanted.associateBy { it.id }
                response.lines.forEach { line ->
                    val unit = byId[line.id] ?: return@forEach
                    translations[unit.text] = if (line.translation.isEmpty()) {
                        ChipText.Unavailable(null)
                    } else {
                        ChipText.Text(line.translation, line.expressions.firstOrNull()?.kind?.underlineStyle())
                    }
                }
                // Anything the server skipped must not stay "loading" forever.
                wanted.forEach { if (translations[it.text] == ChipText.Loading) translations[it.text] = ChipText.Unavailable(null) }
            } catch (e: ApiException) {
                val note = if (e.isOffline) "Can't reach the server." else e.problem?.title
                wanted.forEach { translations[it.text] = ChipText.Unavailable(note) }
            }
            if (ui != null) publish()
        }
    }

    private companion object {
        const val TAG = "MonetLens"
        const val TICK_MS = 100L
        const val CLEAR_FRAME_MS = 200L
        const val FRAME_ATTEMPTS = 6
        const val FRAME_RETRY_MS = 100L
        const val DISPLAY_CHANGE_SETTLE_MS = 600L
        const val MAX_CHIPS = 40
        const val MAX_LINE_CHARS = 500
        const val MAX_CONTEXT_CHARS = 1_800

        /** Tells the API the text was read off the screen, so it can allow for OCR slips. */
        const val SCREEN_OCR_HINT = "screen-ocr"
        val CHIP_PADDING = 8.dp
    }
}
