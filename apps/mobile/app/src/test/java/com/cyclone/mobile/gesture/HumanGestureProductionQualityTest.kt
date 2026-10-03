package com.cyclone.mobile.gesture

import com.cyclone.mobile.gesture.diagnostics.HumanGestureTraceAdapter
import com.cyclone.mobile.gesture.diagnostics.NormalizedGestureTrace
import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HumanGestureProductionQualityTest {
    private data class Scenario(
        val name: String,
        val viewport: GestureBounds,
        val start: GesturePoint,
        val end: GesturePoint,
    )

    private data class Metrics(
        val pathChordRatio: Double,
        val maxDeviationPx: Double,
        val maxDeviationChordRatio: Double,
        val viewportCompliant: Boolean,
    )

    private val standard = GestureBounds(0f, 0f, 1080f, 2400f)
    private val scenarios = listOf(
        Scenario("short_vertical", standard, GesturePoint(540f, 1300f), GesturePoint(540f, 1200f)),
        Scenario("medium_vertical", standard, GesturePoint(540f, 1900f), GesturePoint(540f, 700f)),
        Scenario("long_vertical", standard, GesturePoint(540f, 2250f), GesturePoint(540f, 150f)),
        Scenario("horizontal", standard, GesturePoint(900f, 1200f), GesturePoint(180f, 1200f)),
        Scenario("diagonal", standard, GesturePoint(850f, 1800f), GesturePoint(230f, 600f)),
        Scenario("edge_vertical", standard, GesturePoint(0f, 2200f), GesturePoint(0f, 200f)),
        Scenario("tiny_viewport_edge", GestureBounds(0f, 0f, 8f, 8f), GesturePoint(0f, 7.5f), GesturePoint(0f, 0.5f)),
        Scenario("degenerate", standard, GesturePoint(400f, 400f), GesturePoint(400f, 400f)),
    )

    @Test
    fun realCoreMetricsPreserveProfileOrderingAndSafety() {
        val byProfile = HumanizeProfile.values().associateWith { mutableListOf<Metrics>() }
        val byScenario = scenarios.associate { it.name to HumanizeProfile.values().associateWith { mutableListOf<Metrics>() } }

        repeat(1_000) { index ->
            val seed = 20260908L + index
            for (scenario in scenarios) {
                for (profile in HumanizeProfile.values()) {
                    val plan = HumanGestureEngine.planSwipe(
                        scenario.start,
                        scenario.end,
                        scenario.viewport,
                        profile,
                        seed,
                    )
                    val trace = HumanGestureTraceAdapter.fromSwipe(plan, scenario.viewport, seed, segments = 32)
                    val metrics = analyze(trace)
                    assertTrue("${scenario.name}/$profile escaped viewport", metrics.viewportCompliant)
                    assertTrue("${scenario.name}/$profile ratio invalid", metrics.pathChordRatio.isFinite())
                    assertTrue("${scenario.name}/$profile deviation invalid", metrics.maxDeviationPx.isFinite())
                    if (scenario.name != "degenerate") {
                        byProfile.getValue(profile) += metrics
                    }
                    byScenario.getValue(scenario.name).getValue(profile) += metrics
                }
            }
        }

        val off = byProfile.getValue(HumanizeProfile.OFF).map { it.maxDeviationChordRatio }
        val light = byProfile.getValue(HumanizeProfile.LIGHT).map { it.maxDeviationChordRatio }
        val normal = byProfile.getValue(HumanizeProfile.NORMAL).map { it.maxDeviationChordRatio }
        assertTrue(percentile(off, 0.99) < 1e-6)
        assertTrue(percentile(light, 0.50) > 0.001)
        assertTrue(percentile(normal, 0.50) > percentile(light, 0.50) * 1.5)
        assertTrue(percentile(normal, 0.99) < 0.07)

        for (profile in HumanizeProfile.values()) {
            val degenerate = byScenario.getValue("degenerate").getValue(profile)
            assertTrue(degenerate.all { abs(it.pathChordRatio - 1.0) < 1e-12 })
            assertTrue(degenerate.all { it.maxDeviationPx == 0.0 })
        }

        for (profile in HumanizeProfile.values()) {
            val values = byProfile.getValue(profile)
            println(
                "REAL_CORE profile=$profile " +
                    "dev/chord[p50=${percentile(values.map { it.maxDeviationChordRatio }, 0.50)}," +
                    "p95=${percentile(values.map { it.maxDeviationChordRatio }, 0.95)}," +
                    "p99=${percentile(values.map { it.maxDeviationChordRatio }, 0.99)}] " +
                    "path/chord[p50=${percentile(values.map { it.pathChordRatio }, 0.50)}," +
                    "p99=${percentile(values.map { it.pathChordRatio }, 0.99)}]",
            )
        }
        for (scenario in scenarios) {
            val lightValues = byScenario.getValue(scenario.name).getValue(HumanizeProfile.LIGHT)
            val normalValues = byScenario.getValue(scenario.name).getValue(HumanizeProfile.NORMAL)
            println(
                "REAL_CORE scenario=${scenario.name} " +
                    "light_dev_p50=${percentile(lightValues.map { it.maxDeviationChordRatio }, 0.50)} " +
                    "normal_dev_p50=${percentile(normalValues.map { it.maxDeviationChordRatio }, 0.50)}",
            )
        }
    }

    @Test
    fun targetAwareTapTracesRemainInsideVisibleHitRegion() {
        val viewportsAndTargets = listOf(
            standard to GestureBounds(300f, 700f, 500f, 820f),
            standard to GestureBounds(-50f, 100f, 18f, 115f),
            GestureBounds(0f, 0f, 8f, 8f) to GestureBounds(0.1f, 0.1f, 0.4f, 0.3f),
        )
        var count = 0
        repeat(2_000) { index ->
            for ((viewport, target) in viewportsAndTargets) {
                for (profile in HumanizeProfile.values()) {
                    val seed = index.toLong() * 31L + profile.ordinal
                    val plan = HumanGestureEngine.planTap(target, viewport, profile, seed)
                    val trace = HumanGestureTraceAdapter.fromTap(plan, viewport, target, seed)
                    val bounds = requireNotNull(trace.target)
                    val point = trace.points.single()
                    assertTrue(point.u in bounds.left..bounds.right)
                    assertTrue(point.v in bounds.top..bounds.bottom)
                    assertTrue(trace.durationMs in 35L..220L)
                    count++
                }
            }
        }
        assertEquals(18_000, count)
    }

    private fun analyze(trace: NormalizedGestureTrace): Metrics {
        val width = trace.viewport.widthPx
        val height = trace.viewport.heightPx
        val points = trace.points.map { Triple(it.u * width, it.v * height, it.t) }
        fun distance(a: Triple<Double, Double, Double>, b: Triple<Double, Double, Double>): Double =
            hypot(b.first - a.first, b.second - a.second)
        val path = points.zipWithNext().sumOf { (a, b) -> distance(a, b) }
        val chord = if (points.size > 1) distance(points.first(), points.last()) else 0.0
        val maxDeviation = if (chord > 1e-12) {
            val a = points.first()
            val b = points.last()
            val dx = b.first - a.first
            val dy = b.second - a.second
            points.maxOf { point ->
                abs(dx * (point.second - a.second) - dy * (point.first - a.first)) / chord
            }
        } else {
            0.0
        }
        return Metrics(
            pathChordRatio = if (chord > 1e-12) path / chord else if (path <= 1e-12) 1.0 else Double.POSITIVE_INFINITY,
            maxDeviationPx = maxDeviation,
            maxDeviationChordRatio = if (chord > 1e-12) maxDeviation / chord else 0.0,
            viewportCompliant = trace.points.all { it.u in 0.0..1.0 && it.v in 0.0..1.0 },
        )
    }

    private fun percentile(values: List<Double>, fraction: Double): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val rank = (sorted.size - 1) * fraction
        val low = rank.toInt()
        val high = kotlin.math.ceil(rank).toInt()
        if (low == high) return sorted[low]
        val weight = rank - low
        return sorted[low] * (1.0 - weight) + sorted[high] * weight
    }
}
