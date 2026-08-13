package com.osudroid.mods

/**
 * Represents the osu!stable Spun Out mod.
 */
class ModSpunOut : Mod() {
    override val name = "Spun Out"
    override val acronym = "SO"
    override val description = "Spinners will be automatically completed."
    override val type = ModType.Automation
    override val isRanked = true

    override val incompatibleMods = super.incompatibleMods + arrayOf(
        ModAutoplay::class, ModAutopilot::class
    )

    companion object {
        /**
         * The fixed rotation speed used by osu!stable's Spun Out mod.
         */
        const val SPINS_PER_MINUTE = 286.48f

        /**
         * [SPINS_PER_MINUTE] expressed as rotations per second for gameplay updates.
         */
        const val ROTATIONS_PER_SECOND = SPINS_PER_MINUTE / 60f
    }
}
