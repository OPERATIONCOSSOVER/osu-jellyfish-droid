package com.osudroid.mods

import com.osudroid.mods.settings.FloatModSetting
import com.reco1l.framework.math.roundBy

/**
 * Represents the Deflate mod.
 *
 * Hit objects start oversized and shrink down to their normal size as they approach being hit.
 */
class ModDeflate : ModObjectScaleTween() {
    override val name = "Deflate"
    override val acronym = "DF"

    /**
     * The initial size of hit objects, relative to their normal size.
     *
     * The range matches osu!lazer's: from 1 (no effect) up to a wildly oversized 25.
     */
    override var startScale by FloatModSetting(
        name = "Initial size",
        key = "startScale",
        valueFormatter = { "${it.roundBy(1)}x" },
        defaultValue = 2f,
        minValue = 1f,
        maxValue = 25f,
        step = 0.1f,
        precision = 1
    )
}
