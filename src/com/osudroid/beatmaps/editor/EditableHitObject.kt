package com.osudroid.beatmaps.editor

import kotlin.math.hypot
import kotlin.math.roundToInt

enum class EditorHitObjectKind {
    Circle,
    Slider,
    Spinner,
}

/**
 * A minimally destructive representation of one line in an osu! `[HitObjects]` section.
 *
 * Unchanged objects retain their original line byte-for-byte. Once edited, only the standard comma-separated fields
 * owned by that operation are rewritten.
 */
@ConsistentCopyVisibility
data class EditableHitObject internal constructor(
    val editorId: Long,
    internal val sourceLine: Int?,
    internal val fields: List<String>,
    internal val originalLine: String?,
    internal val dirty: Boolean,
) {
    val x: Int
        get() = fields[0].trim().toFloat().roundToInt()

    val y: Int
        get() = fields[1].trim().toFloat().roundToInt()

    val time: Int
        get() = fields[2].trim().toDouble().roundToInt()

    val type: Int
        get() = fields[3].trim().toInt()

    val kind: EditorHitObjectKind
        get() = when (type % 16) {
            1, 5 -> EditorHitObjectKind.Circle
            2, 6 -> EditorHitObjectKind.Slider
            else -> EditorHitObjectKind.Spinner
        }

    val isNewCombo: Boolean
        get() = type and 4 != 0

    val endTime: Int
        get() = if (kind == EditorHitObjectKind.Spinner) fields.getOrNull(5)?.trim()?.toDoubleOrNull()?.roundToInt() ?: time else time

    val sliderEnd: Pair<Int, Int>?
        get() {
            if (kind != EditorHitObjectKind.Slider) {
                return null
            }

            val point = fields.getOrNull(5)?.substringAfterLast('|') ?: return null
            val coordinates = point.split(':')
            return if (coordinates.size >= 2) {
                val endX = coordinates[0].toFloatOrNull()?.roundToInt() ?: return null
                val endY = coordinates[1].toFloatOrNull()?.roundToInt() ?: return null
                endX to endY
            } else {
                null
            }
        }

    fun moveTo(newX: Int, newY: Int): EditableHitObject {
        if (kind == EditorHitObjectKind.Spinner) {
            return this
        }

        val clampedX = newX.coerceIn(0, 512)
        val clampedY = newY.coerceIn(0, 384)
        val updated = fields.toMutableList()
        val dx = clampedX - x
        val dy = clampedY - y

        updated[0] = clampedX.toString()
        updated[1] = clampedY.toString()

        if (kind == EditorHitObjectKind.Slider && updated.size > 5) {
            updated[5] = updated[5].split('|').mapIndexed { index, segment ->
                if (index == 0) {
                    segment
                } else {
                    val coordinates = segment.split(':').toMutableList()
                    if (coordinates.size >= 2) {
                        coordinates[0].toFloatOrNull()?.let { coordinates[0] = (it + dx).roundToInt().coerceIn(0, 512).toString() }
                        coordinates[1].toFloatOrNull()?.let { coordinates[1] = (it + dy).roundToInt().coerceIn(0, 384).toString() }
                    }
                    coordinates.joinToString(":")
                }
            }.joinToString("|")
        }

        return copy(fields = updated, dirty = true)
    }

    fun moveInTime(newTime: Int): EditableHitObject {
        val clampedTime = newTime.coerceAtLeast(0)
        val updated = fields.toMutableList()
        val delta = clampedTime - time
        updated[2] = clampedTime.toString()

        if (kind == EditorHitObjectKind.Spinner && updated.size > 5) {
            updated[5] = (endTime + delta).coerceAtLeast(clampedTime + 1).toString()
        }

        return copy(fields = updated, dirty = true)
    }

    fun withNewCombo(enabled: Boolean): EditableHitObject {
        val updated = fields.toMutableList()
        updated[3] = if (enabled) (type or 4).toString() else (type and 4.inv()).toString()
        return copy(fields = updated, dirty = true)
    }

    internal fun serialize() = if (!dirty && originalLine != null) originalLine else fields.joinToString(",")

    companion object {
        internal fun parse(line: String, sourceLine: Int): EditableHitObject? {
            val fields = line.split(',')
            if (fields.size < 5) {
                return null
            }

            val type = fields[3].trim().toIntOrNull() ?: return null
            val kind = when (type % 16) {
                1, 5 -> EditorHitObjectKind.Circle
                2, 6 -> EditorHitObjectKind.Slider
                8, 12 -> EditorHitObjectKind.Spinner
                else -> return null
            }

            if (fields[0].trim().toFloatOrNull() == null || fields[1].trim().toFloatOrNull() == null ||
                fields[2].trim().toDoubleOrNull() == null || (kind == EditorHitObjectKind.Spinner && fields.getOrNull(5)?.trim()?.toDoubleOrNull() == null)
            ) {
                return null
            }

            return EditableHitObject(sourceLine.toLong(), sourceLine, fields, line, false)
        }

        fun circle(editorId: Long, x: Int, y: Int, time: Int, newCombo: Boolean) = EditableHitObject(
            editorId,
            null,
            listOf(
                x.coerceIn(0, 512).toString(),
                y.coerceIn(0, 384).toString(),
                time.coerceAtLeast(0).toString(),
                if (newCombo) "5" else "1",
                "0",
                "0:0:0:0:",
            ),
            null,
            true,
        )

        fun slider(
            editorId: Long,
            x: Int,
            y: Int,
            endX: Int,
            endY: Int,
            time: Int,
            newCombo: Boolean,
        ): EditableHitObject {
            val startX = x.coerceIn(0, 512)
            val startY = y.coerceIn(0, 384)
            val targetX = endX.coerceIn(0, 512)
            val targetY = endY.coerceIn(0, 384)
            val length = hypot((targetX - startX).toDouble(), (targetY - startY).toDouble()).coerceAtLeast(1.0)

            return EditableHitObject(
                editorId,
                null,
                listOf(
                    startX.toString(),
                    startY.toString(),
                    time.coerceAtLeast(0).toString(),
                    if (newCombo) "6" else "2",
                    "0",
                    "L|$targetX:$targetY",
                    "1",
                    length.roundToInt().toString(),
                ),
                null,
                true,
            )
        }

        fun spinner(editorId: Long, time: Int, endTime: Int, newCombo: Boolean) = EditableHitObject(
            editorId,
            null,
            listOf(
                "256",
                "192",
                time.coerceAtLeast(0).toString(),
                if (newCombo) "12" else "8",
                "0",
                endTime.coerceAtLeast(time + 1).toString(),
                "0:0:0:0:",
            ),
            null,
            true,
        )
    }
}
