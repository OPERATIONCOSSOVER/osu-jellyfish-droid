package com.osudroid.difficulty.taiko.colour

import com.osudroid.difficulty.taiko.TaikoDifficultyHitObject

/**
 * Groups taiko hits into the colour structures that difficulty calculation reads.
 *
 * Encoding happens in three passes, each one built on top of the previous: runs of a single colour,
 * runs of alternating colours that share a length, and finally groups of those alternating runs that
 * can be compared against earlier groups to find repetition.
 */
object TaikoColourEncoder {

    /**
     * Encodes the colour structures of a beatmap and assigns them back to the hits they came from.
     *
     * @param hitObjects All [TaikoDifficultyHitObject]s in the beatmap, in order.
     */
    @JvmStatic
    fun processAndAssign(hitObjects: List<TaikoDifficultyHitObject>) {
        val hitPatterns = encodeRepeatingHitPatterns(
            encodeAlternatingMonoPatterns(
                encodeMonoStreaks(hitObjects)
            )
        )

        for (hitPattern in hitPatterns) {
            hitPattern.findRepetitionInterval()

            hitPattern.alternatingMonoPatterns.forEachIndexed { monoPatternIndex, monoPattern ->
                monoPattern.parent = hitPattern
                monoPattern.index = monoPatternIndex

                monoPattern.monoStreaks.forEachIndexed { monoStreakIndex, monoStreak ->
                    monoStreak.parent = monoPattern
                    monoStreak.index = monoStreakIndex

                    for (hitObject in monoStreak.hitObjects) {
                        hitObject.monoStreak = monoStreak
                        hitObject.alternatingMonoPattern = monoPattern
                        hitObject.repeatingHitPatterns = hitPattern
                    }
                }
            }
        }
    }

    /**
     * Groups hits into runs of a single colour.
     *
     * Drum rolls and swells are dropped here, as they have no colour and do not interrupt the
     * pattern a player is reading.
     */
    private fun encodeMonoStreaks(data: List<TaikoDifficultyHitObject>): List<MonoStreak> {
        val monoStreaks = mutableListOf<MonoStreak>()
        var currentMonoStreak: MonoStreak? = null

        for (hitObject in data) {
            if (!hitObject.isHit) {
                continue
            }

            if (currentMonoStreak == null || hitObject.hitType != currentMonoStreak.hitType) {
                currentMonoStreak = MonoStreak()
                monoStreaks.add(currentMonoStreak)
            }

            currentMonoStreak.hitObjects.add(hitObject)
        }

        return monoStreaks
    }

    /**
     * Groups runs of a single colour into runs of alternating colours that all share a length.
     *
     * A change in run length is what makes the player's hands change shape, so it ends the pattern.
     */
    private fun encodeAlternatingMonoPatterns(data: List<MonoStreak>): List<AlternatingMonoPattern> {
        val monoPatterns = mutableListOf<AlternatingMonoPattern>()
        var currentMonoPattern = AlternatingMonoPattern()

        for (i in data.indices) {
            currentMonoPattern.monoStreaks.add(data[i])

            if (i == data.size - 1 || data[i].runLength != data[i + 1].runLength) {
                monoPatterns.add(currentMonoPattern)
                currentMonoPattern = AlternatingMonoPattern()
            }
        }

        return monoPatterns
    }

    /**
     * Groups alternating runs into the largest structure, which is what gets compared against
     * earlier structures to find repetition.
     */
    private fun encodeRepeatingHitPatterns(data: List<AlternatingMonoPattern>): List<RepeatingHitPatterns> {
        val hitPatterns = mutableListOf<RepeatingHitPatterns>()
        var currentHitPattern: RepeatingHitPatterns? = null

        for (monoPattern in data) {
            val lastMonoPattern = currentHitPattern?.alternatingMonoPatterns?.lastOrNull()

            // A pattern that no longer repeats starts a new group.
            if (lastMonoPattern != null && !monoPattern.isRepetitionOf(lastMonoPattern)) {
                currentHitPattern = null
            }

            if (currentHitPattern == null) {
                currentHitPattern = RepeatingHitPatterns(hitPatterns.lastOrNull())
                hitPatterns.add(currentHitPattern)
            }

            currentHitPattern.alternatingMonoPatterns.add(monoPattern)
        }

        return hitPatterns
    }
}
