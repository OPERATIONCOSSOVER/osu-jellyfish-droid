package com.osudroid.difficulty.taiko.colour

/**
 * A run of [MonoStreak]s of alternating colours that all share the same length.
 *
 * This is the level at which colour actually becomes difficult, as the player has to keep swapping
 * hands in a fixed shape rather than repeating one colour.
 */
class AlternatingMonoPattern {
    /**
     * The [MonoStreak]s that make up this [AlternatingMonoPattern].
     */
    @JvmField
    val monoStreaks = mutableListOf<MonoStreak>()

    /**
     * The [RepeatingHitPatterns] that this [AlternatingMonoPattern] is part of.
     */
    @JvmField
    var parent: RepeatingHitPatterns? = null

    /**
     * The index of this [AlternatingMonoPattern] within its [parent].
     */
    @JvmField
    var index = 0

    /**
     * The first hit of this [AlternatingMonoPattern].
     */
    val firstHitObject
        get() = monoStreaks.firstOrNull()?.firstHitObject

    /**
     * Determines whether this [AlternatingMonoPattern] plays identically to [other].
     *
     * Two patterns only feel the same if they have the same shape and start on the same colour.
     *
     * @param other The [AlternatingMonoPattern] to compare against.
     * @return Whether both patterns are the same.
     */
    fun isRepetitionOf(other: AlternatingMonoPattern): Boolean {
        val streak = monoStreaks.firstOrNull() ?: return false
        val otherStreak = other.monoStreaks.firstOrNull() ?: return false

        return streak.runLength == otherStreak.runLength &&
            monoStreaks.size == other.monoStreaks.size &&
            streak.hitType == otherStreak.hitType
    }
}
