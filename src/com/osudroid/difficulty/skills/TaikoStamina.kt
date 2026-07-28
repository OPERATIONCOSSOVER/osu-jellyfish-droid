package com.osudroid.difficulty.skills

import com.osudroid.difficulty.evaluators.TaikoStaminaEvaluator
import com.osudroid.difficulty.taiko.TaikoDifficultyHitObject
import com.osudroid.mods.Mod
import kotlin.math.pow

/**
 * Represents the skill required to keep up with the physical demand of hitting taiko notes.
 */
class TaikoStamina(mods: Iterable<Mod>) : StrainSkill<TaikoDifficultyHitObject>(mods) {

    private var currentStrain = 0.0

    override fun strainValueAt(current: TaikoDifficultyHitObject): Double {
        currentStrain *= strainDecay(current.deltaTime)
        currentStrain += TaikoStaminaEvaluator.evaluateDifficultyOf(current) * SKILL_MULTIPLIER

        return currentStrain
    }

    override fun calculateInitialStrain(time: Double, current: TaikoDifficultyHitObject): Double {
        val previous = current.previous(0) ?: return 0.0

        return currentStrain * strainDecay(time - previous.startTime)
    }

    override fun difficultyValue() = weightedDifficultyOf(currentStrainPeaks)

    private fun strainDecay(ms: Double) = STRAIN_DECAY_BASE.pow(ms / 1000)

    companion object {
        private const val SKILL_MULTIPLIER = 1.1
        private const val STRAIN_DECAY_BASE = 0.4
    }
}
