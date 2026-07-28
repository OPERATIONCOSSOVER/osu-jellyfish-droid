package com.osudroid.difficulty.evaluators

import com.osudroid.difficulty.taiko.TaikoDifficultyHitObject
import com.osudroid.difficulty.taiko.colour.AlternatingMonoPattern
import com.osudroid.difficulty.taiko.colour.MonoStreak
import com.osudroid.difficulty.taiko.colour.RepeatingHitPatterns
import kotlin.math.E
import kotlin.math.tanh

/**
 * Evaluates the difficulty of reading the colour of a taiko note.
 */
object TaikoColourEvaluator {

    /**
     * Evaluates the colour difficulty of a note.
     *
     * Difficulty is only awarded on the first note of each colour structure, since that is where the
     * player has to recognise the shape. Every note after that is simply executing a pattern that has
     * already been read.
     *
     * @param current The note to evaluate.
     * @return The colour difficulty of the note.
     */
    @JvmStatic
    fun evaluateDifficultyOf(current: TaikoDifficultyHitObject): Double {
        var difficulty = 0.0

        val monoStreak = current.monoStreak
        val alternatingMonoPattern = current.alternatingMonoPattern
        val repeatingHitPatterns = current.repeatingHitPatterns

        if (monoStreak != null && monoStreak.firstHitObject === current) {
            difficulty += evaluateDifficultyOf(monoStreak)
        }

        if (alternatingMonoPattern != null && alternatingMonoPattern.firstHitObject === current) {
            difficulty += evaluateDifficultyOf(alternatingMonoPattern)
        }

        if (repeatingHitPatterns != null && repeatingHitPatterns.firstHitObject === current) {
            difficulty += evaluateDifficultyOf(repeatingHitPatterns)
        }

        return difficulty
    }

    private fun evaluateDifficultyOf(monoStreak: MonoStreak): Double {
        val parent = monoStreak.parent ?: return 0.0

        return sigmoid(monoStreak.index.toDouble(), 2.0, 2.0, 0.5, 1.0) * evaluateDifficultyOf(parent) * 0.5
    }

    private fun evaluateDifficultyOf(alternatingMonoPattern: AlternatingMonoPattern): Double {
        val parent = alternatingMonoPattern.parent ?: return 0.0

        return sigmoid(alternatingMonoPattern.index.toDouble(), 2.0, 2.0, 0.5, 1.0) * evaluateDifficultyOf(parent)
    }

    private fun evaluateDifficultyOf(repeatingHitPatterns: RepeatingHitPatterns) =
        2 * (1 - sigmoid(repeatingHitPatterns.repetitionInterval.toDouble(), 2.0, 2.0, 0.5, 1.0))

    /**
     * A smooth curve used to fade difficulty in and out rather than stepping between values.
     *
     * @param value The value to evaluate the curve at.
     * @param center The value at which the curve sits halfway.
     * @param width How quickly the curve moves between its bounds.
     * @param middle The value the curve is centred on.
     * @param height The total distance the curve travels.
     */
    private fun sigmoid(value: Double, center: Double, width: Double, middle: Double, height: Double) =
        tanh(E * -(value - center) / width) * (height / 2) + middle
}
