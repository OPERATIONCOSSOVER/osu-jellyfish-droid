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
 * osu!droid/SeasonalBackgrounds/Winter/
 * osu!droid/SeasonalBackgrounds/Spring/
 * osu!droid/SeasonalBackgrounds/Summer/
 * osu!droid/SeasonalBackgrounds/Autumn/
 * ```
 *
 * When a subfolder matching the current season exists and contains images, those are used.
 * Otherwise any image sitting directly in the folder root is used.
 *
 * The matching images form a shuffled playlist. With the slideshow enabled the menu walks through
 * it, crossfading between entries; otherwise the first entry is kept for the rest of the session,
 * no matter how often the menu asks for a background.
 */
object SeasonalBackgroundManager {

    /**
     * Preference keys backing the settings. Kept in sync with `res/xml/settings_graphics.xml`.
     */
    const val PREFERENCE_KEY = "seasonalBackgrounds"
    const val SLIDESHOW_PREFERENCE_KEY = "seasonalBackgroundsSlideshow"
    const val INTERVAL_PREFERENCE_KEY = "seasonalBackgroundsInterval"

    /**
     * Name of the folder that holds the images, relative to the core path.
     */
    const val FOLDER_NAME = "SeasonalBackgrounds"

    const val DEFAULT_INTERVAL_SECONDS = 30
    const val MIN_INTERVAL_SECONDS = 5

    private const val TAG = "SeasonalBackground"

    /**
     * How long a failed resolve is remembered for, in milliseconds. Keeps a missing or unreadable
     * folder from being rescanned on every single background change without giving up on it for the
     * rest of the session.
     */
    private const val RESOLVE_RETRY_COOLDOWN_MS = 5000L

    /**
     * Two names are alternated rather than reusing a single one. A slide is still fading out while
     * its successor is being loaded, and loading over the name the outgoing image was registered
     * under would pull the texture out from under it mid-fade.
     */
    private val TEXTURE_NAMES = arrayOf("::seasonal-background-0", "::seasonal-background-1")

    private val IMAGE_EXTENSIONS = arrayOf(".png", ".jpg", ".jpeg", ".bmp")

    /**
     * The shuffled playlist, resolved once per session. `null` means the folder has not been
     * scanned yet; an empty list means it was scanned and held nothing usable.
     */
    private var playlist: List<File>? = null

    private var playlistIndex = 0

    /**
     * Index into [TEXTURE_NAMES] that the next slide will be loaded into.
     */
    private var slot = 0

    /**
     * When the last attempt at resolving the first slide was made, as a [System.currentTimeMillis]
     * timestamp. Only meaningful while [currentRegion] is still `null`.
     */
    private var lastResolveAttemptTime = 0L

    private var currentRegion: TextureRegion? = null

    /**
     * The very first region handed out. MainScene passes it to the scene `SpriteBackground`, which
     * holds on to it for the lifetime of the scene, so it is never unloaded even once its slot has
     * come back around for reuse.
     */
    private var pinnedRegion: TextureRegion? = null

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
     * Whether the background should cycle. False when there is nothing to cycle between, so that
     * the caller does not run a timer for a single image.
     */
    @JvmStatic
    fun isSlideshowEnabled(): Boolean {
        if (!isEnabled() || !Config.getBoolean(SLIDESHOW_PREFERENCE_KEY, true)) {
            return false
        }

        return getPlaylist().size > 1
    }

    /**
     * How long each slide stays on screen, in seconds.
     */
    @JvmStatic
    fun getIntervalSeconds(): Int {
        val value = Config.getInt(INTERVAL_PREFERENCE_KEY, DEFAULT_INTERVAL_SECONDS)

        return if (value < MIN_INTERVAL_SECONDS) MIN_INTERVAL_SECONDS else value
    }

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
     * The texture currently being shown, loading the first slide if nothing has been shown yet.
     * Returns `null` when the setting is disabled or no usable image could be found.
     *
     * Once a slide is on screen it is handed back for every later call, so the menu keeps the same
     * image when it is returned to or the song is changed. Only [next] moves the slideshow on.
     *
     * Must be called from a thread that is allowed to upload textures, in the same way as the other
     * [ResourceManager] loading calls made from the main menu.
     */
    @JvmStatic
    fun load(): TextureRegion? {
        if (!isEnabled()) {
            return null
        }

        currentRegion?.let { return it }

        val now = System.currentTimeMillis()

        // A failed resolve is only remembered for a short while. The folder is not always readable
        // by the time the menu first asks for a background, and with the slideshow disabled nothing
        // else would ever retry: the menu would fall back to beatmap backgrounds and change on every
        // song change and every return to the menu for the rest of the session.
        if (lastResolveAttemptTime != 0L && now - lastResolveAttemptTime < RESOLVE_RETRY_COOLDOWN_MS) {
            return null
        }

        lastResolveAttemptTime = now

        // Nothing is on screen yet, so rescanning and reshuffling is safe here: no slide can be
        // pulled out from under the menu by it.
        playlist = null
        playlistIndex = 0

        val files = getPlaylist()

        if (files.isEmpty()) {
            return null
        }

        return loadSlide(files[0])
    }

    /**
     * Advances to the next slide and returns its texture, or `null` when there is nothing to
     * advance to. Wraps around at the end of the playlist.
     */
    @JvmStatic
    fun next(): TextureRegion? {
        if (!isEnabled()) {
            return null
        }

        val files = getPlaylist()

        if (files.size < 2) {
            return null
        }

        playlistIndex = (playlistIndex + 1) % files.size

        return loadSlide(files[playlistIndex])
    }

    /**
     * Drops the cached playlist so that the next [load] call rescans the folder and reshuffles.
     */
    @JvmStatic
    fun invalidate() {
        playlist = null
        playlistIndex = 0
        currentRegion = null
        lastResolveAttemptTime = 0L
    }

    /**
     * Loads [file] into the next texture slot, freeing whatever was left in that slot first.
     *
     * The occupant of the slot being reused is two slides old, so it has finished fading and been
     * detached by the time it is dropped. Freeing it before the load rather than after means the
     * name still resolves to it in [ResourceManager].
     */
    private fun loadSlide(file: File): TextureRegion? {
        val name = TEXTURE_NAMES[slot]
        val stale = ResourceManager.getInstance().getTextureIfLoaded(name)

        if (stale != null && stale !== pinnedRegion) {
            try {
                ResourceManager.getInstance().unloadTexture(stale)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unload the previous slide", e)
            }
        }

        val region = try {
            ResourceManager.getInstance().loadHighQualityFile(name, file)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load " + file.path, e)
            null
        } ?: return null

        slot = (slot + 1) % TEXTURE_NAMES.size
        currentRegion = region

        if (pinnedRegion == null) {
            pinnedRegion = region
        }

        return region
    }

    private fun getPlaylist(): List<File> {
        playlist?.let { return it }

        val resolved = scanImages().shuffled()
        playlist = resolved

        if (resolved.isEmpty()) {
            Log.i(TAG, "No usable images found in " + getFolderPath())
        }

        return resolved
    }

    private fun scanImages(): List<File> {
        val root = File(getFolderPath())

        if (!root.isDirectory) {
            return emptyList()
        }

        val seasonal = listImages(File(root, getCurrentSeason().folderName))

        return if (seasonal.isNotEmpty()) seasonal else listImages(root)
    }

    private fun listImages(folder: File): List<File> {
        if (!folder.isDirectory) {
            return emptyList()
        }

        val files = FileUtils.listFiles(folder, IMAGE_EXTENSIONS) ?: return emptyList()

        return files.filter { it.isFile && it.length() > 0 }
    }
}
