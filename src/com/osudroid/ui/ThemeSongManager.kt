package com.osudroid.ui

import android.util.Log
import com.osudroid.beatmaps.timings.EffectControlPoint
import com.osudroid.beatmaps.timings.TimingControlPoint
import net.lingala.zip4j.ZipFile
import ru.nsu.ccfit.zuev.audio.Status
import ru.nsu.ccfit.zuev.osu.Config
import ru.nsu.ccfit.zuev.osu.GlobalManager
import ru.nsu.ccfit.zuev.osu.ToastLogger
import java.io.File
import java.util.LinkedList

/**
 * Manages the osu! theme song, replicating the behaviour of osu!stable: the theme plays on the
 * main menu when the game is opened, stops when the player heads into song select, and does not
 * come back for the rest of the session.
 *
 * By default the audio is bundled as a .osz inside `assets/` and extracted to a `Theme/` folder
 * under the core directory on first run. The player may instead pick any .osz of their own, which
 * is copied next to it and extracted to `Theme/Custom/`. Either way the folder sits outside the
 * Songs directory, so the track never appears in song select.
 *
 * The .osu inside the theme .osz is parsed too, so the menu can show the intro's own artist and
 * title in "now playing" and pulse the cookie to the intro's own timing points rather than to an
 * unrelated beatmap picked out of the library.
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

    /** Cap on how many names a diagnostic listing prints, so a huge .osz cannot flood the log. */
    private const val MAX_LISTED_FILES = 40

    private var audioPath: String? = null

    /**
     * Whether resolution has already been attempted this session.
     *
     * Without this a failed extraction would leave [audioPath] null, and because [isActive] is
     * polled from the menu's update loop the whole .osz would be unzipped again on every single
     * frame. It also keeps the failure notice below to one toast rather than hundreds.
     */
    private var resolveAttempted = false

    /** Why the custom theme could not be used, for display in settings. Null when all is well. */
    private var lastFailureReason: String? = null

    /**
     * Intro metadata and timing, parsed lazily from the .osu sitting next to the theme audio.
     */
    private var introLoaded = false
    private var introLabel: String? = null
    private var introTiming: List<TimingControlPoint> = emptyList()
    private var introEffects: List<EffectControlPoint> = emptyList()

    /**
     * Set once the player leaves the main menu for song select or multiplayer. The intro only owns
     * the menu on the way into the game, so from that point on the menu follows whichever beatmap
     * song select is sitting on. Being process state, this resets naturally on the next launch.
     */
    private var introConsumed = false

    @JvmStatic
    fun isEnabled() = Config.getBoolean(PREFERENCE_KEY, true)

    /**
     * Whether a theme track is available at all: the setting is on and audio has been (or can be)
     * extracted. This stays true for the whole session regardless of where the player is.
     */
    @JvmStatic
    fun isActive(): Boolean {
        if (!isEnabled()) return false
        if (audioPath == null) audioPath = ensureExtracted()
        return audioPath != null
    }

    /**
     * Whether the intro should currently be driving the main menu: a theme is available and the
     * player has not gone into song select yet.
     */
    @JvmStatic
    fun isIntroActive(): Boolean = !introConsumed && isActive()

    /**
     * Hands the menu back to the regular beatmap flow. Called when the player heads into song
     * select or multiplayer; from then on the intro stays out of the way until the next launch.
     */
    @JvmStatic
    fun endIntro() {
        introConsumed = true
    }

    /**
     * Why the picked .osz was rejected, or `null` if there was nothing wrong with it.
     */
    @JvmStatic
    fun getLastFailureReason(): String? = lastFailureReason

    /**
     * The absolute path of the .osz the player picked, or `null` when the bundled theme is in use.
     *
     * A recorded path that no longer resolves to a file counts as not set. That used to happen
     * silently, which looked exactly like the setting never having been applied, so it is called
     * out here.
     */
    @JvmStatic
    fun getCustomOszPath(): String? {
        val path = Config.getString(CUSTOM_PREFERENCE_KEY, "") ?: return null
        if (path.isEmpty()) return null

        if (!File(path).isFile) {
            Log.e(TAG, "A custom intro is recorded at " + path + " but no file is there; falling back to the bundled theme")
            lastFailureReason = "The imported .osz is missing from " + path
            return null
        }

        return path
    }

    /**
     * The file name of the picked .osz, for display in settings.
     */
    @JvmStatic
    fun getCustomOszName(): String? = getCustomOszPath()?.let { File(it).name }

    /**
     * Where a picked .osz should be copied to. Keeping it under the core path means the import
     * survives app restarts without holding on to a content URI permission.
     *
     * The Theme folder is created here. It is otherwise only ever created while extracting the
     * bundled theme, so importing before that had happened wrote into a folder that did not exist:
     * the copy failed, the preference was still set, and the game quietly kept using the bundled
     * track.
     */
    @JvmStatic
    fun getCustomOszDestination(): File {
        val dir = themeDir()

        if (!dir.exists() && !dir.mkdirs()) {
            Log.e(TAG, "Could not create the theme folder at " + dir.absolutePath)
        }

        return File(dir, CUSTOM_OSZ_NAME)
    }

    /**
     * Points the theme at [file] and drops whatever was extracted before, so the next launch picks
     * the new audio up.
     */
    @JvmStatic
    fun setCustomOsz(file: File) {
        Config.setString(CUSTOM_PREFERENCE_KEY, file.absolutePath)

        // The imported copy always has the same path. File size and modification timestamps are
        // not unique enough to detect every replacement on Android storage, so explicitly discard
        // the old extraction whenever the player chooses another archive.
        try {
            customDir().deleteRecursively()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to invalidate the previous custom theme", e)
        }

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
     * Forgets the resolved audio file and parsed intro data so that the next call re-resolves them.
     */
    @JvmStatic
    fun invalidate() {
        audioPath = null
        resolveAttempted = false
        lastFailureReason = null
        introLoaded = false
        introLabel = null
        introTiming = emptyList()
        introEffects = emptyList()
    }

    /**
     * Extracts whichever .osz is in use if it has not been already, returning the absolute path of
     * the audio file inside.
     *
     * A picked .osz that cannot be used no longer leaves the menu silent: the reason is reported
     * and the bundled theme is used instead.
     */
    @JvmStatic
    fun ensureExtracted(): String? {
        audioPath?.let { return it }

        // Only ever resolved once per session; see resolveAttempted.
        if (resolveAttempted) return null
        resolveAttempted = true

        val custom = getCustomOszPath()

        val resolved = if (custom != null) {
            extractCustom(custom) ?: extractBundled()
        } else {
            extractBundled()
        }

        audioPath = resolved
        return resolved
    }

    /**
     * Extracts only the selected custom archive, without falling back to the bundled theme.
     *
     * The settings importer uses this to distinguish a valid custom intro from a failed import.
     * Using [ensureExtracted] there used to turn failures into false successes because its bundled
     * fallback still returned a non-null audio path.
     */
    @JvmStatic
    fun ensureCustomExtracted(): String? {
        val custom = getCustomOszPath() ?: return null
        val resolved = extractCustom(custom)

        audioPath = resolved
        resolveAttempted = true
        return resolved
    }

    private fun themeDir() = File(Config.getCorePath(), FOLDER_NAME)

    private fun customDir() = File(Config.getCorePath(), CUSTOM_FOLDER_NAME)

    /**
     * Records why the picked theme was rejected and says so out loud.
     *
     * Every failure path used to return a bare null, so a corrupt archive, an unsupported audio
     * format and a deleted file were indistinguishable from the outside: all three just played the
     * bundled track.
     */
    private fun failCustom(reason: String, e: Exception? = null): String? {
        lastFailureReason = reason

        if (e != null) {
            Log.e(TAG, reason, e)
        } else {
            Log.e(TAG, reason)
        }

        ToastLogger.showText("Custom intro theme: " + reason, true)
        return null
    }

    /**
     * Every file inside [folder], relative to it, for diagnosing a .osz that yielded no audio.
     */
    private fun listAllFiles(folder: File): List<String> {
        if (!folder.isDirectory) return emptyList()

        return folder.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(folder).path }
            .take(MAX_LISTED_FILES)
            .toList()
    }

    /**
     * Extracts the .osz the player picked, reusing the previous extraction when the same file is
     * still selected and unchanged.
     */
    private fun extractCustom(oszPath: String): String? {
        val osz = File(oszPath)
        val target = customDir()
        val marker = File(target, SOURCE_MARKER_NAME)
        val stamp = osz.absolutePath + "|" + osz.length() + "|" + osz.lastModified()

        if (!osz.isFile) {
            return failCustom("the imported file is no longer at " + osz.absolutePath)
        }

        if (osz.length() == 0L) {
            return failCustom(osz.name + " is empty, so the import did not complete")
        }

        if (marker.isFile && runCatching { marker.readText() }.getOrNull() == stamp) {
            ThemeSongArchive.findCustomAudioFile(target)?.let { return it.absolutePath }
        }

        try {
            if (target.exists()) target.deleteRecursively()
            target.mkdirs()

            try {
                ZipFile(osz).extractAll(target.absolutePath)
            } catch (e: Exception) {
                return failCustom(osz.name + " could not be opened as a .osz archive", e)
            }

            val resolved = ThemeSongArchive.findCustomAudioFile(target)?.absolutePath

            if (resolved == null) {
                val contents = listAllFiles(target)

                Log.e(TAG, "Contents of " + target.absolutePath + ": " + contents.joinToString())

                return if (contents.isEmpty()) {
                    failCustom(osz.name + " extracted to nothing at all")
                } else {
                    failCustom(
                        "nothing in " + osz.name + " has a supported audio extension " +
                            ThemeSongArchive.supportedAudioExtensions.joinToString() +
                            "; it holds " + contents.joinToString()
                    )
                }
            }

            marker.writeText(stamp)
            lastFailureReason = null
            Log.i(TAG, "Custom theme extracted to " + target.absolutePath + ", audio: " + resolved)
            return resolved
        } catch (e: Exception) {
            return failCustom("could not extract " + osz.name, e)
        }
    }

    /**
     * Extracts the .osz bundled in assets to the Theme folder if it has not been already.
     */
    private fun extractBundled(): String? {
        val target = themeDir().apply { mkdirs() }

        // Already extracted by a previous run.
        ThemeSongArchive.findBundledAudioFile(target)?.let { return it.absolutePath }

        try {
            val activity = GlobalManager.getInstance().mainActivity
            val tempOsz = File(Config.getCachePath(), CACHE_OSZ_NAME)

            activity.assets.open(ASSET_PATH).use { input ->
                tempOsz.outputStream().use { output -> input.copyTo(output) }
            }

            ZipFile(tempOsz).extractAll(target.absolutePath)
            tempOsz.delete()

            val resolved = ThemeSongArchive.findBundledAudioFile(target)?.absolutePath
            Log.i(TAG, "Theme extracted to " + target.absolutePath + ", audio: " + resolved)
            return resolved
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract theme .osz from assets", e)
            return null
        }
    }

    /**
     * "Artist - Title" for the intro, or `null` when the theme ships no readable .osu.
     */
    @JvmStatic
    fun getIntroDisplayName(): String? {
        ensureIntroLoaded()
        return introLabel
    }

    /**
     * Whether the intro carries usable timing, meaning the menu can beat along with it.
     */
    @JvmStatic
    fun hasIntroTiming(): Boolean {
        ensureIntroLoaded()
        return introTiming.isNotEmpty()
    }

    /**
     * A fresh, chronologically ordered queue of the intro's timing points. The menu consumes the
     * queue as the track plays, so every caller gets its own copy.
     */
    @JvmStatic
    fun getIntroTimingPoints(): LinkedList<TimingControlPoint> {
        ensureIntroLoaded()
        return LinkedList(introTiming)
    }

    /**
     * A fresh, chronologically ordered queue of the intro's effect (kiai) points.
     */
    @JvmStatic
    fun getIntroEffectPoints(): LinkedList<EffectControlPoint> {
        ensureIntroLoaded()
        return LinkedList(introEffects)
    }

    private fun ensureIntroLoaded() {
        if (introLoaded) return
        introLoaded = true

        val audio = ensureExtracted() ?: return
        val audioFile = File(audio)
        val customRoot = customDir()
        val root = if (isInside(audioFile, customRoot)) {
            customRoot
        } else {
            audioFile.parentFile ?: return
        }
        val osu = ThemeSongArchive.findOsuFileForAudio(root, audioFile) ?: return

        try {
            parseOsu(osu)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read the intro .osu", e)
        }
    }

    private fun isInside(file: File, folder: File): Boolean {
        val filePath = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
        val folderPath = runCatching { folder.canonicalPath }.getOrElse { folder.absolutePath }

        return filePath == folderPath || filePath.startsWith(folderPath + File.separator)
    }

    /**
     * Pulls the artist, title and timing points out of the theme's .osu.
     *
     * Values are collected as plain numbers first and only turned into control points after
     * sorting, so the ordering never depends on how the control point classes expose their time.
     */
    private fun parseOsu(osu: File) {
        var section = ""
        var artist: String? = null
        var title: String? = null

        val timingRaw = ArrayList<Triple<Double, Double, Int>>()
        val effectRaw = ArrayList<Pair<Double, Boolean>>()

        osu.forEachLine { raw ->
            val line = raw.trim()

            if (line.isEmpty() || line.startsWith("//")) return@forEachLine

            if (line.startsWith("[") && line.endsWith("]")) {
                section = line
                return@forEachLine
            }

            when (section) {
                "[Metadata]" -> {
                    val separator = line.indexOf(':')

                    if (separator > 0) {
                        val key = line.substring(0, separator).trim()
                        val value = line.substring(separator + 1).trim()

                        if (key == "Artist") artist = value
                        if (key == "Title") title = value
                    }
                }

                "[TimingPoints]" -> {
                    val parts = line.split(",")

                    if (parts.size >= 2) {
                        val time = parts[0].trim().toDoubleOrNull()
                        val beatLength = parts[1].trim().toDoubleOrNull()

                        if (time != null && beatLength != null) {
                            val meter = parts.getOrNull(2)?.trim()?.toIntOrNull() ?: 4
                            val uninherited = parts.getOrNull(6)?.trim()?.toIntOrNull() ?: 1
                            val flags = parts.getOrNull(7)?.trim()?.toIntOrNull() ?: 0

                            // Inherited points carry a negative slider velocity, not a beat length.
                            if (uninherited != 0 && beatLength > 0) {
                                timingRaw.add(Triple(time, beatLength, if (meter > 0) meter else 4))
                            }

                            effectRaw.add(Pair(time, (flags and 1) != 0))
                        }
                    }
                }
            }
        }

        timingRaw.sortBy { it.first }
        effectRaw.sortBy { it.first }

        introTiming = timingRaw.map { TimingControlPoint(it.first, it.second, it.third) }
        introEffects = effectRaw.map { EffectControlPoint(it.first, it.second) }
        introLabel = buildLabel(artist, title)

        Log.i(TAG, "Intro parsed: " + introLabel + ", " + introTiming.size + " timing points")
    }

    private fun buildLabel(artist: String?, title: String?): String? {
        val a = artist?.takeIf { it.isNotEmpty() }
        val t = title?.takeIf { it.isNotEmpty() }

        return when {
            a != null && t != null -> "$a - $t"
            t != null -> t
            a != null -> a
            else -> null
        }
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
