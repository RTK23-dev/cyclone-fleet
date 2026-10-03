package com.cyclone.mobile.agent

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SemanticCaptureBoundaryTest {
    private val initial = ObservationSurface("task", 7, "workspace:3", "window:41", 100, 200, 0, 12, 10)
    private fun image(at: Long = 12) = JSONObject().put("sessionId", "task").put("displayId", 7)
        .put("width", 100).put("height", 200).put("capturedAtMonotonicMs", at).put("pngBase64", "fixture")

    @Test fun requestedProfileMustBelongToTheCurrentWorkspaceLease() {
        val plane = com.cyclone.mobile.runtime.session.SessionPlane(
            com.cyclone.mobile.runtime.session.SessionPlaneKind.LAYER2_WORKSPACE, "default-foreground", 0, "work", 3)
        val workspace = com.cyclone.mobile.runtime.workspaces.Workspace("work", "Work", "example.app", 10)
        val holder = com.cyclone.mobile.runtime.workspaces.WorkspaceLease("work", 3)
        assertEquals(10, SemanticCaptureBoundary.workspaceProfile(plane, holder, workspace))
        for (stale in listOf(null, holder.copy(generation = 4), holder.copy(workspaceId = "other"))) {
            try { SemanticCaptureBoundary.workspaceProfile(plane, stale, workspace); fail("Foreign lease accepted") }
            catch (_: CaptureChanged) { }
        }
        assertNull(SemanticCaptureBoundary.workspaceProfile(plane.copy(workspaceId = null, workspaceGeneration = null), null, null))
    }

    @Test fun animatingAccessibilityOverlayDoesNotChangeTaskWindowIdentity() {
        val task = com.cyclone.mobile.UiWindowSnapshot(41, "private title", 1, 0, true, true, com.cyclone.mobile.UiBounds(0, 0, 100, 200))
        val overlay = task.copy(id = 42, type = 4, layer = 9)
        val synthetic = task.copy(id = 0x0C4C0E)
        val before = SemanticCaptureBoundary.windowSignature(listOf(task, overlay, synthetic))
        val after = SemanticCaptureBoundary.windowSignature(listOf(overlay.copy(bounds = com.cyclone.mobile.UiBounds(80, 10, 100, 30)), task))
        assertEquals(before, after)
        assertFalse(before.contains("private"))
        var surface = initial.copy(windowSignature = before)
        var captures = 0
        val captured = SemanticCaptureBoundary.capture({ surface }, {
            captures++; surface = surface.copy(windowSignature = after); "tree"
        }, clock = { 10L })
        assertEquals(1, captures); assertEquals("tree", captured.semantic)
    }

    @Test fun oneTraversalAndMeasuredImageServeTheBundle() {
        var now = 10L; var captures = 0; var images = 0; var samples = 0
        val captured = SemanticCaptureBoundary.capture({ samples++; initial },
            { captures++; now = 11; "tree" }, { images++; now = 13; image() }, { now })
        assertEquals(1, captures); assertEquals(1, images); assertEquals(3, samples)
        assertEquals(10, captured.startMs); assertEquals(11, captured.endMs)
        assertEquals(11L, captured.imageStartMs); assertEquals(13L, captured.imageEndMs)
        assertTrue(captured.image!!.getBoolean("available"))
        assertEquals("same_generation", captured.image.getString("association"))
    }

    @Test fun pageWindowRotationDisplayProfileAndScopeRacesDoNotPublishOrCapturePixels() {
        val changes = listOf(initial.copy(revision = 13), initial.copy(windowSignature = "window:42"),
            initial.copy(rotation = 2), initial.copy(displayId = 8), initial.copy(profileId = 11),
            initial.copy(scope = "workspace:4"), initial.copy(width = 200, height = 100), initial.copy(sessionId = "other"))
        changes.forEach { changed ->
            var stamp = initial; var captures = 0; var images = 0; var publishes = 0
            try {
                SemanticCaptureBoundary.capture({ stamp }, { captures++; stamp = changed; "tree" },
                    { images++; image() }, { 10L })
                publishes++
                fail("Expected capture invalidation")
            } catch (_: CaptureChanged) { }
            assertEquals(1, captures); assertEquals(0, images); assertEquals(0, publishes)
        }
    }

    @Test fun screenshotFailurePreservesValidSemanticEvidenceAndRedactsDetails() {
        val captured = SemanticCaptureBoundary.capture({ initial }, { "tree" },
            { error("private screenshot path and screen text") }, { 10L })
        assertEquals("tree", captured.semantic)
        assertFalse(captured.image!!.getBoolean("available"))
        assertEquals("SCREENSHOT_FAILED", captured.image.getString("errorCode"))
        assertFalse(captured.image.toString().contains("private"))
    }

    @Test fun cancellationDuringImageCaptureCannotPublishAValidBundle() {
        var published = false
        try {
            SemanticCaptureBoundary.capture({ initial }, { "tree" },
                { throw java.util.concurrent.CancellationException("stopped") }, { 10L })
            published = true
        } catch (_: java.util.concurrent.CancellationException) { }
        assertFalse(published)
    }

    @Test fun screenshotRaceInvalidatesTreeAsWellAsImage() {
        var stamp = initial; var captures = 0
        try {
            SemanticCaptureBoundary.capture({ stamp }, { captures++; "tree" },
                { stamp = initial.copy(rotation = 2); image() }, { 12L })
            fail("Expected invalidation after pixels")
        } catch (_: CaptureChanged) { }
        assertEquals(1, captures)
    }

    @Test fun staleDelayedWrongScopeAndWrongGeometryImagesAreUnavailable() {
        val cases = listOf(image(9) to 13L, image() to 1511L, image().put("displayId", 8) to 13L,
            image().put("width", 200) to 13L, image().put("capturedAtMonotonicMs", JSONObject.NULL) to 13L)
        cases.forEach { (pixels, end) ->
            var now = 10L
            val captured = SemanticCaptureBoundary.capture({ initial }, { "tree" }, { now = end; pixels }, { now })
            assertEquals("tree", captured.semantic)
            assertFalse(captured.image!!.getBoolean("available"))
            assertFalse(captured.image.has("pngBase64"))
        }
    }

    @Test fun stableSemanticCaptureNeedsNoImageAndNoProjectionCalls() {
        var captures = 0
        val captured = SemanticCaptureBoundary.capture({ initial }, { captures++; "tree" }, clock = { 10L })
        repeat(5) { assertEquals("tree", captured.semantic) }
        assertEquals(1, captures); assertNull(captured.image)
        assertNull(captured.imageStartMs); assertNull(captured.imageEndMs)
    }

    @Test fun onlyTheMappersLastAttemptToleratesAContentOnlyChange() {
        var surface = initial
        val animating = { surface = surface.copy(revision = surface.revision + 1); "tree" }
        try { SemanticCaptureBoundary.capture({ surface }, animating, clock = { 10L }); fail("A moving screen was accepted") } catch (_: CaptureChanged) { }
        val captured = SemanticCaptureBoundary.capture({ surface }, animating, clock = { 10L }, tolerateContentChange = true)
        assertEquals("tree", captured.semantic)
        // A window, size or scope change is never tolerated.
        val resized = { surface = surface.copy(revision = surface.revision + 1, width = 101); "tree" }
        try { SemanticCaptureBoundary.capture({ surface }, resized, clock = { 10L }, tolerateContentChange = true); fail("A resized screen was accepted") } catch (_: CaptureChanged) { }
    }
}
