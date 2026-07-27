package com.osudroid.ui

import android.util.Log
import org.anddev.andengine.opengl.texture.region.TextureRegion
import ru.nsu.ccfit.zuev.osu.Config
import ru.nsu.ccfit.zuev.osu.ResourceManager
import ru.nsu.ccfit.zuev.osu.helper.FileUtils
import java.io.File
import java.util.Calendar

/**
 * Resolves the background shown on the main menu when the "seasonal backgrounds" setting is
 * enabled.
 *
 * Images are read from the `SeasonalBackgrounds` folder inside the core directory. The folder may
 * either contain images directly, or one subfolder per season:
 *
 * ```
 * osu!droid/SeasonalBackgrounds/Winter/*.png
 * osu!droid/SeasonalBackgrounds/Spring/*.png
 * osu!droid/SeasonalBackgrounds/Summer/*.png
 * osu!droid/SeasonalBackgrounds/Autumn/*.png
 * ```
 *
 * When a subfolder matching the current season exists and contains images, one of those is used.
 * Otherwise any image sitting directly in the folder root is used. One image is picked at random
 * per session and then cached, so the background stays stable while the game is running.
 */
object SeasonalBackgroundManager {

    /**
     * Preference key backing the setting. Kept in sync with `res/xml/settings_graphics.xml`.
     */
    const val PREFERENCE_KEY = "seasonalBackgrounds"

    /**
     * Name of the folder that holds the images, relative to the core path.
     */
    const val FOLDER_NAME = "SeasonalBackgrounds"

    /**
     * Name the loaded texture is registered under in [ResourceManager].
     */
    const val TEXTURE_NAME = "::seasonal-background"

    private val IMAGE_EXTENSIONS = arrayOf(".png", ".jpg", ".jpeg", ".bmp")

    /**
     * Whether a texture was already resolved for this session. Tracked separately from the
     * [ResourceManager] lookup so that a folder with no usable images is not rescanned on every
     * background change.
     */
    private var isResolved = false

    enum class Season(val folderName: String) {
        Winter("Winter"),
        Spring("Spring"),
        Summer("Summer"),
        Autumn("Autumn")
    }

    /**
     * Whether the setting is currently enabled.
     */
    @JvmStatic
    fun isEnabled() = Config.getBoolean(PREFERENCE_KEY, false)

    /**
     * The absolute path of the folder images are read from.
     */
    @JvmStatic
    fun getFolderPath() = Config.getCorePath() + FOLDER_NAME + "/"

    @JvmStatic
    fun getCurrentSeason() = when (Calendar.getInstance().get(Calendar.MONTH)) {
        Calendar.DECEMBER, Calendar.JANUARY, Calendar.FEBRUARY -> Season.Winter
        Calendar.MARCH, Calendar.APRIL, Calendar.MAY -> Season.Spring
        Calendar.JUNE, Calendar.JULY, Calendar.AUGUST -> Season.Summer
        else -> Season.Autumn
    }

    /**
     * Returns the texture to use as the menu background, or `null` when the setting is disabled or
     * no usable image could be found.
     *
     * Must be called from a thread that is allowed to upload textures, in the same way as the other
     * [ResourceManager] loading calls made from the main menu.
     */
    @JvmStatic
    fun load(): TextureRegion? {
        if (!isEnabled()) {
            return null
        }

        if (isResolved) {
            return ResourceManager.getInstance().getTextureIfLoaded(TEXTURE_NAME)
        }

        // Marked as resolved regardless of the outcome, a missing or empty folder should not be
        // rescanned every time the background changes.
        isResolved = true

        val file = pickImage() ?: return null

        return try {
            ResourceManager.getInstance().loadHighQualityFile(TEXTURE_NAME, file)
        } catch (e: Exception) {
            Log.e("SeasonalBackground", "Failed to load " + file.path, e)
            null
        }
    }

    /**
     * Drops the cached selection so that the next [load] call rescans the folder and picks another
     * image.
     */
    @JvmStatic
    fun invalidate() {
        isResolved = false
    }

    private fun pickImage(): File? {
        val root = File(getFolderPath())

        if (!root.isDirectory) {
            return null
        }

        val seasonal = listImages(File(root, getCurrentSeason().folderName))
        val candidates = if (seasonal.isNotEmpty()) seasonal else listImages(root)

        if (candidates.isEmpty()) {
            Log.i("SeasonalBackground", "No usable images found in " + root.path)
            return null
        }

        return candidates.random()
    }

    private fun listImages(folder: File): List<File> {
        if (!folder.isDirectory) {
            return emptyList()
        }

        val files = FileUtils.listFiles(folder, IMAGE_EXTENSIONS) ?: return emptyList()

        return files.filter { it.isFile && it.length() > 0 }
    }
}
