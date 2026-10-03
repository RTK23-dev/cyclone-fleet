package com.cyclone.mobile.ai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidedWorkflowOptimizerTest {
    @Test
    fun compactManifestKeepsTeachingContextWithoutLocalFilePaths() {
        val manifest = JSONObject()
            .put("protocol", "cyclone-guided-recording-v1")
            .put("name", "Demo")
            .put("steps", JSONArray().put(
                JSONObject()
                    .put("kind", "tap")
                    .put("placement", JSONObject().put("x1", 120).put("y1", 300))
                    .put("package", "com.example")
                    .put("selector", JSONObject().put("resourceId", "com.example:id/save"))
                    .put("target", JSONObject().put("text", "Save"))
                    .put("nearby", JSONArray().put(JSONObject().put("text", "Cancel")))
                    .put("beforeFingerprint", "a")
                    .put("afterFingerprint", "b")
                    .put("beforeScreenshot", "/private/step.png")
                    .put("beforeUi", "/private/step.json"),
            ))

        val compact = GuidedWorkflowOptimizer.compactManifest(manifest)
        val step = compact.getJSONArray("steps").getJSONObject(0)
        assertEquals("tap", step.getString("kind"))
        assertEquals("Save", step.getJSONObject("target").getString("text"))
        assertFalse(step.has("beforeScreenshot"))
        assertFalse(step.has("beforeUi"))
    }

    @Test
    fun stripFenceAcceptsJsonMarkdown() {
        assertEquals("{\"name\":\"Demo\"}", GuidedWorkflowOptimizer.stripFence("```json\n{\"name\":\"Demo\"}\n```"))
        assertTrue(OpenRouterModelPresets.all.isNotEmpty())
    }
}
