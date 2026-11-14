package dev.monet.app.feature.lens

import android.graphics.Bitmap
import android.util.Log
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import dev.monet.app.core.network.AnnotateRequest
import dev.monet.app.core.network.ApiException
import dev.monet.app.core.network.Depth
import dev.monet.app.feature.lens.capture.ScreenCapturer
import dev.monet.app.feature.lens.capture.ScreenTextRecognizer
import dev.monet.app.feature.lens.capture.SelRect
import dev.monet.app.feature.lens.capture.resolveSelection
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Select mode. Freezes the screen, lets the user drag over text (or tap a block) and translates
 * only that, in a card at the bottom.
 */
internal class SelectController(
    private val host: LensHost,
    private val recognizer: ScreenTextRecognizer,
    private val capturer: () -> ScreenCapturer?,
    /** The frozen frame is shown to the user, so the bubble must not be in it. */
    private val setBubbleVisible: (Boolean) -> Unit,
    private val prepareCapture: () -> Boolean,
    private val onOpenDetail: (String) -> Unit,
    private val onNeedCapture: () -> Unit,
    private val onOpenChanged: (open: Boolean) -> Unit,
) {
    private var view: View? = null
    private var frame: Bitmap? = null
    private var state by mutableStateOf<SelectionUiState?>(null)
    private var captureJob: Job? = null
    private var annotateJob: Job? = null

    val isOpen: Boolean get() = view != null

    fun start() {
        val cap = capturer()
        if (cap == null) {
            onNeedCapture()
            return
        }
        if (captureJob?.isActive == true || isOpen) return
        captureJob = host.scope.launch {
            prepareCapture()
            setBubbleVisible(false)
            delay(CLEAR_FRAME_MS)
            val captured = cap.grabFrame()
            if (captured == null) {
                setBubbleVisible(true)
                host.toast("Could not capture the screen.")
                return@launch
            }
            val lines = try {
                recognizer.recognize(captured)
            } catch (e: Exception) {
                Log.e(TAG, "OCR failed", e)
                emptyList()
            }
            if (lines.isEmpty()) {
                captured.recycle()
                setBubbleVisible(true)
                host.toast("No text found.")
                return@launch
            }
            frame = captured
            state = SelectionUiState(captured.asImageBitmap(), lines)
            show()
        }
    }

    private fun show() {
        val actions = SelectActions(
            onGesture = ::onGesture,
            onClose = ::close,
            onDetails = {
                state?.result?.text?.let { text ->
                    close()
                    onOpenDetail(text)
                }
            },
            onRetry = { state?.result?.let { translate(it.text) } },
        )
        // Exactly the display size (system bars included) so window pixels map 1:1 to frame pixels.
        val (width, height) = host.displaySize()
        val overlay = host.composeView { state?.let { SelectOverlay(it, actions) } }
        view = overlay
        host.add(overlay, host.params(width, height))
        onOpenChanged(true)
    }

    private fun onGesture(rect: SelRect) {
        val current = state ?: return
        val result = resolveSelection(current.lines, rect, tapSizePx = host.px(48.dp)) ?: return
        state = current.copy(result = result, card = CardState.Loading)
        translate(result.text)
    }

    private fun translate(text: String) {
        annotateJob?.cancel()
        state = state?.copy(card = CardState.Loading)
        annotateJob = host.scope.launch {
            val card = try {
                val settings = host.container.settingsRepository.settings.first()
                // `fast` keeps the card quick; the Detail sheet asks for `full`.
                CardState.Ready(
                    host.container.api.annotate(AnnotateRequest(text = text, targetLang = settings.targetLang, depth = Depth.FAST)),
                )
            } catch (e: ApiException) {
                CardState.Failed(e.isOffline, e.problem?.detail ?: e.problem?.title ?: "Something went wrong")
            }
            state = state?.copy(card = card)
        }
    }

    fun close() {
        captureJob?.cancel()
        annotateJob?.cancel()
        val wasOpen = isOpen
        host.remove(view)
        view = null
        state = null
        // Not recycled here: the window may still draw one more time with it; the GC reclaims it.
        frame = null
        setBubbleVisible(true)
        if (wasOpen) onOpenChanged(false)
    }

    private companion object {
        const val TAG = "MonetLens"
        const val CLEAR_FRAME_MS = 250L
    }
}
