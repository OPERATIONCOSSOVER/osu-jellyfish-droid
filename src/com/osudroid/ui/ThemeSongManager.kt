package com.osudroid.ui

import android.util.Log
import net.lingala.zip4j.ZipFile
import ru.nsu.ccfit.zuev.audio.Status
import ru.nsu.ccfit.zuev.osu.Config
import ru.nsu.ccfit.zuev.osu.GlobalManager
import java.io.File

/**
 * Manages the osu! theme song, replicating the behaviour of osu!stable: the theme plays on the
 * main menu, stops when entering gameplay, and resumes when returning.
 *
 * By default the audio is bundled as a .osz inside `assets/` and extracted to a `Theme/` folder
 * under the core directory on first run. The player may instead pick any .osz of their own, which
 * is copied next to it and extracted to `Theme/Custom/`. Either way the folder sits outside the
 * Songs directory, so the track never appears in song select.
 */
object ThemeSongManager {

    private const val TAG = "ThemeSong"

    const val PREFERENCE_KEY = "themeSong"

    /**
     * Preference holding the absolute path of the .osz the player picked, or an empty string when
     * the bundled theme should be used. Kept in sync with `res/xml/settings_graphics.xml`.
     */
    const val CUSTOM_PREFERENCE_KEY = "themeSongCustomOsz"

    private const val ASSET_PATH = "nekodex - circles!.osz"
    private const val FOLDER_NAME = "Theme"
    private const val CUSTOM_FOLDER_NAME = "Theme/Custom"
    private const val CACHE_OSZ_NAME = "theme-extract.osz"

    /**
     * Name the picked .osz is copied under, so that the imported copy survives the content URI it
     * came from being revoked.
     */
    const val CUSTOM_OSZ_NAME = "custom-theme.osz"

    /**
     * Records which .osz the custom folder currently holds, so that picking a different file (or
     * replacing it with one of the same name) re-extracts rather than keeping the old audio.
     */
    private const val SOURCE_MARKER_NAME = ".source"

    private val AUDIO_EXTENSIONS = arrayOf(".mp3", ".ogg", ".wav", ".m4a", ".aac", ".flac")

    private var audioPath: String? = null

    @JvmStatic
    fun isEnabled() = Config.getBoolean(PREFERENCE_KEY, true)

    /**
     * Whether the theme should be playing right now: the setting is on and an audio file has been
     * (or can be) found.
     */
    @JvmStatic
    fun isActive(): Boolean {
        if (!isEnabled()) return false
        if (audioPath == null) audioPath = ensureExtracted()
        return audioPath != null
    }

    /**
     * The absolute path of the .osz the player picked, or `null` when the bundled theme is in use.
     * A path that no longer resolves to a file is treated as not set.
     */
    @JvmStatic
    fun getCustomOszPath(): String? {
        val path = Config.getString(CUSTOM_PREFERENCE_KEY, "") ?: return null
        if (path.isEmpty()) return null
        return if (File(path).isFile) path else null
    }

    /**
     * The file name of the picked .osz, for display in settings.
     */
    @JvmStatic
    fun getCustomOszName(): String? = getCustomOszPath()?.let { File(it).name }

    /**
     * Where a picked .osz should be copied to. Keeping it under the core path means the import
     * survives app restarts without holding on to a content URI permission.
     */
    @JvmStatic
    fun getCustomOszDestination() = File(File(Config.getCorePath(), FOLDER_NAME), CUSTOM_OSZ_NAME)

    /**
     * Points the theme at [file] and drops whatever was extracted before, so the next play picks
     * the new audio up.
     */
    @JvmStatic
    fun setCustomOsz(file: File) {
        Config.setString(CUSTOM_PREFERENCE_KEY, file.absolutePath)
        invalidate()
    }

    /**
     * Goes back to the bundled theme, removing the extracted custom audio.
     */
    @JvmStatic
    fun clearCustomOsz() {
        Config.setString(CUSTOM_PREFERENCE_KEY, "")

        try {
            customDir().deleteRecursively()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove the extracted custom theme", e)
        }

        invalidate()
    }

    /**
     * Forgets the resolved audio file so that the next call re-resolves it.
     */
    @JvmStatic
    fun invalidate() {
        audioPath = null
    }

    /**
     * Extracts whichever .osz is in use if it has not been already, returning the absolute path of
     * the audio file inside. Returns `null` if the .osz is missing or holds no usable audio.
     */
    @JvmStatic
    fun ensureExtracted(): String? {
        audioPath?.let { return it }

        val custom = getCustomOszPath()
        val resolved = if (custom != null) extractCustom(custom) else extractBundled()

        audioPath = resolved
        return resolved
    }

    private fun themeDir() = File(Config.getCorePath(), FOLDER_NAME)

    private fun customDir() = File(Config.getCorePath(), CUSTOM_FOLDER_NAME)

    /**
     * Extracts the .osz the player picked, reusing the previous extraction when the same file is
     * still selected and unchanged.
     */
    private fun extractCustom(oszPath: String): String? {
        val osz = File(oszPath)
        val target = customDir()
        val marker = File(target, SOURCE_MARKER_NAME)
        val stamp = osz.absolutePath + "|" + osz.length() + "|" + osz.lastModified()

        if (marker.isFile && runCatching { marker.readText() }.getOrNull() == stamp) {
            findAudioFile(target)?.let { return it }
        }

        try {
            if (target.exists()) target.deleteRecursively()
            target.mkdirs()

            ZipFile(osz).extractAll(target.absolutePath)

            val resolved = findAudioFile(target)

            if (resolved == null) {
                Log.e(TAG, "No audio found inside the picked theme " + osz.name)
                return null
            }

            marker.writeText(stamp)
            Log.i(TAG, "Custom theme extracted to " + target.absolutePath + ", audio: " + resolved)
            return resolved
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract the picked theme .osz", e)
            return null
        }
    }

    /**
     * Extracts the .osz bundled in assets to the Theme folder if it has not been already.
     */
    private fun extractBundled(): String? {
        val target = themeDir().apply { mkdirs() }

        // Already extracted by a previous run.
        findAudioFile(target)?.let { return it }

        try {
            val activity = GlobalManager.getInstance().mainActivity
            val tempOsz = File(Config.getCachePath(), CACHE_OSZ_NAME)

            activity.assets.open(ASSET_PATH).use { input ->
                tempOsz.outputStream().use { output -> input.copyTo(output) }
            }

            ZipFile(tempOsz).extractAll(target.absolutePath)
            tempOsz.delete()

            val resolved = findAudioFile(target)
            Log.i(TAG, "Theme extracted to " + target.absolutePath + ", audio: " + resolved)
            return resolved
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract theme .osz from assets", e)
            return null
        }
    }

    /**
     * Finds the first playable file directly inside [folder], ignoring the Custom subfolder so the
     * bundled theme never picks up the player's audio.
     */
    private fun findAudioFile(folder: File): String? {
        if (!folder.isDirectory) return null

        return folder.listFiles()?.firstOrNull { file ->
            file.isFile && AUDIO_EXTENSIONS.any { file.name.endsWith(it, true) }
        }?.absolutePath
    }

    /**
     * Plays the theme if it is enabled and an audio file is available.
     *
     * - If the theme is already playing, does nothing.
     * - If it is paused, resumes.
     * - If it is stopped or was never started, preloads and plays from the beginning.
     */
    @JvmStatic
    fun play() {
        if (!isEnabled()) return

        val path = ensureExtracted() ?: return

        val songService = GlobalManager.getInstance().songService ?: return
        val status = songService.status

        if (status == Status.PLAYING) return

        if (status == Status.PAUSED) {
            songService.play()
            return
        }

        songService.preLoad(path)
        songService.play()
        songService.setVolume(Config.getBgmVolume())
    }

    /**
     * Stops whatever is playing and starts the theme again from the beginning. Used after the
     * player picks or clears a custom .osz so the change is heard immediately.
     */
    @JvmStatic
    fun restart() {
        val songService = GlobalManager.getInstance().songService ?: return

        songService.stop()

        if (isEnabled()) play()
    }

    @JvmStatic
    fun pause() {
        val songService = GlobalManager.getInstance().songService ?: return
        if (songService.status == Status.PLAYING) {
            songService.pause()
        }
    }

    @JvmStatic
    fun stop() {
        val songService = GlobalManager.getInstance().songService ?: return
        songService.stop()
    }
}
