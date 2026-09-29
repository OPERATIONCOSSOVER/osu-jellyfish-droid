package com.osudroid.ui.v2.taiko

import org.anddev.andengine.opengl.texture.region.TextureRegion
import ru.nsu.ccfit.zuev.osu.ResourceManager

/**
 * The osu!taiko skin elements this scene understands.
 *
 * None of these have a built-in counterpart in `assets/gfx`, which is how `ResourceManager`
 * normally discovers texture names, so they are registered explicitly against the skin folder
 * instead (see `ResourceManager.TAIKO_SKIN_TEXTURES`). `approachcircle` is the one exception: it is
 * shared with osu!standard and therefore already present.
 *
 * Every lookup may return null. That is the normal case for a skin without osu!taiko art, and the
 * scene falls back to drawing the element programmatically.
 */
internal object TaikoSkinNames {

    /** The panel on the far left that the drum sits in. */
    const val BAR_LEFT = "taiko-bar-left"

    /** The drum itself, split into the outer shell and the struck face. */
    const val DRUM_OUTER = "taiko-drum-outer"
    const val DRUM_INNER = "taiko-drum-inner"

    /** The scrolling conveyor belt behind the notes. */
    const val SLIDER = "taiko-slider"

    /** Kiai glow drawn behind the hit position. */
    const val GLOW = "taiko-glow"

    const val HIT_CIRCLE = "taikohitcircle"
    const val BIG_CIRCLE = "taikobigcircle"
    const val HIT_CIRCLE_OVERLAY = "taikohitcircleoverlay"
    const val BIG_CIRCLE_OVERLAY = "taikobigcircleoverlay"

    /** Judgement bursts. osu!stable has separate kat ("k") variants. */
    const val HIT_300 = "taiko-hit300"
    const val HIT_300K = "taiko-hit300k"
    const val HIT_100 = "taiko-hit100"
    const val HIT_100K = "taiko-hit100k"
    const val HIT_0 = "taiko-hit0"

    /** Shared with osu!standard; drawn on the hit position as a border. */
    const val APPROACH_CIRCLE = "approachcircle"
}

/**
 * Resolves osu!taiko skin textures.
 *
 * This deliberately never falls back to the built-in asset pack: osu!taiko art is absent there by
 * design, and a missing texture has to be reported as null so the scene can draw the element
 * itself.
 */
internal object TaikoSkin {

    private val resources get() = ResourceManager.getInstance()

    /**
     * Returns the texture the running skin provides for [name], or null when it provides none.
     */
    operator fun get(name: String): TextureRegion? = resources.getSkinTextureIfLoaded(name)

    /** Whether the running skin provides [name]. */
    fun has(name: String): Boolean = get(name) != null

    /**
     * The burst for a judgement.
     *
     * osu!stable ships separate kat variants of the 300 and 100 bursts, falling back to the plain
     * variant when a skin only provides that one.
     */
    fun burstFor(result: HitResult, isKat: Boolean): TextureRegion? = when (result) {
        HitResult.Great -> if (isKat) {
            get(TaikoSkinNames.HIT_300K) ?: get(TaikoSkinNames.HIT_300)
        } else {
            get(TaikoSkinNames.HIT_300)
        }

        HitResult.Good -> if (isKat) {
            get(TaikoSkinNames.HIT_100K) ?: get(TaikoSkinNames.HIT_100)
        } else {
            get(TaikoSkinNames.HIT_100)
        }

        HitResult.Miss -> get(TaikoSkinNames.HIT_0)
    }

    /** The note body for a note, preferring the big variant where there is one. */
    fun noteBody(isBig: Boolean): TextureRegion? =
        if (isBig) get(TaikoSkinNames.BIG_CIRCLE) ?: get(TaikoSkinNames.HIT_CIRCLE)
        else get(TaikoSkinNames.HIT_CIRCLE)

    /** The note overlay, which osu!stable only shows once the combo reaches a milestone. */
    fun noteOverlay(isBig: Boolean): TextureRegion? =
        if (isBig) get(TaikoSkinNames.BIG_CIRCLE_OVERLAY) ?: get(TaikoSkinNames.HIT_CIRCLE_OVERLAY)
        else get(TaikoSkinNames.HIT_CIRCLE_OVERLAY)
}
