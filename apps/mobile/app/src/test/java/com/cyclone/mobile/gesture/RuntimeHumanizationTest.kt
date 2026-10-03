package com.cyclone.mobile.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class RuntimeHumanizationTest {
    @Test
    fun `bounded preference parsing accepts only the v03 contract`() {
        assertEquals(HumanizePreference.AUTO, HumanizePreference.parse(null))
        assertEquals(HumanizePreference.AUTO, HumanizePreference.parse("auto"))
        assertEquals(HumanizePreference.OFF, HumanizePreference.parse("OFF"))
        assertEquals(HumanizePreference.LIGHT, HumanizePreference.parse("light"))
        assertEquals(HumanizePreference.NORMAL, HumanizePreference.parse(" normal "))
        assertThrows(IllegalArgumentException::class.java) { HumanizePreference.parse("") }
        assertThrows(IllegalArgumentException::class.java) { HumanizePreference.parse("arbitrary-path") }
        assertNull(HumanizePreference.parseOrNull("arbitrary-path"))
    }

    @Test
    fun `auto selects light taps and long press normal swipe and scroll and off precision`() {
        assertEquals(
            HumanizeProfile.LIGHT,
            HumanGestureRuntimePolicy.resolve(HumanizePreference.AUTO, RuntimeGestureKind.FALLBACK_TAP),
        )
        assertEquals(
            HumanizeProfile.LIGHT,
            HumanGestureRuntimePolicy.resolve(HumanizePreference.AUTO, RuntimeGestureKind.COORDINATE_TAP),
        )
        assertEquals(
            HumanizeProfile.LIGHT,
            HumanGestureRuntimePolicy.resolve(HumanizePreference.AUTO, RuntimeGestureKind.LONG_PRESS),
        )
        assertEquals(
            HumanizeProfile.NORMAL,
            HumanGestureRuntimePolicy.resolve(HumanizePreference.AUTO, RuntimeGestureKind.SWIPE),
        )
        assertEquals(
            HumanizeProfile.NORMAL,
            HumanGestureRuntimePolicy.resolve(HumanizePreference.AUTO, RuntimeGestureKind.SCROLL),
        )
        assertEquals(
            HumanizeProfile.NORMAL,
            HumanGestureRuntimePolicy.resolve(HumanizePreference.AUTO, RuntimeGestureKind.GUIDED_SWIPE),
        )
        assertEquals(
            HumanizeProfile.OFF,
            HumanGestureRuntimePolicy.resolve(HumanizePreference.AUTO, RuntimeGestureKind.PRECISION),
        )
    }

    @Test
    fun `explicit profile overrides action default`() {
        RuntimeGestureKind.entries.forEach { kind ->
            assertEquals(HumanizeProfile.OFF, HumanGestureRuntimePolicy.resolve(HumanizePreference.OFF, kind))
            assertEquals(HumanizeProfile.LIGHT, HumanGestureRuntimePolicy.resolve(HumanizePreference.LIGHT, kind))
            assertEquals(HumanizeProfile.NORMAL, HumanGestureRuntimePolicy.resolve(HumanizePreference.NORMAL, kind))
        }
    }

    @Test
    fun `seed is deterministic but local ordinal prevents identical action seed reuse`() {
        val coordinates = floatArrayOf(100f, 200f, 100f, 800f)
        val first = HumanGestureSeed.derive("cmd-42", "SWIPE", 7L, coordinates, 350L)
        val replay = HumanGestureSeed.derive("cmd-42", "SWIPE", 7L, coordinates, 350L)
        val next = HumanGestureSeed.derive("cmd-42", "SWIPE", 8L, coordinates, 350L)
        assertEquals(first, replay)
        assertNotEquals(first, next)
    }
}
