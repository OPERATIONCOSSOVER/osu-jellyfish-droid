package com.osudroid.mods

import com.osudroid.mods.settings.FloatModSetting
import com.reco1l.framework.math.roundBy

/**
 * Represents the Grow mod.
 *
 * Hit objects start small and swell up to their normal size as they approach being hit.
 */
class ModGrow : ModObjectScaleTween() {
    override val name = "Grow"
    override val acronym = "GR"

    /**
     * The initial size of hit objects, relative to their normal size.
     *
     * The range matches osu!lazer's: capped just below 1 so the mod always has a visible effect.
     */
    override var startScale by FloatModSetting(
        name = "Initial size",
        key = "startScale",
        valueFormatter = { "${it.roundBy(2)}x" },
        defaultValue = 0.5f,
        minValue = 0f,
        maxValue = 0.99f,
        step = 0.01f,
        precision = 2
    )
}
