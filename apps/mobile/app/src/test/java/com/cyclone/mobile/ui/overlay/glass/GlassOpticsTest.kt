package com.cyclone.mobile.ui.overlay.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class GlassOpticsTest {
    @Test fun theRimFacingTheLightShinesMostAndTheOppositeRimLess() {
        val theta = GlassOptics.angle(-1f, 0f)                       // light from the left
        val facing = GlassOptics.rimIntensity(PI.toFloat(), theta, thin = false)
        val opposite = GlassOptics.rimIntensity(0f, theta, thin = false)
        val side = GlassOptics.rimIntensity((PI / 2).toFloat(), theta, thin = false)
        assertEquals(1f, facing, 0.001f)
        assertEquals(0.7f, opposite, 0.001f)
        assertTrue(side < 0.01f)
    }

    @Test fun panelsGetANarrowerShineThanThinPills() {
        val theta = 0f
        val offAxis = (PI / 5).toFloat()
        assertTrue(GlassOptics.rimIntensity(offAxis, theta, thin = true) > GlassOptics.rimIntensity(offAxis, theta, thin = false))
    }

    @Test fun dotsAreCrispOnTheLitSideAndSoftOnTheFarSide() {
        val lx = 1f; val ly = 0f
        val litAlpha = GlassOptics.dotAlpha(1f, 0f, 1f, lx, ly, 1f)
        val farAlpha = GlassOptics.dotAlpha(-1f, 0f, 1f, lx, ly, 1f)
        val litRadius = GlassOptics.dotRadius(1f, 0f, 1f, lx, ly)
        val farRadius = GlassOptics.dotRadius(-1f, 0f, 1f, lx, ly)
        assertTrue(litAlpha > farAlpha * 3)
        assertTrue(farRadius > litRadius)
        val (a, r) = GlassOptics.dot(1f, 0f, 1f, lx, ly, 1f)
        assertEquals(litAlpha, a, 0.0001f)
        assertEquals(litRadius, r, 0.0001f)
    }

    @Test fun dotsFadeTowardsTheInnerEdgeOfTheBand() {
        assertTrue(GlassOptics.dotAlpha(1f, 0f, 1f, 1f, 0f, 1f) > GlassOptics.dotAlpha(1f, 0f, 0.2f, 1f, 0f, 1f))
    }

    @Test fun holdingStillRestsTheLightAndTiltingMovesIt() {
        val (x, y) = GlassOptics.lightFromGravity(1f, 7f, 1f, 7f)
        assertEquals(GlassOptics.REST_X, x, 0.0001f)
        assertEquals(GlassOptics.REST_Y, y, 0.0001f)
        val (tx, _) = GlassOptics.lightFromGravity(-2f, 7f, 1f, 7f)
        assertTrue(tx > x)
        val (cx, cy) = GlassOptics.lightFromGravity(40f, -40f, 0f, 0f)
        assertEquals(-1f, cx, 0f)
        assertEquals(-1f, cy, 0f)
    }

    @Test fun theShineNeverSwitchesOffCompletely() {
        assertEquals(0.45f, GlassOptics.power(0f, 0f), 0f)
        assertEquals(1f, GlassOptics.power(3f, 4f), 0f)
    }

    @Test fun roundedRectDistanceIsNegativeInsideAndZeroOnTheEdge() {
        assertTrue(GlassOptics.roundedRectDistance(50f, 20f, 100f, 40f, 20f) < 0f)
        assertEquals(0f, GlassOptics.roundedRectDistance(50f, 0f, 100f, 40f, 20f), 0.001f)
        assertTrue(GlassOptics.roundedRectDistance(-5f, 20f, 100f, 40f, 20f) > 0f)
    }

    @Test fun rimSegmentsGoAllTheWayRound() {
        val segments = rimSegments(100f, 40f, 20f, 40)
        assertEquals(200, segments.size)
        val normals = (0 until 40).map { segments[it * 5 + 4] }
        assertTrue(normals.any { it < -1.5f } && normals.any { it > 1.5f })     // top and bottom edges
    }
}
