package dev.monet.app.feature.lens.capture

import kotlin.math.abs

/**
 * Decides when the screen under the Lens has changed enough to be read again.
 *
 * It works on tiny frame signatures (a [COLS] x [ROWS] grid of luma samples), so it is cheap enough
 * to run on every captured frame. The flow is: the page starts changing ([Event.MOVING], hide the
 * stale overlays) and, once nothing has moved for [settleMs], it has settled ([Event.SETTLED], read
 * again). A page that never settles, such as a video, ends in [Event.RESTLESS].
 *
 * The Lens draws its own windows into the captured frames. Call [ownChange] whenever those windows
 * change: frames are ignored for [suppressMs] and the next one becomes the new baseline.
 */
class FrameGate(
    private val changedFraction: Float = 0.05f,
    private val motionFraction: Float = 0.01f,
    private val cellDelta: Int = 20,
    private val settleMs: Long = 300,
    private val suppressMs: Long = 450,
    private val maxMovingMs: Long = 4_000,
) {
    enum class Event { NONE, MOVING, SETTLED, RESTLESS }

    private var baseline: ByteArray? = null
    private var last: ByteArray? = null
    private var suppressUntil = 0L
    private var moving = false
    private var movingSince = 0L
    private var lastMotionAt = 0L

    /** The Lens changed its own overlays at [nowMs]; do not mistake that for the page changing. */
    @Synchronized
    fun ownChange(nowMs: Long) {
        suppressUntil = nowMs + suppressMs
        baseline = null
        moving = false
    }

    /** Feed every captured frame. Returns [Event.MOVING] once, when the page starts to change. */
    @Synchronized
    fun onFrame(signature: ByteArray, nowMs: Long): Event {
        val previous = last
        last = signature
        if (nowMs < suppressUntil) return Event.NONE
        if (moving) {
            if (previous == null || changed(signature, previous) >= motionFraction) lastMotionAt = nowMs
            return Event.NONE
        }
        // The last frame seen while suppressed is the settled screen with our overlays on it.
        val base = baseline ?: (previous ?: signature).also { baseline = it }
        if (changed(signature, base) >= changedFraction) {
            moving = true
            movingSince = nowMs
            lastMotionAt = nowMs
            return Event.MOVING
        }
        return Event.NONE
    }

    /** Call periodically: a screen that stopped changing produces no frames to react to. */
    @Synchronized
    fun onTick(nowMs: Long): Event {
        if (!moving) return Event.NONE
        if (nowMs - lastMotionAt >= settleMs) {
            moving = false
            baseline = null
            return Event.SETTLED
        }
        if (nowMs - movingSince >= maxMovingMs) {
            moving = false
            baseline = null
            return Event.RESTLESS
        }
        return Event.NONE
    }

    /** Fraction of cells whose luma differs by more than [cellDelta]; different sizes count as all changed. */
    private fun changed(a: ByteArray, b: ByteArray): Float {
        if (a.size != b.size || a.isEmpty()) return 1f
        var count = 0
        for (i in a.indices) {
            if (abs((a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)) > cellDelta) count++
        }
        return count.toFloat() / a.size
    }

    companion object {
        const val COLS = 32
        const val ROWS = 64
    }
}
