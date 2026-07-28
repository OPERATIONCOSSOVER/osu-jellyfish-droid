package com.osudroid.difficulty.attributes

/**
 * Holds data that can be used to calculate osu!taiko performance points.
 */
class TaikoDifficultyAttributes : DifficultyAttributes() {
    /**
     * The difficulty corresponding to reading the colour of notes.
     */
    @JvmField
    var colourDifficulty = 0.0

    /**
     * The difficulty corresponding to reading rhythm changes.
     */
    @JvmField
    var rhythmDifficulty = 0.0

    /**
     * The difficulty corresponding to keeping up with the note density.
     */
    @JvmField
    var staminaDifficulty = 0.0

    /**
     * The perfect hit window of this beatmap, in milliseconds.
     */
    @JvmField
    var greatHitWindow = 0.0
}
