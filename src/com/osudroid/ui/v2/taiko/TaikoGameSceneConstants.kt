package com.osudroid.ui.v2.taiko

import com.reco1l.framework.Color4

/*
 * Tuning constants for the osu!taiko beta scene.
 *
 * These previously lived in TaikoGameScene's companion object. They are grouped here so the
 * numbers that define how the ruleset looks and scores can be read and adjusted in one place,
 * without scrolling through the scene's behaviour.
 *
 * Wherever osu!stable's behaviour can be verified, the value here matches it and the source is
 * cited:
 *
 * - Note tints are the colours stable multiplies `taikohitcircle.png` / `taikobigcircle.png` by
 *   (Skinning/osu!taiko).
 * - Scoring is ScoreV1 as documented in Gameplay/Score/ScoreV1/osu!taiko.
 * - Health behaviour follows Game_mode/osu!taiko: drain is disabled, the bar starts empty and a
 *   run only passes once it is at least half full.
 * - Hit windows live in `TaikoHitWindow` rather than here, because they are shared with the
 *   difficulty calculator.
 */

// --- Note colours -----------------------------------------------------------------------------

/** Stable tints the note sprite with (235, 69, 44) for a don. */
internal val DON_COLOR = Color4(0xFFEB452C)

/** Stable tints the note sprite with (68, 141, 171) for a kat. */
internal val KAT_COLOR = Color4(0xFF448DAB)

/** Stable tints the note sprite with (252, 83, 6) for a drum roll's head. */
internal val ROLL_HEAD_COLOR = Color4(0xFFFC5306)

/** Colour of an untouched drum roll body. */
internal val ROLL_IDLE_COLOR = Color4(0xFFFBC02D)

/** Colour a drum roll reaches once it is being collected steadily. */
internal val ROLL_ENGAGED_COLOR = Color4(0xFFF9A825)

// --- Presentation timings ---------------------------------------------------------------------

internal const val SONG_INTRO_DURATION_MS = 2000L
internal const val INPUT_FLASH_DURATION = 0.12f
internal const val SKIP_TOUCH_RADIUS = 250f

/** How long a combo milestone burst is shown for. */
internal const val COMBO_MILESTONE_DURATION = 0.45f

// --- Scroll speed -----------------------------------------------------------------------------

/** Pixels travelled per beat at slider velocity 1.0, on an 800px reference playfield. */
internal const val TAIKO_SCROLL_PX_PER_BEAT = 175.0
internal const val REFERENCE_PLAYFIELD_WIDTH = 800f

/** Clamps for extreme slider velocities, so notes never spawn absurdly early or late. */
internal const val MIN_PREEMPT = 200.0
internal const val MAX_PREEMPT = 6000.0

// --- Hit feedback -----------------------------------------------------------------------------

internal const val EXPLOSION_DURATION = 0.12f
internal const val EXPLOSION_GROWTH = 0.6f
internal const val EXPLOSION_ALPHA = 0.85f

internal const val JUDGEMENT_DURATION = 0.35f
internal const val JUDGEMENT_DRIFT = 18f

/**
 * Hit notes clear almost instantly, as in osu!stable. A longer drift leaves struck notes lingering
 * over the lane and makes dense patterns hard to read.
 */
internal const val HIT_DECAY_DURATION = 0.12f
internal const val HIT_DECAY_VELOCITY_X = -90f
internal const val HIT_DECAY_VELOCITY_Y = -1150f
internal const val MISS_DECAY_DURATION = 0.22f

/** A cleared denden lingers a little longer than a note, so the clear reads. */
internal const val DENDEN_CLEAR_DECAY_DURATION = 0.4f

// --- Dendens (swells) -------------------------------------------------------------------------

/**
 * Dendens are easier in taiko than spinners are in osu!, so the required hit count carries this
 * legacy multiplier on top of the difficulty-scaled hits per second.
 */
internal const val SWELL_HIT_MULTIPLIER = 1.65

/**
 * How far a denden's rings grow beyond its centre circle.
 *
 * osu!lazer uses 5x, which only fits because its playfield is far taller than this lane. This is
 * the largest ring that stays inside the lane.
 */
internal const val SWELL_RING_MAX_SCALE = 1.9f

/**
 * Delay after a denden lands on the hit target before its target ring starts growing, in
 * milliseconds. Matches osu!lazer's ring_appear_offset.
 */
internal const val SWELL_RING_APPEAR_OFFSET = 100.0

/** How long a denden's target ring takes to reach full size, in milliseconds. */
internal const val SWELL_RING_GROW_DURATION = 400.0

internal val SWELL_RING_COLOR = Color4(0xFFFFF176)
internal val SWELL_TARGET_RING_COLOR = Color4(0xFFFBC02D)
internal val SWELL_CENTRE_COLOR = Color4(0xFFFFC107)

// --- Scoring (ScoreV1, osu!stable) -------------------------------------------------------------

/**
 * Base score value of a hit, before combo and kiai.
 *
 * osu!stable scores a GREAT at 300 and a GOOD at exactly half that. A big note doubles the value,
 * which is what makes 300/600 for a great and 150/300 for a good.
 */
internal const val SCORE_VALUE_GREAT = 300L
internal const val SCORE_VALUE_GOOD = 150L

/**
 * The "n" of stable's ScoreV1 formula: one point of score per point here, for every full ten
 * combo, up to ten times over.
 *
 * Stable picks one of {32, 48, 64, 80, 96} from the beatmap's difficulty rating. 80 is the
 * documented typical value (roughly 4.5-5 stars on the old rating scale), 64 covers 4-4.5 stars
 * and 96 is the hardest bucket.
 */
internal val TAIKO_SCORE_MULTIPLIERS = intArrayOf(32, 48, 64, 80, 96)

/** Default bucket used when the beatmap's star rating is not available. */
internal const val DEFAULT_TAIKO_SCORE_MULTIPLIER = 80

/**
 * Star rating thresholds separating the [TAIKO_SCORE_MULTIPLIERS] buckets.
 *
 * These are an approximation: stable derives the multiplier from the *old* five star rating, which
 * this client no longer computes. The thresholds are placed so that ordinary maps land on the
 * documented typical value of 80.
 */
internal val TAIKO_SCORE_MULTIPLIER_THRESHOLDS = doubleArrayOf(2.5, 3.5, 4.5, 5.5)

/**
 * Kiai time is osu!taiko's "Go-Go Time" and multiplies every scoring hit by 1.2. The final hit of
 * a denden is multiplied too; the individual denden hits are not.
 */
internal const val KIAI_SCORE_MULTIPLIER = 1.2f

/** Stable steps the combo bonus once per ten combo and stops counting at 100. */
internal const val COMBO_SCORE_STEP = 10
internal const val COMBO_SCORE_LIMIT = 100

/**
 * A drum roll tick is worth a flat 300 (600 on a big roll) with no combo contribution, matching
 * stable's cap of one point per tick rather than per tap.
 */
internal const val ROLL_TICK_SCORE = 300L
internal const val BIG_ROLL_TICK_SCORE = 600L

/** A denden hit is worth a flat 300. Clearing one pays out a GREAT big note instead. */
internal const val DENDEN_HIT_SCORE = 300L

/** Collected ticks needed for a drum roll to reach its fully engaged colour. */
internal const val ROLL_ENGAGED_HITS = 5

// --- Drum rolls -------------------------------------------------------------------------------

/**
 * Stable hard-caps a drum roll at four collectable ticks per beat, so mashing faster than the
 * ticks appear earns nothing.
 */
internal const val ROLL_TICKS_PER_BEAT = 4.0

/** Songs at 125 BPM or below get twice as many ticks per beat. */
internal const val ROLL_TICKS_PER_BEAT_SLOW = 8.0
internal const val ROLL_SLOW_BPM_THRESHOLD = 125.0

// --- Strong (big) notes -----------------------------------------------------------------------

/**
 * How close together two opposite-colour taps must land to count as a single simultaneous hit.
 *
 * Stable requires both keys on a big note for its double score, and treats both keys on a normal
 * note as a miss, so the scene has to be able to tell "one tap" from "two at once".
 */
internal const val STRONG_INPUT_WINDOW = 45.0

/**
 * Stable celebrates a combo milestone every 50 hits rather than osu!'s 25, and speeds the note
 * overlay animation up again at 150.
 */
internal const val COMBO_MILESTONE_INTERVAL = 50
internal const val COMBO_MILESTONE_FAST_INTERVAL = 150

// --- Health ------------------------------------------------------------------------------------

/**
 * osu!taiko does not drain health; the bar starts empty and is filled by hits. A run only passes
 * once it is at least this full.
 */
internal const val HEALTH_PASS_THRESHOLD = 0.5f

/**
 * Health gained or lost per object, as a fraction of the bar, scaled by the map's object count so
 * that a full combo lands near a full bar.
 *
 * Stable's exact rates are not documented; these are tuned so that an all-GREAT run fills the bar
 * and roughly one miss in seven puts a run below the pass mark.
 */
internal const val HEALTH_PER_GREAT = 1.0f
internal const val HEALTH_PER_GOOD = 0.5f
internal const val HEALTH_PER_MISS = -1.5f
internal const val HEALTH_PER_DENDEN_FAIL = -0.75f

// --- Autoplay ---------------------------------------------------------------------------------

/** Shortest gap between autoplay's alternating denden taps, in milliseconds. */
internal const val AUTO_DENDEN_INTERVAL = 50.0
