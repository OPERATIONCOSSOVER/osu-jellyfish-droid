package com.osudroid.difficulty.calculator

import com.osudroid.difficulty.attributes.DroidDifficultyAttributes
import com.osudroid.difficulty.attributes.StandardDifficultyAttributes
import com.osudroid.mods.ModSpunOut
import org.junit.Assert
import org.junit.Test
import kotlin.math.pow

class SpunOutPerformanceCalculatorTest {
    @Test
    fun `Test standard performance penalty`() {
        val withoutSpunOut = StandardPerformanceCalculator(createStandardAttributes(false)).calculate().total
        val withSpunOut = StandardPerformanceCalculator(createStandardAttributes(true)).calculate().total

        Assert.assertTrue(withoutSpunOut > 0)
        Assert.assertEquals(expectedPenalty, withSpunOut / withoutSpunOut, 1e-12)
    }

    @Test
    fun `Test droid performance penalty`() {
        val withoutSpunOut = DroidPerformanceCalculator(createDroidAttributes(false)).calculate().total
        val withSpunOut = DroidPerformanceCalculator(createDroidAttributes(true)).calculate().total

        Assert.assertTrue(withoutSpunOut > 0)
        Assert.assertEquals(expectedPenalty, withSpunOut / withoutSpunOut, 1e-12)
    }

    private fun createStandardAttributes(spunOut: Boolean) = StandardDifficultyAttributes().apply {
        populateCommonAttributes(spunOut)
        speedDifficulty = 1.2
        speedDifficultStrainCount = 1.0
        speedNoteCount = 90.0
        approachRate = 9.0
    }

    private fun createDroidAttributes(spunOut: Boolean) = DroidDifficultyAttributes().apply {
        populateCommonAttributes(spunOut)
        tapDifficulty = 1.2
        tapDifficultStrainCount = 1.0
        rhythmDifficulty = 1.0
        speedNoteCount = 90.0
    }

    private fun com.osudroid.difficulty.attributes.DifficultyAttributes.populateCommonAttributes(spunOut: Boolean) {
        mods = if (spunOut) setOf(ModSpunOut()) else emptySet()
        maxCombo = totalObjectCount
        aimDifficulty = 1.5
        aimDifficultStrainCount = 1.0
        aimSliderFactor = 1.0
        overallDifficulty = 8.0
        hitCircleCount = hitCircleObjectCount
        spinnerCount = spinnerObjectCount
    }

    companion object {
        private const val hitCircleObjectCount = 90
        private const val spinnerObjectCount = 10
        private const val totalObjectCount = hitCircleObjectCount + spinnerObjectCount
        private val expectedPenalty = 1 - (spinnerObjectCount.toDouble() / totalObjectCount).pow(0.85)
    }
}
