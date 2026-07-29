package com.osudroid.ui

import java.io.File

/**
 * Resolves the music and beatmap metadata inside an extracted theme .osz.
 *
 * A normal beatmap archive can contain dozens of hitsounds in addition to its actual song.
 * Selecting the first audio file returned by the filesystem is therefore not reliable. The
 * authoritative song name is the `AudioFilename` entry in the beatmap's `[General]` section.
 */
internal object ThemeSongArchive {

    val supportedAudioExtensions =
        listOf(".mp3", ".ogg", ".wav", ".m4a", ".aac", ".flac")

    /**
     * Finds the song selected by a .osu file. If an archive has no readable .osu metadata, the
     * largest audio file is used as a conservative fallback because full songs are normally much
     * larger than individual hitsounds.
     */
    fun findCustomAudioFile(folder: File): File? {
        if (!folder.isDirectory) return null

        for (osu in findOsuFiles(folder)) {
            resolveAudioFile(folder, osu)?.let { return it }
        }

        return findAudioFiles(folder, recursive = true)
            .maxWithOrNull(compareBy<File> { it.length() }.thenBy { it.name.lowercase() })
    }

    /**
     * Finds the .osu which references [audio]. This keeps the menu's title and timing metadata tied
     * to the same difficulty that supplied the selected song.
     */
    fun findOsuFileForAudio(folder: File, audio: File): File? {
        if (!folder.isDirectory || !audio.isFile) return null

        val audioCanonical = runCatching { audio.canonicalFile }.getOrElse { audio.absoluteFile }
        val osuFiles = findOsuFiles(folder)

        osuFiles.firstOrNull { osu ->
            val resolved = resolveAudioFile(folder, osu) ?: return@firstOrNull false
            val resolvedCanonical =
                runCatching { resolved.canonicalFile }.getOrElse { resolved.absoluteFile }

            resolvedCanonical == audioCanonical
        }?.let { return it }

        return osuFiles.firstOrNull { it.parentFile == audio.parentFile }
            ?: osuFiles.firstOrNull()
    }

    /**
     * Finds a supported audio file for the bundled theme.
     */
    fun findBundledAudioFile(folder: File): File? =
        findAudioFiles(folder, recursive = false)
            .maxWithOrNull(compareBy<File> { it.length() }.thenBy { it.name.lowercase() })

    private fun findOsuFiles(folder: File): List<File> =
        folder.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".osu", ignoreCase = true) }
            .sortedBy { it.relativeTo(folder).path.lowercase() }
            .toList()

    private fun findAudioFiles(folder: File, recursive: Boolean): List<File> {
        val files = if (recursive) {
            folder.walkTopDown().filter { it.isFile }
        } else {
            (folder.listFiles() ?: emptyArray()).asSequence().filter { it.isFile }
        }

        return files.filter(::isSupportedAudio).toList()
    }

    private fun isSupportedAudio(file: File): Boolean =
        supportedAudioExtensions.any { file.name.endsWith(it, ignoreCase = true) }

    private fun resolveAudioFile(root: File, osu: File): File? {
        val audioFilename = readAudioFilename(osu) ?: return null
        val normalized = audioFilename.replace('\\', File.separatorChar)
        val candidate = File(osu.parentFile, normalized)

        val rootCanonical = runCatching { root.canonicalFile }.getOrElse { root.absoluteFile }
        val candidateCanonical =
            runCatching { candidate.canonicalFile }.getOrElse { candidate.absoluteFile }
        val rootPath = rootCanonical.path
        val candidatePath = candidateCanonical.path
        val insideRoot =
            candidatePath == rootPath || candidatePath.startsWith(rootPath + File.separator)

        return candidateCanonical.takeIf {
            insideRoot && it.isFile && isSupportedAudio(it)
        }
    }

    private fun readAudioFilename(osu: File): String? {
        var section = ""

        return runCatching {
            osu.useLines { lines ->
                lines.forEach { raw ->
                    val line = raw.trim().removePrefix("\uFEFF")

                    if (line.startsWith("[") && line.endsWith("]")) {
                        section = line
                        return@forEach
                    }

                    if (section.equals("[General]", ignoreCase = true)) {
                        val separator = line.indexOf(':')

                        if (separator > 0 &&
                            line.substring(0, separator).trim()
                                .equals("AudioFilename", ignoreCase = true)
                        ) {
                            return@useLines line.substring(separator + 1)
                                .trim()
                                .removeSurrounding("\"")
                                .takeIf { it.isNotEmpty() }
                        }
                    }
                }

                null
            }
        }.getOrNull()
    }
}
