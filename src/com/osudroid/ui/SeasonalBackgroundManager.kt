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
 * The matching images form a shuffled playlist. While the setting is enabled the menu always shows
 * an entry from that playlist and never a beatmap background; beatmap backgrounds are only used
 * when the setting is off.
 *
 * The playlist moves on in two ways:
 *
 * - Every time the menu resolves its background again, which happens when the menu is returned to
 *   and when the song is changed with the next/previous buttons. This is [load].
 * - On a timer, while the slideshow setting is enabled. This is [next].
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
     * How long a failed scan is remembered for, in milliseconds. Keeps a missing or unreadable
     * folder from being rescanned on every single background change without giving up on it for the
     * rest of the session.
     */
    private const val RESOLVE_RETRY_COOLDOWN_MS = 5000L

    /**
     * How close together two [load] calls have to be, in milliseconds, to be treated as the same
     * menu event.
     *
     * A single return to the menu or song change can ask for the background more than once, and
     * each of those asks would otherwise skip an image.
     */
    private const val ADVANCE_DEBOUNCE_MS = 1000L

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
     * When the folder was last scanned without finding anything usable, as a
     * [System.currentTimeMillis] timestamp.
     */
    private var lastFailedScanTime = 0L

    /**
     * When the playlist last moved on, as a [System.currentTimeMillis] timestamp.
     */
    private var lastAdvanceTime = 0L

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
     * Whether the background should also cycle on a timer. False when there is nothing to cycle
     * between, so that the caller does not run a timer for a single image.
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
     * Resolves the background the menu should show, moving the playlist on by one entry.
     *
     * The menu resolves its background again when it is returned to and when the song is changed,
     * so each of those shows a different seasonal image. Asks that arrive within
     * [ADVANCE_DEBOUNCE_MS] of each other belong to the same event and are answered with the image
     * already on screen.
     *
     * Returns `null` when the setting is disabled or no usable image could be found, which is what
     * makes the menu fall back to the beatmap background.
     *
     * Must be called from a thread that is allowed to upload textures, in the same way as the other
     * [ResourceManager] loading calls made from the main menu.
     */
    @JvmStatic
    fun load(): TextureRegion? {
        if (!isEnabled()) {
            return null
        }

        val now = System.currentTimeMillis()
        var files = getPlaylist()

        if (files.isEmpty()) {
            // The folder is not always readable by the time the menu first asks for a background, so
            // a fruitless scan is retried rather than remembered for the whole session. Without this
            // the menu would fall back to beatmap backgrounds even though the setting is on.
            if (now - lastFailedScanTime < RESOLVE_RETRY_COOLDOWN_MS) {
                return currentRegion
            }

            lastFailedScanTime = now
            playlist = null
            playlistIndex = 0
            files = getPlaylist()

            if (files.isEmpty()) {
                return currentRegion
            }
        }

        val current = currentRegion

        if (current == null) {
            playlistIndex = 0
            lastAdvanceTime = now

            return loadSlide(files[0])
        }

        // Several asks belonging to the same menu event must not skip images.
        if (now - lastAdvanceTime < ADVANCE_DEBOUNCE_MS || files.size < 2) {
            return current
        }

        lastAdvanceTime = now
        playlistIndex = (playlistIndex + 1) % files.size

        return loadSlide(files[playlistIndex]) ?: current
    }

    /**
     * Advances to the next slide and returns its texture, or `null` when there is nothing to
     * advance to. Wraps around at the end of the playlist. Used by the slideshow timer.
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

        lastAdvanceTime = System.currentTimeMillis()
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
        lastFailedScanTime = 0L
        lastAdvanceTime = 0L
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
