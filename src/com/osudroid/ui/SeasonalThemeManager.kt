package com.osudroid.ui

import ru.nsu.ccfit.zuev.osu.Config
import java.util.Calendar

/**
 * Resolves the seasonal accent the main menu tints itself with.
 *
 * Only Halloween is themed at the moment. While it is in season and the setting is enabled, the
 * spectrum radiating out of the osu! cookie and the star bursts that glow out of the bottom-left
 * and bottom-right corners are drawn orange instead of the usual white.
 *
 * The accent is resolved from the device date, so nothing has to be shipped or downloaded for it
 * to turn itself on.
 */
object SeasonalThemeManager {

    /**
     * Preference key backing the setting. Kept in sync with `res/xml/settings_graphics.xml`.
     */
    const val PREFERENCE_KEY = "seasonalEffects"

    /**
     * Halloween orange, as straight RGB in the 0..1 range AndEngine expects.
     */
    private val HALLOWEEN_ACCENT = floatArrayOf(1f, 0.42f, 0.05f)

    enum class Season { None, Halloween }

    /**
     * Whether seasonal theming is allowed to apply at all.
     */
    @JvmStatic
    fun isEnabled() = Config.getBoolean(PREFERENCE_KEY, true)

    /**
     * The season the current date falls in, ignoring whether the setting is on.
     *
     * Halloween covers the whole of October plus the first two days of November, so the theme does
     * not vanish the morning after.
     */
    @JvmStatic
    fun getCurrentSeason(): Season {
        val calendar = Calendar.getInstance()
        val month = calendar.get(Calendar.MONTH)
        val day = calendar.get(Calendar.DAY_OF_MONTH)

        return when {
            month == Calendar.OCTOBER -> Season.Halloween
            month == Calendar.NOVEMBER && day <= 2 -> Season.Halloween
            else -> Season.None
        }
    }

    @JvmStatic
    fun isHalloween() = isEnabled() && getCurrentSeason() == Season.Halloween

    /**
     * The accent the menu should tint with as `[red, green, blue]`, or `null` when nothing is in
     * season or the setting is off. `null` is what makes the menu keep its default white.
     *
     * The returned array is shared and must only ever be read.
     */
    @JvmStatic
    fun getAccentColor(): FloatArray? = if (isHalloween()) HALLOWEEN_ACCENT else null
}
