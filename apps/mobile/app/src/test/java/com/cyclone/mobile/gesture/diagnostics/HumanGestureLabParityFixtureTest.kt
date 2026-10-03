package com.cyclone.mobile.gesture.diagnostics

import com.cyclone.mobile.gesture.GestureBounds
import com.cyclone.mobile.gesture.GesturePoint
import com.cyclone.mobile.gesture.HumanizeProfile
import com.cyclone.mobile.gesture.TapPlan
import org.junit.Assert.assertEquals
import org.junit.Test

class HumanGestureLabParityFixtureTest {
    @Test
    fun productionTapFixtureMatchesTraceV1AndCanonicalHashV1() {
        val viewport = GestureBounds(0f, 0f, 100f, 200f)
        val target = GestureBounds(20f, 40f, 60f, 80f)
        val plan = TapPlan(
            point = GesturePoint(40f, 60f),
            durationMs = 50L,
            profile = HumanizeProfile.OFF,
        )
        val trace = HumanGestureTraceAdapter.fromTap(plan, viewport, target)

        assertEquals("cyclone.human_gesture.trace.v1", trace.schema)
        assertEquals("human-gesture", trace.engineName)
        assertEquals("1", trace.engineVersion)
        assertEquals("procedural", trace.source)
        assertEquals("tap", trace.gestureType)
        assertEquals(HumanizeProfile.OFF, trace.profile)
        assertEquals(100.0, trace.viewport.widthPx, 0.0)
        assertEquals(200.0, trace.viewport.heightPx, 0.0)
        assertEquals(50L, trace.durationMs)
        assertEquals(NormalizedTraceBounds(0.2, 0.2, 0.6, 0.4), trace.target)
        assertEquals(listOf(NormalizedTracePoint(0.4, 0.3, 0.0)), trace.points)
        assertEquals(
            "7a6f03e5084e2bc89bc17bedc5ca5f475bf53aae7e69eb4144441fd48c653d31",
            HumanGestureTraceHasher.sha256Hex(trace),
        )
    }
}
