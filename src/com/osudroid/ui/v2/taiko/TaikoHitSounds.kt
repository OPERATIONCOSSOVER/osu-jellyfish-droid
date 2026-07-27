package com.osudroid.ui.v2.taiko

import com.osudroid.beatmaps.constants.SampleBank
import com.osudroid.beatmaps.hitobjects.BankHitSampleInfo
import com.osudroid.beatmaps.hitobjects.HitSampleInfo
import ru.nsu.ccfit.zuev.osu.GlobalManager

/**
 * osu!taiko specific hit sample resolution.
 *
 * osu!taiko does not share its normal and soft samples with osu!standard. While [TaikoGameScene]
 * is the active scene, those two banks are looked up under the `taiko-` prefixed banks that ship
 * in `assets/sfx`:
 *
 * ```
 * taiko-normal-hitnormal   taiko-soft-hitnormal
 * taiko-normal-hitwhistle  taiko-soft-hitwhistle
 * taiko-normal-hitfinish   taiko-soft-hitfinish
 * taiko-normal-hitclap     taiko-soft-hitclap
 * ```
 *
 * The bank mapping follows the sample set declared by the beatmap:
 *
 * | Beatmap sample set | Samples used                |
 * |--------------------|-----------------------------|
 * | Normal (`N:C1`)    | `taiko-normal-hit*`         |
 * | Soft (`S:C1`)      | `taiko-soft-hit*`           |
 * | Drum (`D:C1`)      | osu!standard `drum-hit*`    |
 * | Unset              | `taiko-normal-hit*`         |
 *
 * The drum bank is deliberately left alone: osu!taiko ships no drum samples of its own, so maps
 * hitsounded with `D:` keep playing the regular osu!standard drum samples instead of being
 * silently rewritten to another bank.
 *
 * The taiko banks are regular entries of `assets/sfx`, which means
 * [ru.nsu.ccfit.zuev.osu.ResourceManager.loadCustomSkin] registers them like any other sound:
 * a user skin (or a beatmap skin) that contains a file with the same base name automatically
 * overrides the bundled one, so the taiko banks are fully skinnable.
 */
object TaikoHitSounds {

    /**
     * The prefix that separates the taiko sample banks from the osu!standard ones.
     */
    const val PREFIX = "taiko-"

    /**
     * Whether taiko sample resolution should be applied, i.e. whether [TaikoGameScene] is the
     * scene that is currently being played.
     */
    @JvmStatic
    val isActive: Boolean
        get() = runCatching {
            GlobalManager.getInstance().engine.scene is TaikoGameScene
        }.getOrDefault(false)

    /**
     * Returns the filenames to look a [HitSampleInfo] up with while playing taiko, in order of
     * preference (highest first).
     *
     * Samples that come from a file declared by the beatmap itself are left untouched, and so are
     * drum bank samples. Only the normal and soft banks are remapped onto the taiko sample set,
     * and those never fall back to their osu!standard counterparts.
     */
    @JvmStatic
    fun lookupNamesFor(sample: HitSampleInfo): List<String> {
        if (sample !is BankHitSampleInfo) {
            return sample.lookupNames
        }

        // Drum banks stay on the osu!standard drum samples.
        if (sample.bank == SampleBank.Drum) {
            return sample.lookupNames
        }

        // Soft keeps the taiko soft bank; normal and unset banks both use the taiko normal bank.
        val bank = if (sample.bank == SampleBank.Soft) SampleBank.Soft else SampleBank.Normal

        return buildList {
            if (sample.customSampleBank >= 2) {
                add("$PREFIX${bank.prefix}-${sample.name}${sample.customSampleBank}")
            }

            add("$PREFIX${bank.prefix}-${sample.name}")
        }
    }
}
