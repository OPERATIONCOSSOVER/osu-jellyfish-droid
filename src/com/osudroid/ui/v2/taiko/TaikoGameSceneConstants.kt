package com.osudroid.ui.v2.taiko

import com.reco1l.framework.Color4

/*
 * Tuning constants for the osu!taiko beta scene.
 *
 * These previously lived in TaikoGameScene's companion object. They are grouped here so the
 * numbers that define how the ruleset looks and scores can be read and adjusted in one place,
 * without scrolling through the scene's behaviour.
 */

// --- Note colours -----------------------------------------------------------------------------

internal val DON_COLOR = Color4(0xFFEF5350)
internal val KAT_COLOR = Color4(0xFF42A5F5)

// --- Presentation timings ---------------------------------------------------------------------

internal const val SONG_INTRO_DURATION_MS = 2000L
internal const val INPUT_FLASH_DURATION = 0.12f
internal const val SKIP_TOUCH_RADIUS = 250f

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

// --- Scoring ----------------------------------------------------------------------------------

internal const val DENDEN_HIT_SCORE = 300L
internal const val DENDEN_COMPLETE_SCORE = 600L
internal const val ROLL_TICK_SCORE = 300L
internal const val BIG_ROLL_TICK_SCORE = 720L

/** Collected ticks needed for a drum roll to reach its fully engaged colour. */
internal const val ROLL_ENGAGED_HITS = 5

// --- Autoplay ---------------------------------------------------------------------------------

/** Shortest gap between autoplay's alternating denden taps, in milliseconds. */
internal const val AUTO_DENDEN_INTERVAL = 50.0
