package com.cyclone.mobile.gesture

import com.cyclone.mobile.gesture.diagnostics.HumanGestureTraceAdapter
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HumanGestureEngineFuzzTest {
    @Test
    fun manySeedsStayDeterministicFiniteConvexAndScreenSafe() {
        val viewports = listOf(
            GestureBounds(0f, 0f, 1f, 1f),
            GestureBounds(0f, 0f, 8f, 8f),
            GestureBounds(0f, 0f, 360f, 800f),
            GestureBounds(0f, 0f, 1080f, 2400f),
            GestureBounds(-5000f, -3000f, 5000f, 3000f),
            GestureBounds(1_000_000f, -2_000_000f, 1_010_000f, -1_994_000f),
        )

        repeat(25_000) { index ->
            val seed = index.toLong() * 0x9E3779B9L
            val viewport = viewports[index % viewports.size]
            val spanX = viewport.width * 1.4f
            val spanY = viewport.height * 1.4f
            val rng = SeededGestureRng(seed xor 0x5A5A5A5AL)
            fun x() = viewport.left - viewport.width * 0.2f + (rng.nextUnit() * spanX).toFloat()
            fun y() = viewport.top - viewport.height * 0.2f + (rng.nextUnit() * spanY).toFloat()
            val start = GesturePoint(x(), y())
            val end = if (index % 97 == 0) start else GesturePoint(x(), y())
            val profile = HumanizeProfile.values()[index % HumanizeProfile.values().size]

            val first = HumanGestureEngine.planSwipe(start, end, viewport, profile, seed)
            val replay = HumanGestureEngine.planSwipe(start, end, viewport, profile, seed)
            assertEquals(first, replay)
            assertTrue(first.durationMs in 70L..1_200L)

            val controlXs = listOf(first.start.x, first.control1.x, first.control2.x, first.end.x)
            val controlYs = listOf(first.start.y, first.control1.y, first.control2.y, first.end.y)
            val minX = controlXs.minOrNull()!!
            val maxX = controlXs.maxOrNull()!!
            val minY = controlYs.minOrNull()!!
            val maxY = controlYs.maxOrNull()!!
            val epsilon = maxOf(1e-4f, maxOf(viewport.width, viewport.height) * 1e-6f)
            for (point in first.sample(32)) {
                assertTrue(point.x.isFinite() && point.y.isFinite())
                assertTrue("sample escaped viewport at seed=$seed", viewport.contains(point))
                assertTrue("sample escaped cubic x convex hull at seed=$seed", point.x >= minX - epsilon && point.x <= maxX + epsilon)
                assertTrue("sample escaped cubic y convex hull at seed=$seed", point.y >= minY - epsilon && point.y <= maxY + epsilon)
            }

            val trace = HumanGestureTraceAdapter.fromSwipe(first, viewport, seed, segments = 16)
            val replayTrace = HumanGestureTraceAdapter.fromSwipe(replay, viewport, seed, segments = 16)
            assertEquals(trace, replayTrace)
            assertTrue(trace.points.all { it.u.isFinite() && it.v.isFinite() && it.t.isFinite() })
            assertTrue(trace.points.all { it.u in 0.0..1.0 && it.v in 0.0..1.0 && it.t in 0.0..1.0 })

            val targetCenter = viewport.clamp(GesturePoint(x(), y()))
            val halfWidth = maxOf(0.0001f, viewport.width * (0.00001f + rng.nextUnit().toFloat() * 0.25f))
            val halfHeight = maxOf(0.0001f, viewport.height * (0.00001f + rng.nextUnit().toFloat() * 0.25f))
            val target = GestureBounds(
                targetCenter.x - halfWidth,
                targetCenter.y - halfHeight,
                targetCenter.x + halfWidth,
                targetCenter.y + halfHeight,
            )
            val tap = HumanGestureEngine.planTap(target, viewport, profile, seed)
            val replayTap = HumanGestureEngine.planTap(target, viewport, profile, seed)
            assertEquals(tap, replayTap)
            assertTrue(viewport.contains(tap.point))
            assertTrue(target.contains(tap.point))
            assertTrue(tap.point.x.isFinite() && tap.point.y.isFinite())
            assertTrue(tap.durationMs in 35L..220L)
        }
    }

    @Test
    fun ordinaryLongStrokeProfilesDoNotInvertAcrossSeeds() {
        val viewport = GestureBounds(0f, 0f, 1080f, 2400f)
        val start = GesturePoint(540f, 2100f)
        val end = GesturePoint(540f, 300f)
        repeat(2_000) { index ->
            val seed = 0xC1C10E5L + index
            val off = HumanGestureEngine.planSwipe(start, end, viewport, HumanizeProfile.OFF, seed)
            val light = HumanGestureEngine.planSwipe(start, end, viewport, HumanizeProfile.LIGHT, seed)
            val normal = HumanGestureEngine.planSwipe(start, end, viewport, HumanizeProfile.NORMAL, seed)
            val offDeviation = maxChordDeviation(off)
            val lightDeviation = maxChordDeviation(light)
            val normalDeviation = maxChordDeviation(normal)
            assertTrue(offDeviation < 0.001f)
            assertTrue("LIGHT must separate from OFF at seed=$seed", lightDeviation > offDeviation + 0.1f)
            assertTrue("NORMAL/LIGHT inversion at seed=$seed", normalDeviation > lightDeviation)
        }
    }

    private fun maxChordDeviation(stroke: StrokePlan): Float {
        val a = stroke.start
        val b = stroke.end
        val dx = b.x - a.x
        val dy = b.y - a.y
        val chord = a.distanceTo(b)
        if (chord < 1e-6f) return 0f
        return stroke.sample(32).maxOf { point ->
            abs(dx * (point.y - a.y) - dy * (point.x - a.x)) / chord
        }
    }
}
