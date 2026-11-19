package dev.monet.app.feature.lens

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class LensMode { OFF, AUTO, SELECT }

/** What the pill needs to show about the active reading pass. */
data class ReadingProgress(val label: String, val hasPrev: Boolean, val hasNext: Boolean)

data class LensUiState(
    val running: Boolean = false,
    val mode: LensMode = LensMode.AUTO,
    val linesPerStep: Int = 2,
    /** Resting opacity of the bubble, in percent. */
    val overlayOpacity: Int = dev.monet.app.core.settings.DEFAULT_OPACITY_PERCENT,
    val lastRead: List<dev.monet.app.feature.lens.capture.TextBlock> = emptyList(),
    val reading: ReadingProgress? = null,
    /** Android ended the screen capture (lock screen, "stop sharing"); the user has to allow it again. */
    val captureLost: Boolean = false,
)

/** Process-wide state shared by the overlay service and the app UI. */
object LensState {
    private val _state = MutableStateFlow(LensUiState())
    val state: StateFlow<LensUiState> = _state.asStateFlow()

    fun update(transform: (LensUiState) -> LensUiState) = _state.update(transform)
}

const val MIN_LINES_PER_STEP = 1
const val MAX_LINES_PER_STEP = dev.monet.app.feature.lens.capture.LINES_ALL

fun stepLines(current: Int, delta: Int): Int = dev.monet.app.core.settings.stepLinesOption(current, delta)

/** The bubble snaps to whichever screen edge its window is closer to. */
fun snapToEdgeX(windowX: Int, windowWidth: Int, screenWidth: Int): Int =
    if (windowX + windowWidth / 2 < screenWidth / 2) 0 else screenWidth - windowWidth

fun isOnRightHalf(windowX: Int, windowWidth: Int, screenWidth: Int): Boolean =
    windowX + windowWidth / 2 >= screenWidth / 2

fun clampY(y: Int, windowHeight: Int, screenHeight: Int): Int =
    y.coerceIn(0, (screenHeight - windowHeight).coerceAtLeast(0))

/** Top of the bubble window while the pill is open: lifted just enough for the pill to fit above [bottomLimit]. */
fun pillTop(collapsedY: Int, pillHeight: Int, bottomLimit: Int): Int =
    minOf(collapsedY, bottomLimit - pillHeight).coerceAtLeast(0)
