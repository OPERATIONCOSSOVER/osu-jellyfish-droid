package com.osudroid.mods

import com.osudroid.mods.settings.FloatModSetting
import com.reco1l.framework.math.roundBy
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin

/**
 * Represents the Wiggle mod.
 *
 * Ported from osu!lazer's `OsuModWiggle`. Every [WIGGLE_DURATION] milliseconds a hit object picks a
 * new random point around where it should actually be and drifts towards it, so objects never quite
 * sit still.
 *
 * Unlike most other visual mods, Wiggle moves the object itself rather than only its visuals: in
 * osu!lazer the drawable is moved, which takes its hit area along with it. Gameplay code therefore
 * applies the offset to the object's position, not just to its sprites.
 */
class ModWiggle : Mod() {
    override val name = "Wiggle"
    override val acronym = "WG"
    override val description = "They just won't stay still..."
    override val type = ModType.Fun

    /**
     * The multiplier applied to how far objects drift from their real position.
     *
     * The range matches osu!lazer's: 0.1x to 2x in steps of 0.1.
     */
    var strength by FloatModSetting(
        name = "Strength",
        key = "strength",
        valueFormatter = { "${it.roundBy(1)}x" },
        defaultValue = 1f,
        minValue = 0.1f,
        maxValue = 2f,
        step = 0.1f,
        precision = 1
    )

    /**
     * Precomputes the wiggle offsets for a single hit object.
     *
     * The whole trail is generated up front rather than sampled per frame because the offsets come
     * from a seeded [Random]: reproducing the value for an arbitrary time would mean replaying the
     * generator from the start on every frame.
     *
     * @param startTime The start time of the hit object, in milliseconds.
     * @param timePreempt How long the object is visible before it must be hit, in milliseconds.
     * @param duration How long the object lasts after its start time, in milliseconds. Pass 0 for
     * objects without a duration, such as circles.
     * @return The [WiggleTrail] describing how the object should move over its lifetime.
     */
    fun createTrail(startTime: Double, timePreempt: Double, duration: Double): WiggleTrail {
        // Objects wiggle throughout their approach, and then keep wiggling for as long as they last.
        val approachWiggles = (timePreempt / WIGGLE_DURATION).toInt()
        val durationWiggles = (duration / WIGGLE_DURATION).toInt()
        val totalWiggles = approachWiggles + durationWiggles

        val offsets = FloatArray(totalWiggles * 2)

        // Seeding with the start time is what makes a given object always wiggle the same way, which
        // in turn is what keeps replays reproducible.
        val random = Random(startTime.toInt().toLong())

        for (i in 0 until totalWiggles) {
            val angle = random.nextDouble() * 2 * Math.PI
            val distance = random.nextDouble() * strength * WIGGLE_RADIUS

            offsets[i * 2] = (distance * cos(angle)).toFloat()
            offsets[i * 2 + 1] = (distance * sin(angle)).toFloat()
        }

        return WiggleTrail(startTime - timePreempt, startTime, approachWiggles, durationWiggles, offsets)
    }

    /**
     * The precomputed movement of a single hit object under the Wiggle mod.
     *
     * Offsets are expressed in osu!pixels relative to the object's real position, so that this class
     * does not need to know how the playfield is laid out on screen.
     */
    class WiggleTrail internal constructor(
        private val approachStartTime: Double,
        private val startTime: Double,
        private val approachWiggles: Int,
        private val durationWiggles: Int,
        private val offsets: FloatArray
    ) {
        /**
         * The horizontal offset in osu!pixels, as of the last call to [computeOffsetAt].
         */
        @JvmField
        var offsetX = 0f

        /**
         * The vertical offset in osu!pixels, as of the last call to [computeOffsetAt].
         */
        @JvmField
        var offsetY = 0f

        /**
         * Computes the offset at [time], storing the result in [offsetX] and [offsetY].
         *
         * Writing into fields rather than returning a value keeps this allocation free, as it is
         * called for every wiggling object on every frame.
         *
         * @param time The time to sample, in milliseconds.
         */
        fun computeOffsetAt(time: Double) {
            if (offsets.isEmpty() || time <= approachStartTime) {
                offsetX = 0f
                offsetY = 0f
                return
            }

            if (approachWiggles > 0 && time < approachStartTime + approachWiggles * WIGGLE_DURATION) {
                val position = (time - approachStartTime) / WIGGLE_DURATION
                val index = position.toInt()

                interpolate(index, (position - index).toFloat())
                return
            }

            // Between the end of the approach and the start time the object simply holds its last
            // position. This gap only exists because the wiggle count is truncated.
            if (durationWiggles == 0 || time <= startTime) {
                settleOn(approachWiggles - 1)
                return
            }

            if (time < startTime + durationWiggles * WIGGLE_DURATION) {
                val position = (time - startTime) / WIGGLE_DURATION
                val index = position.toInt()

                interpolate(approachWiggles + index, (position - index).toFloat())
                return
            }

            settleOn(approachWiggles + durationWiggles - 1)
        }

        /**
         * Moves linearly from the previous wiggle target towards the one at [index].
         *
         * The very first wiggle starts from the object's real position, matching lazer where the
         * first movement begins wherever the drawable already is.
         */
        private fun interpolate(index: Int, progress: Float) {
            val fromX: Float
            val fromY: Float

            if (index == 0) {
                fromX = 0f
                fromY = 0f
            } else {
                fromX = offsets[(index - 1) * 2]
                fromY = offsets[(index - 1) * 2 + 1]
            }

            val toX = offsets[index * 2]
            val toY = offsets[index * 2 + 1]

            offsetX = fromX + (toX - fromX) * progress
            offsetY = fromY + (toY - fromY) * progress
        }

        private fun settleOn(index: Int) {
            if (index < 0) {
                offsetX = 0f
                offsetY = 0f
                return
            }

            offsetX = offsets[index * 2]
            offsetY = offsets[index * 2 + 1]
        }
    }

    companion object {
        /**
         * How long each individual wiggle takes, in milliseconds. Higher means fewer wiggles.
         */
        const val WIGGLE_DURATION = 100.0

        /**
         * The maximum distance an object can drift at 1x strength, in osu!pixels.
         */
        const val WIGGLE_RADIUS = 7.0
    }
}
