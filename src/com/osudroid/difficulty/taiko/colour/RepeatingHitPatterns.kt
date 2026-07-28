package com.osudroid.difficulty.taiko.colour

import kotlin.math.min

/**
 * A run of [AlternatingMonoPattern]s that is checked against the patterns before it for repetition.
 *
 * A colour pattern that the player has recently seen is much easier to read the second time, so how
 * long ago the same pattern last appeared is what actually drives colour difficulty.
 */
class RepeatingHitPatterns(
    /**
     * The [RepeatingHitPatterns] that occurs before this one.
     */
    @JvmField
    val previous: RepeatingHitPatterns?
) {
    /**
     * The [AlternatingMonoPattern]s that make up this [RepeatingHitPatterns].
     */
    @JvmField
    val alternatingMonoPatterns = mutableListOf<AlternatingMonoPattern>()

    /**
     * How many patterns ago this pattern last appeared.
     *
     * This is [MAX_REPETITION_INTERVAL] plus one when the pattern has not been seen recently, which
     * is treated as "not a repetition" by the colour evaluator.
     */
    @JvmField
    var repetitionInterval = MAX_REPETITION_INTERVAL + 1

    /**
     * The first hit of this [RepeatingHitPatterns].
     */
    val firstHitObject
        get() = alternatingMonoPatterns.firstOrNull()?.firstHitObject

    /**
     * Finds how many patterns ago this pattern last appeared, and stores it in [repetitionInterval].
     */
    fun findRepetitionInterval() {
        // The immediately preceding pattern is skipped, as an alternating pattern is by definition
        // different from the one right before it.
        var other = previous?.previous

        if (other == null) {
            repetitionInterval = MAX_REPETITION_INTERVAL + 1
            return
        }

        var interval = 2

        while (interval < MAX_REPETITION_INTERVAL) {
            if (isRepetitionOf(other!!)) {
                repetitionInterval = min(interval, MAX_REPETITION_INTERVAL)
                return
            }

            other = other.previous ?: break

            ++interval
        }

        repetitionInterval = MAX_REPETITION_INTERVAL + 1
    }

    /**
     * Determines whether this [RepeatingHitPatterns] plays identically to [other].
     *
     * Only the opening patterns are compared, as that is what the player recognises before they have
     * to commit to the rest of the shape.
     *
     * @param other The [RepeatingHitPatterns] to compare against.
     * @return Whether both patterns are the same.
     */
    private fun isRepetitionOf(other: RepeatingHitPatterns): Boolean {
        if (alternatingMonoPatterns.size != other.alternatingMonoPatterns.size) {
            return false
        }

        for (i in 0 until min(alternatingMonoPatterns.size, 2)) {
            if (!alternatingMonoPatterns[i].isRepetitionOf(other.alternatingMonoPatterns[i])) {
                return false
            }
        }

        return true
    }

    companion object {
        /**
         * The longest interval that is still considered a repetition.
         */
        const val MAX_REPETITION_INTERVAL = 16
    }
}
