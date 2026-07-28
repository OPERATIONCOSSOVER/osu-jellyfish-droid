package com.osudroid.difficulty.skills

import com.osudroid.difficulty.taiko.TaikoDifficultyHitObject
import com.osudroid.mods.Mod
import kotlin.math.max
import kotlin.math.min

/**
 * Represents the skill required to read the rhythm changes of a taiko beatmap.
 */
class TaikoRhythm(mods: Iterable<Mod>) : StrainSkill<TaikoDifficultyHitObject>(mods) {

    private var currentStrain = 0.0

    /**
     * The amount of notes since the last rhythm change.
     */
    private var notesSinceRhythmChange = 0

    /**
     * The most recent notes, used to notice a rhythm the player has already played.
     */
    private val rhythmHistory = mutableListOf<TaikoDifficultyHitObject>()

    override fun strainValueAt(current: TaikoDifficultyHitObject): Double {
        // Drum rolls and swells have no rhythm to read, and they break up whatever came before them.
        if (!current.isHit) {
            resetRhythmAndStrain()
            return 0.0
        }

        currentStrain *= STRAIN_DECAY

        ++notesSinceRhythmChange

        // The rhythm did not change, so there is nothing new to read.
        if (current.rhythm.difficulty == 0.0) {
            return 0.0
        }

        var objectStrain = current.rhythm.difficulty

        objectStrain *= repetitionPenalties(current)
        objectStrain *= patternLengthPenalty(notesSinceRhythmChange)
        objectStrain *= speedPenalty(current.deltaTime)

        // This has to happen after the penalties above, as they read it.
        notesSinceRhythmChange = 0

        currentStrain += objectStrain

        return currentStrain
    }

    // Rhythm strain is reset at the start of every section rather than carried over, as a rhythm
    // that has already been read does not stay difficult.
    override fun calculateInitialStrain(time: Double, current: TaikoDifficultyHitObject) = 0.0

    override fun difficultyValue() = weightedDifficultyOf(currentStrainPeaks)

    /**
     * Penalises rhythm changes that the player has recently seen.
     */
    private fun repetitionPenalties(current: TaikoDifficultyHitObject): Double {
        var penalty = 1.0

        rhythmHistory.add(current)

        if (rhythmHistory.size > RHYTHM_HISTORY_MAX_LENGTH) {
            rhythmHistory.removeAt(0)
        }

        for (mostRecentPatternsToCompare in 2..RHYTHM_HISTORY_MAX_LENGTH / 2) {
            for (start in rhythmHistory.size - mostRecentPatternsToCompare - 1 downTo 0) {
                if (!samePattern(start, mostRecentPatternsToCompare)) {
                    continue
                }

                penalty *= repetitionPenalty(current.index - rhythmHistory[start].index)
                break
            }
        }

        return penalty
    }

    /**
     * Determines whether the rhythms starting at [start] are the same as the most recent ones.
     */
    private fun samePattern(start: Int, mostRecentPatternsToCompare: Int): Boolean {
        for (i in 0 until mostRecentPatternsToCompare) {
            val earlier = rhythmHistory[start + i]
            val recent = rhythmHistory[rhythmHistory.size - mostRecentPatternsToCompare + i]

            if (earlier.rhythm !== recent.rhythm) {
                return false
            }
        }

        return true
    }

    /**
     * Reduces the value of a rhythm the longer ago it was last played.
     */
    private fun repetitionPenalty(notesSince: Int) = min(1.0, 0.032 * notesSince)

    /**
     * Reduces the value of rhythms that are too short or too long to settle into.
     */
    private fun patternLengthPenalty(patternLength: Int): Double {
        val shortPatternPenalty = min(0.15 * patternLength, 1.0)
        val longPatternPenalty = (2.5 - 0.15 * patternLength).coerceIn(0.0, 1.0)

        return min(shortPatternPenalty, longPatternPenalty)
    }

    /**
     * Reduces the value of rhythm changes that are slow enough to be read one note at a time.
     */
    private fun speedPenalty(deltaTime: Double): Double {
        if (deltaTime < 80) {
            return 1.0
        }

        if (deltaTime < 210) {
            return max(0.0, 1.4 - 0.005 * deltaTime)
        }

        resetRhythmAndStrain()

        return 0.0
    }

    private fun resetRhythmAndStrain() {
        currentStrain = 0.0
        notesSinceRhythmChange = 0
    }

    companion object {
        private const val STRAIN_DECAY = 0.96
        private const val RHYTHM_HISTORY_MAX_LENGTH = 8
    }
}
