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
 * The audio is bundled as a .osz inside `assets/` and extracted to a `Theme/` folder under
 * the core directory on first run. The folder is outside the Songs directory, so the track never
 * appears in song select.
 */
object ThemeSongManager {

    private const val TAG = "ThemeSong"
    const val PREFERENCE_KEY = "themeSong"
    private const val ASSET_PATH = "nekodex - circles!.osz"
    private const val FOLDER_NAME = "Theme"
    private const val CACHE_OSZ_NAME = "theme-extract.osz"

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
        if (audioPath == null) audioPath = findAudioFile()
        return audioPath != null
    }

    /**
     * Extracts the bundled .osz to the Theme folder if it has not been already, returning the
     * absolute path of the audio file inside. Returns `null` if the .osz is missing or the audio
     * cannot be found.
     */
    @JvmStatic
    fun ensureExtracted(): String? {
        audioPath?.let { return it }

        val themeDir = File(Config.getCorePath(), FOLDER_NAME).apply { mkdirs() }

        // Already extracted by a previous run
        findAudioFile()?.let {
            audioPath = it
            return it
        }

        try {
            val activity = GlobalManager.getInstance().mainActivity
            val tempOsz = File(Config.getCachePath(), CACHE_OSZ_NAME)

            activity.assets.open(ASSET_PATH).use { input ->
                tempOsz.outputStream().use { output -> input.copyTo(output) }
            }

            ZipFile(tempOsz).extractAll(themeDir.absolutePath)
            tempOsz.delete()

            audioPath = findAudioFile()
            Log.i(TAG, "Theme extracted to ${themeDir.absolutePath}, audio: $audioPath")
            return audioPath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract theme .osz from assets", e)
            return null
        }
    }

    private fun findAudioFile(): String? {
        val themeDir = File(Config.getCorePath(), FOLDER_NAME)
        if (!themeDir.isDirectory) return null

        return themeDir.listFiles()?.firstOrNull { file ->
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
        audioPath = path

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
