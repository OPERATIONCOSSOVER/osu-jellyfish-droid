package com.osudroid.ui.v2.taiko

import org.anddev.andengine.opengl.texture.region.TextureRegion
import ru.nsu.ccfit.zuev.osu.ResourceManager

/**
 * The osu!taiko skin elements this scene understands.
 *
 * Most of these have no built-in counterpart in `assets/gfx`, which is how `ResourceManager`
 * normally discovers texture names, so they are registered explicitly against the skin folder
 * instead (see `ResourceManager.TAIKO_SKIN_TEXTURES`). `approachcircle`, `sliderscorepoint`,
 * `spinner-circle`, `spinner-approachcircle` and `lighting` are shared with osu!standard and are
 * therefore already present in the built-in asset pack.
 *
 * Every lookup may return null. That is the normal case for a skin without osu!taiko art, and the
 * scene falls back to drawing the element programmatically.
 */
internal object TaikoSkinNames {

    /** The panel on the far left that the drum sits in. */
    const val BAR_LEFT = "taiko-bar-left"

    /** The scrolling conveyor belt behind the notes, and its kiai/fail states. */
    const val BAR_RIGHT = "taiko-bar-right"
    const val BAR_RIGHT_GLOW = "taiko-bar-right-glow"
    const val SLIDER = "taiko-slider"
    const val SLIDER_FAIL = "taiko-slider-fail"

    /** The drum itself, split into the outer shell and the struck face. */
    const val DRUM_OUTER = "taiko-drum-outer"
    const val DRUM_INNER = "taiko-drum-inner"

    /** Kiai glow drawn behind the hit position. */
    const val GLOW = "taiko-glow"

    const val HIT_CIRCLE = "taikohitcircle"
    const val BIG_CIRCLE = "taikobigcircle"
    const val HIT_CIRCLE_OVERLAY = "taikohitcircleoverlay"
    const val BIG_CIRCLE_OVERLAY = "taikobigcircleoverlay"

    /** Judgement bursts. Stable uses the `k` variants for a big note's second hit. */
    const val HIT_300 = "taiko-hit300"
    const val HIT_300K = "taiko-hit300k"
    const val HIT_100 = "taiko-hit100"
    const val HIT_100K = "taiko-hit100k"
    const val HIT_0 = "taiko-hit0"

    /** Drum roll art and its tick markers. */
    const val ROLL_MIDDLE = "taiko-roll-middle"
    const val ROLL_END = "taiko-roll-end"
    const val ROLL_TICK = "sliderscorepoint"
    const val BARLINE = "taiko-barline"

    /** Shaker / denden art. */
    const val SPINNER_WARNING = "spinner-warning"
    const val SPINNER_CIRCLE = "spinner-circle"
    const val SPINNER_APPROACH = "spinner-approachcircle"

    /** Pippidon (the taiko mascot) and its combo-milestone flower. */
    const val PIPPIDON_IDLE = "pippidonidle"
    const val PIPPIDON_KIAI = "pippidonkiai"
    const val PIPPIDON_FAIL = "pippidonfail"
    const val PIPPIDON_CLEAR = "pippidonclear"
    const val FLOWER_GROUP = "taiko-flower-group"

    /** Shared with osu!standard. */
    const val APPROACH_CIRCLE = "approachcircle"
    const val LIGHTING = "lighting"
}

/**
 * Resolves osu!taiko skin textures.
 *
 * This deliberately never falls back to the built-in asset pack for taiko-only elements: osu!taiko
 * art is absent there by design, and a missing texture has to be reported as null so the scene can
 * draw the element itself. Elements shared with osu!standard resolve normally.
 */
internal object TaikoSkin {

    private val resources get() = ResourceManager.getInstance()

    /** Returns the texture the running skin provides for [name], or null when it provides none. */
    operator fun get(name: String): TextureRegion? = resources.getSkinTextureIfLoaded(name)

    /** Whether the running skin provides [name]. */
    fun has(name: String): Boolean = get(name) != null

    /**
     * Returns every frame of an animatable taiko element, or a one-element list holding the static
     * image when the skin only provides that. [withHyphen] matches stable's two naming schemes:
     * `taiko-hit300-0.png` uses a hyphen, `pippidonidle0.png` does not.
     */
    fun frames(name: String, withHyphen: Boolean = true): List<TextureRegion> {
        val count = resources.getFrameCount(name)

        if (count <= 0) {
            return listOfNotNull(get(name))
        }

        val separator = if (withHyphen) "-" else ""

        return buildList {
            for (index in 0 until count) {
                get("$name$separator$index")?.let(::add)
            }
        }
    }

    /** The base name of the burst stable uses for a judgement / strong hit. */
    fun burstName(result: HitResult, isStrong: Boolean): String = when (result) {
        HitResult.Great -> if (isStrong) TaikoSkinNames.HIT_300K else TaikoSkinNames.HIT_300
        HitResult.Good -> if (isStrong) TaikoSkinNames.HIT_100K else TaikoSkinNames.HIT_100
        HitResult.Miss -> TaikoSkinNames.HIT_0
    }

    /** Burst frames for a judgement. Stable shows the `k` sprite on a big note's second hit. */
    fun burstFrames(result: HitResult, isStrong: Boolean): List<TextureRegion> =
        frames(burstName(result, isStrong))

    /** The note body for a note, preferring the big variant where there is one. */
    fun noteBody(isBig: Boolean): TextureRegion? =
        if (isBig) get(TaikoSkinNames.BIG_CIRCLE) ?: get(TaikoSkinNames.HIT_CIRCLE)
        else get(TaikoSkinNames.HIT_CIRCLE)

    /**
     * The note overlay frames. Stable only starts alternating the two frames once the combo reaches
     * 50, and doubles the rate at 150, but the overlay itself is present from the start.
     */
    fun noteOverlayFrames(isBig: Boolean): List<TextureRegion> =
        frames(if (isBig) TaikoSkinNames.BIG_CIRCLE_OVERLAY else TaikoSkinNames.HIT_CIRCLE_OVERLAY)

    /** Pippidon frames for one of stable's four mascot states. */
    fun pippidonFrames(state: MascotState): List<TextureRegion> = frames(
        when (state) {
            MascotState.Idle -> TaikoSkinNames.PIPPIDON_IDLE
            MascotState.Kiai -> TaikoSkinNames.PIPPIDON_KIAI
            MascotState.Fail -> TaikoSkinNames.PIPPIDON_FAIL
            MascotState.Clear -> TaikoSkinNames.PIPPIDON_CLEAR
        },
        withHyphen = false
    )

    /** The first available flower image for a combo milestone. */
    fun flower(): TextureRegion? = frames(TaikoSkinNames.FLOWER_GROUP).firstOrNull()
}
