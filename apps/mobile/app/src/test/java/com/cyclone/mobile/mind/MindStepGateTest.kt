package com.cyclone.mobile.mind

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MindStepGateTest {
    @Test
    fun pauseWaitsForTheRunningStepThenHoldsTheNext() {
        val gate = MindStepGate()
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        val steps = AtomicInteger()
        val mind = Thread {
            gate.step { inside.countDown(); release.await(); steps.incrementAndGet() }
            gate.step { steps.incrementAndGet() }
        }
        mind.start()
        assertTrue(inside.await(2, TimeUnit.SECONDS))
        // While a step runs, a short pause gives up: the switch rolls back instead of cutting an action in half.
        assertFalse(gate.pause(50))
        release.countDown()
        assertTrue(gate.pause(2_000))
        assertTrue(gate.paused)
        Thread.sleep(100)
        assertEquals("the next step waits at the boundary", 1, steps.get())
        gate.resume()
        mind.join(2_000)
        assertEquals(2, steps.get())
        assertFalse(gate.paused)
    }

    @Test
    fun resumeWithoutPauseAndDoublePauseAreHarmless() {
        val gate = MindStepGate()
        gate.resume()
        assertTrue(gate.pause(10))
        assertTrue(gate.pause(10))
        gate.resume()
        gate.resume()
        // Exactly one permit: a step runs and a pause still works afterwards.
        assertEquals(1, gate.step { 1 })
        assertTrue(gate.pause(10))
        gate.resume()
    }
}
