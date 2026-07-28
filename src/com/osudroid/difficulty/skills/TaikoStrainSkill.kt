package com.osudroid.difficulty.skills

import com.osudroid.difficulty.taiko.TaikoDifficultyHitObject
import com.osudroid.mods.Mod

/**
 * The base of every osu!taiko skill.
 *
 * Turns the peak strain of each section into a single difficulty value, weighting the hardest
 * sections most heavily so that a map is rated by its peaks rather than its length.
 */
abstract class TaikoStrainSkill(mods: Iterable<Mod>) : StrainSkill<TaikoDifficultyHitObject>(mods) {

    override fun difficultyValue(): Double {
        var difficulty = 0.0
        var weight = 1.0

        for (strain in currentStrainPeaks.filter { it > 0 }.sortedDescending()) {
            difficulty += strain * weight
            weight *= DECAY_WEIGHT
        }

        return difficulty
    }

    companion object {
        /**
         * How much less each section counts than the one above it.
         */
        private const val DECAY_WEIGHT = 0.9
    }
}
