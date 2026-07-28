package com.osudroid.beatmaps

import com.osudroid.GameMode
import com.osudroid.mods.Mod

/**
 * Represents a [PlayableBeatmap] for [GameMode.Taiko] game mode.
 */
class TaikoPlayableBeatmap @JvmOverloads constructor(
    baseBeatmap: IBeatmap,
    mods: Iterable<Mod>? = null
) : PlayableBeatmap(baseBeatmap, mods) {
    override fun createHitWindow() = TaikoHitWindow(difficulty.od)
}
