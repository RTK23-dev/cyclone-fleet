package com.cyclone.mobile.gesture

import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HumanGestureEngineTest {
    private val viewport = GestureBounds(0f, 0f, 1080f, 2400f)

    @Test
    fun sameSeedReplaysExactly() {
        val target = GestureBounds(120f, 240f, 360f, 340f)
        for (profile in HumanizeProfile.values()) {
            assertEquals(
                HumanGestureEngine.planTap(target, viewport, profile, seed = 99L),
                HumanGestureEngine.planTap(target, viewport, profile, seed = 99L),
            )
            assertEquals(
                HumanGestureEngine.planSwipe(
                    GesturePoint(40f, 2200f), GesturePoint(900f, 180f), viewport, profile, seed = 99L,
                ),
                HumanGestureEngine.planSwipe(
                    GesturePoint(40f, 2200f), GesturePoint(900f, 180f), viewport, profile, seed = 99L,
                ),
            )
        }
    }

    @Test
    fun offIsPrecisionBaseline() {
        val target = GestureBounds(100f, 200f, 220f, 260f)
        val tap = HumanGestureEngine.planTap(target, viewport, HumanizeProfile.OFF, seed = 123L)
        assertEquals(target.center, tap.point)

        val start = GesturePoint(100f, 2200f)
        val end = GesturePoint(900f, 200f)
        val stroke = HumanGestureEngine.planSwipe(
            start, end, viewport, HumanizeProfile.OFF, seed = 123L, preferredDurationMs = 444L,
        )
        assertEquals(444L, stroke.durationMs)
        for (i in 0..20) {
            val t = i / 20f
            val expected = GesturePoint.lerp(start, end, t)
            val actual = stroke.sampleAt(t)
            assertTrue(actual.distanceTo(expected) < 0.001f)
        }
    }

    @Test
    fun tapsRemainInsideClippedTargetAndViewport() {
        val partiallyOffscreen = GestureBounds(-80f, -50f, 32f, 24f)
        for (profile in HumanizeProfile.values()) {
            for (seed in 0L..200L) {
                val plan = HumanGestureEngine.planTap(partiallyOffscreen, viewport, profile, seed)
                assertTrue(viewport.contains(plan.point))
                assertTrue(plan.point.x in 0f..32f)
                assertTrue(plan.point.y in 0f..24f)
                assertTrue(plan.durationMs in 35L..220L)
            }
        }
    }

    @Test
    fun tinyTargetsAndTinySwipesStayFiniteAndBounded() {
        val tinyViewport = GestureBounds(0f, 0f, 8f, 8f)
        val tinyTarget = GestureBounds(0.2f, 0.2f, 0.7f, 0.9f)
        for (profile in HumanizeProfile.values()) {
            val tap = HumanGestureEngine.planTap(tinyTarget, tinyViewport, profile, seed = 7L)
            assertTrue(tinyTarget.contains(tap.point))
            val stroke = HumanGestureEngine.planSwipe(
                GesturePoint(0.1f, 0.1f), GesturePoint(0.3f, 0.4f), tinyViewport, profile, seed = 8L,
            )
            for (point in stroke.sample(64)) {
                assertTrue(point.x.isFinite() && point.y.isFinite())
                assertTrue(tinyViewport.contains(point))
            }
        }
    }

    @Test
    fun endpointsAreClampedButNeverRandomlyMoved() {
        val outsideStart = GesturePoint(-20f, 2600f)
        val outsideEnd = GesturePoint(1200f, -40f)
        for (profile in HumanizeProfile.values()) {
            val stroke = HumanGestureEngine.planSwipe(outsideStart, outsideEnd, viewport, profile, seed = 44L)
            assertEquals(GesturePoint(0f, 2400f), stroke.start)
            assertEquals(GesturePoint(1080f, 0f), stroke.end)
            assertTrue(stroke.sample(128).all(viewport::contains))
        }
    }

    @Test
    fun zeroDistanceIsStable() {
        val point = GesturePoint(500f, 500f)
        val stroke = HumanGestureEngine.planSwipe(point, point, viewport, HumanizeProfile.NORMAL, seed = 42L)
        for (sample in stroke.sample(128)) {
            assertTrue(abs(sample.x - point.x) < 0.001f)
            assertTrue(abs(sample.y - point.y) < 0.001f)
        }
    }

    @Test
    fun profilesHaveMeasuredSeparationWithoutOverCurving() {
        val start = GesturePoint(100f, 2100f)
        val end = GesturePoint(900f, 300f)
        var lightDeviation = 0.0
        var normalDeviation = 0.0
        repeat(200) { seed ->
            val light = HumanGestureEngine.planSwipe(start, end, viewport, HumanizeProfile.LIGHT, seed.toLong())
            val normal = HumanGestureEngine.planSwipe(start, end, viewport, HumanizeProfile.NORMAL, seed.toLong())
            lightDeviation += maxChordDeviation(light)
            normalDeviation += maxChordDeviation(normal)
            assertTrue(maxChordDeviation(light) < start.distanceTo(end) * 0.03f + 2f)
            assertTrue(maxChordDeviation(normal) < start.distanceTo(end) * 0.07f + 2f)
        }
        assertTrue(normalDeviation > lightDeviation * 1.5)
    }

    @Test
    fun differentSeedsProduceVariationWhenEnabled() {
        val start = GesturePoint(200f, 1900f)
        val end = GesturePoint(800f, 400f)
        assertNotEquals(
            HumanGestureEngine.planSwipe(start, end, viewport, HumanizeProfile.NORMAL, seed = 1L),
            HumanGestureEngine.planSwipe(start, end, viewport, HumanizeProfile.NORMAL, seed = 2L),
        )
    }

    @Test
    fun invalidGeometryFailsClosed() {
        expectIllegalArgument { GesturePoint(Float.NaN, 1f) }
        expectIllegalArgument { GestureBounds(10f, 0f, 1f, 10f) }
        expectIllegalArgument {
            HumanGestureEngine.planTap(
                GestureBounds(2000f, 2000f, 2100f, 2100f), viewport, HumanizeProfile.NORMAL, 1L,
            )
        }
        expectIllegalArgument {
            HumanGestureEngine.planSwipe(
                GesturePoint(1f, 1f), GesturePoint(2f, 2f), GestureBounds(0f, 0f, 0f, 10f),
            )
        }
    }

    private fun maxChordDeviation(stroke: StrokePlan): Float {
        val a = stroke.start
        val b = stroke.end
        val dx = b.x - a.x
        val dy = b.y - a.y
        val chord = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (chord < 1e-6f) return 0f
        return stroke.sample(64).maxOf { point ->
            abs(dy * point.x - dx * point.y + b.x * a.y - b.y * a.x) / chord
        }
    }

    private fun expectIllegalArgument(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
