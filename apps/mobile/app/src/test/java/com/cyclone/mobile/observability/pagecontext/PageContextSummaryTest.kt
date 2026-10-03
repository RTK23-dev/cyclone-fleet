package com.cyclone.mobile.observability.pagecontext

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageContextSummaryTest {
    @Test
    fun summaryClassifiesPageContent() {
        val snapshot = JSONObject()
            .put("nodes", JSONArray()
                .put(node("Battery usage", "text", clickable = false, depth = 1))
                .put(node("Settings", "button", clickable = true, depth = 2))
                .put(node("Home", "tab", clickable = true, depth = 2))
                .put(editable("Search", "com.example:id/search_field"))
                .put(node("Wi-Fi", "switch", clickable = true, depth = 2))
                .put(scrollable())
                .put(node("OTP", "text", clickable = false, depth = 2, sensitive = true)))

        val summary = PageContextSummary.build(snapshot, "page-key", "Settings", controlCount = 7, textLineCount = 5)

        assertEquals("page-key", summary.getString("pageKey"))
        assertEquals("Settings", summary.getString("title"))
        assertEquals(listOf("Battery usage"), summary.getJSONArray("headings").strings())
        assertEquals(listOf("Settings"), summary.getJSONArray("buttons").strings())
        assertEquals(listOf("Home"), summary.getJSONArray("tabs").strings())
        assertEquals(listOf("Search"), summary.getJSONArray("formFields").strings())
        assertEquals(listOf("Wi-Fi"), summary.getJSONArray("switches").strings())
        assertEquals(1, summary.getInt("scrollableRegions"))
        assertEquals(1, summary.getInt("sensitiveFieldsRedacted"))
        assertEquals(7, summary.getInt("controlCount"))
        assertEquals(5, summary.getInt("textLineCount"))
        assertTrue(summary.getString("contentNote").contains("5 visible text lines"))
        assertTrue(summary.getString("text").startsWith("Settings"))
        assertTrue(summary.getString("text").contains("5 visible text lines"))
        assertEquals(summary.getString("text"), PageContextSummary.flattened(summary))
    }

    @Test
    fun appsSummaryFlattenedTextIsBoundedAndNonBlank() {
        val snapshot = JSONObject()
            .put("nodes", JSONArray()
                .put(node("Apps", "text", clickable = false, depth = 1))
                .put(node("All apps", "button", clickable = true, depth = 2))
                .put(node("Default apps", "button", clickable = true, depth = 2)))
        val summary = PageContextSummary.build(snapshot, "apps", "Apps", controlCount = 2, textLineCount = 3)
        assertEquals("cyclone-page-summary-v1", summary.getString("protocol"))
        assertTrue(summary.getString("text").contains("Apps"))
        assertTrue(summary.getString("text").length <= PageContextSummary.DEFAULT_PLAIN_LIMIT)
        assertTrue(PageContextSummary.flattened(summary).isNotBlank())
    }

    private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

    private fun node(text: String, role: String, clickable: Boolean, depth: Int, sensitive: Boolean = false): JSONObject = JSONObject()
        .put("text", text)
        .put("contentDescription", "")
        .put("role", role)
        .put("class", if (role == "text") "android.widget.TextView" else "android.widget.Button")
        .put("resourceId", if (sensitive) "com.example:id/otp_input" else "com.example:id/plain")
        .put("path", "0/0")
        .put("depth", depth)
        .put("visibleToUser", true)
        .put("clickable", clickable)
        .put("editable", false)
        .put("scrollable", false)
        .put("longClickable", false)
        .put("checkable", role == "switch")
        .put("bounds", bounds(0, depth * 80, 400, 60))

    private fun editable(label: String, resourceId: String): JSONObject = JSONObject()
        .put("text", "<redacted>")
        .put("contentDescription", label)
        .put("role", "edit_text")
        .put("class", "android.widget.EditText")
        .put("resourceId", resourceId)
        .put("path", "0/1")
        .put("depth", 2)
        .put("visibleToUser", true)
        .put("clickable", false)
        .put("editable", true)
        .put("scrollable", false)
        .put("longClickable", false)
        .put("checkable", false)
        .put("bounds", bounds(0, 300, 400, 60))

    private fun scrollable(): JSONObject = JSONObject()
        .put("text", "")
        .put("contentDescription", "")
        .put("role", "scroll_view")
        .put("class", "android.widget.ScrollView")
        .put("resourceId", "com.example:id/scroll_area")
        .put("path", "0/2")
        .put("depth", 1)
        .put("visibleToUser", true)
        .put("clickable", false)
        .put("editable", false)
        .put("scrollable", true)
        .put("longClickable", false)
        .put("checkable", false)
        .put("bounds", bounds(0, 0, 1080, 2400))

    private fun bounds(left: Int, top: Int, right: Int, bottom: Int): JSONObject = JSONObject()
        .put("left", left)
        .put("top", top)
        .put("right", right)
        .put("bottom", bottom)
}
