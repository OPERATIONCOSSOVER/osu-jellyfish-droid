package com.osudroid.ui.v2.taiko

import com.reco1l.framework.Color4

/*
 * Tuning constants for the osu!taiko beta scene.
 *
 * These previously lived in TaikoGameScene's companion object. They are grouped here so the
 * numbers that define how the ruleset looks, animates and scores can be read and adjusted in one
 * place.
 *
 * The presentation numbers are transcribed from osu!stable's 768px reference playfield as
 * reproduced by osu!lazer (TaikoPlayfield.BASE_HEIGHT = 200, INPUT_DRUM_WIDTH = 180, the
 * hit target at x = 180 + 200 / 2 - 24 = 256, playfield y = 216). The animation timings come
 * from lazer's stable-compatibility layer (LegacyHitExplosion, LegacyInputDrum, LegacyKiaiGlow,
 * LegacyCirclePiece, DrawableHit) and the osu!wiki osu!taiko skinning article:
 *
 * - https://osu.ppy.sh/wiki/en/Skinning/osu!taiko
 * - https://github.com/ppy/osu/blob/master/osu.Game.Rulesets.Taiko/UI/TaikoPlayfield.cs
 * - https://github.com/ppy/osu/blob/master/osu.Game.Rulesets.Taiko/Skinning/Legacy/LegacyHitExplosion.cs
 * - https://github.com/ppy/osu/blob/master/osu.Game.Rulesets.Taiko/Objects/Drawables/DrawableHit.cs
 *
 * Scoring is ScoreV1 as documented in Gameplay/Score/ScoreV1/osu!taiko. Health behaviour follows
 * Game_mode/osu!taiko: drain is disabled, the bar starts empty and a run only passes once it is
 * at least half full. Hit windows live in `TaikoHitWindow` because they are shared with the
 * difficulty calculator.
 */

// --- Stable playfield geometry -----------------------------------------------------------------

/** osu!stable's internal gamefield is 480px tall; its skin assets are authored against 768px. */
internal const val STABLE_GAMEFIELD_HEIGHT = 480f
internal const val STABLE_REFERENCE_HEIGHT = 768f

/** Top of the 200px taiko bar, and the bar's height, in the 768px reference space. */
internal const val STABLE_PLAYFIELD_Y = 216f
internal const val STABLE_PLAYFIELD_HEIGHT = 200f

/** Width of `taiko-bar-left` and the input drum. */
internal const val STABLE_INPUT_DRUM_WIDTH = 180f

/** Centre of the judgement circle: 180 (drum) + 100 (half bar) - 24 (stable's offset). */
internal const val STABLE_HIT_TARGET_X = 256f

/** Stable's normal note is 0.475 of the playfield height; a strong note is 1/0.65 times that. */
internal const val STABLE_NORMAL_NOTE_SCALE = 0.475f
internal const val STABLE_STRONG_SCALE = 1f / 0.65f

/** Legacy `approachcircle` / `taikobigcircle` scales on the hit target. */
internal const val STABLE_APPROACH_SCALE = 0.83f
internal const val STABLE_BIG_TARGET_SCALE = 0.8f
internal const val STABLE_APPROACH_ALPHA = 0.47f
internal const val STABLE_BIG_TARGET_ALPHA = 0.22f

/**
 * Stable scrolls notes at 0.1px/ms in its 480px gamefield at base velocity, and applies an extra
 * 1.4 multiplier to slider velocity in taiko (see lazer's TaikoPlayfieldAdjustmentContainer and
 * TaikoBeatmapConverter.VELOCITY_MULTIPLIER).
 */
internal const val STABLE_BASE_SCROLL_PX_PER_MS = 0.1
internal const val STABLE_TAIKO_VELOCITY_MULTIPLIER = 1.4

/** `taiko-slider` scrolls left at 0.1px/ms in the 768px reference space. */
internal const val STABLE_SCROLLER_PX_PER_MS = 0.1f

/** Clamps for extreme slider velocities, so notes never spawn absurdly early or late. */
internal const val MIN_PREEMPT = 200.0
internal const val MAX_PREEMPT = 6000.0

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

/** Fallback colours for the parts stable skins with art. */
internal val TAIKO_BAR_COLOR = Color4(0xFF1B1B22)
internal val TAIKO_BAR_TOP_COLOR = Color4(0xFF2C2C36)
internal val TAIKO_PANEL_COLOR = Color4(0xFFE91E8C)
internal val TAIKO_SCROLLER_COLOR = Color4(0xFFFF9800)
internal val TAIKO_SCROLLER_WAVE_COLOR = Color4(0xFFFFC046)
internal val TAIKO_SCROLLER_FAIL_COLOR = Color4(0xFF4A2B32)
internal val KIAI_GLOW_COLOR = Color4(0xFFFFE400)

// --- Presentation timings ---------------------------------------------------------------------

internal const val SONG_INTRO_DURATION_MS = 2000L
internal const val SKIP_TOUCH_RADIUS = 250f

/** Stable's drum halves flash in over 80ms, hold for 100ms, then fade out over 50ms. */
internal const val DRUM_FLASH_IN = 0.08f
internal const val DRUM_FLASH_HOLD = 0.1f
internal const val DRUM_FLASH_OUT = 0.05f
internal const val DRUM_FLASH_DURATION = DRUM_FLASH_IN + DRUM_FLASH_HOLD + DRUM_FLASH_OUT

/** Legacy hit burst: 120ms fade in, 180ms fade out, with a 0.6 -> 1.1 -> 0.9 -> 1 pop. */
internal const val HIT_BURST_FADE_IN = 0.12f
internal const val HIT_BURST_FADE_OUT = 0.18f
internal const val HIT_BURST_DURATION = HIT_BURST_FADE_IN + HIT_BURST_FADE_OUT
internal const val HIT_BURST_SCALE_UP = 0.096f
internal const val HIT_BURST_SCALE_DOWN = 0.048f
internal const val HIT_BURST_SCALE_SETTLE = 0.024f
internal const val HIT_BURST_START_SCALE = 0.6f
internal const val HIT_BURST_PEAK_SCALE = 1.1f
internal const val HIT_BURST_DIP_SCALE = 0.9f

/**
 * A hit note snaps to the judgement circle, rises over 300ms, falls over the next 600ms, scales
 * to 0.8 and fades over 800ms. Stable rates these independently of the playback rate.
 */
internal const val HIT_FLY_UP_DURATION = 0.3f
internal const val HIT_FLY_DOWN_DURATION = 0.6f
internal const val HIT_FLY_DURATION = 0.8f
internal const val HIT_FLY_SCALE = 0.8f

/** Missed notes fade out in 100ms while they keep scrolling past the judgement circle. */
internal const val MISS_DECAY_DURATION = 0.1f

/** Roll ticks pop to 1.4x and fade out over 200ms. */
internal const val ROLL_TICK_ANIM_DURATION = 0.2f

/** Denden clear: 300ms fade with a small 5% scale-up. */
internal const val DENDEN_CLEAR_DECAY_DURATION = 0.3f

/** Judgement text (used for swell progress/clear) drifts up and fades over 800ms. */
internal const val JUDGEMENT_DURATION = 0.8f
internal const val JUDGEMENT_DRIFT = 36f

/** Kiai glow fades in over 100ms, out over 600ms, and pulses for 80ms on a hit. */
internal const val KIAI_GLOW_FADE_IN = 0.1f
internal const val KIAI_GLOW_FADE_OUT = 0.6f
internal const val KIAI_GLOW_PULSE = 0.08f
internal const val KIAI_GLOW_PULSE_SCALE = 1.1875f

/** `taiko-bar-right-glow` and the scroller's fail state crossfade over 200ms. */
internal const val BAR_GLOW_FADE = 0.2f
internal const val SCROLLER_FAIL_FADE = 0.2f

/** Kiai note flash peaks at 0.3 alpha and decays over max(80ms, beat length - 80ms). */
internal const val KIAI_FLASH_ALPHA = 0.3f
internal const val KIAI_FLASH_MIN_DURATION = 80.0

/** How long a combo milestone / mascot clear state is shown for. */
internal const val COMBO_MILESTONE_DURATION = 0.8f
internal const val MASCOT_FAIL_DURATION = 0.6f

// --- Dendens (swells) -------------------------------------------------------------------------

/**
 * Dendens are easier in taiko than spinners are in osu!, so the required hit count carries this
 * legacy multiplier on top of the difficulty-scaled hits per second.
 */
internal const val SWELL_HIT_MULTIPLIER = 1.65

/** Stable's `spinner-approachcircle` shrinks from this scale to 1 over the swell's duration. */
internal const val SWELL_APPROACH_START_SCALE = 1.6f

/** The spinner circle rotates anti-clockwise on every counted hit. */
internal const val SWELL_ROTATION_PER_HIT = 24f

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
 * Stable starts animating the note overlay at 50 combo and doubles its rate at 150; it celebrates
 * a combo milestone every 50 hits rather than osu!'s 25.
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
