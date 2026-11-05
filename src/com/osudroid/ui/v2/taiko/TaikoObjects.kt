package com.osudroid.ui.v2.taiko

import com.osudroid.beatmaps.hitobjects.HitSampleInfo
import com.reco1l.andengine.component.UIComponent
import com.reco1l.andengine.shape.UIBox
import com.reco1l.andengine.shape.UICircle
import com.reco1l.andengine.sprite.UISprite
import com.reco1l.andengine.text.UIText

/** The four kinds of object the osu!taiko beta scene can put on the playfield. */
internal enum class ObjectKind {
    Don,
    Kat,
    Drumroll,
    Denden
}

/** The judgement an object was given. */
internal enum class HitResult {

    /** A GREAT (300). */
    Great,

    /** A GOOD (100), worth exactly half of a GREAT. */
    Good,

    /** A MISS/BAD, worth nothing and breaking the combo. */
    Miss
}

/** The four states stable's pippidon mascot can be in. */
internal enum class MascotState {
    Idle,
    Kiai,
    Fail,
    Clear
}

/**
 * A single playfield object, resolved from a parsed beatmap hit object.
 *
 * This carries both the immutable description of the object and the mutable state the scene
 * accumulates while it is being played, including the entities currently drawing it.
 */
internal data class TaikoObject(
    val kind: ObjectKind,
    val startTime: Double,
    val endTime: Double,
    val isBig: Boolean,
    val samples: List<HitSampleInfo>,
    /** Scroll speed in pixels per millisecond, resolved from the map's timing and SV. */
    var velocity: Double = 0.0,
    /** Time in milliseconds this object takes to travel from spawn to the hit target. */
    var preempt: Double = 1650.0,
    var judged: Boolean = false,
    var entity: UIComponent? = null,

    // --- Notes ---------------------------------------------------------------------------------

    /**
     * Notes only: whether a big note was struck with both colours at once.
     *
     * osu!stable only pays a big note's double score when both keys land together; a single
     * correct key still hits it, but for the ordinary single value.
     */
    var strongHit: Boolean = false,

    /** Notes only: the colour the object was judged with, used to pick the burst. */
    var judgedIsKat: Boolean = false,

    /** Notes only: how far off the judgement was, in milliseconds. */
    var judgedOffset: Double = 0.0,

    /**
     * Notes only: the score the judgement paid out.
     *
     * A big note struck with one key can still be upgraded to a strong hit when the opposite
     * colour lands within a few tens of milliseconds, and the upgrade pays exactly what the first
     * tap already paid.
     */
    var judgedScore: Long = 0L,

    /**
     * Notes only: when the object was judged.
     *
     * A big note can be upgraded from a single hit to a strong hit if the opposite colour arrives
     * within a few tens of milliseconds, so the original judgement has to stay reachable.
     */
    var judgedAt: Double = 0.0,

    /** Notes only: the sprite drawing the note body, when the skin provides one. */
    var noteSprite: UISprite? = null,

    /** Notes only: the two frames of stable's animated note overlay. */
    var noteOverlayFrames: List<UISprite> = emptyList(),

    /** Notes only: the additive copy that flashes on the beat during kiai time. */
    var noteFlashSprite: UIComponent? = null,

    // --- Dendens (swells) ---------------------------------------------------------------------

    /**
     * Denden only: how many alternating hits are needed to clear it, derived from the map's
     * overall difficulty and the denden's duration.
     */
    var requiredHits: Int = 0,
    /** Denden only: how many alternating hits have been collected so far. */
    var hitsSoFar: Int = 0,
    /**
     * Denden only: the colour of the last counted hit, or null before the first one. osu!taiko only
     * advances the counter when the colour changes, so this is what enforces alternation.
     */
    var lastHitKat: Boolean? = null,

    /** Denden only: the countdown label drawn in the middle of the swell. */
    var counterText: UIText? = null,
    /** Denden only: `spinner-circle`, rotated anti-clockwise on each counted hit. */
    var swellCircle: UIComponent? = null,
    /** Denden only: `spinner-approachcircle`, shrinking towards the circle over the swell. */
    var swellApproachRing: UIComponent? = null,
    /** Denden only: an outline ring showing how much of the swell has been filled. */
    var swellExpandingRing: UICircle? = null,
    /** Denden only: `spinner-warning`, shown while the swell is still approaching. */
    var swellWarning: UIComponent? = null,
    /** Denden only: accumulated rotation of the spinner circle. */
    var swellRotation: Float = 0f,

    // --- Drum rolls ---------------------------------------------------------------------------

    /** Drumroll only: the absolute times of each tick, in milliseconds. */
    var tickTimes: List<Double> = emptyList(),
    /** Drumroll only: the next tick that can still be collected. */
    var nextTickIndex: Int = 0,
    /** Drumroll only: how many ticks were actually collected. */
    var ticksHit: Int = 0,
    /** Drumroll only: half the tick spacing, which is the window a tick can be hit within. */
    var tickWindow: Double = 50.0,
    /** Drumroll only: the elongated body, recoloured as the roll is played. */
    var rollBody: UIComponent? = null,
    /** Drumroll only: the round head drawn over the start of the roll. */
    var rollHead: UIComponent? = null,
    /** Drumroll only: height of this roll's body, i.e. the note diameter it is drawn with. */
    var rollHeight: Float = 0f,
    /** Drumroll only: one marker per collectable tick, popped as it is collected. */
    var tickSprites: MutableList<UIComponent> = mutableListOf(),
    /**
     * Drumroll only: a rolling count of collected ticks, clamped to [ROLL_ENGAGED_HITS]. It rises
     * on a collected tick and falls on a dropped one, and is what drives the body's colour.
     */
    var rollingHits: Int = 0
) {

    /** Whether this object is a don or kat note. */
    val isNote: Boolean
        get() = kind == ObjectKind.Don || kind == ObjectKind.Kat
}

/** The kind of motion a short-lived gameplay entity performs. */
internal enum class DecayMotion {
    /** Simple linear drift with a linear fade. */
    Linear,

    /** A missed note keeps scrolling while it fades over 100ms. */
    Miss,

    /** Stable's parabolic "flying hit": up, then down, shrinking and fading. */
    HitFly,

    /** Stable's legacy hit burst: fast pop, fade in, then fade out. */
    HitBurst,

    /** A collected roll tick scales to 1.4x and fades out. */
    RollTick,

    /** A cleared denden scales up slightly and fades out. */
    SwellClear
}

/**
 * A short-lived gameplay entity and the animation driving it.
 *
 * Keeping judged notes, bursts and ticks around briefly avoids them popping out of existence the
 * instant they are used, and lets every animation follow stable's timing curves rather than a
 * generic linear fade.
 */
internal class DecayingEntity(
    val entity: UIComponent,
    val duration: Float,
    var remaining: Float,
    val motion: DecayMotion = DecayMotion.Linear,
    val velocityX: Float = 0f,
    val velocityY: Float = 0f,
    val originX: Float = 0f,
    val originY: Float = 0f,
    val travelHeight: Float = 0f
)
