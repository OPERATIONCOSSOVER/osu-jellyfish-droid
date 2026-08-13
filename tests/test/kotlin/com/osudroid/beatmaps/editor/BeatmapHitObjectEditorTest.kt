package com.osudroid.beatmaps.editor

import com.osudroid.beatmaps.timings.TimingControlPoint
import com.osudroid.beatmaps.timings.TimingControlPointManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BeatmapHitObjectEditorTest {
    private val original = """osu file format v14

[Metadata]
Title:Editor Test

[HitObjects]
// keep this comment
64,64,1000,1,0,0:0:0:0:
128,128,2000,2,0,L|256:128,1,128
256,192,3000,8,0,4000,0:0:0:0:
"""

    @Test
    fun `parses circles sliders and spinners`() {
        val objects = BeatmapHitObjectEditor.parse(original.toByteArray())

        assertEquals(listOf(EditorHitObjectKind.Circle, EditorHitObjectKind.Slider, EditorHitObjectKind.Spinner), objects.map { it.kind })
        assertEquals(4000, objects.last().endTime)
        assertEquals(256 to 128, objects[1].sliderEnd)
    }

    @Test
    fun `adds moves deletes and chronologically sorts objects without touching other sections`() {
        val objects = BeatmapHitObjectEditor.parse(original.toByteArray()).toMutableList()
        objects.removeAt(2)
        objects[0] = objects[0].moveTo(100, 200).moveInTime(2500)
        objects += EditableHitObject.circle(100, 300, 100, 500, true)

        val transformed = BeatmapHitObjectEditor.transform(original, objects)

        assertTrue(transformed.contains("[Metadata]\nTitle:Editor Test"))
        assertTrue(transformed.contains("// keep this comment"))
        assertTrue(transformed.indexOf("300,100,500,5") < transformed.indexOf("128,128,2000,2"))
        assertTrue(transformed.indexOf("128,128,2000,2") < transformed.indexOf("100,200,2500,1"))
        assertFalse(transformed.contains("256,192,3000,8"))
    }

    @Test
    fun `moving a slider translates its control points`() {
        val slider = BeatmapHitObjectEditor.parse(original.toByteArray())[1].moveTo(228, 178)

        assertEquals(228, slider.x)
        assertEquals(178, slider.y)
        assertEquals(356 to 178, slider.sliderEnd)
    }

    @Test
    fun `creates valid minimal slider and spinner lines`() {
        val slider = EditableHitObject.slider(1, 64, 64, 192, 64, 1000, false)
        val spinner = EditableHitObject.spinner(2, 2000, 3000, true)

        assertEquals("64,64,1000,2,0,L|192:64,1,128", slider.serialize())
        assertEquals("256,192,2000,12,0,3000,0:0:0:0:", spinner.serialize())
    }

    @Test
    fun `snaps to active timing point subdivisions`() {
        val points = TimingControlPointManager().apply {
            add(TimingControlPoint(0.0, 500.0, 4))
            add(TimingControlPoint(2000.0, 400.0, 4))
        }

        assertEquals(1000, BeatSnapper.snap(1040, points, 4))
        assertEquals(2100, BeatSnapper.snap(2070, points, 4))
    }
}
