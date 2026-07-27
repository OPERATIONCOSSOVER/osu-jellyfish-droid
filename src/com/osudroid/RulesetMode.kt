package com.osudroid

/**
 * Playable rulesets exposed by osu!jellyfish.
 *
 * This is intentionally separate from [GameMode], which controls osu!standard-compatible
 * difficulty and gameplay behavior inside the existing osu!droid ruleset.
 */
enum class RulesetMode(
    @JvmField val beatmapMode: Int,
    @JvmField val displayName: String
) {
    Droid(0, "osu!droid"),
    Taiko(1, "osu!taiko (BETA)");

    fun next() = when (this) {
        Droid -> Taiko
        Taiko -> Droid
    }

    companion object {
        @JvmStatic
        fun fromBeatmapMode(value: Int) = entries.firstOrNull { it.beatmapMode == value } ?: Droid
    }
}
