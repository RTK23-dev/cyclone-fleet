package com.cyclone.mobile.gesture.diagnostics

import com.cyclone.mobile.gesture.GestureBounds
import com.cyclone.mobile.gesture.GesturePoint
import com.cyclone.mobile.gesture.HumanGestureEngine
import com.cyclone.mobile.gesture.HumanizeProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HumanGestureTraceAdapterTest {
    @Test
    fun swipeTraceMatchesAgent3NormalizedContractConcepts() {
        val viewport = GestureBounds(-100f, 200f, 980f, 2600f)
        val seed = 42L
        val plan = HumanGestureEngine.planSwipe(
            start = GesturePoint(50f, 2200f),
            end = GesturePoint(800f, 420f),
            viewport = viewport,
            profile = HumanizeProfile.NORMAL,
            seed = seed,
        )
        val trace = HumanGestureTraceAdapter.fromSwipe(plan, viewport, seed, segments = 32)

        assertEquals("cyclone.human_gesture.trace.v1", trace.schema)
        assertEquals("human-gesture", trace.engineName)
        assertEquals("1", trace.engineVersion)
        assertEquals("procedural", trace.source)
        assertEquals("swipe", trace.gestureType)
        assertEquals(HumanizeProfile.NORMAL, trace.profile)
        assertEquals(seed, trace.seed)
        assertEquals(1080.0, trace.viewport.widthPx, 0.0)
        assertEquals(2400.0, trace.viewport.heightPx, 0.0)
        assertEquals(plan.durationMs, trace.durationMs)
        assertEquals(33, trace.points.size)
        assertEquals(0.0, trace.points.first().t, 0.0)
        assertEquals(1.0, trace.points.last().t, 0.0)
        assertTrue(trace.points.all { it.u in 0.0..1.0 && it.v in 0.0..1.0 && it.t in 0.0..1.0 })
        assertTrue(trace.points.zipWithNext().all { (a, b) -> b.t >= a.t })
    }

    @Test
    fun samePlanAndSamplingProduceExactlyEqualTraceData() {
        val viewport = GestureBounds(0f, 0f, 1080f, 2400f)
        val plan = HumanGestureEngine.planSwipe(
            GesturePoint(100f, 2100f),
            GesturePoint(900f, 300f),
            viewport,
            HumanizeProfile.LIGHT,
            seed = 20260908L,
        )
        assertEquals(
            HumanGestureTraceAdapter.fromSwipe(plan, viewport, 20260908L, segments = 24),
            HumanGestureTraceAdapter.fromSwipe(plan, viewport, 20260908L, segments = 24),
        )
    }

    @Test
    fun tapTraceUsesVisibleClippedTargetAsFinalHitRegion() {
        val viewport = GestureBounds(0f, 0f, 360f, 800f)
        val target = GestureBounds(-20f, 100f, 40f, 180f)
        val plan = HumanGestureEngine.planTap(target, viewport, HumanizeProfile.LIGHT, seed = 7L)
        val trace = HumanGestureTraceAdapter.fromTap(plan, viewport, target, seed = 7L)

        assertEquals("tap", trace.gestureType)
        assertEquals(1, trace.points.size)
        val normalizedTarget = requireNotNull(trace.target)
        assertEquals(0.0, normalizedTarget.left, 0.0)
        assertEquals(40.0 / 360.0, normalizedTarget.right, 1e-12)
        assertEquals(100.0 / 800.0, normalizedTarget.top, 1e-12)
        assertEquals(180.0 / 800.0, normalizedTarget.bottom, 1e-12)
        val point = trace.points.single()
        assertTrue(point.u in normalizedTarget.left..normalizedTarget.right)
        assertTrue(point.v in normalizedTarget.top..normalizedTarget.bottom)
    }

    @Test(expected = IllegalArgumentException::class)
    fun adapterFailsClosedForInvalidViewport() {
        val viewport = GestureBounds(0f, 0f, 0f, 100f)
        val plan = HumanGestureEngine.planSwipe(
            GesturePoint(0f, 0f),
            GesturePoint(0f, 1f),
            GestureBounds(0f, 0f, 1f, 100f),
            HumanizeProfile.OFF,
        )
        HumanGestureTraceAdapter.fromSwipe(plan, viewport)
    }
}
