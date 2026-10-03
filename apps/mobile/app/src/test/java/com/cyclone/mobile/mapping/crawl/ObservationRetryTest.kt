package com.cyclone.mobile.mapping.crawl

import com.cyclone.mobile.agent.CaptureChanged
import com.cyclone.mobile.gateway.GatewayProtocolException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservationRetryTest {
    @Test fun aScreenThatKeepsChangingIsRetriedWithLongerQuietWaitsThenTolerated() {
        val tried = mutableListOf<ObservationRetry.Attempt>()
        val pauses = mutableListOf<Long>()
        val (value, cause) = ObservationRetry.run({ attempt ->
            tried += attempt
            if (!attempt.tolerateContentChange) throw CaptureChanged()
            "tree"
        }, pause = { pauses += it })
        assertEquals("tree", value)
        assertNull(cause)
        assertEquals(5, tried.size)
        assertEquals(listOf(0L, 1_000L, 2_000L, 3_000L, 3_000L), tried.map { it.settleMaxMs })
        assertEquals(listOf(false, false, false, false, true), tried.map { it.tolerateContentChange })
        assertEquals(listOf(200L, 400L, 600L, 800L), pauses)
    }

    @Test fun theFirstGoodCaptureWins() {
        var calls = 0
        val (value, _) = ObservationRetry.run({ calls++; if (calls < 2) throw GatewayProtocolException("OBSERVATION_CHANGED_DURING_CAPTURE", "x") else "ok" }, pause = {})
        assertEquals("ok", value)
        assertEquals(2, calls)
    }

    @Test fun aRealFailureStopsAtOnceAndKeepsItsCause() {
        var calls = 0
        val (value, cause) = ObservationRetry.run<String>({ calls++; throw GatewayProtocolException("ACCESSIBILITY_NOT_CONNECTED", "off") }, pause = {})
        assertNull(value)
        assertEquals("ACCESSIBILITY_OFF", cause)
        assertEquals(1, calls)
        assertEquals("DISPLAY_GONE", ObservationRetry.cause(IllegalStateException("DISPLAY_GONE")))
        assertEquals("SESSION_DISPLAY_MISMATCH", ObservationRetry.cause(GatewayProtocolException("SESSION_DISPLAY_MISMATCH", "x")))
        assertEquals("ILLEGAL_ARGUMENT_EXCEPTION", ObservationRetry.cause(IllegalArgumentException("x")))
    }

    @Test fun aScreenThatNeverSettlesEvenToleratedReportsIt() {
        val (value, cause) = ObservationRetry.run<String>({ throw CaptureChanged() }, pause = {})
        assertNull(value)
        assertEquals("SCREEN_KEPT_CHANGING", cause)
        assertFalse(cause!!.contains(" "))
        assertTrue(ObservationRetry.PLAN.last().tolerateContentChange)
    }
}
