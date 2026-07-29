package com.osudroid.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ThemeSongArchiveTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `osu AudioFilename wins over larger hitsound`() {
        val root = temporaryFolder.newFolder("theme")
        val song = File(root, "intro.mp3").apply { writeBytes(ByteArray(128)) }
        File(root, "normal-hitnormal.wav").writeBytes(ByteArray(2048))
        File(root, "intro.osu").writeText(
            """
            osu file format v14

            [General]
            AudioFilename: intro.mp3
            """.trimIndent()
        )

        assertEquals(song.canonicalFile, ThemeSongArchive.findCustomAudioFile(root)?.canonicalFile)
    }

    @Test
    fun `nested quoted AudioFilename is resolved`() {
        val root = temporaryFolder.newFolder("nested-theme")
        val set = File(root, "Beatmap Set").apply { mkdirs() }
        val song = File(set, "menu song.ogg").apply { writeBytes(ByteArray(256)) }
        File(set, "difficulty.osu").writeText(
            """
            osu file format v14

            [General]
            AudioFilename: "menu song.ogg"
            """.trimIndent()
        )

        assertEquals(song.canonicalFile, ThemeSongArchive.findCustomAudioFile(root)?.canonicalFile)
    }

    @Test
    fun `largest audio is used when archive has no readable osu metadata`() {
        val root = temporaryFolder.newFolder("metadata-free-theme")
        File(root, "click.wav").writeBytes(ByteArray(64))
        val song = File(root, "song.flac").apply { writeBytes(ByteArray(4096)) }

        assertEquals(song.canonicalFile, ThemeSongArchive.findCustomAudioFile(root)?.canonicalFile)
    }

    @Test
    fun `metadata lookup follows the selected audio`() {
        val root = temporaryFolder.newFolder("multi-difficulty-theme")
        val song = File(root, "song.mp3").apply { writeBytes(ByteArray(128)) }
        val osu = File(root, "hard.osu").apply {
            writeText(
                """
                [General]
                AudioFilename: song.mp3
                """.trimIndent()
            )
        }

        assertEquals(
            osu.canonicalFile,
            ThemeSongArchive.findOsuFileForAudio(root, song)?.canonicalFile
        )
    }
}
