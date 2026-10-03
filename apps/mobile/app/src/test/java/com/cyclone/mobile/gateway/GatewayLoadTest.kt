package com.cyclone.mobile.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GatewayLoadTest {
    @Test
    fun `requests run until all but one worker are stuck, then new ones hear busy at once`() {
        val load = GatewayLoad(slots = 4, stuckMs = 3_000)
        val a = load.enter("observe.semantic", 0).first!!
        load.enter("capture.screenshot", 100).first!!
        load.enter("observe.semantic", 200).first!!
        // Three held, but not for long yet: still admitted.
        val fresh = load.enter("ui.search", 1_000)
        assertNotNull(fresh.first)
        load.exit(fresh.first!!)
        val (ticket, busy) = load.enter("action.execute", 3_500)
        assertNull(ticket)
        assertEquals(GatewayLoad.Busy("observe.semantic", 3_500), busy)
        load.exit(a)
        assertNotNull(load.enter("action.execute", 3_600).first)
    }

    @Test
    fun `health and status always answer so the PC can tell busy from gone`() {
        val load = GatewayLoad(slots = 4, stuckMs = 3_000)
        repeat(3) { load.enter("observe.semantic", 0) }
        assertNull(load.enter("observe.semantic", 10_000).first)
        GatewayLoad.ALWAYS.forEach { op -> assertNotNull(op, load.enter(op, 10_000).first) }
        assertEquals(3 + GatewayLoad.ALWAYS.size, load.inFlight())
    }

    @Test
    fun `the new op is registered and read-only`() {
        assert("health.report" in GatewayProtocol.operations)
        assert("health.report" in GatewayProtocol.legacyReadOnlyOperations)
    }
}
