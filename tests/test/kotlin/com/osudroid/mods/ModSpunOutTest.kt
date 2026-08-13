package com.osudroid.mods

import com.osudroid.utils.ModUtils
import org.junit.Assert
import org.junit.Test

class ModSpunOutTest {
    @Test
    fun `Test stable metadata`() {
        ModSpunOut().apply {
            Assert.assertEquals("Spun Out", name)
            Assert.assertEquals("SO", acronym)
            Assert.assertEquals(ModType.Automation, type)
            Assert.assertTrue(isRanked)
            Assert.assertEquals(286.48f, ModSpunOut.SPINS_PER_MINUTE, 0f)
            Assert.assertEquals(286.48f / 60f, ModSpunOut.ROTATIONS_PER_SECOND, 0f)
        }
    }

    @Test
    fun `Test automation compatibility`() {
        val spunOut = ModSpunOut()

        Assert.assertFalse(spunOut.isCompatibleWith(ModAutoplay()))
        Assert.assertFalse(spunOut.isCompatibleWith(ModAutopilot()))
        Assert.assertTrue(spunOut.isCompatibleWith(ModRelax()))
    }

    @Test
    fun `Test API deserialization`() {
        val mods = ModUtils.deserializeMods("""[{"acronym":"SO"}]""")

        Assert.assertTrue(ModSpunOut::class in mods)
    }
}
