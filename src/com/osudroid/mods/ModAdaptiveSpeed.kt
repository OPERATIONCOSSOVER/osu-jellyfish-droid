package com.osudroid.mods

import com.osudroid.beatmaps.Beatmap
import com.osudroid.mods.settings.FloatModSetting
import com.reco1l.framework.math.roundBy
import kotlinx.coroutines.CoroutineScope
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sign

/**
 * Represents the Adaptive Speed mod.
 *
 * The track rate adapts to the player's hit timing. Hitting early speeds the track up, hitting late slows it down,
 * and missing slows it down by a fixed amount.
 *
 * This is a port of osu!lazer's `ModAdaptiveSpeed`. Unlike osu!lazer, which drives the rate from a per-frame
 * `IUpdatableByPlayfield` hook, this implementation performs its damping inside [applyToRate], which
 * `GameScene.update` already evaluates on every frame.
 */
class ModAdaptiveSpeed : Mod(), IModApplicableToBeatmap, IModApplicableToTrackRate {


    override val name = "Adaptive Speed"

    override val acronym = "AS"

    override val description = "Let track speed adapt to you."

    override val type = ModType.Fun

    // The track rate is driven by the local player's own hits, so gameplay duration differs across players.
    override val isValidForMultiplayer = false

    override val isValidForMultiplayerAsFreeMod = false

    override val incompatibleMods = super.incompatibleMods +
        ModRateAdjust::class + ModTimeRamp::class + ModAutoplay::class

    override val extraInformation
        get() = "${initialRate.roundBy(2)}x"


    /**
     * The rate the track starts at, before any adaptation takes place.
     */
    var initialRate by FloatModSetting(
        name = "Initial rate",
        key = "initialRate",
        valueFormatter = { "${it.roundBy(2)}x" },
        defaultValue = 1f,
        minValue = 0.5f,
        maxValue = 2f,
        step = 0.01f,
        precision = 2
    )


    /**
     * The rate the track is currently playing at.
     */
    private var speedChange = 1f

    /**
     * The rate [speedChange] is being damped toward.
     */
    private var targetRate = 1f

    /**
     * The most recent rates, used as a fixed size rolling window.
     */
    private val recentRates = FloatArray(RECENT_RATE_COUNT) { 1f }

    /**
     * The sorted, distinct end times of every hit object in the beatmap.
     *
     * Used to resolve the end time of the object preceding a judged object.
     */
    private var endTimes = DoubleArray(0)

    /**
     * The track time, in milliseconds, at which [applyToRate] was last evaluated.
     */
    private var lastTime = Double.NaN

    private var isInitialized = false


    override fun applyToBeatmap(beatmap: Beatmap, scope: CoroutineScope?) {
        endTimes = beatmap.hitObjects.objects
            .map { it.endTime }
            .distinct()
            .sorted()
            .toDoubleArray()

        reset()
    }

    override fun applyToRate(time: Double, rate: Float): Float {
        // Difficulty calculation and audio preloading query the rate with infinite times. They expect a single
        // representative value, and must not disturb the gameplay state.
        if (!time.isFinite()) {
            return rate * initialRate
        }

        if (!isInitialized) {
            reset()
        }

        // Time moving backwards means the beatmap was restarted or seeked, so the adaptation starts over.
        if (!lastTime.isNaN() && time < lastTime) {
            reset()
        }

        val elapsed = if (lastTime.isNaN()) 0.0 else time - lastTime
        lastTime = time

        if (elapsed > 0) {
            // Equivalent to osu!lazer's Interpolation.DampContinuously.
            val progress = 1 - 2.0.pow(-elapsed / DAMP_HALF_TIME)
            speedChange += ((targetRate - speedChange) * progress).toFloat()

            // Settle exactly on the target so that the audio rate stops being rewritten every frame once the
            // damping has effectively converged.
            if (abs(targetRate - speedChange) < RATE_SETTLE_THRESHOLD) {
                speedChange = targetRate
            }
        }

        return rate * speedChange
    }

    /**
     * Feeds a judgement into the adaptation.
     *
     * @param objectEndTime The end time of the judged hit object, in milliseconds.
     * @param hitTime The time at which the object was actually hit, in milliseconds.
     * @param isHit Whether the object was hit. Misses apply a fixed slowdown instead.
     */
    fun onObjectJudged(objectEndTime: Double, hitTime: Double, isHit: Boolean) {
        // Objects with no preceding object give no interval to measure against.
        val precedingEndTime = getPrecedingEndTime(objectEndTime) ?: return

        val relativeRateChange = if (!isHit) RATE_CHANGE_ON_MISS else
            ((objectEndTime - precedingEndTime) / (hitTime - precedingEndTime))
                .coerceIn(MIN_ALLOWABLE_RATE_CHANGE, MAX_ALLOWABLE_RATE_CHANGE)

        // Drop the oldest rate and append the newest one.
        System.arraycopy(recentRates, 1, recentRates, 0, recentRates.size - 1)

        recentRates[recentRates.size - 1] = (relativeRateChange * speedChange).toFloat()
            .coerceIn(MIN_ALLOWABLE_RATE, MAX_ALLOWABLE_RATE)

        updateTargetRate()
    }

    /**
     * Moves [targetRate] toward the average of [recentRates], weighted by how consistently those rates have been
     * moving in a single direction. An inconsistent player barely moves the target at all.
     */
    private fun updateTargetRate() {
        var consistency = 0

        for (i in 1 until recentRates.size) {
            consistency += sign(recentRates[i] - recentRates[i - 1]).toInt()
        }

        val average = recentRates.average().toFloat()

        targetRate += (average - targetRate) * (abs(consistency) / (RECENT_RATE_COUNT - 1f))
    }

    private fun getPrecedingEndTime(objectEndTime: Double): Double? {
        if (endTimes.isEmpty()) {
            return null
        }

        var index = endTimes.binarySearch(objectEndTime)

        if (index < 0) {
            index = -index - 1
        }

        index -= 1

        return if (index >= 0) endTimes[index] else null
    }

    private fun reset() {
        speedChange = initialRate
        targetRate = initialRate
        recentRates.fill(initialRate)
        lastTime = Double.NaN
        isInitialized = true
    }


    companion object {
        /**
         * The number of recent rates to keep track of.
         */
        private const val RECENT_RATE_COUNT = 8

        private const val MIN_ALLOWABLE_RATE = 0.4f

        private const val MAX_ALLOWABLE_RATE = 2.5f

        private const val MIN_ALLOWABLE_RATE_CHANGE = 0.9

        private const val MAX_ALLOWABLE_RATE_CHANGE = 1.11

        /**
         * The relative rate change applied when an object is missed.
         */
        private const val RATE_CHANGE_ON_MISS = 0.95

        /**
         * The half-life, in milliseconds, of the damping applied toward the target rate.
         */
        private const val DAMP_HALF_TIME = 50.0

        /**
         * The distance from the target rate at which the current rate snaps onto it.
         */
        private const val RATE_SETTLE_THRESHOLD = 0.0005f
    }
}
