package com.osudroid.beatmaps.editor

import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.ConcurrentModificationException

class EditableBeatmapDocument private constructor(
    val file: File,
    private var originalContent: ByteArray,
    private var originalLastModified: Long,
    val objects: MutableList<EditableHitObject>,
) {
    /**
     * Atomically saves [updatedObjects], refusing to overwrite an externally modified file.
     */
    fun save(updatedObjects: List<EditableHitObject>, metadata: EditableBeatmapMetadata? = null) {
        val currentContent = file.readBytes()
        if (file.lastModified() != originalLastModified || !currentContent.contentEquals(originalContent)) {
            throw ConcurrentModificationException("The beatmap changed after it was opened; reload it before saving")
        }

        var transformed = BeatmapHitObjectEditor.transform(originalContent, updatedObjects)
        if (metadata != null) {
            transformed = BeatmapMetadataEditor.transform(transformed, metadata)
        }
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

        originalContent = transformed
        originalLastModified = file.lastModified()
        objects.clear()
        objects.addAll(BeatmapHitObjectEditor.parse(transformed))
    }

    companion object {
        fun open(file: File): EditableBeatmapDocument {
            require(file.isFile) { "Beatmap file does not exist: ${file.path}" }
            val content = file.readBytes()
            return EditableBeatmapDocument(file, content, file.lastModified(), BeatmapHitObjectEditor.parse(content).toMutableList())
        }
    }
}

object BeatmapHitObjectEditor {
    private val utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    fun parse(content: ByteArray): List<EditableHitObject> {
        val text = decode(content)
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val sectionStart = lines.indexOfFirst { it.trim().equals("[HitObjects]", ignoreCase = true) }
        if (sectionStart < 0) {
            return emptyList()
        }

        val sectionEnd = (sectionStart + 1 until lines.size)
            .firstOrNull { lines[it].trim().let { line -> line.startsWith('[') && line.endsWith(']') } }
            ?: lines.size

        return (sectionStart + 1 until sectionEnd).mapNotNull { EditableHitObject.parse(lines[it], it) }
    }

    fun transform(content: ByteArray, objects: List<EditableHitObject>): ByteArray {
        require(objects.map { it.editorId }.toSet().size == objects.size) { "Editor object IDs must be unique" }

        val hasBom = hasBom(content)
        val text = decode(content)
        val transformed = transform(text, objects).toByteArray(StandardCharsets.UTF_8)
        return if (hasBom) utf8Bom + transformed else transformed
    }

    fun transform(content: String, objects: List<EditableHitObject>): String {
        val lineEnding = if (content.contains("\r\n")) "\r\n" else "\n"
        val hasTrailingLineEnding = content.endsWith("\n") || content.endsWith("\r")
        val lines = content.replace("\r\n", "\n").replace('\r', '\n').split('\n').toMutableList()

        if (hasTrailingLineEnding && lines.lastOrNull()?.isEmpty() == true) {
            lines.removeAt(lines.lastIndex)
        }

        var sectionStart = lines.indexOfFirst { it.trim().equals("[HitObjects]", ignoreCase = true) }
        if (sectionStart < 0) {
            if (lines.lastOrNull()?.isNotBlank() == true) {
                lines += ""
            }
            lines += "[HitObjects]"
            sectionStart = lines.lastIndex
        }

        val sectionEnd = (sectionStart + 1 until lines.size)
            .firstOrNull { lines[it].trim().let { line -> line.startsWith('[') && line.endsWith(']') } }
            ?: lines.size
        val passthrough = (sectionStart + 1 until sectionEnd)
            .filter { EditableHitObject.parse(lines[it], it) == null }
            .map { lines[it] }
        val serializedObjects = objects
            .sortedWith(compareBy<EditableHitObject> { it.time }.thenBy { it.editorId })
            .map(EditableHitObject::serialize)
        val replacement = (passthrough + serializedObjects).toMutableList()

        while (replacement.firstOrNull()?.isBlank() == true && replacement.size > 1) {
            replacement.removeAt(0)
        }

        lines.subList(sectionStart + 1, sectionEnd).clear()
        lines.addAll(sectionStart + 1, replacement)

        return lines.joinToString(lineEnding) + if (hasTrailingLineEnding) lineEnding else ""
    }

    private fun hasBom(content: ByteArray) =
        content.size >= utf8Bom.size && content.copyOfRange(0, utf8Bom.size).contentEquals(utf8Bom)

    private fun decode(content: ByteArray): String {
        val offset = if (hasBom(content)) utf8Bom.size else 0
        return String(content, offset, content.size - offset, StandardCharsets.UTF_8)
    }
}
