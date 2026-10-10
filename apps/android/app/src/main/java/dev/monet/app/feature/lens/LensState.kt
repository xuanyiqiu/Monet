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
    val lastRead: List<dev.monet.app.feature.lens.capture.TextBlock> = emptyList(),
    val reading: ReadingProgress? = null,
)

/** Process-wide state shared by the overlay service and the app UI. */
object LensState {
    private val _state = MutableStateFlow(LensUiState())
    val state: StateFlow<LensUiState> = _state.asStateFlow()

    fun update(transform: (LensUiState) -> LensUiState) = _state.update(transform)
}

const val MIN_LINES_PER_STEP = 1
const val MAX_LINES_PER_STEP = 5

fun stepLines(current: Int, delta: Int): Int = (current + delta).coerceIn(MIN_LINES_PER_STEP, MAX_LINES_PER_STEP)

/** The bubble snaps to whichever screen edge its window is closer to. */
fun snapToEdgeX(windowX: Int, windowWidth: Int, screenWidth: Int): Int =
    if (windowX + windowWidth / 2 < screenWidth / 2) 0 else screenWidth - windowWidth

fun isOnRightHalf(windowX: Int, windowWidth: Int, screenWidth: Int): Boolean =
    windowX + windowWidth / 2 >= screenWidth / 2

fun clampY(y: Int, windowHeight: Int, screenHeight: Int): Int =
    y.coerceIn(0, (screenHeight - windowHeight).coerceAtLeast(0))
