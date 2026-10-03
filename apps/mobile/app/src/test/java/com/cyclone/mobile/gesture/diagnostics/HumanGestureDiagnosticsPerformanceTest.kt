package com.cyclone.mobile.gesture.diagnostics

import com.cyclone.mobile.gesture.GestureBounds
import com.cyclone.mobile.gesture.GesturePoint
import com.cyclone.mobile.gesture.HumanGestureEngine
import com.cyclone.mobile.gesture.HumanizeProfile
import org.junit.Assert.assertTrue
import org.junit.Test

class HumanGestureDiagnosticsPerformanceTest {
    @Test
    fun canonicalTraceHashAndCombinedDiagnosticsStayBoundedAfterWarmup() {
        val viewport = GestureBounds(0f, 0f, 1080f, 2400f)
        val plan = HumanGestureEngine.planSwipe(
            GesturePoint(40f, 2200f),
            GesturePoint(900f, 200f),
            viewport,
            HumanizeProfile.NORMAL,
            seed = 20260908L,
        )
        val trace = HumanGestureTraceAdapter.fromSwipe(
            plan,
            viewport,
            seed = 20260908L,
            segments = HumanGestureDiagnostics.CANONICAL_SWIPE_SEGMENTS,
        )

        repeat(3_000) {
            HumanGestureTraceHasher.sha256Hex(trace)
            HumanGestureDiagnostics.forSwipe(plan, viewport)
        }

        val hashSamples = LongArray(10_000)
        var checksum = 0
        for (i in hashSamples.indices) {
            val before = System.nanoTime()
            val hash = HumanGestureTraceHasher.sha256Hex(trace)
            hashSamples[i] = System.nanoTime() - before
            checksum = checksum xor hash.hashCode()
        }
        hashSamples.sort()
        val hashP50 = percentile(hashSamples, 0.50)
        val hashP95 = percentile(hashSamples, 0.95)
        val hashP99 = percentile(hashSamples, 0.99)
        println("HumanGestureTraceHasher latency ns: p50=$hashP50 p95=$hashP95 p99=$hashP99 checksum=$checksum")

        val combinedSamples = LongArray(10_000)
        for (i in combinedSamples.indices) {
            val before = System.nanoTime()
            val diagnostics = HumanGestureDiagnostics.forSwipe(plan, viewport)
            combinedSamples[i] = System.nanoTime() - before
            checksum = checksum xor diagnostics.traceHashSha256.hashCode()
        }
        combinedSamples.sort()
        val combinedP50 = percentile(combinedSamples, 0.50)
        val combinedP95 = percentile(combinedSamples, 0.95)
        val combinedP99 = percentile(combinedSamples, 0.99)
        println("HumanGestureDiagnostics swipe latency ns: p50=$combinedP50 p95=$combinedP95 p99=$combinedP99 checksum=$checksum")

        assertTrue("p99 trace hashing $hashP99 ns exceeded 1 ms diagnostic budget", hashP99 < 1_000_000L)
        assertTrue("p99 combined diagnostics $combinedP99 ns exceeded 2 ms diagnostic budget", combinedP99 < 2_000_000L)
    }

    private fun percentile(sorted: LongArray, fraction: Double): Long =
        sorted[((sorted.size - 1) * fraction).toInt()]
}
