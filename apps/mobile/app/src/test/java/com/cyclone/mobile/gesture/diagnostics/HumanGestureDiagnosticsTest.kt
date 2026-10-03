package com.cyclone.mobile.gesture.diagnostics

import com.cyclone.mobile.gesture.GestureBounds
import com.cyclone.mobile.gesture.GesturePoint
import com.cyclone.mobile.gesture.HumanGestureEngine
import com.cyclone.mobile.gesture.HumanizePreference
import com.cyclone.mobile.gesture.HumanizeProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HumanGestureDiagnosticsTest {
    private val viewport = GestureBounds(0f, 0f, 1080f, 2400f)

    @Test
    fun samePlanProducesSameCanonicalHashAndExplicitVersions() {
        val plan = HumanGestureEngine.planSwipe(
            start = GesturePoint(100f, 2200f),
            end = GesturePoint(900f, 260f),
            viewport = viewport,
            profile = HumanizeProfile.NORMAL,
            seed = 20260908L,
        )

        val first = HumanGestureDiagnostics.forSwipe(plan, viewport)
        val second = HumanGestureDiagnostics.forSwipe(plan, viewport)

        assertEquals(first, second)
        assertEquals("cyclone.human_gesture.control.v1", first.controlVersion)
        assertEquals("cyclone.human_gesture.trace.v1", first.traceVersion)
        assertEquals("cyclone.human_gesture.trace_hash.v1", first.hashVersion)
        assertEquals("human-gesture", first.engineName)
        assertEquals("1", first.engineVersion)
        assertEquals(HumanGestureDiagnosticGestureType.SWIPE, first.gestureType)
        assertEquals(HumanizeProfile.NORMAL, first.resolvedProfile)
        assertTrue(first.traceHashSha256.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun seedAndSourceMetadataDoNotChangeCanonicalMotionHash() {
        val plan = HumanGestureEngine.planSwipe(
            GesturePoint(80f, 2050f),
            GesturePoint(870f, 300f),
            viewport,
            HumanizeProfile.LIGHT,
            seed = 77L,
        )
        val trace = HumanGestureTraceAdapter.fromSwipe(plan, viewport, seed = 77L)
        val changedDebugMetadata = trace.copy(seed = 9999L, source = "device_capture")

        assertEquals(
            HumanGestureTraceHasher.sha256Hex(trace),
            HumanGestureTraceHasher.sha256Hex(changedDebugMetadata),
        )
    }

    @Test
    fun meaningfulGeometryAndVersionChangesChangeHash() {
        val plan = HumanGestureEngine.planSwipe(
            GesturePoint(100f, 2000f),
            GesturePoint(900f, 350f),
            viewport,
            HumanizeProfile.NORMAL,
            seed = 11L,
        )
        val trace = HumanGestureTraceAdapter.fromSwipe(plan, viewport)
        val baseHash = HumanGestureTraceHasher.sha256Hex(trace)
        val changedPoint = trace.points[trace.points.size / 2].copy(
            u = (trace.points[trace.points.size / 2].u + 1e-9).coerceAtMost(1.0),
        )
        val changedPoints = trace.points.toMutableList().also { it[it.size / 2] = changedPoint }

        assertNotEquals(baseHash, HumanGestureTraceHasher.sha256Hex(trace.copy(points = changedPoints)))
        assertNotEquals(baseHash, HumanGestureTraceHasher.sha256Hex(trace.copy(schema = "cyclone.human_gesture.trace.v2")))
        assertNotEquals(baseHash, HumanGestureTraceHasher.sha256Hex(trace.copy(engineVersion = "2")))
    }

    @Test
    fun profileChangesHashWhenProductionMotionChanges() {
        val start = GesturePoint(200f, 2100f)
        val end = GesturePoint(820f, 300f)
        val light = HumanGestureEngine.planSwipe(start, end, viewport, HumanizeProfile.LIGHT, seed = 222L)
        val normal = HumanGestureEngine.planSwipe(start, end, viewport, HumanizeProfile.NORMAL, seed = 222L)

        assertNotEquals(
            HumanGestureDiagnostics.forSwipe(light, viewport).traceHashSha256,
            HumanGestureDiagnostics.forSwipe(normal, viewport).traceHashSha256,
        )
    }

    @Test
    fun offStraightCompatibilityRemainsIntact() {
        val start = GesturePoint(120f, 2200f)
        val end = GesturePoint(900f, 240f)
        val plan = HumanGestureEngine.planSwipe(start, end, viewport, HumanizeProfile.OFF, seed = 1L)
        val points = plan.sample(24)

        points.forEachIndexed { index, point ->
            val expected = GesturePoint.lerp(start, end, index.toFloat() / 24f)
            assertEquals(expected.x, point.x, 0.001f)
            assertEquals(expected.y, point.y, 0.001f)
        }
        assertEquals(
            HumanGestureDiagnostics.forSwipe(plan, viewport).traceHashSha256,
            HumanGestureDiagnostics.forSwipe(plan, viewport).traceHashSha256,
        )
    }

    @Test
    fun targetAwareTapHashIsStableAndBoundToVisibleTarget() {
        val target = GestureBounds(-20f, 100f, 80f, 210f)
        val firstPlan = HumanGestureEngine.planTap(target, viewport, HumanizeProfile.LIGHT, seed = 1234L)
        val replayPlan = HumanGestureEngine.planTap(target, viewport, HumanizeProfile.LIGHT, seed = 1234L)
        val first = HumanGestureDiagnostics.forTap(firstPlan, viewport, target)
        val replay = HumanGestureDiagnostics.forTap(replayPlan, viewport, target)

        assertEquals(first, replay)
        assertEquals(HumanGestureDiagnosticGestureType.TAP, first.gestureType)

        val widerTarget = GestureBounds(-20f, 100f, 120f, 210f)
        val samePointPlan = firstPlan.copy()
        assertNotEquals(
            first.traceHashSha256,
            HumanGestureDiagnostics.forTap(samePointPlan, viewport, widerTarget).traceHashSha256,
        )
    }

    @Test
    fun tinyEdgeAndDegeneratePlansProduceFiniteStableEvidence() {
        val tinyViewport = GestureBounds(0f, 0f, 1f, 1f)
        val stroke = HumanGestureEngine.planSwipe(
            GesturePoint(-100f, -100f),
            GesturePoint(100f, 100f),
            tinyViewport,
            HumanizeProfile.NORMAL,
            seed = 5L,
        )
        val degenerate = HumanGestureEngine.planSwipe(
            GesturePoint(0.5f, 0.5f),
            GesturePoint(0.5f, 0.5f),
            tinyViewport,
            HumanizeProfile.NORMAL,
            seed = 6L,
        )

        listOf(stroke, degenerate).forEach { plan ->
            val trace = HumanGestureTraceAdapter.fromSwipe(plan, tinyViewport)
            assertTrue(trace.points.all { it.u.isFinite() && it.v.isFinite() && it.t.isFinite() })
            assertTrue(trace.points.all { it.u in 0.0..1.0 && it.v in 0.0..1.0 && it.t in 0.0..1.0 })
            val diagnostics = HumanGestureDiagnostics.forSwipe(plan, tinyViewport)
            assertTrue(diagnostics.traceHashSha256.matches(Regex("[0-9a-f]{64}")))
        }
    }

    @Test
    fun canonicalBinaryFixtureHasIndependentGoldenSha256() {
        val trace = NormalizedGestureTrace(
            schema = "cyclone.human_gesture.trace.v1",
            engineName = "human-gesture",
            engineVersion = "1",
            source = "procedural",
            gestureType = "swipe",
            profile = HumanizeProfile.NORMAL,
            seed = 123L,
            viewport = TraceViewport(1080.0, 2400.0),
            durationMs = 340L,
            target = null,
            points = listOf(
                NormalizedTracePoint(0.1, 0.9, 0.0),
                NormalizedTracePoint(0.2, 0.7, 0.5),
                NormalizedTracePoint(0.4, 0.2, 1.0),
            ),
        )

        assertEquals(
            "19f9285b1e2669883722b236b24ef29347b206c6cef66af996b4bd3ef19be5bb",
            HumanGestureTraceHasher.sha256Hex(trace),
        )
        assertEquals(215, HumanGestureTraceHasher.canonicalBytes(trace).size)
    }

    @Test
    fun executionProjectionIsBoundedAndContainsNoFreeformSensitivePayload() {
        val plan = HumanGestureEngine.planTap(
            GestureBounds(100f, 200f, 240f, 320f),
            viewport,
            HumanizeProfile.LIGHT,
            seed = 19L,
        )
        val planDiagnostics = HumanGestureDiagnostics.forTap(
            plan,
            viewport,
            GestureBounds(100f, 200f, 240f, 320f),
        )
        val execution = HumanGestureExecutionDiagnostics(
            requestedPreference = HumanizePreference.AUTO,
            resolvedProfile = HumanizeProfile.LIGHT,
            executionMode = HumanGestureExecutionMode.SYNTHESIZED_TOUCH,
            backend = HumanGestureExecutionBackend.ACCESSIBILITY_GESTURE,
            plan = planDiagnostics,
            synthesisCpuNanos = 800L,
        )

        assertNull(execution.downgradeReason)
        assertEquals(planDiagnostics, execution.plan)
        assertFalse(
            HumanGestureExecutionDiagnostics::class.java.declaredFields.any { field ->
                field.name.contains("text", ignoreCase = true) ||
                    field.name.contains("selector", ignoreCase = true) ||
                    field.name.contains("screenshot", ignoreCase = true) ||
                    field.name.contains("session", ignoreCase = true) ||
                    field.name.contains("display", ignoreCase = true)
            },
        )
    }

    @Test
    fun fiveThousandSeedReplayBatchHasZeroHashMismatches() {
        val profiles = HumanizeProfile.entries
        var checksum = 0
        repeat(5_000) { index ->
            val seed = 0x503_000L + index
            val profile = profiles[index % profiles.size]
            val start = GesturePoint((index % 1080).toFloat(), (2300 - index % 1900).toFloat())
            val end = GesturePoint((1079 - index % 980).toFloat(), (100 + index % 1800).toFloat())
            val first = HumanGestureEngine.planSwipe(start, end, viewport, profile, seed)
            val replay = HumanGestureEngine.planSwipe(start, end, viewport, profile, seed)
            val firstHash = HumanGestureDiagnostics.forSwipe(first, viewport).traceHashSha256
            val replayHash = HumanGestureDiagnostics.forSwipe(replay, viewport).traceHashSha256
            assertEquals("hash replay mismatch at seed=$seed profile=$profile", firstHash, replayHash)
            checksum = checksum xor firstHash.hashCode()
        }
        assertNotEquals(0, checksum)
    }
}
