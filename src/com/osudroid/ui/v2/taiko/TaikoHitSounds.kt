package com.osudroid.ui.v2.taiko

import com.osudroid.beatmaps.constants.SampleBank
import com.osudroid.beatmaps.hitobjects.BankHitSampleInfo
import com.osudroid.beatmaps.hitobjects.HitSampleInfo
import ru.nsu.ccfit.zuev.osu.GlobalManager

/**
 * osu!taiko specific hit sample resolution.
 *
 * osu!taiko does not share its drum samples with osu!standard. While [TaikoGameScene] is the
 * active scene, every bank sample is looked up under the `taiko-` prefixed banks that ship in
 * `assets/sfx`:
 *
 * ```
 * taiko-normal-hitnormal   taiko-soft-hitnormal
 * taiko-normal-hitwhistle  taiko-soft-hitwhistle
 * taiko-normal-hitfinish   taiko-soft-hitfinish
 * taiko-normal-hitclap     taiko-soft-hitclap
 * ```
 *
 * These are regular entries of `assets/sfx`, which means
 * [ru.nsu.ccfit.zuev.osu.ResourceManager.loadCustomSkin] registers them like any other sound:
 * a user skin (or a beatmap skin) that contains a file with the same base name automatically
 * overrides the bundled one, so the taiko banks are fully skinnable.
 *
 * The osu!standard `normal-hit*` / `soft-hit*` / `drum-hit*` samples are deliberately **not**
 * used as a fallback while playing taiko.
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
     * Samples that come from a file declared by the beatmap itself are left untouched - only the
     * bank samples are remapped, and they never fall back to the osu!standard banks.
     */
    @JvmStatic
    fun lookupNamesFor(sample: HitSampleInfo): List<String> {
        if (sample !is BankHitSampleInfo) {
            return sample.lookupNames
        }

        // Taiko only ships the normal and soft banks. Anything else (including maps that do not
        // declare a bank at all) falls back to the taiko normal bank rather than to osu!standard.
        val bank = when (sample.bank) {
            SampleBank.Soft -> SampleBank.Soft
            SampleBank.Normal -> SampleBank.Normal
            else -> SampleBank.Normal
        }

        return buildList {
            if (sample.customSampleBank >= 2) {
                add("$PREFIX${bank.prefix}-${sample.name}${sample.customSampleBank}")
            }

            add("$PREFIX${bank.prefix}-${sample.name}")

            if (bank != SampleBank.Normal) {
                add("$PREFIX${SampleBank.Normal.prefix}-${sample.name}")
            }
        }
    }
}
