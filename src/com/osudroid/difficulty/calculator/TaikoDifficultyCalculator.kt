package com.osudroid.difficulty.calculator

import com.osudroid.beatmaps.Beatmap
import com.osudroid.beatmaps.PlayableBeatmap
import com.osudroid.beatmaps.TaikoPlayableBeatmap
import com.osudroid.difficulty.attributes.TaikoDifficultyAttributes
import com.osudroid.difficulty.skills.Skill
import com.osudroid.difficulty.skills.TaikoColour
import com.osudroid.difficulty.skills.TaikoRhythm
import com.osudroid.difficulty.skills.TaikoStamina
import com.osudroid.difficulty.taiko.TaikoDifficultyHitObject
import com.osudroid.difficulty.taiko.colour.TaikoColourEncoder
import com.osudroid.difficulty.utils.DifficultyCalculationUtils
import com.osudroid.mods.Mod
import kotlin.math.sinh
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ensureActive

/**
 * A difficulty calculator for calculating osu!taiko star rating.
 */
class TaikoDifficultyCalculator : DifficultyCalculator<TaikoPlayableBeatmap, TaikoDifficultyHitObject, TaikoDifficultyAttributes>() {

    override fun createDifficultyAttributes(
        beatmap: Beatmap,
        playableBeatmap: PlayableBeatmap,
        skills: Array<Skill<TaikoDifficultyHitObject>>,
        objects: Array<TaikoDifficultyHitObject>,
        forReplay: Boolean
    ) = TaikoDifficultyAttributes().apply {
        mods = playableBeatmap.mods.values.toSet()
        clockRate = playableBeatmap.speedMultiplier.toDouble()
        hitCircleCount = playableBeatmap.hitObjects.circleCount
        sliderCount = playableBeatmap.hitObjects.sliderCount
        spinnerCount = playableBeatmap.hitObjects.spinnerCount
        overallDifficulty = playableBeatmap.difficulty.od.toDouble()
        greatHitWindow = playableBeatmap.hitWindow.greatWindow.toDouble()

        // Only hits build combo in osu!taiko. Drum rolls and swells are worth nothing towards it.
        maxCombo = playableBeatmap.hitObjects.circleCount

        val colour = skills.find<TaikoColour>()
        val rhythm = skills.find<TaikoRhythm>()
        val stamina = skills.find<TaikoStamina>()

        colourDifficulty = (colour?.difficultyValue() ?: 0.0) * COLOUR_SKILL_MULTIPLIER
        rhythmDifficulty = (rhythm?.difficultyValue() ?: 0.0) * RHYTHM_SKILL_MULTIPLIER
        staminaDifficulty = (stamina?.difficultyValue() ?: 0.0) * STAMINA_SKILL_MULTIPLIER

        starRating = rescale(combinedDifficultyValue(colour, rhythm, stamina) * STAR_RATING_MULTIPLIER)
    }

    override fun createSkills(beatmap: TaikoPlayableBeatmap, forReplay: Boolean): Array<Skill<TaikoDifficultyHitObject>> {
        val mods = beatmap.mods.values

        return arrayOf(
            TaikoColour(mods),
            TaikoRhythm(mods),
            TaikoStamina(mods)
        )
    }

    @Suppress("UNCHECKED_CAST")
    override fun createDifficultyHitObjects(beatmap: TaikoPlayableBeatmap, scope: CoroutineScope?): Array<TaikoDifficultyHitObject> {
        if (beatmap.hitObjects.objects.isEmpty()) {
            return emptyArray()
        }

        val clockRate = beatmap.speedMultiplier.toDouble()
        val overallDifficulty = beatmap.difficulty.od.toDouble()
        val objects = beatmap.hitObjects.objects
        val arr = arrayOfNulls<TaikoDifficultyHitObject>(objects.size - 1)

        // Colour reading only concerns notes that actually have a colour, so they are collected
        // separately as the objects are built.
        val noteObjects = mutableListOf<TaikoDifficultyHitObject>()

        for (i in 1 until objects.size) {
            scope?.ensureActive()

            arr[i - 1] = TaikoDifficultyHitObject(
                objects[i],
                objects[i - 1],
                clockRate,
                arr as Array<TaikoDifficultyHitObject>,
                i - 1,
                overallDifficulty,
                noteObjects
            )
        }

        // The colour structures span the whole beatmap, so they can only be built once every note exists.
        TaikoColourEncoder.processAndAssign(noteObjects)

        return arr as Array<TaikoDifficultyHitObject>
    }

    override fun createPlayableBeatmap(beatmap: Beatmap, mods: Iterable<Mod>?, scope: CoroutineScope?) =
        beatmap.createTaikoPlayableBeatmap(mods, scope)

    /**
     * Combines the peaks of every skill into a single difficulty value.
     *
     * Combining section by section rewards a section that is hard in several ways at once, which is
     * what actually makes a taiko map difficult to play.
     */
    private fun combinedDifficultyValue(
        colour: TaikoColour?,
        rhythm: TaikoRhythm?,
        stamina: TaikoStamina?
    ): Double {
        val colourPeaks = colour?.currentStrainPeaks ?: return 0.0
        val rhythmPeaks = rhythm?.currentStrainPeaks ?: return 0.0
        val staminaPeaks = stamina?.currentStrainPeaks ?: return 0.0

        val peaks = mutableListOf<Double>()

        for (i in colourPeaks.indices) {
            if (i >= rhythmPeaks.size || i >= staminaPeaks.size) {
                break
            }

            val colourPeak = colourPeaks[i] * COLOUR_SKILL_MULTIPLIER
            val rhythmPeak = rhythmPeaks[i] * RHYTHM_SKILL_MULTIPLIER
            val staminaPeak = staminaPeaks[i] * STAMINA_SKILL_MULTIPLIER

            // Colour and stamina are both hand movement, so they are combined more aggressively than rhythm.
            var peak = DifficultyCalculationUtils.norm(1.5, colourPeak, staminaPeak)
            peak = DifficultyCalculationUtils.norm(2.0, peak, rhythmPeak)

            if (peak > 0) {
                peaks.add(peak)
            }
        }

        var difficulty = 0.0
        var weight = 1.0

        for (peak in peaks.sortedDescending()) {
            difficulty += peak * weight
            weight *= DECAY_WEIGHT
        }

        return difficulty
    }

    /**
     * Maps the raw difficulty onto the star rating scale that players are used to.
     */
    private fun rescale(starRating: Double) =
        if (starRating < 0) starRating else 10.43 * sinh(starRating / 16)

    companion object {
        /**
         * The epoch time of the last change to difficulty calculation, in milliseconds.
         */
        const val VERSION = 1785240700000

        private const val COLOUR_SKILL_MULTIPLIER = 0.375
        private const val RHYTHM_SKILL_MULTIPLIER = 0.375
        private const val STAMINA_SKILL_MULTIPLIER = 0.375
        private const val STAR_RATING_MULTIPLIER = 1.4
        private const val DECAY_WEIGHT = 0.9
    }
}
