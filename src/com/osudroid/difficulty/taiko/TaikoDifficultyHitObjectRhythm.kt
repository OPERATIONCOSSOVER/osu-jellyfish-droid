package com.osudroid.difficulty.taiko

import kotlin.math.abs

/**
 * Represents a rhythm change in a taiko map.
 *
 * A rhythm change is described by the ratio between the time elapsed since the previous note and the
 * time elapsed before that. A ratio of 1 means the rhythm did not change at all, whereas a ratio far
 * from 1 means the player has to adapt to a new note density.
 */
class TaikoDifficultyHitObjectRhythm(
    /**
     * The numerator of the rhythm ratio.
     */
    numerator: Int,

    /**
     * The denominator of the rhythm ratio.
     */
    denominator: Int,

    /**
     * The difficulty added by this rhythm change.
     */
    @JvmField
    val difficulty: Double
) {
    /**
     * The ratio between the current delta time and the previous delta time that this rhythm describes.
     */
    @JvmField
    val ratio = numerator.toDouble() / denominator

    companion object {
        /**
         * The rhythm changes that are common enough in taiko maps to be worth recognising.
         *
         * Anything that is not close to one of these is snapped to the nearest entry, which keeps
         * slight timing imprecision in a map from being read as an exotic rhythm.
         */
        private val commonRhythms = arrayOf(
            TaikoDifficultyHitObjectRhythm(1, 1, 0.0),
            TaikoDifficultyHitObjectRhythm(2, 1, 0.3),
            TaikoDifficultyHitObjectRhythm(1, 2, 0.5),
            TaikoDifficultyHitObjectRhythm(3, 1, 0.3),
            TaikoDifficultyHitObjectRhythm(1, 3, 0.35),
            TaikoDifficultyHitObjectRhythm(3, 2, 0.6),
            TaikoDifficultyHitObjectRhythm(2, 3, 0.4),
            TaikoDifficultyHitObjectRhythm(5, 4, 0.5),
            TaikoDifficultyHitObjectRhythm(4, 5, 0.7)
        )

        /**
         * Finds the common rhythm that most closely matches a delta time change.
         *
         * @param deltaTime The time elapsed since the previous note, in milliseconds.
         * @param previousDeltaTime The time elapsed before the previous note, in milliseconds.
         * @return The closest rhythm change.
         */
        @JvmStatic
        fun closestRhythm(deltaTime: Double, previousDeltaTime: Double?): TaikoDifficultyHitObjectRhythm {
            // Without a preceding note there is nothing to compare against, so the rhythm is unchanged.
            if (previousDeltaTime == null || previousDeltaTime <= 0) {
                return commonRhythms[0]
            }

            val ratio = deltaTime / previousDeltaTime

            return commonRhythms.minByOrNull { abs(it.ratio - ratio) } ?: commonRhythms[0]
        }
    }
}
