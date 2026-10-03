package com.cyclone.mobile

import com.cyclone.mobile.guided.RoutineTeachingSession
import com.cyclone.mobile.guided.RoutineTeachingStep
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V29RoutineTeachingTest {
    @Test
    fun teachingStepRoundTripKeepsSemanticReplayAndCorrection() {
        val original = RoutineTeachingStep(
            index = 3,
            kind = "long_click",
            title = "Held Orders",
            summary = "Native long click is available",
            packageName = "com.example.store",
            pageTitle = "Orders",
            pageKey = "orders-page",
            selectorJson = JSONObject().put("resourceId", "orders").toString(),
            advertisedActions = listOf("ACTION_CLICK", "ACTION_LONG_CLICK"),
            semanticSignal = "ACTION_LONG_CLICK",
            replayStrategy = "SEMANTIC_LONG_CLICK",
            demonstratedDurationMs = 2_000,
            optimizedDurationMs = 0,
            note = "This opens the order actions menu",
        )
        val restored = RoutineTeachingStep.fromJson(original.toJson())
        assertEquals(original.kind, restored.kind)
        assertEquals("ACTION_LONG_CLICK", restored.semanticSignal)
        assertEquals("SEMANTIC_LONG_CLICK", restored.replayStrategy)
        assertEquals(2_000L, restored.demonstratedDurationMs)
        assertEquals(0L, restored.optimizedDurationMs)
        assertEquals(original.note, restored.note)
    }

    @Test
    fun teachingSessionRoundTripKeepsTimelineAndSelectedModel() {
        val session = RoutineTeachingSession(
            id = "session-1",
            name = "Morning routine",
            modelId = "qwen/qwen3.8-27b",
            startedAt = 100,
            status = "COMPLETE",
            pagesSeen = 4,
            actionsSeen = 6,
            steps = listOf(RoutineTeachingStep(index = 1, kind = "page", title = "Page: Home", summary = "Captured")),
        )
        val restored = RoutineTeachingSession.fromJson(session.toJson())
        assertEquals("qwen/qwen3.8-27b", restored.modelId)
        assertEquals(4, restored.pagesSeen)
        assertEquals(1, restored.steps.size)
    }

    @Test
    fun uiSnapshotDecoderPreservesAdvertisedAndroidActions() {
        val node = JSONObject()
            .put("id", "n1")
            .put("path", "0/1")
            .put("depth", 1)
            .put("windowId", 1)
            .put("class", "android.widget.Button")
            .put("role", "button")
            .put("text", "Orders")
            .put("contentDescription", "")
            .put("resourceId", "com.example:id/orders")
            .put("bounds", JSONObject().put("left", 1).put("top", 2).put("right", 100).put("bottom", 80))
            .put("clickable", true)
            .put("longClickable", true)
            .put("editable", false)
            .put("scrollable", false)
            .put("enabled", true)
            .put("visibleToUser", true)
            .put("actions", JSONArray(listOf("ACTION_CLICK", "ACTION_LONG_CLICK")))
        val json = JSONObject()
            .put("package", "com.example")
            .put("class", "MainActivity")
            .put("screen", JSONObject().put("width", 1080).put("height", 2400))
            .put("timestampMs", 123L)
            .put("fingerprint", "abc")
            .put("controller", "HUMAN")
            .put("windows", JSONArray())
            .put("nodes", JSONArray().put(node))

        val snapshot = uiSnapshotFromJson(json)
        assertEquals("com.example", snapshot.packageName)
        assertEquals(1, snapshot.nodes.size)
        assertTrue(snapshot.nodes.single().actions.contains("ACTION_LONG_CLICK"))
    }
}
