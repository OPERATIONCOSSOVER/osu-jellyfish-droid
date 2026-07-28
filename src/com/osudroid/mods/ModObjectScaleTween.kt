package com.osudroid.mods

/**
 * Base class for [Mod]s that adjust the size of hit objects during their fade in animation.
 *
 * Ported from osu!lazer's `OsuModObjectScaleTween`, which is the shared parent of the Grow and
 * Deflate mods.
 *
 * Listing this class in [incompatibleMods] is what makes every scale tween mod mutually exclusive:
 * compatibility is resolved with `KClass.isInstance`, so any two subclasses will reject each other
 * without either of them having to know about the other.
 */
abstract class ModObjectScaleTween : Mod() {
    override val description = "Hit them at the right size!"
    override val type = ModType.Fun

    override val incompatibleMods = super.incompatibleMods + arrayOf(
        // Any other scale tween mod, i.e. Grow against Deflate.
        ModObjectScaleTween::class,
        // Both of these rely on the approach circle, which these mods hide.
        ModApproachDifferent::class,
        ModTraceable::class
    )

    /**
     * The size multiplier applied to hit objects when they first appear.
     *
     * Implemented as a mod setting by subclasses so each can expose its own range.
     */
    abstract var startScale: Float

    /**
     * The size multiplier hit objects settle on by the time they are due to be hit.
     */
    open val endScale = 1f
}
