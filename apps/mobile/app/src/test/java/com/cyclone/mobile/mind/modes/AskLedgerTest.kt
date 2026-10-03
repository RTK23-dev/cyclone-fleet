package com.cyclone.mobile.mind.modes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class AskLedgerTest {
    @Before fun clear() = AskLedger.clear()

    @Test
    fun `a request is routed, then finished or handed over, and the same words twice stay two requests`() {
        val first = AskLedger.begin("volume up", nowMs = 1_000)
        val second = AskLedger.begin("volume up", nowMs = 2_000)
        AskLedger.routed("volume up", "instant", "grammar", "a clear command", 0, nowMs = 2_010)
        // The newest open request with these words takes the route.
        assertEquals("waiting", AskLedger.get(first)!!.state)
        assertEquals("running", AskLedger.get(second)!!.state)
        AskLedger.finished("volume up", "instant", true, "Volume up.", nowMs = 2_400)
        assertEquals("done", AskLedger.get(second)!!.state)
        assertEquals(2_400L, AskLedger.get(second)!!.finishedAtMs)

        val mind = AskLedger.begin("make a plan", nowMs = 3_000)
        AskLedger.routed("make a plan", "instant", "phone", "", 0, nowMs = 3_010)
        AskLedger.handed("make a plan", "mind", nowMs = 3_500)
        assertEquals("mind", AskLedger.get(mind)!!.lane)
        assertEquals("running", AskLedger.get(mind)!!.state)
        assertEquals("cancelled", AskLedger.cancel(mind, nowMs = 4_000)!!.state)
    }

    @Test
    fun `a route long after the request is not matched to it, and only the last twenty are kept`() {
        val old = AskLedger.begin("open chrome", nowMs = 0)
        AskLedger.routed("open chrome", "instant", "grammar", null, 0, nowMs = AskLedger.MATCH_WINDOW_MS + 1)
        assertNull(AskLedger.get(old)!!.lane)
        repeat(25) { AskLedger.begin("r$it", nowMs = 10L + it) }
        assertNull(AskLedger.get(old))
        assertEquals("r24", AskLedger.latest()!!.text)
    }
}
