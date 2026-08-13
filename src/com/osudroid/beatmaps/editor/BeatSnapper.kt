package com.osudroid.beatmaps.editor

import com.osudroid.beatmaps.timings.TimingControlPointManager
import kotlin.math.round
import kotlin.math.roundToInt

object BeatSnapper {
    /**
     * Snaps [time] to the nearest subdivision of the active uninherited timing point.
     */
    fun snap(time: Int, controlPoints: TimingControlPointManager, divisor: Int): Int {
        require(divisor > 0) { "Beat divisor must be greater than zero" }

        val point = controlPoints.controlPointAt(time.toDouble())
        val step = point.msPerBeat / divisor
        if (!step.isFinite() || step <= 0) {
            return time.coerceAtLeast(0)
        }

        val snapped = point.time + round((time - point.time) / step) * step
        return snapped.roundToInt().coerceAtLeast(point.time.roundToInt().coerceAtLeast(0))
    }
}
