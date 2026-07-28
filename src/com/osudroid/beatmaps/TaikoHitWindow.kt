package com.osudroid.beatmaps

/**
 * Represents the osu!taiko hit window.
 */
class TaikoHitWindow @JvmOverloads constructor(
    /**
     * The overall difficulty of this [TaikoHitWindow]. Defaults to 5.
     */
    overallDifficulty: Double? = 5.0
) : HitWindow(overallDifficulty) {
    /**
     * Creates a new [TaikoHitWindow] with the specified overall difficulty.
     * The overall difficulty will be converted to a [Double].
     *
     * @param overallDifficulty The overall difficulty of this [TaikoHitWindow]. Defaults to 5.
     */
    constructor(overallDifficulty: Float? = 5f) : this(overallDifficulty?.toDouble())

    override val greatWindow
        get() = difficultyRange(overallDifficulty, 50.0, 35.0, 20.0)

    override val okWindow
        get() = difficultyRange(overallDifficulty, 120.0, 80.0, 50.0)

    /**
     * osu!taiko has no meh judgement. This is the window past which a hit is missed instead.
     */
    override val mehWindow
        get() = difficultyRange(overallDifficulty, 135.0, 95.0, 70.0)

    /**
     * Maps an overall difficulty onto a hit window.
     *
     * Unlike osu!standard, the taiko windows are not a single linear function of the overall
     * difficulty - they bend at OD 5, so the two halves are interpolated separately.
     *
     * @param difficulty The overall difficulty to map.
     * @param min The window at OD 0.
     * @param mid The window at OD 5.
     * @param max The window at OD 10.
     * @return The window in milliseconds.
     */
    private fun difficultyRange(difficulty: Double, min: Double, mid: Double, max: Double) = when {
        difficulty > 5 -> mid + (max - mid) * (difficulty - 5) / 5
        difficulty < 5 -> mid - (mid - min) * (5 - difficulty) / 5
        else -> mid
    }
}
