package com.osudroid.difficulty.taiko.colour

import com.osudroid.difficulty.taiko.TaikoDifficultyHitObject

/**
 * An unbroken run of hits of the same colour.
 *
 * Repeating the same colour is physically easy, so a long streak is only interesting in terms of how
 * it sits between the streaks of the opposite colour around it.
 */
class MonoStreak {
    /**
     * The hits that make up this [MonoStreak].
     */
    @JvmField
    val hitObjects = mutableListOf<TaikoDifficultyHitObject>()

    /**
     * The [AlternatingMonoPattern] that this [MonoStreak] is part of.
     */
    @JvmField
    var parent: AlternatingMonoPattern? = null

    /**
     * The index of this [MonoStreak] within its [parent].
     */
    @JvmField
    var index = 0

    /**
     * The first hit of this [MonoStreak].
     */
    val firstHitObject
        get() = hitObjects.firstOrNull()

    /**
     * The colour of this [MonoStreak].
     */
    val hitType
        get() = firstHitObject?.hitType

    /**
     * The amount of hits in this [MonoStreak].
     */
    val runLength
        get() = hitObjects.size
}
