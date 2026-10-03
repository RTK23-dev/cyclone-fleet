package com.cyclone.mobile.observability.pagecontext

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageTextExtractorTest {
    @Test
    fun linesAreSpatiallyOrderedAndDuplicateOverlayTextRemoved() {
        val snapshot = snapshot(
            node("Continue", "button", true, y = 500, x = 40),
            node("Header", "text", false, y = 100, x = 40),
            node("Continue", "button", true, y = 505, x = 100),
        )

        val text = PageTextExtractor.extract(snapshot)
        val lines = text.getJSONArray("lines")
        assertEquals(2, lines.length())
        assertEquals("Header", lines.getJSONObject(0).getString("text"))
        assertEquals(100, lines.getJSONObject(0).getInt("y"))
        assertEquals("Continue", lines.getJSONObject(1).getString("text"))
        assertFalse(text.getBoolean("truncated"))
        assertTrue(text.getString("text").contains("Header"))
        assertTrue(text.getString("text").contains("Continue"))
    }

    @Test
    fun editableValuesAreOmittedButFieldLabelsSurvive() {
        val snapshot = snapshot(
            node("email value", "edit_text", false, y = 300, x = 40, editable = true, description = "Email address"),
            node("Password hint", "edit_text", false, y = 360, x = 40, editable = true, description = "Password", sensitive = true),
        )

        val lines = PageTextExtractor.extract(snapshot).getJSONArray("lines")
        assertEquals(1, lines.length())
        assertEquals("Email address", lines.getJSONObject(0).getString("text"))
    }

    @Test
    fun offscreenZeroAreaAndRedactedOnlyNodesAreExcluded() {
        val snapshot = snapshot(
            node("visible", "text", false, y = 50, x = 40),
            node("offscreen", "text", false, y = 2500, x = 40),
            node("zero width", "text", false, y = 80, x = 40, width = 0),
            node("<redacted>", "text", false, y = 120, x = 40),
        )

        val text = PageTextExtractor.extract(snapshot)
        val lines = text.getJSONArray("lines")
        assertEquals(1, lines.length())
        assertEquals("visible", lines.getJSONObject(0).getString("text"))
    }

    @Test
    fun maxLinesTruncationIsReported() {
        val snapshot = snapshot(
            node("one", "text", false, y = 10, x = 40),
            node("two", "text", false, y = 60, x = 40),
            node("three", "text", false, y = 110, x = 40),
        )

        val text = PageTextExtractor.extract(snapshot, maxLines = 2)
        assertEquals(2, text.getJSONArray("lines").length())
        assertTrue(text.getBoolean("truncated"))
    }

    @Test
    fun zeroSizeScreenStillKeepsOnScreenVisibleNodes() {
        val snapshot = snapshot(
            node("Settings", "text", false, y = 80, x = 24),
            node("Network and internet", "text", true, y = 220, x = 24),
            screenWidth = 0,
            screenHeight = 0,
        )

        val text = PageTextExtractor.extract(snapshot)
        assertEquals("cyclone-page-text-v1", text.getString("protocol"))
        assertEquals(2, text.getJSONArray("lines").length())
        assertTrue(text.getString("text").contains("Settings"))
        assertTrue(text.getString("text").contains("Network and internet"))
    }

    @Test
    fun settingsPageFixtureEmitsBoundedFlattenedText() {
        val snapshot = snapshot(
            node("Settings", "heading", false, y = 40, x = 24),
            node("Network and internet", "text", true, y = 180, x = 24),
            node("Connected devices", "text", true, y = 260, x = 24),
            node("Apps", "text", true, y = 340, x = 24),
            node("Notifications", "text", true, y = 420, x = 24),
            node("Battery", "text", true, y = 500, x = 24),
        )
        val text = PageTextExtractor.extract(snapshot)
        assertEquals("cyclone-page-text-v1", text.getString("protocol"))
        assertTrue(text.getInt("lineCount") >= 5)
        assertTrue(text.getString("text").contains("Network and internet"))
        assertTrue(text.getString("text").length <= PageTextExtractor.DEFAULT_PLAIN_LIMIT)
        assertEquals(text.getString("text"), PageTextExtractor.flattened(text))
    }

    @Test
    fun appsPageFixtureEmitsBoundedFlattenedText() {
        val snapshot = snapshot(
            node("Apps", "heading", false, y = 40, x = 24),
            node("All apps", "text", true, y = 160, x = 24),
            node("Default apps", "text", true, y = 240, x = 24),
            node("Screen time", "text", true, y = 320, x = 24),
        )
        val text = PageTextExtractor.extract(snapshot)
        assertEquals("cyclone-page-text-v1", text.getString("protocol"))
        assertTrue(text.getString("text").contains("All apps"))
        assertTrue(text.getString("text").contains("Default apps"))
    }

    @Test
    fun flattenedJoinsProtocolLinesWhenDirectTextMissing() {
        val card = JSONObject()
            .put("protocol", "cyclone-page-text-v1")
            .put("lines", JSONArray()
                .put(JSONObject().put("text", "Clock"))
                .put(JSONObject().put("text", "Alarm")))
        assertEquals("Clock Alarm", PageTextExtractor.flattened(card))
    }

    private fun snapshot(vararg nodes: JSONObject, screenWidth: Int = 1080, screenHeight: Int = 2400): JSONObject = JSONObject()
        .put("screen", JSONObject().put("width", screenWidth).put("height", screenHeight))
        .put("nodes", JSONArray().also { array -> nodes.forEach(array::put) })

    private fun node(
        text: String,
        role: String,
        clickable: Boolean,
        y: Int,
        x: Int,
        editable: Boolean = false,
        description: String = "",
        sensitive: Boolean = false,
        width: Int = 200,
    ): JSONObject = JSONObject()
        .put("text", text)
        .put("contentDescription", description)
        .put("role", role)
        .put("class", "android.widget.TextView")
        .put("resourceId", if (sensitive) "com.example:id/otp_field" else "com.example:id/plain_field")
        .put("path", "0/1")
        .put("depth", 2)
        .put("visibleToUser", true)
        .put("clickable", clickable)
        .put("editable", editable)
        .put("scrollable", false)
        .put("longClickable", false)
        .put("checkable", false)
        .put("bounds", JSONObject()
            .put("left", x)
            .put("top", y)
            .put("right", x + width)
            .put("bottom", y + 40))
}
