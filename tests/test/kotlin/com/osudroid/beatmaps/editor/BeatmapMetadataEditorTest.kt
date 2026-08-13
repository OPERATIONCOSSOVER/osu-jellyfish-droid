package com.osudroid.beatmaps.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BeatmapMetadataEditorTest {
    private val metadata = EditableBeatmapMetadata(
        title = "New Title",
        titleUnicode = "新しいタイトル",
        artist = "New Artist",
        artistUnicode = "新しいアーティスト",
        creator = "Mapper",
        version = "Insane",
        source = "Game",
        tags = "tag one two",
    )

    @Test
    fun `updates metadata while preserving unrelated content and line endings`() {
        val original = listOf(
            "osu file format v14",
            "",
            "[Metadata]",
            "Title:Old Title",
            "Artist:Old Artist",
            "Creator:Old Mapper",
            "Version:Normal",
            "BeatmapID:123",
            "BeatmapSetID:456",
            "CustomProperty:keep me",
            "",
            "[HitObjects]",
            "64,64,1000,1,0,0:0:0:0:",
        ).joinToString("\r\n", postfix = "\r\n")

        val transformed = BeatmapMetadataEditor.transform(original, metadata)

        assertTrue(transformed.contains("Title:New Title\r\n"))
        assertTrue(transformed.contains("TitleUnicode:新しいタイトル\r\n"))
        assertTrue(transformed.contains("ArtistUnicode:新しいアーティスト\r\n"))
        assertTrue(transformed.contains("BeatmapID:123\r\n"))
        assertTrue(transformed.contains("BeatmapSetID:456\r\n"))
        assertTrue(transformed.contains("CustomProperty:keep me\r\n"))
        assertTrue(transformed.contains("64,64,1000,1,0,0:0:0:0:\r\n"))
        assertFalse(transformed.replace("\r\n", "").contains('\n'))
        assertTrue(transformed.endsWith("\r\n"))
    }

    @Test
    fun `updates duplicate keys so the parser cannot retain a stale value`() {
        val original = """osu file format v14

[Metadata]
Title:First
Title :Second
Artist:Artist
Creator:Creator
Version:Version

[Difficulty]
HPDrainRate:5"""

        val transformed = BeatmapMetadataEditor.transform(original, metadata)

        assertEquals(2, Regex("(?m)^Title\\s*:New Title$").findAll(transformed).count())
        assertFalse(transformed.contains("Title:First"))
        assertFalse(transformed.contains("Title :Second"))
    }

    @Test
    fun `inserts a missing metadata section before difficulty`() {
        val original = """osu file format v14

[General]
AudioFilename:audio.mp3

[Difficulty]
HPDrainRate:5

[HitObjects]
64,64,1000,1,0,0:0:0:0:"""

        val transformed = BeatmapMetadataEditor.transform(original, metadata)

        assertTrue(transformed.indexOf("[Metadata]") > transformed.indexOf("[General]"))
        assertTrue(transformed.indexOf("[Metadata]") < transformed.indexOf("[Difficulty]"))
        assertTrue(transformed.contains("Tags:tag one two"))
    }

    @Test
    fun `preserves a UTF-8 BOM`() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val input = bom + "[Metadata]\nTitle:Old\nArtist:Artist\nCreator:Creator\nVersion:Normal\n".toByteArray()

        val transformed = BeatmapMetadataEditor.transform(input, metadata)

        assertTrue(transformed.copyOfRange(0, 3).contentEquals(bom))
        assertTrue(transformed.decodeToString(3).contains("Title:New Title"))
    }

    @Test
    fun `rejects missing required metadata`() {
        val invalid = metadata.copy(title = "  ")

        try {
            BeatmapMetadataEditor.transform("[Metadata]\n", invalid)
            fail("Expected empty title to be rejected")
        } catch (e: IllegalArgumentException) {
            assertEquals("Title cannot be empty", e.message)
        }
    }

    @Test
    fun `rejects line breaks in metadata values`() {
        val invalid = metadata.copy(tags = "safe\n[HitObjects]")

        try {
            BeatmapMetadataEditor.transform("[Metadata]\n", invalid)
            fail("Expected line break to be rejected")
        } catch (e: IllegalArgumentException) {
            assertEquals("Tags cannot contain a line break", e.message)
        }
    }
}
