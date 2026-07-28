package com.osudroid.difficulty.taiko

import com.osudroid.GameMode
import com.osudroid.beatmaps.hitobjects.BankHitSampleInfo
import com.osudroid.beatmaps.hitobjects.HitCircle
import com.osudroid.beatmaps.hitobjects.HitObject
import com.osudroid.beatmaps.hitobjects.Slider
import com.osudroid.beatmaps.hitobjects.Spinner
import com.osudroid.difficulty.DifficultyHitObject
import com.osudroid.difficulty.taiko.colour.AlternatingMonoPattern
import com.osudroid.difficulty.taiko.colour.MonoStreak
import com.osudroid.difficulty.taiko.colour.RepeatingHitPatterns

/**
 * Represents a [HitObject] with additional information for osu!taiko difficulty calculation.
 *
 * osu!droid stores beatmaps with osu!standard hit objects regardless of the mode they are played in,
 * so the taiko reading of an object is derived here instead of during beatmap conversion. Hit
 * samples carry the drum colour: a whistle or a clap is played on the rim, and anything else is
 * played on the skin.
 */
class TaikoDifficultyHitObject(
    obj: HitObject,
    lastObj: HitObject?,
    clockRate: Double,
    difficultyHitObjects: Array<out DifficultyHitObject>,
    index: Int,

    override val overallDifficulty: Double,

    /**
     * All hits in the beatmap, in order.
     *
     * Drum rolls and swells are not part of this list, as they are not read as colours.
     */
    private val noteObjects: MutableList<TaikoDifficultyHitObject>
) : DifficultyHitObject(obj, lastObj, clockRate, difficultyHitObjects, index) {

    override val mode = GameMode.Taiko

    // Taiko notes travel along a track and are all hit in the same place, so there is no circle size
    // to normalise against. These exist purely to satisfy the base class.
    override val normalizedRadius = 50f
    override val smallCircleBonus = 0.0

    private val bankSamples = obj.samples.filterIsInstance<BankHitSampleInfo>()

    /**
     * Whether this is a hit, as opposed to a drum roll or a swell.
     */
    @JvmField
    val isHit = obj is HitCircle

    /**
     * Whether this is a drum roll.
     */
    @JvmField
    val isDrumRoll = obj is Slider

    /**
     * Whether this is a swell.
     */
    @JvmField
    val isSwell = obj is Spinner

    /**
     * Whether this note is played on the rim of the drum.
     */
    @JvmField
    val isRim = bankSamples.any {
        it.name == BankHitSampleInfo.HIT_WHISTLE || it.name == BankHitSampleInfo.HIT_CLAP
    }

    /**
     * Whether this note must be hit with both hands.
     */
    @JvmField
    val isStrong = bankSamples.any { it.name == BankHitSampleInfo.HIT_FINISH }

    /**
     * The colour of this note, or `null` if this is not a hit.
     */
    @JvmField
    val hitType = if (!isHit) null else if (isRim) HitType.Rim else HitType.Centre

    /**
     * The rhythm change that this note introduces.
     */
    @JvmField
    val rhythm = TaikoDifficultyHitObjectRhythm.closestRhythm(
        deltaTime,
        (previous(0) as? TaikoDifficultyHitObject)?.deltaTime
    )

    /**
     * The [MonoStreak] that this note belongs to.
     */
    @JvmField
    var monoStreak: MonoStreak? = null

    /**
     * The [AlternatingMonoPattern] that this note belongs to.
     */
    @JvmField
    var alternatingMonoPattern: AlternatingMonoPattern? = null

    /**
     * The [RepeatingHitPatterns] that this note belongs to.
     */
    @JvmField
    var repeatingHitPatterns: RepeatingHitPatterns? = null

    /**
     * The index of this note in [noteObjects], or -1 if this is not a hit.
     */
    @JvmField
    val noteIndex: Int

    init {
        if (isHit) {
            noteObjects.add(this)
            noteIndex = noteObjects.size - 1
        } else {
            noteIndex = -1
        }
    }

    /**
     * Gets the hit at a specific index before this note, skipping drum rolls and swells.
     *
     * @param backwardsIndex The amount of hits to move backwards by.
     * @return The hit, or `null` if the index is out of range.
     */
    fun previousNote(backwardsIndex: Int) = noteObjects.getOrNull(noteIndex - (backwardsIndex + 1))

    /**
     * Gets the hit at a specific index after this note, skipping drum rolls and swells.
     *
     * @param forwardsIndex The amount of hits to move forwards by.
     * @return The hit, or `null` if the index is out of range.
     */
    fun nextNote(forwardsIndex: Int) = noteObjects.getOrNull(noteIndex + forwardsIndex + 1)

    /**
     * Taiko has no cursor to move, so none of the aim geometry in the base class applies.
     */
    override fun computeProperties(clockRate: Double) = Unit
}
