package com.osudroid.beatmaps.editor

import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

/**
 * User-editable values from the `[Metadata]` section of an osu! beatmap.
 *
 * Online identifiers are intentionally excluded. Editing descriptive metadata must never detach a beatmap from its
 * online beatmap or beatmap set.
 */
data class EditableBeatmapMetadata(
    val title: String,
    val titleUnicode: String,
    val artist: String,
    val artistUnicode: String,
    val creator: String,
    val version: String,
    val source: String,
    val tags: String,
)

/**
 * Safely updates the editable portion of a beatmap's `[Metadata]` section.
 *
 * The transformer deliberately works on the original text instead of serializing a parsed beatmap. This preserves
 * comments, unknown properties, unsupported sections, object formatting, and the file's existing line endings.
 */
object BeatmapMetadataEditor {
    private val utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    private val metadataKeys = linkedMapOf(
        "Title" to EditableBeatmapMetadata::title,
        "TitleUnicode" to EditableBeatmapMetadata::titleUnicode,
        "Artist" to EditableBeatmapMetadata::artist,
        "ArtistUnicode" to EditableBeatmapMetadata::artistUnicode,
        "Creator" to EditableBeatmapMetadata::creator,
        "Version" to EditableBeatmapMetadata::version,
        "Source" to EditableBeatmapMetadata::source,
        "Tags" to EditableBeatmapMetadata::tags,
    )

    /**
     * Atomically saves [metadata] to [file].
     *
     * @throws IllegalArgumentException If required values are blank or any value contains a line break.
     */
    @JvmStatic
    fun save(file: File, metadata: EditableBeatmapMetadata) {
        require(file.isFile) { "Beatmap file does not exist: ${file.path}" }

        val transformed = transform(file.readBytes(), metadata)
        val atomicFile = AtomicFile(file)
        var output: FileOutputStream? = null

        try {
            output = atomicFile.startWrite()
            output.write(transformed)
            atomicFile.finishWrite(output)
        } catch (e: Exception) {
            output?.let(atomicFile::failWrite)
            throw e
        }
    }

    /**
     * Transforms UTF-8 encoded beatmap [content], preserving a UTF-8 BOM when present.
     */
    fun transform(content: ByteArray, metadata: EditableBeatmapMetadata): ByteArray {
        val hasBom = content.size >= utf8Bom.size && content.copyOfRange(0, utf8Bom.size).contentEquals(utf8Bom)
        val text = String(content, if (hasBom) utf8Bom.size else 0, content.size - if (hasBom) utf8Bom.size else 0, StandardCharsets.UTF_8)
        val transformed = transform(text, metadata).toByteArray(StandardCharsets.UTF_8)

        return if (hasBom) utf8Bom + transformed else transformed
    }

    /**
     * Transforms beatmap [content] while keeping all non-metadata content untouched.
     */
    fun transform(content: String, metadata: EditableBeatmapMetadata): String {
        val normalizedMetadata = metadata.normalizedAndValidated()
        val lineEnding = if (content.contains("\r\n")) "\r\n" else "\n"
        val hasTrailingLineEnding = content.endsWith("\n") || content.endsWith("\r")
        val normalizedContent = content.replace("\r\n", "\n").replace('\r', '\n')
        val lines = normalizedContent.split('\n').toMutableList()

        if (hasTrailingLineEnding && lines.lastOrNull()?.isEmpty() == true) {
            lines.removeAt(lines.lastIndex)
        }

        val metadataStart = lines.indexOfFirst { it.trim().equals("[Metadata]", ignoreCase = true) }

        if (metadataStart < 0) {
            insertMetadataSection(lines, normalizedMetadata)
        } else {
            updateMetadataSection(lines, metadataStart, normalizedMetadata)
        }

        return lines.joinToString(lineEnding) + if (hasTrailingLineEnding) lineEnding else ""
    }

    private fun EditableBeatmapMetadata.normalizedAndValidated(): EditableBeatmapMetadata {
        val normalized = copy(
            title = title.trim(),
            titleUnicode = titleUnicode.trim(),
            artist = artist.trim(),
            artistUnicode = artistUnicode.trim(),
            creator = creator.trim(),
            version = version.trim(),
            source = source.trim(),
            tags = tags.trim(),
        )

        linkedMapOf(
            "Title" to normalized.title,
            "Artist" to normalized.artist,
            "Creator" to normalized.creator,
            "Difficulty name" to normalized.version,
        ).forEach { (name, value) ->
            require(value.isNotBlank()) { "$name cannot be empty" }
        }

        metadataKeys.forEach { (name, property) ->
            require(property(normalized).none { it == '\r' || it == '\n' }) { "$name cannot contain a line break" }
        }

        return normalized
    }

    private fun updateMetadataSection(
        lines: MutableList<String>,
        metadataStart: Int,
        metadata: EditableBeatmapMetadata,
    ) {
        val metadataEnd = (metadataStart + 1 until lines.size)
            .firstOrNull { lines[it].trim().let { line -> line.startsWith('[') && line.endsWith(']') } }
            ?: lines.size
        val foundKeys = mutableSetOf<String>()

        for (index in metadataStart + 1 until metadataEnd) {
            val line = lines[index]
            val colon = line.indexOf(':')

            if (colon < 0) {
                continue
            }

            val originalKey = line.substring(0, colon).trim()
            val canonicalKey = metadataKeys.keys.firstOrNull { it.equals(originalKey, ignoreCase = true) } ?: continue
            val value = metadataKeys.getValue(canonicalKey)(metadata)

            // Keep the original key, indentation, and colon spacing. Only the user-editable value is replaced.
            lines[index] = line.substring(0, colon + 1) + value
            foundKeys += canonicalKey
        }

        val missingLines = metadataKeys
            .filterKeys { it !in foundKeys }
            .map { (key, property) -> "$key:${property(metadata)}" }

        lines.addAll(metadataEnd, missingLines)
    }

    private fun insertMetadataSection(lines: MutableList<String>, metadata: EditableBeatmapMetadata) {
        // Metadata must be before HitObjects because lightweight parsing intentionally stops when HitObjects begins.
        val preferredSections = setOf("[difficulty]", "[events]", "[timingpoints]", "[colours]", "[colors]", "[hitobjects]")
        val insertionIndex = lines.indexOfFirst { it.trim().lowercase() in preferredSections }
            .takeIf { it >= 0 }
            ?: lines.size
        val section = buildList {
            add("[Metadata]")
            metadataKeys.forEach { (key, property) -> add("$key:${property(metadata)}") }
            add("")
        }.toMutableList()

        if (insertionIndex > 0 && lines[insertionIndex - 1].isNotBlank()) {
            section.add(0, "")
        }

        lines.addAll(insertionIndex, section)
    }
}
