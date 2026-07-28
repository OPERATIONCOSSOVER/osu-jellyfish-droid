package com.osudroid.difficulty.evaluators

import com.osudroid.difficulty.taiko.TaikoDifficultyHitObject

/**
 * Evaluates the physical difficulty of keeping up with a taiko note.
 */
object TaikoStaminaEvaluator {

    /**
     * Evaluates the stamina difficulty of a note.
     *
     * Taiko is played with alternating hands, so the note that actually limits how fast a player can
     * go is the one two notes earlier, which is the last note played by the same hand. Drum rolls
     * and swells are free, as they do not have to be hit accurately.
     *
     * @param current The note to evaluate.
     * @return The stamina difficulty of the note.
     */
    @JvmStatic
    fun evaluateDifficultyOf(current: TaikoDifficultyHitObject): Double {
        if (!current.isHit) {
            return 0.0
        }

        val keyPrevious = current.previousNote(1) ?: return 0.0

        return 0.5 + speedBonus(current.startTime - keyPrevious.startTime)
    }

    /**
     * Awards notes that have to be hit in quick succession by the same hand.
     *
     * @param notePairDuration The time between the two notes played by the same hand, in milliseconds.
     */
    private fun speedBonus(notePairDuration: Double) = 175 / (notePairDuration + 100)
}
