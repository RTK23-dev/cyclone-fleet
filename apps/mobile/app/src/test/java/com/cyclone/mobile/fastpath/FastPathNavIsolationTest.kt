package com.cyclone.mobile.fastpath

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FastPathNavIsolationTest {
    @Test
    fun formFieldBatchThenOneNavClickIsKept() {
        val result = FastPathNavIsolation.keepPlanned(
            listOf(
                FastPathPlannedAction("phone.type", false),
                FastPathPlannedAction("phone.type", false),
                FastPathPlannedAction("phone.click", true),
            ),
        )
        assertEquals(3, result.allowed.size)
        assertTrue(result.dropped.isEmpty())
        assertNull(result.reason)
    }

    @Test
    fun secondScreenChangingMutationIsDropped() {
        val result = FastPathNavIsolation.keepPlanned(
            listOf(
                FastPathPlannedAction("phone.click", true),
                FastPathPlannedAction("phone.click", true),
                FastPathPlannedAction("phone.back", true),
            ),
        )
        assertEquals(listOf("phone.click"), result.allowed.map { it.tool })
        assertEquals(listOf("phone.click", "phone.back"), result.dropped.map { it.tool })
        assertTrue(result.truncated)
        assertTrue(result.reason!!.startsWith("NAV_ISOLATION"))
    }

    @Test
    fun formFillAfterNavigationIsDropped() {
        val result = FastPathNavIsolation.keepPlanned(
            listOf(
                FastPathPlannedAction("phone.open_app", true),
                FastPathPlannedAction("phone.type", false),
            ),
        )
        assertEquals(listOf("phone.open_app"), result.allowed.map { it.tool })
        assertEquals(listOf("phone.type"), result.dropped.map { it.tool })
    }

    @Test
    fun samePageTypesAreNotScreenChangingEvenIfModelMarkedExpectedPageChange() {
        assertFalse(FastPathNavIsolation.isScreenChanging("phone.type", expectedPageChange = true))
        assertFalse(FastPathNavIsolation.isScreenChanging("phone.replace_text", expectedPageChange = false))
        assertFalse(FastPathNavIsolation.isScreenChanging("phone.scroll", expectedPageChange = false))
        assertTrue(FastPathNavIsolation.isScreenChanging("phone.scroll", expectedPageChange = true))
        assertTrue(FastPathNavIsolation.isScreenChanging("phone.launch_intent", expectedPageChange = false))
        assertFalse(FastPathNavIsolation.isScreenChanging("phone.click", expectedPageChange = false))
        assertTrue(FastPathNavIsolation.isScreenChanging("phone.click", expectedPageChange = true))
    }

    @Test
    fun genericKeepPreservesCallerPayloads() {
        data class Act(val tool: String, val nav: Boolean, val id: String)
        val kept = FastPathNavIsolation.keep(
            listOf(Act("phone.click", true, "a"), Act("phone.home", true, "b")),
            { it.tool },
            { it.nav },
        )
        assertEquals(listOf("a"), kept.allowed.map { it.id })
    }
}
