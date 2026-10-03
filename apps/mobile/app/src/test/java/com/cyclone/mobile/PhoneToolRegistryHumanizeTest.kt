package com.cyclone.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneToolRegistryHumanizeTest {
    @Test
    fun `touch tools publish the bounded v03 humanize enum`() {
        val expected = listOf("auto", "off", "light", "normal")
        listOf("phone.click", "phone.long_press", "phone.tap", "phone.scroll", "phone.swipe").forEach { name ->
            val definition = requireNotNull(PhoneToolRegistry.definition(name)).toJson()
            val humanize = definition.getJSONObject("parameters").getJSONObject("humanize")
            val values = humanize.getJSONArray("enum")
            assertEquals(expected.size, values.length())
            expected.forEachIndexed { index, value -> assertEquals(value, values.getString(index)) }
            assertEquals("auto", humanize.getString("default"))
            assertEquals("reject", humanize.getString("unknownValues"))
        }
    }

    @Test
    fun `non touch tools do not accidentally acquire the humanize contract`() {
        listOf("phone.type", "phone.back", "phone.observe").forEach { name ->
            val definition = requireNotNull(PhoneToolRegistry.definition(name)).toJson()
            assertTrue(definition.isNull("parameters"))
        }
    }
}
